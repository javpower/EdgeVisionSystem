package com.edge.vision.core.topology.croparea;

import com.edge.vision.core.quality.InspectionResult;
import com.edge.vision.core.template.model.DetectedObject;
import com.edge.vision.core.template.model.Point;
import com.edge.vision.util.IndustrialObjectDetector;
import org.opencv.core.Mat;
import org.opencv.imgcodecs.Imgcodecs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 裁剪区域检测服务
 * <p>
 * 完整流程：
 * 1. 建模阶段：整体检测 -> 裁剪 -> 细节识别 -> 保存模板
 * 2. 检测阶段：整体检测 -> 裁剪 -> 细节识别 -> 匹配比对
 */
@Service
public class CropAreaInspectionService {
    private static final Logger logger = LoggerFactory.getLogger(CropAreaInspectionService.class);

    private final CropAreaMatcher matcher;
    private final CropAreaTemplateBuilder templateBuilder;
    private final IndustrialObjectDetector objectDetector;

    // 默认容差（像素）
    private final Point DEFAULT_TOLERANCE = new Point(5.0, 5.0);

    public CropAreaInspectionService(CropAreaMatcher matcher,
                                     CropAreaTemplateBuilder templateBuilder) {
        this.matcher = matcher;
        this.templateBuilder = templateBuilder;
        this.objectDetector = new IndustrialObjectDetector();
    }

    /**
     * 建模流程
     *
     * @param templateId         模板ID
     * @param imagePath          标准图像路径
     * @param objectTemplate     整体检测模板路径（小图）
     * @param detectedObjects    在裁剪区域中检测到的特征对象
     * @param saveCropImagePath  保存裁剪图像的路径（可选，用于调试）
     * @return 构建的模板
     */
    public CropAreaTemplate buildTemplate(String templateId,
                                          String imagePath,
                                          String objectTemplate,
                                          List<DetectedObject> detectedObjects,
                                          String saveCropImagePath) {
        logger.info("=== Building Template: {} ===", templateId);

        // 步骤1：整体检测，获取工件区域
        IndustrialObjectDetector.DetectionResult detectionResult =
            objectDetector.detectObject(objectTemplate, imagePath, null);

        if (!detectionResult.success) {
            throw new RuntimeException("整体检测失败: " + detectionResult.message);
        }

        logger.info("整体检测成功，匹配点数: {}", detectionResult.matchedCount);

        // 步骤2：裁剪工件区域
        Mat fullImage = Imgcodecs.imread(imagePath);
        Point[] corners = convertToPointArray(detectionResult.corners);

        Mat croppedImage = templateBuilder.cropImageByCorners(fullImage, corners, 10);

        // 保存裁剪图像（如果指定了路径）
        if (saveCropImagePath != null && !saveCropImagePath.isEmpty()) {
            Imgcodecs.imwrite(saveCropImagePath, croppedImage);
            logger.info("裁剪图像已保存至: {}", saveCropImagePath);
        }

        // 步骤3：构建模板（使用 Builder 模式，包含图片路径）
        List<com.edge.vision.core.template.model.TemplateFeature> features = new ArrayList<>();
        for (int i = 0; i < detectedObjects.size(); i++) {
            DetectedObject obj = detectedObjects.get(i);

            com.edge.vision.core.template.model.TemplateFeature feature =
                new com.edge.vision.core.template.model.TemplateFeature(
                    "feature_" + i,
                    obj.getClassName() != null ? obj.getClassName() : "Feature_" + i,
                    obj.getCenter(),
                    obj.getClassId()
                );
            feature.setTolerance(new com.edge.vision.core.template.model.TemplateFeature.Tolerance(
                DEFAULT_TOLERANCE.x, DEFAULT_TOLERANCE.y));

            features.add(feature);
        }

        // 步骤3：使用 Builder 构建模板，包含图片路径和特征
        CropAreaTemplate.Builder templateBuilder = new CropAreaTemplate.Builder()
            .templateId(templateId)
            .cropSize(croppedImage.cols(), croppedImage.rows())
            .objectTemplatePath(objectTemplate)
            .cropImagePath(saveCropImagePath);

        // 添加所有特征
        for (com.edge.vision.core.template.model.TemplateFeature feature : features) {
            templateBuilder.addFeature(feature);
        }

        CropAreaTemplate template = templateBuilder.build();

        // 释放资源
        croppedImage.release();
        fullImage.release();
        if (detectionResult.resultImage != null) {
            detectionResult.resultImage.release();
        }

        logger.info("=== Template Built: {} ===", template);
        return template;
    }

