package com.edge.vision.controller;

import com.edge.vision.core.template.TemplateManager;
import com.edge.vision.core.template.model.DetectedObject;
import com.edge.vision.core.template.model.Template;
import com.edge.vision.core.template.model.TemplateFeature;
import com.edge.vision.dto.ManualTemplateRequest;
import com.edge.vision.dto.ManualTemplateResponse;
import com.edge.vision.service.CameraService;
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

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * 手动模板创建控制器
 * <p>
 * 完全手动创建模板，不使用 YOLO 模型
 * <p>
 * 建模流程：
 * 1. 调用摄像头截图或上传图片 -> 返回图片给前端
 * 2. 前端手动框选工件整体和标注细节 -> 返回预览（带标注）
 * 3. 保存模板 -> 保存截图和手动标注数据
 */
@RestController
@RequestMapping("/api/manual-template")
public class ManualTemplateController {

    private static final Logger logger = LoggerFactory.getLogger(ManualTemplateController.class);

    @Autowired
    private CameraService cameraService;

    @Autowired
    private TemplateManager templateManager;

    @org.springframework.beans.factory.annotation.Value("${upload.path:uploads}")
    private String uploadPath;

    /**
     * 第一步：调用摄像头截图，返回给前端
     */
    @PostMapping("/capture")
    public ResponseEntity<?> captureImage(@RequestBody java.util.Map<String, String> request) {
        try {
            String partType = request.get("partType");
            if (partType == null || partType.isEmpty()) {
                return ResponseEntity.ok(new Response(false, "请提供工件类型", null));
            }

            if (!cameraService.isRunning()) {
                return ResponseEntity.ok(new Response(false, "摄像头未启动", null));
            }

            // 获取拼接图像
            Mat mat = cameraService.getStitchedImage();
            if (mat == null || mat.empty()) {
                return ResponseEntity.ok(new Response(false, "获取图像失败", null));
            }

            // 保存图像到临时目录，使用工件类型作为文件名
            Path dir = Paths.get(uploadPath, "manual-temp");
            Files.createDirectories(dir);

            String filename = partType + "_manual_temp.jpg";
            Path filePath = dir.resolve(filename);

            // 保存图像
            Imgcodecs.imwrite(filePath.toString(), mat);

            // 生成 base64 用于返回
            String stitchedImageBase64 = matToBase64(mat);
            mat.release();

            logger.info("手动模板摄像头截图保存成功: {}", filePath);

            // 返回工件类型作为 templateId 和图片 base64
            java.util.Map<String, Object> result = new java.util.HashMap<>();
            result.put("templateId", partType);
            result.put("imageUrl", "data:image/jpeg;base64," + stitchedImageBase64);

            return ResponseEntity.ok(new Response(true, "截图成功", result));

        } catch (Exception e) {
            logger.error("截图失败", e);
            return ResponseEntity.ok(new Response(false, "截图失败: " + e.getMessage(), null));
        }
    }

    /**
     * 第一步备选：上传本地图片
     */
    @PostMapping("/upload")
    public ResponseEntity<?> uploadImage(@RequestBody java.util.Map<String, String> request) {
        try {
            String partType = request.get("partType");
            String imageData = request.get("imageData");

            if (partType == null || partType.isEmpty()) {
                return ResponseEntity.ok(new Response(false, "请提供工件类型", null));
            }
            if (imageData == null || imageData.isEmpty()) {
                return ResponseEntity.ok(new Response(false, "请提供图片数据", null));
            }

            // 去除 data:image/jpeg;base64, 前缀
            String base64Data = imageData;
            if (imageData.startsWith("data:image/")) {
                base64Data = imageData.substring(imageData.indexOf(',') + 1);
            }

            // 保存图像到临时目录
            Path dir = Paths.get(uploadPath, "manual-temp");
            Files.createDirectories(dir);

            String filename = partType + "_manual_temp.jpg";
            Path filePath = dir.resolve(filename);

            // 解码并保存
            byte[] imageBytes = Base64.getDecoder().decode(base64Data);
            Files.write(filePath, imageBytes);

            logger.info("手动模板图片上传保存成功: {}", filePath);

            // 返回结果
            java.util.Map<String, Object> result = new java.util.HashMap<>();
            result.put("templateId", partType);
            result.put("imageUrl", imageData);

            return ResponseEntity.ok(new Response(true, "上传成功", result));

        } catch (Exception e) {
            logger.error("上传失败", e);
            return ResponseEntity.ok(new Response(false, "上传失败: " + e.getMessage(), null));
        }
    }

