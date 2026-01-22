package com.edge.vision.controller;

import com.edge.vision.core.infer.YOLOInferenceEngine;
import com.edge.vision.core.template.TemplateManager;
import com.edge.vision.core.template.model.Template;
import com.edge.vision.core.template.model.TemplateFeature;
import com.edge.vision.dto.CropAreaPreviewResponse;
import com.edge.vision.dto.CropAreaTemplateRequest;
import com.edge.vision.service.CameraService;
import com.edge.vision.util.ObjectDetectionUtil;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.imgcodecs.Imgcodecs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 裁剪区域模板管理控制器
 * <p>
 * 建模流程：
 * 1. 调用摄像头截图 -> 返回图片给前端
 * 2. 前端框出四角 -> 后端截图+识别 -> 返回预览（带标注）
 * 3. 保存模板 -> 保存截图和识别数据
 */
@RestController
@RequestMapping("/api/crop-area-template")
public class CropAreaTemplateController {

    private static final Logger logger = LoggerFactory.getLogger(CropAreaTemplateController.class);

    @Autowired
    private CameraService cameraService;

    @Autowired
    private ObjectDetectionUtil objectDetectionUtil;

    @Autowired
    private TemplateManager templateManager;

    @Value("${upload.path:uploads}")
    private String uploadPath;

    // 细节检测引擎
    private YOLOInferenceEngine detailInferenceEngine;

    @Autowired
    private com.edge.vision.config.YamlConfig config;

    @jakarta.annotation.PostConstruct
    public void init() {
        // 初始化细节检测引擎
        if (config.getModels().getDetailModel() != null && !config.getModels().getDetailModel().isEmpty()) {
            try {
                detailInferenceEngine = new YOLOInferenceEngine(
                    config.getModels().getDetailModel(),
                    config.getModels().getConfThres(),
                    config.getModels().getIouThres(),
                    config.getModels().getDevice()
                );
                logger.info("Detail inference engine initialized successfully");
            } catch (Exception e) {
                logger.warn("Failed to initialize detail inference engine: {}", e.getMessage());
            }
        }
    }

    @jakarta.annotation.PreDestroy
    public void cleanup() {
        if (detailInferenceEngine != null) {
            detailInferenceEngine.close();
        }
    }

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

            // 获取拼接图像的 base64
            String stitchedImageBase64 = cameraService.getStitchedImageBase64();
            if (stitchedImageBase64 == null) {
                return ResponseEntity.ok(new Response(false, "获取图像失败", null));
            }

            // 保存图像到临时目录，使用工件类型作为文件名
            Path dir = Paths.get(uploadPath, "crop-area-temp");
            Files.createDirectories(dir);

            String filename = partType + "_temp.jpg";
            Path filePath = dir.resolve(filename);

            // 将 base64 转换为 Mat 并保存
            byte[] bytes = Base64.getDecoder().decode(stitchedImageBase64);
            Mat mat = Imgcodecs.imdecode(new org.opencv.core.MatOfByte(bytes), Imgcodecs.IMREAD_COLOR);
            Imgcodecs.imwrite(filePath.toString(), mat);
            mat.release();

