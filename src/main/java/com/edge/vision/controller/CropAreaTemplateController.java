package com.edge.vision.controller;

import com.edge.vision.core.infer.YOLOInferenceEngine;
import com.edge.vision.core.template.model.TemplateFeature;
import com.edge.vision.core.topology.croparea.CropAreaTemplate;
import com.edge.vision.dto.CropAreaPreviewResponse;
import com.edge.vision.dto.CropAreaTemplateRequest;
import com.edge.vision.service.CameraService;
import com.edge.vision.util.ObjectDetectionUtil;
import org.opencv.core.Mat;
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

    @Value("${upload.path:uploads}")
    private String uploadPath;

    @Value("${template.path:templates}")
    private String templatePath;

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
    public ResponseEntity<?> captureImage() {
        try {
            if (!cameraService.isRunning()) {
                return ResponseEntity.ok(new Response(false, "摄像头未启动", null));
            }

            // 获取拼接图像的 base64
            String stitchedImageBase64 = cameraService.getStitchedImageBase64();
            if (stitchedImageBase64 == null) {
                return ResponseEntity.ok(new Response(false, "获取图像失败", null));
            }

            // 保存图像到临时目录
            Path dir = Paths.get(uploadPath, "crop-area-temp");
            Files.createDirectories(dir);

            String filename = System.currentTimeMillis() + "_capture.jpg";
            Path filePath = dir.resolve(filename);

            // 将 base64 转换为 Mat 并保存
            byte[] bytes = Base64.getDecoder().decode(stitchedImageBase64);
            Mat mat = Imgcodecs.imdecode(new org.opencv.core.MatOfByte(bytes), Imgcodecs.IMREAD_COLOR);
            Imgcodecs.imwrite(filePath.toString(), mat);
            mat.release();

            logger.info("摄像头截图保存成功: {}", filePath);

            return ResponseEntity.ok(new Response(
                true, "截图成功",
                new CaptureResult(filename, "/uploads/crop-area-temp/" + filename)
            ));

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

            // 从 templateId 获取原始图片路径
            String originalImagePath = Paths.get(uploadPath, "crop-area-temp", request.getTemplateId()).toString();

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

            // 保存裁剪图像
            String croppedPath = Paths.get(uploadPath, "crop-area-temp", request.getTemplateId() + "_crop.jpg").toString();
            Imgcodecs.imwrite(croppedPath, cropped);

            // 调用 YOLOInferenceEngine 识别
            List<com.edge.vision.model.Detection> detections = detailInferenceEngine.predict(cropped);

            // 转换 Detection 为 DetectedObject 和 FeatureInfo
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

            // 在图片上绘制标注（可选）
            // 这里可以调用绘制工具绘制 bbox

            cropped.release();

            return ResponseEntity.ok(CropAreaPreviewResponse.success(
                "/uploads/crop-area-temp/" + request.getTemplateId() + "_crop.jpg",
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

            // 获取原始图片和截图
            String originalImagePath = Paths.get(uploadPath, "crop-area-temp", request.getTemplateId()).toString();

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

            // 创建模板目录
            Path templateDir = Paths.get(templatePath, "crop-area", request.getTemplateId());
            Files.createDirectories(templateDir);

            // 保存模板截图
            String templateImagePath = templateDir.resolve("template.jpg").toString();
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

            // 创建模板
            CropAreaTemplate template = new CropAreaTemplate();
            template.setTemplateId(request.getTemplateId());
            template.setTemplateImagePath(templateImagePath);
            template.setCorners(corners);
            template.setCropWidth(cropped.cols());
            template.setCropHeight(cropped.rows());
            template.setObjectTemplatePath(request.getObjectTemplatePath());
            template.setFeatures(features);

            // 保存模板到文件（JSON 格式）
            String templateDataPath = templateDir.resolve("template.json").toString();
            saveTemplateToJson(template, templateDataPath);

            cropped.release();

            logger.info("模板保存成功: {}", template);

            return ResponseEntity.ok(new Response(true, "保存成功", template));

        } catch (Exception e) {
            logger.error("保存失败", e);
            return ResponseEntity.ok(new Response(false, "保存失败: " + e.getMessage(), null));
        }
    }

    /**
     * 将模板保存为 JSON
     */
    private void saveTemplateToJson(CropAreaTemplate template, String path) throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        mapper.writerWithDefaultPrettyPrinter().writeValue(new File(path), template);
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
