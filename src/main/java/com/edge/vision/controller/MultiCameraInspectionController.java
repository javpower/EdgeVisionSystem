package com.edge.vision.controller;

import com.edge.vision.config.YamlConfig;
import com.edge.vision.core.template.TemplateManager;
import com.edge.vision.core.template.model.Template;
import com.edge.vision.dto.MultiCameraInspectionRequest;
import com.edge.vision.dto.MultiCameraInspectionResponse;
import com.edge.vision.model.InspectionEntity;
import com.edge.vision.service.CameraService;
import com.edge.vision.service.DataManager;
import com.edge.vision.service.PartCameraTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.opencv.core.Mat;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 多摄像头质检控制器
 */
@Tag(name = "多摄像头质检", description = "基于多个摄像头模板的质量检测")
@RestController
@RequestMapping("/api/multi-camera-inspection")
public class MultiCameraInspectionController {

    private static final Logger logger = LoggerFactory.getLogger(MultiCameraInspectionController.class);

    @Autowired
    private PartCameraTemplateService partCameraTemplateService;

    @Autowired
    private CameraService cameraService;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private YamlConfig config;

    @Autowired(required = false)
    private TemplateManager templateManager;

    /**
     * 多摄像头质检
     */
    @Operation(summary = "多摄像头质检", description = "对工件的所有摄像头模板进行质量检测")
    @PostMapping("/inspect")
    public ResponseEntity<MultiCameraInspectionResponse> inspect(@RequestBody MultiCameraInspectionRequest request) {
        try {
            String partType = request.getPartType();
            if (partType == null || partType.isEmpty()) {
                return ResponseEntity.ok(MultiCameraInspectionResponse.error("请提供工件类型"));
            }

            if (!cameraService.isRunning()) {
                return ResponseEntity.ok(MultiCameraInspectionResponse.error("摄像头未启动"));
            }

            logger.info("Starting multi-camera inspection for part: {}", partType);

            // 执行质检
            List<PartCameraTemplateService.CameraInspectionResult> inspectionResults =
                partCameraTemplateService.inspect(partType);

            // 转换为响应格式
            List<MultiCameraInspectionResponse.CameraInspectionResult> cameraResults = new ArrayList<>();
            for (PartCameraTemplateService.CameraInspectionResult result : inspectionResults) {
                List<MultiCameraInspectionResponse.FeatureComparison> features = new ArrayList<>();
                for (PartCameraTemplateService.FeatureComparisonInfo fi : result.features) {
                    MultiCameraInspectionResponse.FeatureComparison fc = new MultiCameraInspectionResponse.FeatureComparison();
                    fc.setFeatureId(fi.featureId);
                    fc.setFeatureName(fi.featureName);
                    fc.setClassName(fi.className);
                    fc.setClassId(fi.classId);
                    fc.setTemplateX(fi.templateX);
                    fc.setTemplateY(fi.templateY);
                    fc.setDetectedX(fi.detectedX);
                    fc.setDetectedY(fi.detectedY);
                    fc.setXError(fi.xError);
                    fc.setYError(fi.yError);
                    fc.setTotalError(fi.totalError);
                    fc.setToleranceX(fi.toleranceX);
                    fc.setToleranceY(fi.toleranceY);
                    fc.setWithinTolerance(fi.withinTolerance);
                    fc.setStatus(fi.status);
                    features.add(fc);
                }

                cameraResults.add(new MultiCameraInspectionResponse.CameraInspectionResult(
                    result.cameraId,
                    result.templateId,
                    result.passed,
                    result.imageUrl,
                    result.errorMessage,
                    features,result.details
                ));
            }

            // 检查是否有错误信息（如：请调整工件方向、请对齐到正确位置）
            boolean hasError = cameraResults.stream().anyMatch(r -> r.getErrorMessage() != null && !r.getErrorMessage().isEmpty());

            if (hasError) {
                // 有错误，不保存记录，直接返回错误响应
                String errorMsg = cameraResults.stream()
                    .filter(r -> r.getErrorMessage() != null)
                    .findFirst()
                    .map(MultiCameraInspectionResponse.CameraInspectionResult::getErrorMessage)
                    .orElse("检测失败");

                logger.warn("Multi-camera inspection failed with error: {}", errorMsg);
                return ResponseEntity.ok(MultiCameraInspectionResponse.error(errorMsg));
            }

            // 判断整体是否通过（所有摄像头都通过）
            boolean allPassed = cameraResults.stream().allMatch(MultiCameraInspectionResponse.CameraInspectionResult::isPassed);

            logger.info("Multi-camera inspection completed: partType={}, allPassed={}, cameras={}",
                partType, allPassed, cameraResults.size());

            // 只有在没有错误时才保存检测记录
            saveInspectionRecord(request, cameraResults, allPassed);

            return ResponseEntity.ok(MultiCameraInspectionResponse.success(partType, cameraResults));

        } catch (IllegalStateException e) {
            logger.warn("Inspection failed: {}", e.getMessage());
            return ResponseEntity.ok(MultiCameraInspectionResponse.error(e.getMessage()));
        } catch (Exception e) {
            logger.error("Inspection failed", e);
            return ResponseEntity.ok(MultiCameraInspectionResponse.error("质检失败: " + e.getMessage()));
        }
    }

