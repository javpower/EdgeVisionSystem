package com.edge.vision.controller;

import com.edge.vision.core.template.TemplateManager;
import com.edge.vision.dto.MultiCameraCaptureResponse;
import com.edge.vision.dto.MultiCameraPreviewRequest;
import com.edge.vision.dto.MultiCameraPreviewResponse;
import com.edge.vision.dto.MultiCameraTemplateRequest;
import com.edge.vision.dto.MultiCameraTemplateResponse;
import com.edge.vision.service.CameraService;
import com.edge.vision.service.PartCameraTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfInt;
import org.opencv.imgcodecs.Imgcodecs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * 多摄像头模板管理控制器
 * <p>
 * 建模流程：
 * 1. 调用截图 -> 返回各个摄像头的图片给前端
 * 2. 前端给每个图片框出模板整体（cropRect）
 * 3. 后端批量YOLO识别 -> 创建多个模板（每个摄像头一个）
 */
@Tag(name = "多摄像头模板管理", description = "基于多个摄像头的模板创建和管理")
@RestController
@RequestMapping("/api/multi-camera-template")
public class MultiCameraTemplateController {

    private static final Logger logger = LoggerFactory.getLogger(MultiCameraTemplateController.class);

    @Autowired
    private CameraService cameraService;

    @Autowired
    private PartCameraTemplateService partCameraTemplateService;

    @Autowired
    private TemplateManager templateManager;

    @Value("${upload.path:uploads}")
    private String uploadPath;

    /**
     * 第一步：截图，返回各个摄像头的图片
     */
    @Operation(summary = "多摄像头截图", description = "获取各个摄像头的当前画面，用于多摄像头建模")
    @PostMapping("/capture")
    public ResponseEntity<MultiCameraCaptureResponse> capture(@RequestBody Map<String, String> request) {
        try {
            String partType = request.get("partType");
            if (partType == null || partType.isEmpty()) {
                return ResponseEntity.ok(MultiCameraCaptureResponse.error("请提供工件类型"));
            }

            if (!cameraService.isRunning()) {
                return ResponseEntity.ok(MultiCameraCaptureResponse.error("摄像头未启动"));
            }

            int cameraCount = cameraService.getCameraCount();
            if (cameraCount == 0) {
                return ResponseEntity.ok(MultiCameraCaptureResponse.error("没有可用的摄像头"));
            }

            // 创建临时目录
            Path tempDir = Paths.get(uploadPath, "multi-camera-temp", partType);
            Files.createDirectories(tempDir);

            List<MultiCameraCaptureResponse.CameraImage> cameras = new ArrayList<>();

            // 获取每个摄像头的当前帧
            for (int i = 0; i < cameraCount; i++) {
                Mat frame = null;
                try {
                    // 从CameraService获取当前帧
                    String base64 = cameraService.getCameraImageBase64(i);
                    if (base64 == null || base64.isEmpty()) {
                        logger.warn("Camera {} frame is null or empty", i);
                        continue;
                    }

                    // 保存临时图片
                    String filename = "camera_" + i + ".jpg";
                    Path filePath = tempDir.resolve(filename);

                    // 解码base64并保存
                    String base64Data = base64;
                    if (base64.startsWith("data:image/")) {
                        base64Data = base64.substring(base64.indexOf(',') + 1);
                    }
                    byte[] imageBytes = Base64.getDecoder().decode(base64Data);
                    Files.write(filePath, imageBytes);

                    cameras.add(new MultiCameraCaptureResponse.CameraImage(i, "data:image/jpeg;base64," + base64));

                    logger.info("Captured frame from camera {}: {}", i, filePath);

                } catch (Exception e) {
                    logger.error("Failed to capture camera {}", i, e);
                }
            }

            if (cameras.isEmpty()) {
                return ResponseEntity.ok(MultiCameraCaptureResponse.error("未能获取任何摄像头的画面"));
            }

            return ResponseEntity.ok(MultiCameraCaptureResponse.success(partType, cameras));

        } catch (Exception e) {
            logger.error("Capture failed", e);
            return ResponseEntity.ok(MultiCameraCaptureResponse.error("截图失败: " + e.getMessage()));
        }
    }