            logger.info("摄像头截图保存成功: {}", filePath);

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
     * 第二步：预览 - 根据四角截图，调用detailInferenceEngine识别，返回带标注的图片
     */
    @PostMapping("/preview")
    public ResponseEntity<?> preview(@RequestBody CropAreaTemplateRequest request) {
        try {
            logger.info("预览请求: templateId={}, corners={}", request.getTemplateId(), request.getCorners());

            // 从 templateId (工件类型) 获取原始图片路径
            String originalImagePath = Paths.get(uploadPath, "crop-area-temp", request.getTemplateId() + "_temp.jpg").toString();

            if (!new File(originalImagePath).exists()) {
                return ResponseEntity.ok(CropAreaPreviewResponse.error("原始图片不存在"));
            }

            // 转换 corners 格式
            double[][] corners = new double[4][2];
            List<Double> cornersList = request.getCorners();
            for (int i = 0; i < 4; i++) {
                corners[i][0] = cornersList.get(i * 2);
                corners[i][1] = cornersList.get(i * 2 + 1);
            }

            // 截图
            Mat cropped = objectDetectionUtil.cropImageByCorners(
                originalImagePath, corners, null, 10);

            // 调用 YOLOInferenceEngine 识别
            List<com.edge.vision.model.Detection> detections = detailInferenceEngine.predict(cropped);

            // 在图片上绘制检测框
            Mat resultMat = drawDetections(cropped.clone(), detections);

            // 转换为 base64
            String resultImageBase64 = matToBase64(resultMat);
            resultMat.release();
            cropped.release();

            // 转换 Detection 为 FeatureInfo
            List<CropAreaPreviewResponse.FeatureInfo> features = new ArrayList<>();
            for (int i = 0; i < detections.size(); i++) {
                com.edge.vision.model.Detection det = detections.get(i);

                // 从 bbox 计算中心点
                float[] bbox = det.getBbox();
                double centerX = (bbox[0] + bbox[2]) / 2.0;
                double centerY = (bbox[1] + bbox[3]) / 2.0;
                double width = bbox[2] - bbox[0];
                double height = bbox[3] - bbox[1];

                features.add(new CropAreaPreviewResponse.FeatureInfo(
                    "feature_" + i,
                    det.getLabel(),
                    det.getClassId(),
                    centerX,
                    centerY,
                    width,
                    height,
                    det.getConfidence()
                ));
            }

            return ResponseEntity.ok(CropAreaPreviewResponse.success(
                "data:image/jpeg;base64," + resultImageBase64,
                features,
                cropped.cols(),
                cropped.rows()
            ));

        } catch (Exception e) {
            logger.error("预览失败", e);
            return ResponseEntity.ok(CropAreaPreviewResponse.error("预览失败: " + e.getMessage()));
        }
    }

    /**
     * 第三步：保存模板
     */
    @PostMapping("/save")
    public ResponseEntity<?> save(@RequestBody CropAreaTemplateRequest request) {
        try {
            logger.info("保存模板: templateId={}", request.getTemplateId());

            // 获取原始图片路径（使用 {partType}_temp.jpg）
            String originalImagePath = Paths.get(uploadPath, "crop-area-temp", request.getTemplateId() + "_temp.jpg").toString();

            // 转换 corners
            double[][] corners = new double[4][2];
            List<Double> cornersList = request.getCorners();
            for (int i = 0; i < 4; i++) {
                corners[i][0] = cornersList.get(i * 2);
                corners[i][1] = cornersList.get(i * 2 + 1);
            }

            // 重新截图（不带标注）
            Mat cropped = objectDetectionUtil.cropImageByCorners(
                originalImagePath, corners, null, 10);

            // 保存模板截图到 templates/images/ 目录
            Path imagesDir = Paths.get("templates", "images");
            Files.createDirectories(imagesDir);
            String imageFileName = request.getTemplateId() + ".jpg";
            String templateImagePath = imagesDir.resolve(imageFileName).toString();
            Imgcodecs.imwrite(templateImagePath, cropped);

            // 调用 YOLOInferenceEngine 识别
            List<com.edge.vision.model.Detection> detections = detailInferenceEngine.predict(cropped);

            // 转换 Detection 为 TemplateFeature
            List<TemplateFeature> features = new ArrayList<>();
            for (int i = 0; i < detections.size(); i++) {
                com.edge.vision.model.Detection det = detections.get(i);

                // 从 bbox 计算中心点
                float[] bbox = det.getBbox();
                double centerX = (bbox[0] + bbox[2]) / 2.0;
                double centerY = (bbox[1] + bbox[3]) / 2.0;

                TemplateFeature feature = new TemplateFeature(
                    "feature_" + i,
                    det.getLabel(),
                    new com.edge.vision.core.template.model.Point(centerX, centerY),
                    det.getClassId()
                );
                feature.setTolerance(new TemplateFeature.Tolerance(
                    request.getToleranceX(), request.getToleranceY()));

                features.add(feature);
            }

            // 创建 Template，将裁剪区域信息存入 metadata
            Template template = new Template();
            template.setTemplateId(request.getTemplateId());
            template.setImagePath("templates/images/" + imageFileName);  // 相对路径
            template.setFeatures(features);
            // 设置图像尺寸
            template.setImageSize(new com.edge.vision.core.template.model.ImageSize(cropped.cols(), cropped.rows()));


            // 将裁剪区域信息存入 metadata
            java.util.Map<String, Object> metadata = new java.util.HashMap<>();
            metadata.put("cropWidth", cropped.cols());
            metadata.put("cropHeight", cropped.rows());
            metadata.put("objectTemplatePath", template.getImagePath());
            metadata.put("matchStrategy", "CROP_AREA");
            template.setMetadata(metadata);
            // 关联工件类型
            template.setPartType(request.getTemplateId());

            // 使用 TemplateManager 保存模板
            templateManager.save(template);
            templateManager.setCurrentTemplate(template);

            cropped.release();

            logger.info("模板保存成功: {}", template);

            return ResponseEntity.ok(new Response(true, "保存成功", template));

        } catch (Exception e) {
            logger.error("保存失败", e);
            return ResponseEntity.ok(new Response(false, "保存失败: " + e.getMessage(), null));
        }
    }