    /**
     * 第二步：预览 - 根据手动标注生成预览图片
     */
    @PostMapping("/preview")
    public ResponseEntity<?> preview(@RequestBody ManualTemplateRequest request) {
        try {
            logger.info("手动模板预览请求: templateId={}, annotations={}",
                request.getTemplateId(), request.getAnnotations().size());

            // 获取原始图片路径
            String originalImagePath = Paths.get(uploadPath, "manual-temp",
                request.getTemplateId() + "_manual_temp.jpg").toString();

            if (!new File(originalImagePath).exists()) {
                return ResponseEntity.ok(ManualTemplateResponse.error("原始图片不存在"));
            }

            // 读取原始图片
            Mat fullImage = Imgcodecs.imread(originalImagePath);
            if (fullImage == null || fullImage.empty()) {
                return ResponseEntity.ok(ManualTemplateResponse.error("无法读取图片"));
            }

            // 创建带标注的预览图
            Mat resultMat = fullImage.clone();

            // 绘制裁剪区域（工件整体）
            if (request.getCropArea() != null) {
                drawCropArea(resultMat, request.getCropArea());
            }

            // 绘制所有标注
            List<ManualTemplateResponse.AnnotationPreview> previews = new ArrayList<>();
            if (request.getAnnotations() != null) {
                for (int i = 0; i < request.getAnnotations().size(); i++) {
                    ManualTemplateRequest.ManualAnnotation ann = request.getAnnotations().get(i);
                    drawAnnotation(resultMat, ann, i);
                    previews.add(new ManualTemplateResponse.AnnotationPreview(
                        ann.getId(),
                        ann.getName(),
                        ann.getClassId(),
                        ann.getBbox().getX(),
                        ann.getBbox().getY(),
                        ann.getBbox().getWidth(),
                        ann.getBbox().getHeight()
                    ));
                }
            }

            // 转换为 base64
            String resultImageBase64 = matToBase64(resultMat);
            resultMat.release();
            fullImage.release();

            return ResponseEntity.ok(ManualTemplateResponse.success(
                "预览生成成功",
                request.getTemplateId(),
                "data:image/jpeg;base64," + resultImageBase64,
                previews
            ));

        } catch (Exception e) {
            logger.error("预览失败", e);
            return ResponseEntity.ok(ManualTemplateResponse.error("预览失败: " + e.getMessage()));
        }
    }

    /**
     * 第三步：保存模板
     */
    @PostMapping("/save")
    public ResponseEntity<?> save(@RequestBody ManualTemplateRequest request) {
        try {
            logger.info("保存手动模板: templateId={}", request.getTemplateId());

            // 获取原始图片路径
            String originalImagePath = Paths.get(uploadPath, "manual-temp",
                request.getTemplateId() + "_manual_temp.jpg").toString();

            Mat originalImage = Imgcodecs.imread(originalImagePath);
            if (originalImage == null || originalImage.empty()) {
                return ResponseEntity.ok(new Response(false, "无法读取原始图片", null));
            }

            // 读取图片为 base64
            String base64Image = matToBase64(originalImage);

            // 转换 cropArea 为 cropRect
            int[] cropRect = new int[4];
            if (request.getCropArea() != null) {
                cropRect[0] = (int) request.getCropArea().getX();
                cropRect[1] = (int) request.getCropArea().getY();
                cropRect[2] = (int) request.getCropArea().getWidth();
                cropRect[3] = (int) request.getCropArea().getHeight();
            } else {
                // 如果没有指定裁剪区域，使用整张图片
                cropRect[0] = 0;
                cropRect[1] = 0;
                cropRect[2] = originalImage.cols();
                cropRect[3] = originalImage.rows();
            }

            // 转换手动标注为 DetectedObject
            List<DetectedObject> detectedObjects = new ArrayList<>();
            if (request.getAnnotations() != null) {
                for (ManualTemplateRequest.ManualAnnotation ann : request.getAnnotations()) {
                    DetectedObject obj = new DetectedObject();
                    obj.setClassId(ann.getClassId());
                    obj.setClassName(ann.getName());

                    // 计算中心点
                    double centerX = ann.getBbox().getX() + ann.getBbox().getWidth() / 2.0;
                    double centerY = ann.getBbox().getY() + ann.getBbox().getHeight() / 2.0;
                    obj.setCenter(new com.edge.vision.core.template.model.Point(centerX, centerY));
                    obj.setWidth(ann.getBbox().getWidth());
                    obj.setHeight(ann.getBbox().getHeight());
                    obj.setConfidence(1.0); // 手动标注置信度为1

                    detectedObjects.add(obj);
                }
            }

            // 使用 VisionTool 创建模板
            Template template = VisionTool.createTemplate(base64Image, cropRect, detectedObjects, request.getTemplateId());

            // 设置元数据
            java.util.Map<String, Object> metadata = new java.util.HashMap<>();
            metadata.put("cropWidth", originalImage.cols());
            metadata.put("cropHeight", originalImage.rows());
            metadata.put("objectTemplatePath", template.getImagePath());
            metadata.put("matchStrategy", "MANUAL");
            metadata.put("creationMethod", "MANUAL_ANNOTATION");
            template.setMetadata(metadata);

            // 关联工件类型
            template.setPartType(request.getTemplateId());

            // 设置容差
            template.setToleranceX(request.getToleranceX());
            template.setToleranceY(request.getToleranceY());
            template.setDescription(request.getDescription());

            // 设置单个特征的容差（如果有单独设置）
            if (request.getAnnotations() != null) {
                for (int i = 0; i < template.getFeatures().size(); i++) {
                    TemplateFeature feature = template.getFeatures().get(i);
                    ManualTemplateRequest.ManualAnnotation ann = request.getAnnotations().get(i);

                    if (ann.getToleranceX() > 0) {
                        feature.getTolerance().setX(ann.getToleranceX());
                    }
                    if (ann.getToleranceY() > 0) {
                        feature.getTolerance().setY(ann.getToleranceY());
                    }
                    feature.setRequired(ann.isRequired());
                }
            }

            originalImage.release();

            // 使用 TemplateManager 保存模板
            templateManager.save(template);
            templateManager.setCurrentTemplate(template);

            logger.info("手动模板保存成功: {}", template);
            return ResponseEntity.ok(new Response(true, "保存成功", template));

        } catch (Exception e) {
            logger.error("保存失败", e);
            return ResponseEntity.ok(new Response(false, "保存失败: " + e.getMessage(), null));
        }
    }

