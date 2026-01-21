package com.edge.vision.service;

import com.edge.vision.config.YamlConfig;
import com.edge.vision.event.UploadEvent;
import com.edge.vision.model.InspectionEntity;
import com.edge.vision.repository.InspectionRepository;
import com.google.gson.Gson;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

@Service
public class DataManager implements ApplicationListener<UploadEvent> {
    private static final Logger logger = LoggerFactory.getLogger(DataManager.class);
    
    @Autowired
    private InspectionRepository repository;
    
    @Autowired
    private YamlConfig config;
    
    @Autowired
    private ApplicationEventPublisher eventPublisher;
    
    private final Gson gson = new Gson();
    private OkHttpClient httpClient;
    
    @PostConstruct
    public void init() {
        // 初始化HTTP客户端
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(config.getRemote().getTimeout(), TimeUnit.SECONDS)
                .readTimeout(config.getRemote().getTimeout(), TimeUnit.SECONDS)
                .writeTimeout(config.getRemote().getTimeout(), TimeUnit.SECONDS)
                .build();
    }
    
    /**
     * 保存检测记录
     * @param entity 检测实体
     * @param imageBase64 图像的base64编码
     */
    public void saveRecord(InspectionEntity entity, String imageBase64) throws IOException {
        // 1. 保存图像到本地
        if (imageBase64 != null && config.getSystem().isSaveLocal()) {
            saveImage(entity, imageBase64);
        }
        
        // 2. 保存记录到数据库
        repository.insert(entity);
        
        // 3. 触发上传事件（异步）
        if (StringUtils.hasText(config.getRemote().getUploadUrl())) {
            eventPublisher.publishEvent(new UploadEvent(this, entity, entity.getImagePath()));
        }
    }
    
    /**
     * 保存图像文件
     * <p>
     * 存储路径: data/images/yyyy-MM-dd/partType/xxx.jpg
     * 相对路径（用于API访问）: /api/images/yyyy-MM-dd/partType/xxx.jpg
     */
    private void saveImage(InspectionEntity entity, String imageBase64) throws IOException {
        // 创建目录: data/images/yyyy-MM-dd/partType/
        String partType = entity.getPartName() != null ? entity.getPartName() : "UNKNOWN";
        String dateStr = LocalDate.now().toString();
        Path dir = Paths.get("data", "images", dateStr, partType);

        Files.createDirectories(dir);

        // 生成文件名: partType_timestamp.jpg
        String filename = partType + "_" +
                         entity.getTimestamp().toEpochSecond(java.time.ZoneOffset.UTC) +
                         ".jpg";

        Path imagePath = dir.resolve(filename);

        // 解码并保存
        byte[] imageBytes = Base64.getDecoder().decode(imageBase64);
        Files.write(imagePath, imageBytes);

        // 存储相对路径（用于API访问）
        // 格式: /api/images/yyyy-MM-dd/partType/xxx.jpg
        String relativePath = "/api/images/" + dateStr + "/" + partType + "/" + filename;
        entity.setImagePath(relativePath);
    }
    
    /**
     * 异步上传数据到远程服务器
     */
    @Override
    @Async
    public void onApplicationEvent(UploadEvent event) {
        InspectionEntity entity = event.getEntity();
        
        try {
            asyncUpload(entity);
            
            // 更新上传状态
            entity.setUploaded(true);
            repository.update(entity);
            
            logger.info("Successfully uploaded record: {}", entity.getId());
            
        } catch (Exception e) {
            logger.error("Failed to upload record: {}", entity.getId(), e);
            // 失败时不更新状态，可以后续重试
        }
    }
    
