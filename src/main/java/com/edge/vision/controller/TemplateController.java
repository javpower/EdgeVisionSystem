package com.edge.vision.controller;

import com.edge.vision.config.YamlConfig;
import com.edge.vision.core.infer.YOLOInferenceEngine;
import com.edge.vision.core.template.TemplateBuilder;
import com.edge.vision.core.template.TemplateManager;
import com.edge.vision.core.template.model.DetectedObject;
import com.edge.vision.core.template.model.ImageSize;
import com.edge.vision.core.template.model.Point;
import com.edge.vision.core.template.model.Template;
import com.edge.vision.dto.InspectionRequest;
import com.edge.vision.dto.InspectionResponse;
import com.edge.vision.dto.TemplateBuildResponse;
import com.edge.vision.model.Detection;
import com.edge.vision.service.CameraService;
import com.edge.vision.service.QualityStandardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.imgcodecs.Imgcodecs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 模板管理 API
 * <p>
 * 提供一键建模功能，自动截屏、识别、建模
 */
@Tag(name = "模板管理", description = "质量检测模板的一键建模、管理和应用")
@RestController
@RequestMapping("/api/templates")
public class TemplateController {
    private static final Logger logger = LoggerFactory.getLogger(TemplateController.class);

    @Autowired
    private TemplateManager templateManager;

    @Autowired
    private TemplateBuilder templateBuilder;

    @Autowired
    private QualityStandardService qualityStandardService;

    @Autowired
    private CameraService cameraService;

    @Autowired
    private YamlConfig yamlConfig;

    // 细节检测引擎（用于一键建模）
    private YOLOInferenceEngine detailInferenceEngine;

    /**
     * 预览识别结果（用于一键建模前确认）
     * <p>
     * 截取当前画面，调用模型识别，返回带框的图像供用户确认
     */
    @Operation(summary = "预览识别结果", description = "截取当前画面并识别，返回带框图像供确认")
    @PostMapping("/preview-detection")
    public ResponseEntity<Map<String, Object>> previewDetection(@RequestBody Map<String, Object> request) {
        try {
            logger.info("=== Preview Detection ===");

            // 1. 检查摄像头是否运行
            if (!cameraService.isRunning()) {
                return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", "摄像头未启动，请先启动摄像头"));
            }

            // 2. 初始化检测引擎（如果尚未初始化）
            if (detailInferenceEngine == null) {
                try {
                    detailInferenceEngine = new YOLOInferenceEngine(
                        yamlConfig.getModels().getDetailModel(),
                        yamlConfig.getModels().getConfThres(),
                        yamlConfig.getModels().getIouThres(),
                        yamlConfig.getModels().getDevice(),
                        1280, 1280
                    );
                    logger.info("Detail inference engine initialized for preview");
                } catch (Exception e) {
                    logger.error("Failed to initialize detail inference engine", e);
                    return ResponseEntity.internalServerError()
                        .body(Map.of("success", false, "message", "检测引擎初始化失败: " + e.getMessage()));
                }
            }

            // 3. 截取当前拼接图像
            String stitchedImageBase64 = cameraService.getStitchedImageBase64();
            if (stitchedImageBase64 == null) {
                return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "message", "无法获取当前画面"));
            }

            // 4. 解码图像
            byte[] imageBytes = Base64.getDecoder().decode(stitchedImageBase64);
            Mat imageMat = Imgcodecs.imdecode(new MatOfByte(imageBytes), Imgcodecs.IMREAD_COLOR);