    // ============ 工具方法 ============

    /**
     * 绘制裁剪区域（工件整体）
     */
    private void drawCropArea(Mat image, ManualTemplateRequest.CropArea cropArea) {
        // 绘制蓝色边界框
        org.opencv.core.Point p1 = new org.opencv.core.Point(cropArea.getX(), cropArea.getY());
        org.opencv.core.Point p2 = new org.opencv.core.Point(
            cropArea.getX() + cropArea.getWidth(),
            cropArea.getY() + cropArea.getHeight()
        );
        Imgproc.rectangle(image, p1, p2, new Scalar(255, 0, 0), 3);

        // 绘制标签
        String label = "Workpiece Area";
        org.opencv.core.Point textPos = new org.opencv.core.Point(cropArea.getX(), cropArea.getY() - 10);
        Imgproc.putText(image, label, textPos,
            Imgproc.FONT_HERSHEY_SIMPLEX, 0.8, new Scalar(255, 0, 0), 2);
    }

    /**
     * 绘制标注
     */
    private void drawAnnotation(Mat image, ManualTemplateRequest.ManualAnnotation annotation, int index) {
        ManualTemplateRequest.BoundingBox bbox = annotation.getBbox();

        // 根据类别选择颜色
        Scalar color = getColorByClassId(annotation.getClassId());

        // 绘制边界框
        org.opencv.core.Point p1 = new org.opencv.core.Point(bbox.getX(), bbox.getY());
        org.opencv.core.Point p2 = new org.opencv.core.Point(
            bbox.getX() + bbox.getWidth(),
            bbox.getY() + bbox.getHeight()
        );
        Imgproc.rectangle(image, p1, p2, color, 2);

        // 绘制标签
        String label = String.format("%s_%d", annotation.getName(), index);
        int[] baseline = new int[1];
        org.opencv.core.Size textSize = Imgproc.getTextSize(
            label, Imgproc.FONT_HERSHEY_SIMPLEX, 0.5, 1, baseline);

        double textY = Math.max(bbox.getY() - 5, textSize.height + 5);
        org.opencv.core.Point textPos = new org.opencv.core.Point(bbox.getX(), textY);

        // 绘制背景
        org.opencv.core.Point bg1 = new org.opencv.core.Point(
            bbox.getX(), textY - textSize.height - 5);
        org.opencv.core.Point bg2 = new org.opencv.core.Point(
            bbox.getX() + textSize.width, textY + 5);
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
     * 简单响应类
     */
    public static class Response {
        private boolean success;
        private String message;
        private Object data;

        public Response(boolean success, String message, Object data) {
            this.success = success;
            this.message = message;
            this.data = data;
        }

        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }

        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }

        public Object getData() { return data; }
        public void setData(Object data) { this.data = data; }
    }
}
