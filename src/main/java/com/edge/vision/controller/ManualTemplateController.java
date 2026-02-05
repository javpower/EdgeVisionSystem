package com.edge.vision.controller;

import com.edge.vision.core.template.TemplateManager;
import com.edge.vision.core.template.model.DetectedObject;
import com.edge.vision.core.template.model.Template;
import com.edge.vision.core.template.model.TemplateFeature;
import com.edge.vision.dto.ManualMultiCameraRequest;
import com.edge.vision.dto.ManualMultiCameraResponse;
import com.edge.vision.service.CameraService;
import com.edge.vision.service.PartCameraTemplateService;
import com.edge.vision.util.VisionTool;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.Scalar;
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
import java.util.*;

/**
 * 手动模板创建控制器 - 多摄像头版本
 * <p>
 * 完全手动创建模板，不使用 YOLO 模型
 * <p>
 * 建模流程：
 * 1. 调用摄像头截图 -> 返回各个摄像头的图片给前端
 * 2. 前端手动框选每个摄像头的工作整体和标注细节 -> 返回预览（带标注）
 * 3. 保存模板 -> 保存截图和手动标注数据，批量创建多个摄像头模板
 */
@RestController
@RequestMapping("/api/manual-template")
public class ManualTemplateController {

    private static final Logger logger = LoggerFactory.getLogger(ManualTemplateController.class);

    @Autowired
    private CameraService cameraService;

    @Autowired
    private TemplateManager templateManager;

    @Autowired
    private PartCameraTemplateService partCameraTemplateService;

    @org.springframework.beans.factory.annotation.Value("${upload.path:uploads}")
    private String uploadPath;

    /**
     * 第一步：截图，返回各个摄像头的图片
     */
    @PostMapping("/capture")
    public ResponseEntity<?> captureImage(@RequestBody java.util.Map<String, String> request) {
        try {
            String partType = request.get("partType");
            if (partType == null || partType.isEmpty()) {
                return ResponseEntity.ok(Response.error("请提供工件类型"));
            }

            if (!cameraService.isRunning()) {
                return ResponseEntity.ok(Response.error("摄像头未启动"));
            }

            int cameraCount = cameraService.getCameraCount();
            if (cameraCount == 0) {
                return ResponseEntity.ok(Response.error("没有可用的摄像头"));
            }

            // 创建临时目录
            Path tempDir = Paths.get(uploadPath, "manual-temp", partType);
            Files.createDirectories(tempDir);

            List<CameraImageData> cameras = new ArrayList<>();

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

                    cameras.add(new CameraImageData(i, "data:image/jpeg;base64," + base64));

                    logger.info("Captured frame from camera {}: {}", i, filePath);

                } catch (Exception e) {
                    logger.error("Failed to capture camera {}", i, e);
                }
            }

            if (cameras.isEmpty()) {
                return ResponseEntity.ok(Response.error("未能获取任何摄像头的画面"));
            }

            java.util.Map<String, Object> result = new java.util.HashMap<>();
            result.put("partType", partType);
            result.put("cameras", cameras);

