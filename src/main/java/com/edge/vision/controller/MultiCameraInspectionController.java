package com.edge.vision.controller;

import com.edge.vision.config.YamlConfig;
import com.edge.vision.dto.MultiCameraInspectionRequest;
import com.edge.vision.dto.MultiCameraInspectionResponse;
import com.edge.vision.model.InspectionEntity;
import com.edge.vision.service.CameraService;
import com.edge.vision.service.DataManager;
import com.edge.vision.service.PartCameraTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

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
                    features
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
     * 直接保存每个摄像头的原始图片，不进行识别
     */
    @Operation(summary = "多摄像头数据采集", description = "直接保存每个摄像头的原始图片到指定目录（用于数据收集）")
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

            // 获取所有摄像头的图片
            Map<Integer, String> cameraTemplates = partCameraTemplateService.getCameraTemplates(partType);
            if (cameraTemplates.isEmpty()) {
                response.put("success", false);
                response.put("message", "未找到工件类型对应的摄像头模板");
                return ResponseEntity.badRequest().body(response);
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
                // URL解码处理中文路径
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

            // 为每个摄像头保存原始图片
            for (Integer cameraId : cameraTemplates.keySet()) {
                // 获取摄像头图片的Mat
                org.opencv.core.Mat imageMat = cameraService.getCameraImageMat(cameraId);
                if (imageMat == null || imageMat.empty()) {
                    logger.warn("Failed to get image for camera {}", cameraId);
                    continue;
                }

                // 生成文件名: partType_yyyyMMdd_HHmmss_cameraN.jpg
                String imageFileName = fileBaseName + "_camera" + cameraId + ".jpg";
                Path imageFilePath = collectDir.resolve(imageFileName);

                // 保存图片
                org.opencv.imgcodecs.Imgcodecs.imwrite(imageFilePath.toString(), imageMat);
                imageMat.release();

                // 记录保存的文件信息
                Map<String, Object> fileInfo = new HashMap<>();
                fileInfo.put("cameraId", cameraId);
                fileInfo.put("imagePath", imageFilePath.toString());
                fileInfo.put("fileName", imageFileName);
                savedFiles.add(fileInfo);

                logger.info("Saved camera {} image to: {}", cameraId, imageFilePath);
            }

            // 构建响应
            response.put("success", true);
            response.put("message", "数据采集成功");
            response.put("collected", true);
            response.put("partType", partType);
            response.put("fileBaseName", fileBaseName);
            response.put("collectDir", collectDir.toString());
            response.put("savedFiles", savedFiles);
            response.put("totalCameras", savedFiles.size());

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
                    cameraDetail.put("features", result.getFeatures());
                }
                cameraDetails.add(cameraDetail);
            }
            meta.put("cameraDetails", cameraDetails);

            entity.setMeta(meta);

            // 保存所有摄像头的图片，收集图片路径
            List<String> imagePaths = new ArrayList<>();
            for (MultiCameraInspectionResponse.CameraInspectionResult result : cameraResults) {
                if (result.getImageUrl() != null && result.getImageUrl().startsWith("data:image/jpeg;base64,")) {
                    String base64 = result.getImageUrl().substring("data:image/jpeg;base64,".length());
                    String imagePath = saveCameraImage(entity, result.getCameraId(), base64);
                    if (imagePath != null) {
                        imagePaths.add(imagePath);
                    }
                }
            }

            // 用逗号连接所有图片路径
            if (!imagePaths.isEmpty()) {
                entity.setImagePath(String.join(",", imagePaths));
            }

            // 保存记录到数据库（不再保存图片，因为已经单独保存过了）
            dataManager.saveRecord(entity, null);

            logger.info("Saved inspection record: partType={}, batchId={}, passed={}, images={}",
                request.getPartType(), request.getBatchId(), allPassed, imagePaths.size());

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