    /**
     * 执行异步上传
     */
    private void asyncUpload(InspectionEntity entity) throws IOException {
        if (!StringUtils.hasText(config.getRemote().getUploadUrl())) {
            return;
        }
        
        // 构建 multipart/form-data 请求
        MultipartBody.Builder bodyBuilder = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("device_id", config.getSystem().getDeviceId())
                .addFormDataPart("timestamp", String.valueOf(entity.getTimestamp().toEpochSecond(java.time.ZoneOffset.UTC)))
                .addFormDataPart("batch_id", entity.getBatchId())
                .addFormDataPart("part_name", entity.getPartName());
        
        // 添加操作员（如果有）
        if (entity.getOperator() != null) {
            bodyBuilder.addFormDataPart("operator", entity.getOperator());
        }
        
        // 添加分析结果
        if (entity.getMeta() != null) {
            String analysisJson = gson.toJson(entity.getMeta());
            bodyBuilder.addFormDataPart("analysis", analysisJson);
        }
        
        // 添加图像文件（如果有）
        if (entity.getImagePath() != null) {
            Path imagePath = Paths.get(entity.getImagePath().replace("api","data"));
            if (Files.exists(imagePath)) {
                String filename = imagePath.getFileName().toString();
                byte[] imageBytes = Files.readAllBytes(imagePath);
                
                bodyBuilder.addFormDataPart("image", filename,
                        RequestBody.create(imageBytes, MediaType.parse("image/jpeg")));
            }
        }
        
        RequestBody body = bodyBuilder.build();
        
        Request request = new Request.Builder()
                .url(config.getRemote().getUploadUrl())
                .post(body)
                .build();
        
        // 执行请求
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Upload failed with code: " + response.code());
            }
        }
    }
    
    /**
     * 查询记录（新版本：支持时间范围和分页）
     */
    public PageResult queryRecords(LocalDate startDate, LocalDate endDate, String batchId,
                                    Integer page, Integer pageSize) {
        java.util.List<InspectionEntity> results = new java.util.ArrayList<>();

        // 按时间范围查询
        if (startDate != null && endDate != null) {
            results.addAll(repository.findByDateBetween(startDate, endDate));
        } else if (startDate != null) {
            results.addAll(repository.findByDateAfter(startDate));
        } else if (endDate != null) {
            results.addAll(repository.findByDateBefore(endDate));
        }

        // 按批次ID筛选
        if (batchId != null) {
            if (results.isEmpty()) {
                results.addAll(repository.findByBatchId(batchId));
            } else {
                results = results.stream()
                    .filter(r -> batchId.equals(r.getBatchId()))
                    .collect(java.util.stream.Collectors.toList());
            }
        }

        // 如果没有筛选条件，查询所有
        if (startDate == null && endDate == null && batchId == null) {
            results.addAll(repository.findAll());
        }

        // 去重
        java.util.Set<String> seenIds = new java.util.HashSet<>();
        results.removeIf(e -> !seenIds.add(e.getId()));

        // 按时间倒序排序
        results.sort((a, b) -> b.getTimestamp().compareTo(a.getTimestamp()));

        int total = results.size();

        // 分页处理
        if (page != null && pageSize != null && page > 0 && pageSize > 0) {
            int startIndex = (page - 1) * pageSize;
            if (startIndex >= total) {
                return new PageResult(java.util.Collections.emptyList(), total, page, pageSize);
            }
            int endIndex = Math.min(startIndex + pageSize, total);
            results = results.subList(startIndex, endIndex);
        }

        return new PageResult(results, total, page != null ? page : 1, pageSize != null ? pageSize : total);
    }

    /**
     * 查询记录（旧版本：保持兼容）
     */
    public java.util.List<InspectionEntity> queryRecords(LocalDate date, String batchId, Integer limit) {
        PageResult pageResult = queryRecords(date, date != null ? date : null, batchId, null, limit);
        return pageResult.getData();
    }

    /**
     * 获取统计数据
     */
    public java.util.Map<String, Object> getStatistics() {
        java.util.Map<String, Object> stats = new java.util.HashMap<>();

        java.util.List<InspectionEntity> allRecords = repository.findAll();

        stats.put("totalRecords", allRecords.size());
        stats.put("uploadedRecords", allRecords.stream().filter(InspectionEntity::isUploaded).count());

        return stats;
    }

    /**
     * 分页结果封装类
     */
    public static class PageResult {
        private java.util.List<InspectionEntity> data;
        private int total;
        private int page;
        private int pageSize;
        private int totalPages;

        public PageResult(java.util.List<InspectionEntity> data, int total, int page, int pageSize) {
            this.data = data != null ? data : java.util.Collections.emptyList();
            this.total = total;
            this.page = page;
            this.pageSize = pageSize;
            this.totalPages = pageSize > 0 ? (int) Math.ceil((double) total / pageSize) : 1;
        }

        public java.util.List<InspectionEntity> getData() {
            return data;
        }

        public void setData(java.util.List<InspectionEntity> data) {
            this.data = data;
        }

        public int getTotal() {
            return total;
        }

        public void setTotal(int total) {
            this.total = total;
        }

        public int getPage() {
            return page;
        }

        public void setPage(int page) {
            this.page = page;
        }

        public int getPageSize() {
            return pageSize;
        }

        public void setPageSize(int pageSize) {
            this.pageSize = pageSize;
        }

        public int getTotalPages() {
            return totalPages;
        }

        public void setTotalPages(int totalPages) {
            this.totalPages = totalPages;
        }

        public boolean hasNext() {
            return page < totalPages;
        }

        public boolean hasPrevious() {
            return page > 1;
        }
    }
}