    /**
     * 建模流程（简化版本，不保存裁剪图像）
     */
    public CropAreaTemplate buildTemplate(String templateId,
                                          String imagePath,
                                          String objectTemplate,
                                          List<DetectedObject> detectedObjects) {
        return buildTemplate(templateId, imagePath, objectTemplate, detectedObjects, null);
    }

    /**
     * 检测流程（使用模板中存储的图片路径）
     *
     * @param template        模板（包含 objectTemplatePath）
     * @param imagePath       检测图像路径
     * @param detectedObjects 在裁剪区域中检测到的特征对象
     * @return 检测结果
     */
    public InspectionResult inspect(CropAreaTemplate template,
                                   String imagePath,
                                   List<DetectedObject> detectedObjects) {
        logger.info("=== Inspection: {} ===", template.getTemplateId());

        // 从模板获取整体检测模板路径
        String objectTemplate = template.getObjectTemplatePath();
        if (objectTemplate == null || objectTemplate.isEmpty()) {
            logger.error("模板未配置整体检测模板路径");
            InspectionResult result = new InspectionResult(template.getTemplateId());
            result.setPassed(false);
            result.setMessage("模板未配置整体检测模板路径");
            return result;
        }

        // 步骤1：整体检测，获取工件区域
        IndustrialObjectDetector.DetectionResult detectionResult =
            objectDetector.detectObject(objectTemplate, imagePath, null);

        if (!detectionResult.success) {
            logger.error("整体检测失败: {}", detectionResult.message);
            InspectionResult result = new InspectionResult(template.getTemplateId());
            result.setPassed(false);
            result.setMessage("整体检测失败: " + detectionResult.message);
            return result;
        }

        logger.info("整体检测成功，匹配点数: {}", detectionResult.matchedCount);

        // 步骤2：执行匹配
        // 注意：detectedObjects 应该是在裁剪区域中检测到的，坐标是相对坐标
        InspectionResult result = matcher.match(template, detectedObjects);

        // 释放资源
        if (detectionResult.resultImage != null) {
            detectionResult.resultImage.release();
        }

        return result;
    }

    /**
     * 检测流程（兼容旧接口，传入 objectTemplate）
     *
     * @param template        模板
     * @param imagePath       图像路径
     * @param objectTemplate  整体检测模板路径（小图）
     * @param detectedObjects 在裁剪区域中检测到的特征对象
     * @return 检测结果
     */
    public InspectionResult inspectWithTemplate(CropAreaTemplate template,
                                               String imagePath,
                                               String objectTemplate,
                                               List<DetectedObject> detectedObjects) {
        return inspect(template, imagePath, detectedObjects);
    }

    /**
     * 从检测到的角点计算边界框原点
     *
     * @param corners 四个角坐标
     * @return 边界框左上角坐标
     */
    public Point getCropOrigin(org.opencv.core.Point[] corners) {
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;

        for (org.opencv.core.Point corner : corners) {
            minX = Math.min(minX, corner.x);
            minY = Math.min(minY, corner.y);
        }

        return new Point(minX, minY);
    }

    /**
     * 将 OpenCV Point 数组转换为项目中的 Point 数组
     */
    private Point[] convertToPointArray(org.opencv.core.Point[] cvPoints) {
        Point[] points = new Point[cvPoints.length];
        for (int i = 0; i < cvPoints.length; i++) {
            points[i] = new Point(cvPoints[i].x, cvPoints[i].y);
        }
        return points;
    }

    /**
     * 将全局坐标的检测对象转换为相对坐标
     *
     * @param globalObjects  全局坐标的检测对象
     * @param cropOrigin     裁剪区域原点
     * @return 相对坐标的检测对象
     */
    public List<DetectedObject> convertToRelativeCoordinates(
            List<DetectedObject> globalObjects,
            Point cropOrigin) {

        List<DetectedObject> relativeObjects = new ArrayList<>();

        for (DetectedObject obj : globalObjects) {
            Point relativeCenter = new Point(
                obj.getCenter().x - cropOrigin.x,
                obj.getCenter().y - cropOrigin.y
            );

            DetectedObject relativeObj = new DetectedObject(
                obj.getClassId(),
                relativeCenter,
                obj.getWidth(),
                obj.getHeight()
            );
            relativeObj.setClassName(obj.getClassName());
            relativeObj.setConfidence(obj.getConfidence());

            relativeObjects.add(relativeObj);
        }

        return relativeObjects;
    }
}