    /**
     * 第二步：预览YOLO识别结果
     */
    @Operation(summary = "预览识别结果", description = "批量YOLO识别，返回带框的图片和识别结果")
    @PostMapping("/preview")
    public ResponseEntity<MultiCameraPreviewResponse> preview(@RequestBody MultiCameraPreviewRequest request) {
        try {
            String partType = request.getPartType();
            if (partType == null || partType.isEmpty()) {
                return ResponseEntity.ok(MultiCameraPreviewResponse.error("请提供工件类型"));
            }

            if (request.getCameraCrops() == null || request.getCameraCrops().isEmpty()) {
                return ResponseEntity.ok(MultiCameraPreviewResponse.error("请提供摄像头裁剪数据"));
            }

            logger.info("Previewing detections for part: {}, cameras: {}",
                partType, request.getCameraCrops().size());

            // 准备图像数据列表
            List<PartCameraTemplateService.CameraImageData> imageDataList = new ArrayList<>();
            Path tempDir = Paths.get(uploadPath, "multi-camera-temp", partType);

            for (MultiCameraPreviewRequest.CameraCropData camData : request.getCameraCrops()) {
                String tempImagePath = tempDir.resolve("camera_" + camData.getCameraId() + ".jpg").toString();

                if (!java.nio.file.Files.exists(Paths.get(tempImagePath))) {
                    logger.warn("Temp image not found for camera {}", camData.getCameraId());
                    continue;
                }

                imageDataList.add(new PartCameraTemplateService.CameraImageData(
                    camData.getCameraId(),
                    camData.getCropRect(),
                    tempImagePath
                ));
            }

            if (imageDataList.isEmpty()) {
                return ResponseEntity.ok(MultiCameraPreviewResponse.error("没有找到有效的临时图片"));
            }

            // 批量YOLO识别并获取预览结果
            List<PartCameraTemplateService.CameraPreviewResult> previewResults =
                partCameraTemplateService.previewDetections(partType, imageDataList);

            // 转换为响应格式
            List<MultiCameraPreviewResponse.CameraPreviewData> cameras = new ArrayList<>();
            for (PartCameraTemplateService.CameraPreviewResult result : previewResults) {
                List<MultiCameraPreviewResponse.FeatureInfo> features = new ArrayList<>();
                for (PartCameraTemplateService.FeatureInfo fi : result.features) {
                    features.add(new MultiCameraPreviewResponse.FeatureInfo(
                        fi.featureId, fi.className, fi.classId,
                        fi.centerX, fi.centerY, fi.width, fi.height, fi.confidence
                    ));
                }
                cameras.add(new MultiCameraPreviewResponse.CameraPreviewData(
                    result.cameraId, result.imageUrl, features
                ));
            }

            logger.info("Preview completed for {} cameras", cameras.size());

            return ResponseEntity.ok(MultiCameraPreviewResponse.success(partType, cameras));

        } catch (Exception e) {
            logger.error("Preview failed", e);
            return ResponseEntity.ok(MultiCameraPreviewResponse.error("预览失败: " + e.getMessage()));
        }
    }

    /**
     * 第三步：批量创建模板
     */
    @Operation(summary = "批量创建模板", description = "根据前端框选的区域，批量YOLO识别并创建多个摄像头模板")
    @PostMapping("/save")
    public ResponseEntity<MultiCameraTemplateResponse> save(@RequestBody MultiCameraTemplateRequest request) {
        try {
            String partType = request.getPartType();
            if (partType == null || partType.isEmpty()) {
                return ResponseEntity.ok(MultiCameraTemplateResponse.error("请提供工件类型"));
            }

            if (request.getCameraTemplates() == null || request.getCameraTemplates().isEmpty()) {
                return ResponseEntity.ok(MultiCameraTemplateResponse.error("请提供摄像头模板数据"));
            }

            logger.info("Creating multi-camera templates for part: {}, cameras: {}",
                partType, request.getCameraTemplates().size());

            // 先删除旧的模板和图片
            deletePartTypeImages(partType);
            partCameraTemplateService.removePartTemplates(partType);
            logger.info("Cleared old templates and images for part: {}", partType);

            // 准备图像数据列表
            List<PartCameraTemplateService.CameraImageData> imageDataList = new ArrayList<>();
            Path tempDir = Paths.get(uploadPath, "multi-camera-temp", partType);

            for (MultiCameraTemplateRequest.CameraTemplateData camData : request.getCameraTemplates()) {
                String tempImagePath = tempDir.resolve("camera_" + camData.getCameraId() + ".jpg").toString();

                if (!Files.exists(Paths.get(tempImagePath))) {
                    logger.warn("Temp image not found for camera {}", camData.getCameraId());
                    continue;
                }

                imageDataList.add(new PartCameraTemplateService.CameraImageData(
                    camData.getCameraId(),
                    camData.getCropRect(),
                    tempImagePath
                ));
            }

            if (imageDataList.isEmpty()) {
                return ResponseEntity.ok(MultiCameraTemplateResponse.error("没有找到有效的临时图片"));
            }

            // 批量创建模板
            List<com.edge.vision.core.template.model.Template> templates = partCameraTemplateService.createTemplatesBatch(
                partType,
                imageDataList,
                request.getToleranceX(),
                request.getToleranceY()
            );

            if (templates.isEmpty()) {
                return ResponseEntity.ok(MultiCameraTemplateResponse.error("未能创建任何模板，请检查YOLO识别结果"));
            }

            // 构建响应
            List<MultiCameraTemplateResponse.SavedTemplateInfo> templateInfos = new ArrayList<>();
            for (com.edge.vision.core.template.model.Template template : templates) {
                Integer cameraId = (Integer) template.getMetadata().get("cameraId");
                templateInfos.add(new MultiCameraTemplateResponse.SavedTemplateInfo(
                    cameraId != null ? cameraId : -1,
                    template.getTemplateId(),
                    template.getImagePath(),
                    template.getFeatures() != null ? template.getFeatures().size() : 0
                ));
            }

            logger.info("Successfully created {} templates for part {}", templates.size(), partType);

            return ResponseEntity.ok(MultiCameraTemplateResponse.success(partType, templateInfos));

        } catch (Exception e) {
            logger.error("Save failed", e);
            return ResponseEntity.ok(MultiCameraTemplateResponse.error("保存失败: " + e.getMessage()));
        }
    }