            return ResponseEntity.ok(Response.success(partType, cameras));

        } catch (Exception e) {
            logger.error("截图失败", e);
            return ResponseEntity.ok(Response.error("截图失败: " + e.getMessage()));
        }
    }

    /**
     * 第二步：预览 - 根据手动标注生成预览图片
     */
    @PostMapping("/preview")
    public ResponseEntity<?> preview(@RequestBody ManualMultiCameraRequest request) {
        try {
            String partType = request.getPartType();
            if (partType == null || partType.isEmpty()) {
                return ResponseEntity.ok(ManualMultiCameraResponse.error("请提供工件类型"));
            }

            if (request.getCameraTemplates() == null || request.getCameraTemplates().isEmpty()) {
                return ResponseEntity.ok(ManualMultiCameraResponse.error("请提供摄像头数据"));
            }

            logger.info("手动模板预览请求: partType={}, cameras={}",
                partType, request.getCameraTemplates().size());

            Path tempDir = Paths.get(uploadPath, "manual-temp", partType);

            List<ManualMultiCameraResponse.CameraPreviewData> previews = new ArrayList<>();

            for (ManualMultiCameraRequest.CameraTemplateData camData : request.getCameraTemplates()) {
                String tempImagePath = tempDir.resolve("camera_" + camData.getCameraId() + ".jpg").toString();

                if (!Files.exists(Paths.get(tempImagePath))) {
                    logger.warn("Temp image not found for camera {}", camData.getCameraId());
                    continue;
                }

                // 读取原始图片
                Mat fullImage = Imgcodecs.imread(tempImagePath);
                if (fullImage == null || fullImage.empty()) {
                    logger.warn("Failed to load image for camera {}", camData.getCameraId());
                    continue;
                }

                // 创建带标注的预览图
                Mat resultMat = fullImage.clone();

                // 绘制裁剪区域（工件整体）
                if (camData.getCropRect() != null && camData.getCropRect().length >= 4) {
                    drawCropArea(resultMat, camData.getCropRect());
                }

                // 绘制所有标注
                List<ManualMultiCameraResponse.FeatureInfo> features = new ArrayList<>();
                if (camData.getAnnotations() != null) {
                    for (int i = 0; i < camData.getAnnotations().size(); i++) {
                        ManualMultiCameraRequest.ManualAnnotation ann = camData.getAnnotations().get(i);
                        drawAnnotation(resultMat, ann, i);
                        features.add(new ManualMultiCameraResponse.FeatureInfo(
                            ann.getId(),
                            ann.getName(),
                            ann.getClassId(),
                            ann.getBbox()[0] + ann.getBbox()[2] / 2.0,
                            ann.getBbox()[1] + ann.getBbox()[3] / 2.0,
                            ann.getBbox()[2],
                            ann.getBbox()[3]
                        ));
                    }
                }

                // 转换为 base64
                String resultImageBase64 = matToBase64(resultMat);
                resultMat.release();
                fullImage.release();

                previews.add(new ManualMultiCameraResponse.CameraPreviewData(
                    camData.getCameraId(),
                    "data:image/jpeg;base64," + resultImageBase64,
                    features
                ));
            }

            return ResponseEntity.ok(ManualMultiCameraResponse.success(partType, previews));

        } catch (Exception e) {
            logger.error("预览失败", e);
            return ResponseEntity.ok(ManualMultiCameraResponse.error("预览失败: " + e.getMessage()));
        }
    }

    /**
     * 第三步：保存模板
     */
    @PostMapping("/save")
    public ResponseEntity<?> save(@RequestBody ManualMultiCameraRequest request) {
        try {
            String partType = request.getPartType();
            if (partType == null || partType.isEmpty()) {
                return ResponseEntity.ok(ManualMultiCameraResponse.error("请提供工件类型"));
            }

            if (request.getCameraTemplates() == null || request.getCameraTemplates().isEmpty()) {
                return ResponseEntity.ok(ManualMultiCameraResponse.error("请提供摄像头数据"));
            }

            logger.info("保存手动模板: partType={}, cameras={}",
                partType, request.getCameraTemplates().size());

            // 先删除旧的模板和图片
            deletePartTypeImages(partType);
            partCameraTemplateService.removePartTemplates(partType);
            logger.info("Cleared old templates and images for part: {}", partType);

            Path tempDir = Paths.get(uploadPath, "manual-temp", partType);

            List<Template> templates = new ArrayList<>();

            for (ManualMultiCameraRequest.CameraTemplateData camData : request.getCameraTemplates()) {
                String tempImagePath = tempDir.resolve("camera_" + camData.getCameraId() + ".jpg").toString();

                if (!Files.exists(Paths.get(tempImagePath))) {
                    logger.warn("Temp image not found for camera {}", camData.getCameraId());
                    continue;
                }

                Mat originalImage = Imgcodecs.imread(tempImagePath);
                if (originalImage == null || originalImage.empty()) {
                    logger.warn("Failed to load image for camera {}", camData.getCameraId());
                    continue;
                }

                // 读取图片为 base64
                String base64Image = matToBase64(originalImage);

                // 转换 cropRect
                int[] cropRect = new int[4];
                if (camData.getCropRect() != null && camData.getCropRect().length >= 4) {
                    cropRect[0] = camData.getCropRect()[0];
                    cropRect[1] = camData.getCropRect()[1];
                    cropRect[2] = camData.getCropRect()[2];
                    cropRect[3] = camData.getCropRect()[3];
                } else {
                    // 如果没有指定裁剪区域，使用整张图片
                    cropRect[0] = 0;
                    cropRect[1] = 0;
                    cropRect[2] = originalImage.cols();
                    cropRect[3] = originalImage.rows();
                }

                // 转换手动标注为 DetectedObject
                List<DetectedObject> detectedObjects = new ArrayList<>();
                if (camData.getAnnotations() != null) {
                    for (ManualMultiCameraRequest.ManualAnnotation ann : camData.getAnnotations()) {
                        DetectedObject obj = new DetectedObject();
                        obj.setClassId(ann.getClassId());
                        obj.setClassName(ann.getName());

                        // 计算中心点 (bbox: x, y, width, height)
                        double centerX = ann.getBbox()[0] + ann.getBbox()[2] / 2.0;
                        double centerY = ann.getBbox()[1] + ann.getBbox()[3] / 2.0;
                        obj.setCenter(new com.edge.vision.core.template.model.Point(centerX, centerY));
                        obj.setWidth(ann.getBbox()[2]);
                        obj.setHeight(ann.getBbox()[3]);
                        obj.setConfidence(1.0); // 手动标注置信度为1

                        detectedObjects.add(obj);
                    }
                }

                // 生成模板ID：partType_cameraId
                String templateId = partType + "_camera_" + camData.getCameraId();

                // 使用 VisionTool 创建模板
                Template template = VisionTool.createTemplate(base64Image, cropRect, detectedObjects, templateId);

                // 设置元数据
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("cameraId", camData.getCameraId());
                metadata.put("partType", partType);
                metadata.put("cropWidth", originalImage.cols());
                metadata.put("cropHeight", originalImage.rows());
                metadata.put("objectTemplatePath", template.getImagePath());
                metadata.put("matchStrategy", "MANUAL_MULTI_CAMERA");
                metadata.put("creationMethod", "MANUAL_ANNOTATION");
                // 检查是否为空模板
                boolean isEmpty = detectedObjects.isEmpty();
                metadata.put("emptyTemplate", isEmpty);
                template.setMetadata(metadata);

                // 关联工件类型
                template.setPartType(partType);

                // 设置容差
                template.setToleranceX(request.getToleranceX());
                template.setToleranceY(request.getToleranceY());
                template.setDescription(request.getDescription());

                // 设置单个特征的容差（如果有单独设置）
                if (camData.getAnnotations() != null) {
                    for (int i = 0; i < template.getFeatures().size(); i++) {
                        TemplateFeature feature = template.getFeatures().get(i);
                        if (i < camData.getAnnotations().size()) {
                            ManualMultiCameraRequest.ManualAnnotation ann = camData.getAnnotations().get(i);
                            if (ann.getToleranceX() > 0) {
                                feature.getTolerance().setX(ann.getToleranceX());
                            }
                            if (ann.getToleranceY() > 0) {
                                feature.getTolerance().setY(ann.getToleranceY());
                            }
                            feature.setRequired(ann.isRequired());
                        }
                    }
                }

                originalImage.release();

                // 使用 TemplateManager 保存模板
                templateManager.save(template);
                templateManager.setCurrentTemplate(template);

                // 保存映射关系
                partCameraTemplateService.saveCameraTemplate(partType, camData.getCameraId(), templateId);

                templates.add(template);

                logger.info("手动模板保存成功: cameraId={}, templateId={}, features={}",
                    camData.getCameraId(), templateId, detectedObjects.size());
            }

            // 构建响应
            List<ManualMultiCameraResponse.SavedTemplateInfo> templateInfos = new ArrayList<>();
            for (Template template : templates) {
                Integer cameraId = (Integer) template.getMetadata().get("cameraId");
                templateInfos.add(new ManualMultiCameraResponse.SavedTemplateInfo(
                    cameraId != null ? cameraId : -1,
                    template.getTemplateId(),
                    template.getImagePath(),
                    template.getFeatures() != null ? template.getFeatures().size() : 0
                ));
            }

            return ResponseEntity.ok(ManualMultiCameraResponse.saveSuccess(partType, templateInfos));

        } catch (Exception e) {
            logger.error("保存失败", e);
            return ResponseEntity.ok(ManualMultiCameraResponse.error("保存失败: " + e.getMessage()));
        }
    }

    /**
     * 删除指定工件类型的所有模板图片
     */
    private int deletePartTypeImages(String partType) {
        int deletedCount = 0;
        try {
            // 获取该工件的所有模板
            List<Template> templates = templateManager.getTemplatesByPartType(partType);

            for (Template template : templates) {
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

    // ============ 工具方法 ============

    /**
     * 绘制裁剪区域（工件整体）
     */
    private void drawCropArea(Mat image, int[] cropRect) {
        // 绘制蓝色边界框
        org.opencv.core.Point p1 = new org.opencv.core.Point(cropRect[0], cropRect[1]);
        org.opencv.core.Point p2 = new org.opencv.core.Point(
            cropRect[0] + cropRect[2],
            cropRect[1] + cropRect[3]
        );
        Imgproc.rectangle(image, p1, p2, new Scalar(255, 0, 0), 3);

        // 绘制标签
        String label = "Workpiece Area";
        org.opencv.core.Point textPos = new org.opencv.core.Point(cropRect[0], cropRect[1] - 10);
        Imgproc.putText(image, label, textPos,
            Imgproc.FONT_HERSHEY_SIMPLEX, 0.8, new Scalar(255, 0, 0), 2);
    }

    /**
     * 绘制标注
     */
    private void drawAnnotation(Mat image, ManualMultiCameraRequest.ManualAnnotation annotation, int index) {
        int[] bbox = annotation.getBbox();

        // 根据类别选择颜色
        Scalar color = getColorByClassId(annotation.getClassId());

        // 绘制边界框
        org.opencv.core.Point p1 = new org.opencv.core.Point(bbox[0], bbox[1]);
        org.opencv.core.Point p2 = new org.opencv.core.Point(
            bbox[0] + bbox[2],
            bbox[1] + bbox[3]
        );
        Imgproc.rectangle(image, p1, p2, color, 2);

        // 绘制标签
        String label = String.format("%s_%d", annotation.getName(), index);
        int[] baseline = new int[1];
        org.opencv.core.Size textSize = Imgproc.getTextSize(
            label, Imgproc.FONT_HERSHEY_SIMPLEX, 0.5, 1, baseline);

        double textY = Math.max(bbox[1] - 5, textSize.height + 5);
        org.opencv.core.Point textPos = new org.opencv.core.Point(bbox[0], textY);

        // 绘制背景
        org.opencv.core.Point bg1 = new org.opencv.core.Point(
            bbox[0], textY - textSize.height - 5);
        org.opencv.core.Point bg2 = new org.opencv.core.Point(
            bbox[0] + textSize.width, textY + 5);
        Imgproc.rectangle(image, bg1, bg2, color, -1);

        // 绘制文字
        Imgproc.putText(image, label, textPos,
            Imgproc.FONT_HERSHEY_SIMPLEX, 0.5, new Scalar(0, 0, 0), 1);
    }

    /**
     * 根据类别ID获取颜色
     */
    private Scalar getColorByClassId(int classId) {
        Scalar[] colors = {
            new Scalar(0, 255, 0),      // 绿色
            new Scalar(0, 255, 255),    // 黄色
            new Scalar(255, 0, 255),    // 紫色
            new Scalar(255, 255, 0),    // 青色
            new Scalar(255, 128, 0),    // 橙色
            new Scalar(128, 0, 255),    // 玫红
            new Scalar(0, 128, 255),    // 深蓝
            new Scalar(255, 0, 128)     // 粉红
        };
        return colors[classId % colors.length];
    }

    /**
     * 将Mat转换为Base64字符串
     */
    private String matToBase64(Mat mat) {
        MatOfByte mob = new MatOfByte();
        Imgcodecs.imencode(".jpg", mat, mob);
        byte[] bytes = mob.toArray();
        mob.release();
        return Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * 摄像头图像数据（响应用）
     */
    public static class CameraImageData {
        public int cameraId;
        public String imageUrl;

        public CameraImageData(int cameraId, String imageUrl) {
            this.cameraId = cameraId;
            this.imageUrl = imageUrl;
        }

        public int getCameraId() { return cameraId; }
        public String getImageUrl() { return imageUrl; }
    }

    /**
     * 简单响应类
     */
    public static class Response {
        private boolean success;
        private String message;
        private String partType;
        private List<CameraImageData> cameras;

        public Response() {}

        public static Response success(String partType, List<CameraImageData> cameras) {
            Response response = new Response();
            response.success = true;
            response.message = "截图成功";
            response.partType = partType;
            response.cameras = cameras;
            return response;
        }

        public static Response error(String message) {
            Response response = new Response();
            response.success = false;
            response.message = message;
            return response;
        }

        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }

        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }

        public String getPartType() { return partType; }
        public void setPartType(String partType) { this.partType = partType; }

        public List<CameraImageData> getCameras() { return cameras; }
        public void setCameras(List<CameraImageData> cameras) { this.cameras = cameras; }
    }
}