    // ============ 工具方法 ============

    /**
     * 在图像上绘制检测框
     */
    private Mat drawDetections(Mat image, List<com.edge.vision.model.Detection> detections) {
        for (com.edge.vision.model.Detection detection : detections) {
            float[] bbox = detection.getBbox();
            if (bbox != null && bbox.length >= 4) {
                // 绘制边界框
                org.opencv.core.Point p1 = new org.opencv.core.Point(bbox[0], bbox[1]);
                org.opencv.core.Point p2 = new org.opencv.core.Point(bbox[2], bbox[3]);
                org.opencv.imgproc.Imgproc.rectangle(image, p1, p2, new org.opencv.core.Scalar(0, 255, 0), 2);

                // 绘制标签
                String label = String.format("%s: %.2f", detection.getLabel(), detection.getConfidence());
                int[] baseline = new int[1];
                org.opencv.core.Size textSize = org.opencv.imgproc.Imgproc.getTextSize(
                    label, org.opencv.imgproc.Imgproc.FONT_HERSHEY_SIMPLEX, 0.5, 1, baseline);

                double textY = Math.max(bbox[1] - 5, textSize.height + 5);
                org.opencv.core.Point textPos = new org.opencv.core.Point(bbox[0], textY);

                // 绘制背景
                org.opencv.core.Point bg1 = new org.opencv.core.Point(
                    bbox[0], textY - textSize.height - 5);
                org.opencv.core.Point bg2 = new org.opencv.core.Point(
                    bbox[0] + textSize.width, textY + 5);
                org.opencv.imgproc.Imgproc.rectangle(image, bg1, bg2,
                    new org.opencv.core.Scalar(0, 255, 0), -1);

                // 绘制文字
                org.opencv.imgproc.Imgproc.putText(image, label, textPos,
                    org.opencv.imgproc.Imgproc.FONT_HERSHEY_SIMPLEX, 0.5,
                    new org.opencv.core.Scalar(0, 0, 0), 1);
            }
        }
        return image;
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

    /**
     * 截图结果
     */
    public static class CaptureResult {
        private String filename;
        private String imageUrl;

        public CaptureResult(String filename, String imageUrl) {
            this.filename = filename;
            this.imageUrl = imageUrl;
        }

        public String getFilename() { return filename; }
        public void setFilename(String filename) { this.filename = filename; }

        public String getImageUrl() { return imageUrl; }
        public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
    }
}