            if (imageMat.empty()) {
                return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "message", "图像解码失败"));
            }

            // 5. 调用YOLO模型进行检测
            List<Detection> detections = detailInferenceEngine.predict(imageMat);
            logger.info("Detected {} objects", detections.size());

            // 6. 绘制检测框
            Mat resultMat = drawDetections(imageMat.clone(), detections);
            String resultImageBase64 = matToBase64(resultMat);
            resultMat.release();

            // 7. 转换检测结果
            List<Map<String, Object>> detectionResults = new ArrayList<>();
            for (Detection detection : detections) {
                Map<String, Object> result = new HashMap<>();
                result.put("classId", detection.getClassId());
                result.put("className", detection.getLabel());
                result.put("confidence", detection.getConfidence());

                float[] bbox = detection.getBbox();
                if (bbox != null && bbox.length >= 4) {
                    result.put("bbox", bbox);
                    result.put("centerX", (bbox[0] + bbox[2]) / 2.0);
                    result.put("centerY", (bbox[1] + bbox[3]) / 2.0);
                    result.put("width", bbox[2] - bbox[0]);
                    result.put("height", bbox[3] - bbox[1]);
                }

                detectionResults.add(result);
            }

            imageMat.release();

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("image", "data:image/jpeg;base64," + resultImageBase64);
            response.put("detections", detectionResults);
            response.put("count", detections.size());
            response.put("imageWidth", imageMat.cols());
            response.put("imageHeight", imageMat.rows());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Preview detection failed", e);
            return ResponseEntity.internalServerError()
                .body(Map.of("success", false, "message", "预览失败: " + e.getMessage()));
        }
    }

    /**
     * 一键建模
     * <p>
     * 截取当前画面，调用模型识别，自动生成模板并立即生效
     * 只需传入工件类型，后端自动完成截图、识别、建模
     */
    @Operation(summary = "一键建模", description = "截取当前画面，调用模型识别，自动生成模板并立即生效")
    @PostMapping("/one-click-build")
    public ResponseEntity<TemplateBuildResponse> oneClickBuild(@RequestBody Map<String, Object> request) {
        try {
            String partType = (String) request.get("partType");
            if (partType == null || partType.isEmpty()) {
                return ResponseEntity.badRequest()
                    .body(TemplateBuildResponse.error("请提供工件类型"));
            }

            logger.info("=== One-Click Template Build ===");
            logger.info("Part Type: {}", partType);

            // 1. 检查摄像头是否运行
            if (!cameraService.isRunning()) {
                return ResponseEntity.badRequest()
                    .body(TemplateBuildResponse.error("摄像头未启动，请先启动摄像头"));
            }

            // 2. 初始化检测引擎（如果尚未初始化）

            // 2.2 初始化细节检测引擎（必须，用于检测特征）
            if (detailInferenceEngine == null) {
                try {
                    detailInferenceEngine = new YOLOInferenceEngine(
                        yamlConfig.getModels().getDetailModel(),
                        yamlConfig.getModels().getConfThres(),
                        yamlConfig.getModels().getIouThres(),
                        yamlConfig.getModels().getDevice(),
                        1280, 1280
                    );
                    logger.info("Detail inference engine initialized for one-click build");
                } catch (Exception e) {
                    logger.error("Failed to initialize detail inference engine", e);
                    return ResponseEntity.internalServerError()
                        .body(TemplateBuildResponse.error("检测引擎初始化失败: " + e.getMessage()));
                }
            }

            // 3. 截取当前拼接图像
            String stitchedImageBase64 = cameraService.getStitchedImageBase64();
            if (stitchedImageBase64 == null) {
                return ResponseEntity.internalServerError()
                    .body(TemplateBuildResponse.error("无法获取当前画面"));
            }

            // 4. 解码图像
            byte[] imageBytes = Base64.getDecoder().decode(stitchedImageBase64);
            Mat imageMat = Imgcodecs.imdecode(new MatOfByte(imageBytes), Imgcodecs.IMREAD_COLOR);

            if (imageMat.empty()) {
                return ResponseEntity.internalServerError()
                    .body(TemplateBuildResponse.error("图像解码失败"));
            }

            logger.info("Image size: {}x{}", imageMat.cols(), imageMat.rows());

            // 5. 调用YOLO模型进行检测
            List<Detection> detections = detailInferenceEngine.predict(imageMat);
            logger.info("Detected {} objects", detections.size());

            if (detections.isEmpty()) {
                imageMat.release();
                return ResponseEntity.badRequest()
                    .body(TemplateBuildResponse.error("未检测到任何特征，请确保画面中有目标工件"));
            }
            // 6. 转换为DetectedObject列表（直接使用检测结果中的className）
            List<DetectedObject> detectedObjects = new ArrayList<>();
            for (Detection detection : detections) {
                DetectedObject obj = new DetectedObject();
                obj.setClassId(detection.getClassId());
                obj.setClassName(detection.getLabel());
                obj.setConfidence(detection.getConfidence());

                // 从bbox计算center和尺寸
                float[] bbox = detection.getBbox();
                if (bbox != null && bbox.length >= 4) {
                    double centerX = (bbox[0] + bbox[2]) / 2.0;
                    double centerY = (bbox[1] + bbox[3]) / 2.0;
                    double width = bbox[2] - bbox[0];
                    double height = bbox[3] - bbox[1];

                    obj.setCenter(new Point(centerX, centerY));
                    obj.setWidth(width);
                    obj.setHeight(height);
                }

                detectedObjects.add(obj);
                logger.debug("  - classId={}, className={} at ({}, {}) conf={}",
                    obj.getClassId(), obj.getClassName(),
                    obj.getCenter().x, obj.getCenter().y, obj.getConfidence());
            }

            // 7. 创建图像尺寸对象
            ImageSize imageSize = new ImageSize(imageMat.cols(), imageMat.rows());

            // 8. 从检测结果构建模板
            String templateId = partType + "_" + System.currentTimeMillis();

            // 获取容差值（优先使用请求参数，否则使用配置默认值）
            double toleranceX = 20.0;
            double toleranceY = 20.0;

            // 从请求中获取容差值
            Object toleranceXObj = request.get("toleranceX");
            Object toleranceYObj = request.get("toleranceY");
            if (toleranceXObj instanceof Number) {
                toleranceX = ((Number) toleranceXObj).doubleValue();
            } else if (yamlConfig.getInspection() != null) {
                toleranceX = yamlConfig.getInspection().getDefaultToleranceX();
            }

            if (toleranceYObj instanceof Number) {
                toleranceY = ((Number) toleranceYObj).doubleValue();
            } else if (yamlConfig.getInspection() != null) {
                toleranceY = yamlConfig.getInspection().getDefaultToleranceY();
            }

            logger.info("Tolerance: X={}, Y={}", toleranceX, toleranceY);

            // 从检测结果中提取类别映射（不写死，使用识别结果中的className）
            Map<Integer, String> classNameMapping = new HashMap<>();
            detectedObjects.forEach(obj -> {
                if (obj.getClassName() != null && !classNameMapping.containsKey(obj.getClassId())) {
                    classNameMapping.put(obj.getClassId(), obj.getClassName());
                }
            });

            logger.info("Class name mapping: {}", classNameMapping);

            TemplateBuilder.BuildConfig config = TemplateBuilder.BuildConfig.builder()
                .templateId(templateId)
                .tolerance(toleranceX, toleranceY)
                .includeAuxiliaryAnchors(true)
                .classNameMapping(classNameMapping);

            Template template = templateBuilder.buildFromDetection(detectedObjects, imageSize, config);

            // 9. 关联工件类型
            template.putMetadata("partType", partType);

            // 11. 保存并激活模板
            templateManager.save(template);
            templateManager.setCurrentTemplate(template);

            imageMat.release();

            logger.info("=== One-Click Build Successful ===");
            logger.info("Template ID: {}", templateId);
            logger.info("Features: {}", detectedObjects.size());
            logger.info("Part Type: {}", partType);

            return ResponseEntity.ok(TemplateBuildResponse.success(templateId, template));

        } catch (Exception e) {
            logger.error("One-click build failed", e);
            return ResponseEntity.internalServerError()
                .body(TemplateBuildResponse.error("一键建模失败: " + e.getMessage()));
        }
    }

    /**
     * 激活指定模板
     */
    @Operation(summary = "激活模板", description = "将指定模板设置为当前使用的模板")
    @PostMapping("/{templateId}/activate")
    public ResponseEntity<TemplateBuildResponse> activateTemplate(@PathVariable String templateId) {
        try {
            templateManager.setCurrentTemplate(templateId);

            Template template = templateManager.getCurrentTemplate();
            return ResponseEntity.ok(TemplateBuildResponse.success(templateId, template));

        } catch (Exception e) {
            return ResponseEntity.badRequest()
                .body(TemplateBuildResponse.error("模板加载失败: " + e.getMessage()));
        }
    }

    /**
     * 获取当前激活的模板
     */
    @Operation(summary = "获取当前模板", description = "获取当前激活使用的模板信息")
    @GetMapping("/current")
    public ResponseEntity<Template> getCurrentTemplate() {
        Template template = templateManager.getCurrentTemplate();
        if (template == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(template);
    }

    /**
     * 获取所有可用模板
     */
    @Operation(summary = "获取所有模板", description = "获取所有已加载的模板列表")
    @GetMapping
    public ResponseEntity<List<Template>> getAllTemplates() {
        return ResponseEntity.ok(new ArrayList<>(templateManager.getAllTemplates()));
    }

    /**
     * 获取指定模板详情
     */
    @Operation(summary = "获取模板详情", description = "获取指定模板的详细信息")
    @GetMapping("/{templateId}")
    public ResponseEntity<Template> getTemplate(@PathVariable String templateId) {
        try {
            Template template = templateManager.load(templateId);
            return ResponseEntity.ok(template);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 删除指定模板
     */
    @Operation(summary = "删除模板", description = "删除指定的模板")
    @DeleteMapping("/{templateId}")
    public ResponseEntity<Void> deleteTemplate(@PathVariable String templateId) {
        templateManager.removeTemplate(templateId);
        return ResponseEntity.ok().build();
    }

    /**
     * 执行质量检测（使用当前模板）
     */
    @Operation(summary = "执行质量检测", description = "基于当前激活的模板执行质量检测")
    @PostMapping("/inspect")
    public ResponseEntity<InspectionResponse> inspect(@RequestBody InspectionRequest request) {
        try {
            // 转换 DTO 到内部模型
            List<DetectedObject> detectedObjects = request.getDetections().stream()
                .map(dto -> {
                    DetectedObject obj = new DetectedObject();
                    obj.setClassId(dto.getClassId());
                    obj.setClassName(dto.getClassName());
                    obj.setCenter(new Point(dto.getCenterX(), dto.getCenterY()));
                    obj.setWidth(dto.getWidth());
                    obj.setHeight(dto.getHeight());
                    obj.setConfidence(dto.getConfidence());
                    return obj;
                })
                .collect(Collectors.toList());

            // 执行检测（使用集成了新比对逻辑的服务）
            QualityStandardService.QualityEvaluationResult result =
                qualityStandardService.evaluateWithTemplate(request.getPartType(), detectedObjects);

            // 转换结果
            List<InspectionResponse.FeatureComparison> comparisons = null;
            if (result.getTemplateComparisons() != null) {
                // 创建 classId 到 className 的映射
                Map<Integer, String> classIdToName = detectedObjects.stream()
                    .filter(obj -> obj.getClassName() != null)
                    .collect(Collectors.toMap(
                        DetectedObject::getClassId,
                        DetectedObject::getClassName,
                        (existing, replacement) -> existing
                    ));

                comparisons = result.getTemplateComparisons().stream()
                    .map(comp -> {
                        InspectionResponse.FeatureComparison fc = new InspectionResponse.FeatureComparison();
                        fc.setFeatureId(comp.getFeatureId());
                        fc.setFeatureName(comp.getFeatureName());
                        fc.setClassName(classIdToName.getOrDefault(comp.getClassId(), "class_" + comp.getClassId()));
                        fc.setClassId(comp.getClassId());
                        fc.setTemplateX(comp.getTemplatePosition() != null ? comp.getTemplatePosition().x : 0);
                        fc.setTemplateY(comp.getTemplatePosition() != null ? comp.getTemplatePosition().y : 0);
                        fc.setDetectedX(comp.getDetectedPosition() != null ? comp.getDetectedPosition().x : 0);
                        fc.setDetectedY(comp.getDetectedPosition() != null ? comp.getDetectedPosition().y : 0);
                        fc.setXError(comp.getXError());
                        fc.setYError(comp.getYError());
                        fc.setTotalError(comp.getTotalError());
                        fc.setToleranceX(comp.getToleranceX());
                        fc.setToleranceY(comp.getToleranceY());
                        fc.setWithinTolerance(comp.isWithinTolerance());
                        fc.setStatus(comp.getStatus().toString());
                        return fc;
                    })
                    .collect(Collectors.toList());
            }

            return ResponseEntity.ok(InspectionResponse.success(
                result.isPassed(),
                result.getMessage(),
                result.getPartType(),
                comparisons,
                result.getProcessingTimeMs() != null ? result.getProcessingTimeMs() : 0
            ));

        } catch (Exception e) {
            logger.error("Inspection failed", e);
            return ResponseEntity.internalServerError()
                .body(InspectionResponse.success(false, "检测失败: " + e.getMessage(),
                    request.getPartType(), null, 0));
        }
    }

    /**
     * 重新加载所有模板
     */
    @Operation(summary = "重新加载模板", description = "从存储目录重新加载所有模板")
    @PostMapping("/reload")
    public ResponseEntity<Void> reloadTemplates() {
        templateManager.clearCache();
        templateManager.loadAllTemplates();
        return ResponseEntity.ok().build();
    }

    // ============ 工具方法 ============

    /**
     * 在图像上绘制检测框
     */
    private Mat drawDetections(Mat image, List<Detection> detections) {
        for (Detection detection : detections) {
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
     * 获取角点标签
     */
    private String getCornerLabel(int index) {
        switch (index) {
            case 0: return "TL";  // Top-Left
            case 1: return "TR";  // Top-Right
            case 2: return "BR";  // Bottom-Right
            case 3: return "BL";  // Bottom-Left
            default: return "" + index;
        }
    }

    /**
     * 从边界框计算四个角
     */
    private List<List<Double>> calculateCornersFromBbox(List<Double> bbox) {
        double x1 = bbox.get(0);
        double y1 = bbox.get(1);
        double x2 = bbox.get(2);
        double y2 = bbox.get(3);

        List<List<Double>> corners = new ArrayList<>();
        corners.add(Arrays.asList(x1, y1));  // TL
        corners.add(Arrays.asList(x2, y1));  // TR
        corners.add(Arrays.asList(x2, y2));  // BR
        corners.add(Arrays.asList(x1, y2));  // BL

        return corners;
    }

    /**
     * 从 YOLO 检测结果中自动计算工件四角
     * <p>
     * 假设：工件整体是检测结果中面积最大的边界框
     *
     * @param detections YOLO 检测结果列表
     * @return 四角坐标 [TL, TR, BR, BL] 或 null（如果没有检测到工件）
     */
    private List<List<Double>> calculateCornersFromDetections(List<Detection> detections) {
        if (detections == null || detections.isEmpty()) {
            return null;
        }

        // 找到面积最大的检测框（假设是工件整体）
        Detection largestDetection = null;
        double maxArea = 0;

        for (Detection detection : detections) {
            float[] bbox = detection.getBbox();
            if (bbox != null && bbox.length >= 4) {
                double width = bbox[2] - bbox[0];
                double height = bbox[3] - bbox[1];
                double area = width * height;

                if (area > maxArea) {
                    maxArea = area;
                    largestDetection = detection;
                }
            }
        }

        if (largestDetection == null) {
            logger.warn("No valid detection bbox found");
            return null;
        }

        // 从最大边界框计算四角
        float[] bbox = largestDetection.getBbox();
        List<Double> bboxList = Arrays.asList(
            (double) bbox[0],
            (double) bbox[1],
            (double) bbox[2],
            (double) bbox[3]
        );

        List<List<Double>> corners = calculateCornersFromBbox(bboxList);

        logger.info("Auto-calculated corners from largest detection (label={}, area={}): {}",
            largestDetection.getLabel(), (int)maxArea, corners);

        return corners;
    }
}