    /**
     * 数据采集接口 - 多摄像头版本
     * 不调用YOLO模型，只保存图片和基于模板的标注JSON
     */
    @Operation(summary = "多摄像头数据采集", description = "保存每个摄像头对应的图片和基于模板生成标注JSON（不使用YOLO）")
    @PostMapping("/collect-data")
    public ResponseEntity<Map<String, Object>> collectData(@RequestBody com.edge.vision.model.CollectDataRequest request) {
        Map<String, Object> response = new HashMap<>();

        try {
            String partType = request.getConfirmedPartName();
            if (partType == null || partType.isEmpty()) {
                response.put("success", false);
                response.put("message", "请提供工件类型");
                return ResponseEntity.badRequest().body(response);
            }

            if (!cameraService.isRunning()) {
                response.put("success", false);
                response.put("message", "摄像头未启动");
                return ResponseEntity.badRequest().body(response);
            }

            logger.info("Starting multi-camera data collection for part: {}", partType);

            // 获取该工件类型的摄像头模板映射
            Map<Integer, String> cameraTemplates = partCameraTemplateService.getCameraTemplates(partType);

            // 确定要采集的摄像头列表
            List<Integer> camerasToCollect;
            if (!cameraTemplates.isEmpty()) {
                // 有模板：只采集有模板的摄像头
                camerasToCollect = new ArrayList<>(cameraTemplates.keySet());
                logger.info("Found templates for {} cameras", camerasToCollect.size());
            } else {
                // 无模板：根据请求的 cameraIds 采集
                if (request.getCameraIds() != null && !request.getCameraIds().isEmpty()) {
                    camerasToCollect = request.getCameraIds();
                    logger.info("No templates found, collecting from specified {} cameras", camerasToCollect.size());
                } else {
                    // 采集所有摄像头
                    int cameraCount = cameraService.getCameraCount();
                    camerasToCollect = new ArrayList<>();
                    for (int i = 0; i < cameraCount; i++) {
                        camerasToCollect.add(i);
                    }
                    logger.info("No templates found, collecting from all {} cameras", camerasToCollect.size());
                }
            }

            // 生成时间戳文件名
            LocalDateTime now = LocalDateTime.now();
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
            String timeStr = now.format(formatter);
            String fileBaseName = partType + "_" + timeStr;

            // 确定保存目录
            Path collectDir;
            if (request.getSaveDir() != null && !request.getSaveDir().isEmpty()) {
                String saveDirStr = request.getSaveDir();
                try {
                    saveDirStr = java.net.URLDecoder.decode(saveDirStr, java.nio.charset.StandardCharsets.UTF_8.name());
                } catch (Exception e) {
                    logger.warn("URL decode failed for saveDir: {}", saveDirStr);
                }
                saveDirStr = saveDirStr.trim();
                Path inputPath = Paths.get(saveDirStr);
                if (inputPath.isAbsolute()) {
                    collectDir = inputPath.normalize();
                } else {
                    collectDir = inputPath.toAbsolutePath().normalize();
                }
            } else {
                collectDir = Paths.get("data", "collected", partType).toAbsolutePath().normalize();
            }

            Files.createDirectories(collectDir);
            logger.info("Collection directory: {}", collectDir);

            List<Map<String, Object>> savedFiles = new ArrayList<>();
            Map<Integer, Mat> cameraImages = new HashMap<>();

            // 遍历要采集的摄像头，保存图片
            for (int cameraId : camerasToCollect) {
                String templateId = cameraTemplates.getOrDefault(cameraId, null);

                // 从对应摄像头获取图片
                Mat imageMat = cameraService.getCameraImageMat(cameraId);
                if (imageMat == null || imageMat.empty()) {
                    logger.warn("Failed to get image for camera {}", cameraId);
                    continue;
                }

                // 保存到 Map 用于后续生成 JSON（有模板时）
                if (templateId != null) {
                    cameraImages.put(cameraId, imageMat);
                }

                // 生成文件名: partType_yyyyMMdd_HHmmss_cameraN.jpg
                String imageFileName = fileBaseName + "_camera" + cameraId + ".jpg";
                Path imageFilePath = collectDir.resolve(imageFileName);

                // 图片压缩处理（如果任一边超过 6000）
                Mat matToSave = resizeIfNeeded(imageMat);

                // 保存图片（使用 Java IO，支持中文路径）
                saveMatToFile(matToSave, imageFilePath);

                // 如果创建了新的 Mat，释放它
                if (matToSave != imageMat) {
                    matToSave.release();
                }

                savedFiles.add(Map.of(
                    "cameraId", cameraId,
                    "templateId", templateId != null ? templateId : "",
                    "imagePath", imageFilePath.toString(),
                    "fileName", imageFileName
                ));

                logger.info("Saved camera {} image to: {}", cameraId, imageFilePath);

                // 如果没有模板，立即释放图片
                if (templateId == null) {
                    imageMat.release();
                }
            }

            // 检查是否有模板
            boolean hasAnyTemplate = !cameraTemplates.isEmpty();

            // 根据模板生成标注JSON（使用模板匹配计算实际坐标）
            if (hasAnyTemplate && templateManager != null) {
                for (Map.Entry<Integer, String> entry : cameraTemplates.entrySet()) {
                    int cameraId = entry.getKey();
                    String templateId = entry.getValue();
                    Mat imageMat = cameraImages.get(cameraId);

                    if (imageMat == null) continue;

                    // 加载模板
                    try {
                        Template template = templateManager.load(templateId);
                        if (template == null) {
                            logger.warn("Template {} not found", templateId);
                            continue;
                        }

                        // 使用 VisionTool.calculateTemplateCoordinates 计算模板在当前图片中的实际位置
                        List<com.edge.vision.core.template.model.DetectedObject> templateObjects =
                            com.edge.vision.util.VisionTool.calculateTemplateCoordinates(template, imageMat);

                        if (templateObjects == null || templateObjects.isEmpty()) {
                            logger.warn("No template objects matched for camera {}, template {}", cameraId, templateId);
                            continue;
                        }

                        // 生成该摄像头的标注JSON
                        List<Map<String, Object>> labels = new ArrayList<>();

                        for (com.edge.vision.core.template.model.DetectedObject obj : templateObjects) {
                            Map<String, Object> label = new HashMap<>();
                            label.put("name", obj.getClassName());

                            // 使用计算出的实际坐标（center + width/height 转换为 x1,y1,x2,y2）
                            double cx = obj.getCenter().x;
                            double cy = obj.getCenter().y;
                            double w = obj.getWidth();
                            double h = obj.getHeight();
                            label.put("x1", (int) (cx - w / 2));
                            label.put("y1", (int) (cy - h / 2));
                            label.put("x2", (int) (cx + w / 2));
                            label.put("y2", (int) (cy + h / 2));

                            labels.add(label);
                        }

                        // 保存JSON文件（结构完全和 InspectController 一致）
                        String jsonFileName = fileBaseName + "_camera" + cameraId + ".json";
                        Path jsonPath = collectDir.resolve(jsonFileName);

                        Map<String, Object> jsonData = new HashMap<>();
                        jsonData.put("labels", labels);

                        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                        mapper.writerWithDefaultPrettyPrinter().writeValue(jsonPath.toFile(), jsonData);

                        logger.info("Generated annotation JSON for camera {}: {} labels, path: {}",
                            cameraId, labels.size(), jsonPath);

                    } catch (Exception e) {
                        logger.error("Failed to generate annotation JSON for camera {}", cameraId, e);
                    }
                }
            }

            // 释放所有图片
            for (Mat mat : cameraImages.values()) {
                if (mat != null) {
                    mat.release();
                }
            }

            // 构建响应
            response.put("success", true);
            response.put("message", hasAnyTemplate ? "数据采集成功（图片+JSON）" : "数据采集成功（仅图片，无模板）");
            response.put("collected", true);
            response.put("partType", partType);
            response.put("fileBaseName", fileBaseName);
            response.put("collectDir", collectDir.toString());
            response.put("hasTemplate", hasAnyTemplate);
            response.put("savedFiles", savedFiles);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Multi-camera data collection failed", e);
            response.put("success", false);
            response.put("message", "数据采集失败: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * 保存检测记录
     */
    private void saveInspectionRecord(MultiCameraInspectionRequest request,
                                      List<MultiCameraInspectionResponse.CameraInspectionResult> cameraResults,
                                      boolean allPassed) {
        try {
            // 统计特征数量
            int totalFeatures = 0;
            int passedFeatures = 0;
            int failedFeatures = 0;
            int extraFeatures = 0;

            for (MultiCameraInspectionResponse.CameraInspectionResult result : cameraResults) {
                if (result.getFeatures() != null) {
                    totalFeatures += result.getFeatures().size();
                    for (MultiCameraInspectionResponse.FeatureComparison feature : result.getFeatures()) {
                        if ("PASSED".equals(feature.getStatus())) {
                            passedFeatures++;
                        } else if ("EXTRA".equals(feature.getStatus())) {
                            extraFeatures++;
                        } else {
                            failedFeatures++;
                        }
                    }
                }
            }

            // 创建检测记录
            InspectionEntity entity = new InspectionEntity();
            entity.setDeviceId(config.getSystem() != null ? config.getSystem().getDeviceId() : "UNKNOWN");
            entity.setBatchId(request.getBatchId());
            entity.setPartName(request.getPartType());
            entity.setOperator(request.getOperator());
            entity.setTimestamp(LocalDateTime.now());

            // 质检结果
            entity.setPassed(allPassed);
            entity.setQualityStatus(allPassed ? "PASS" : "FAIL");
            entity.setQualityMessage(allPassed ? "所有摄像头检测通过" : "存在检测失败的摄像头");

            // 使用的模板（多摄像头模式，使用partType作为模板标识）
            entity.setTemplateId(request.getPartType());

            // 元数据
            Map<String, Object> meta = new HashMap<>();
            meta.put("inspectionType", "MULTI_CAMERA");
            meta.put("cameraCount", cameraResults.size());
            meta.put("totalFeatures", totalFeatures);
            meta.put("passedFeatures", passedFeatures);
            meta.put("failedFeatures", failedFeatures);
            meta.put("extraFeatures", extraFeatures);

            // 每个摄像头的详细结果
            List<Map<String, Object>> cameraDetails = new ArrayList<>();
            for (MultiCameraInspectionResponse.CameraInspectionResult result : cameraResults) {
                Map<String, Object> cameraDetail = new HashMap<>();
                cameraDetail.put("cameraId", result.getCameraId());
                cameraDetail.put("templateId", result.getTemplateId());
                cameraDetail.put("passed", result.isPassed());
                if (result.getErrorMessage() != null) {
                    cameraDetail.put("errorMessage", result.getErrorMessage());
                }
                if (result.getFeatures() != null) {
                    cameraDetail.put("templateComparisons", result.getFeatures());
                }
                cameraDetail.put("details", result.getDetails());
                cameraDetails.add(cameraDetail);

            }
            meta.put("cameraDetails", cameraDetails);


            entity.setMeta(meta);

            // 保存所有摄像头的图片，收集图片路径
            List<String> imagePathList = new ArrayList<>();
            for (MultiCameraInspectionResponse.CameraInspectionResult result : cameraResults) {
                if (result.getImageUrl() != null && result.getImageUrl().startsWith("data:image/jpeg;base64,")) {
                    String base64 = result.getImageUrl().substring("data:image/jpeg;base64,".length());
                    String imagePath = saveCameraImage(entity, result.getCameraId(), base64);
                    if (imagePath != null) {
                        imagePathList.add(imagePath);
                    }
                }
            }

            // 用逗号连接所有图片路径
            if (!imagePathList.isEmpty()) {
                entity.setImagePath(String.join(",", imagePathList));
            }

            // 保存记录到数据库（不再保存图片，因为已经单独保存过了）
            dataManager.saveRecord(entity, null);

            logger.info("Saved inspection record: partType={}, batchId={}, passed={}, images={}",
                request.getPartType(), request.getBatchId(), allPassed, imagePathList.size());

        } catch (Exception e) {
            logger.error("Failed to save inspection record", e);
            // 不影响检测结果，只记录日志
        }
    }

    /**
     * 保存单个摄像头的图片，返回图片访问路径
     */
    private String saveCameraImage(InspectionEntity entity, int cameraId, String imageBase64) {
        try {
            // 创建目录: data/images/yyyy-MM-dd/partType/
            String partType = entity.getPartName() != null ? entity.getPartName() : "UNKNOWN";
            String dateStr = java.time.LocalDate.now().toString();
            java.nio.file.Path dir = java.nio.file.Paths.get("data", "images", dateStr, partType);
            java.nio.file.Files.createDirectories(dir);

            // 生成文件名: partType_cameraId_timestamp.jpg
            String filename = partType + "_camera" + cameraId + "_" +
                             entity.getTimestamp().toEpochSecond(java.time.ZoneOffset.UTC) +
                             ".jpg";

            java.nio.file.Path imagePath = dir.resolve(filename);

            // 解码并保存
            byte[] imageBytes = java.util.Base64.getDecoder().decode(imageBase64);
            java.nio.file.Files.write(imagePath, imageBytes);

            // 存储相对路径（用于API访问）
            // 格式: /api/images/yyyy-MM-dd/partType/xxx.jpg
            String relativePath = "/api/images/" + dateStr + "/" + partType + "/" + filename;
            logger.debug("Saved camera {} image to: {}", cameraId, relativePath);
            return relativePath;

        } catch (Exception e) {
            logger.error("Failed to save camera {} image", cameraId, e);
            return null;
        }
    }

    /**
     * 将base64字符串保存到文件
     */
    private void saveBase64ToFile(String base64, Path filePath) throws Exception {
        byte[] imageBytes = java.util.Base64.getDecoder().decode(base64);
        Files.write(filePath, imageBytes);
    }

    /**
     * 保存 Mat 到文件（使用 Java IO，支持中文路径）
     * 避免 OpenCV Imgcodecs.imwrite 在 Windows 中文路径上的问题
     */
    private void saveMatToFile(Mat mat, Path path) throws Exception {
        // 将 Mat 编码为 JPEG 字节数组
        org.opencv.core.MatOfByte mob = new org.opencv.core.MatOfByte();
        org.opencv.core.MatOfInt params = new org.opencv.core.MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 95);
        boolean encoded = Imgcodecs.imencode(".jpg", mat, mob, params);

        if (!encoded) {
            mob.release();
            params.release();
            throw new Exception("Failed to encode image to JPEG");
        }

        byte[] imageBytes = mob.toArray();
        mob.release();
        params.release();

        // 使用 Java NIO 写入文件（支持中文路径）
        Files.write(path, imageBytes);
        logger.debug("Saved image to: {}", path.toAbsolutePath());
    }

    /**
     * 等比例压缩图片（如果任一边超过 6000）
     */
    private Mat resizeIfNeeded(Mat src) {
        int width = src.cols();
        int height = src.rows();
        int maxDim = Math.max(width, height);

        // 如果最大边不超过 6000，直接返回原图
        if (maxDim <= 6000) {
            return src;
        }

        // 计算等比例缩放比例
        double scale = 6000.0 / maxDim;
        int newWidth = (int) (width * scale);
        int newHeight = (int) (height * scale);

        logger.info("Resizing image from {}x{} to {}x{} (scale: {})",
                width, height, newWidth, newHeight, String.format("%.4f", scale));

        // 执行 resize
        Mat dst = new Mat();
        Imgproc.resize(src, dst, new org.opencv.core.Size(newWidth, newHeight), 0, 0, Imgproc.INTER_AREA);

        return dst;
    }

    /**
     * 获取工件的摄像头模板信息
     */
    @Operation(summary = "获取工件摄像头信息", description = "获取指定工件的所有摄像头模板映射信息")
    @GetMapping("/{partType}/cameras")
    public ResponseEntity<Map<String, Object>> getCameras(@PathVariable String partType) {
        try {
            Map<Integer, String> cameraTemplates = partCameraTemplateService.getCameraTemplates(partType);

            List<Map<String, Object>> cameraList = new ArrayList<>();
            for (Map.Entry<Integer, String> entry : cameraTemplates.entrySet()) {
                Map<String, Object> info = new java.util.HashMap<>();
                info.put("cameraId", entry.getKey());
                info.put("templateId", entry.getValue());
                cameraList.add(info);
            }

            Map<String, Object> response = new java.util.HashMap<>();
            response.put("success", true);
            response.put("partType", partType);
            response.put("cameras", cameraList);
            response.put("cameraCount", cameraList.size());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Get cameras failed", e);
            Map<String, Object> response = new java.util.HashMap<>();
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.ok(response);
        }
    }
}