    /**
     * 获取工件的所有摄像头模板信息
     */
    @Operation(summary = "获取工件模板信息", description = "获取指定工件的所有摄像头模板映射信息")
    @GetMapping("/{partType}")
    public ResponseEntity<Map<String, Object>> getTemplates(@PathVariable String partType) {
        try {
            Map<Integer, String> cameraTemplates = partCameraTemplateService.getCameraTemplates(partType);

            List<Map<String, Object>> templateList = new ArrayList<>();
            for (Map.Entry<Integer, String> entry : cameraTemplates.entrySet()) {
                Map<String, Object> info = new java.util.HashMap<>();
                info.put("cameraId", entry.getKey());
                info.put("templateId", entry.getValue());
                templateList.add(info);
            }

            Map<String, Object> response = new java.util.HashMap<>();
            response.put("success", true);
            response.put("partType", partType);
            response.put("cameras", templateList);
            response.put("cameraCount", templateList.size());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Get templates failed", e);
            Map<String, Object> response = new java.util.HashMap<>();
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.ok(response);
        }
    }

    /**
     * 删除工件的所有模板
     */
    @Operation(summary = "删除工件模板", description = "删除指定工件的所有摄像头模板及关联图片")
    @DeleteMapping("/{partType}")
    public ResponseEntity<Map<String, Object>> deleteTemplates(@PathVariable String partType) {
        try {
            // 先删除图片
            int deletedImages = deletePartTypeImages(partType);
            logger.info("Deleted {} images for part: {}", deletedImages, partType);

            // 再删除模板
            partCameraTemplateService.removePartTemplates(partType);

            Map<String, Object> response = new java.util.HashMap<>();
            response.put("success", true);
            response.put("message", "删除成功");
            response.put("deletedImages", deletedImages);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Delete templates failed", e);
            Map<String, Object> response = new java.util.HashMap<>();
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.ok(response);
        }
    }

    /**
     * 删除指定工件类型的所有模板图片
     */
    private int deletePartTypeImages(String partType) {
        int deletedCount = 0;
        try {
            // 获取该工件的所有模板
            List<com.edge.vision.core.template.model.Template> templates =
                templateManager.getTemplatesByPartType(partType);

            for (com.edge.vision.core.template.model.Template template : templates) {
                String imagePath = template.getImagePath();
                if (imagePath != null && !imagePath.isEmpty()) {
                    try {
                        Path filePath = Paths.get(imagePath);
                        if (Files.exists(filePath)) {
                            Files.delete(filePath);
                            deletedCount++;
                            logger.info("Deleted template image: {}", imagePath);
                        }
                    } catch (Exception e) {
                        logger.warn("Failed to delete image: {}", imagePath, e);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Failed to delete images for part: {}", partType, e);
        }
        return deletedCount;
    }

    /**
     * 获取所有工件类型
     */
    @Operation(summary = "获取所有工件类型", description = "获取所有已建模的工件类型列表")
    @GetMapping("/part-types")
    public ResponseEntity<Map<String, Object>> getAllPartTypes() {
        try {
            java.util.Set<String> partTypes = templateManager.getAllPartTypes();

            // 为每个工件类型获取摄像头数量
            List<Map<String, Object>> partTypeList = new ArrayList<>();
            for (String partType : partTypes) {
                Map<String, Object> info = new java.util.HashMap<>();
                info.put("partType", partType);
                info.put("cameraCount", partCameraTemplateService.getCameraCount(partType));
                partTypeList.add(info);
            }

            Map<String, Object> response = new java.util.HashMap<>();
            response.put("success", true);
            response.put("partTypes", partTypeList);
            response.put("count", partTypeList.size());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Get part types failed", e);
            Map<String, Object> response = new java.util.HashMap<>();
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.ok(response);
        }
    }
}
