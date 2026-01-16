package com.edge.vision.core.quality;

import com.edge.vision.core.template.*;
import com.edge.vision.core.template.model.AnchorPoint;
import com.edge.vision.core.template.model.DetectedObject;
import com.edge.vision.core.template.model.Point;
import com.edge.vision.core.template.model.Template;
import com.edge.vision.core.transform.CoordinateTransformer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 质量检测器
 * <p>
 * 整合模型识别、模板比对和坐标对齐，实现完整的质量检测流程
 */
@Component
public class QualityInspector {
    private static final Logger logger = LoggerFactory.getLogger(QualityInspector.class);

    @Autowired
    private TemplateManager templateManager;

    @Autowired
    private CoordinateTransformer coordinateTransformer;

    @Autowired
    private TemplateMatcher templateMatcher;

    @Autowired
    private RotationInvariantMatcher rotationInvariantMatcher;

    public QualityInspector() {
    }

    public QualityInspector(TemplateManager templateManager) {
        this.templateManager = templateManager;
    }

    /**
     * 执行质量检测
     *
     * @param detectedObjects YOLO 检测到的对象列表
     * @return 检测结果
     */
    public InspectionResult inspect(List<DetectedObject> detectedObjects) {
        Template template = templateManager.getCurrentTemplate();
        if (template == null) {
            throw new IllegalStateException("No template loaded. Please set a template first.");
        }

        return inspect(template, detectedObjects);
    }

    /**
     * 执行质量检测
     *
     * @param template        模板
     * @param detectedObjects YOLO 检测到的对象列表
     * @return 检测结果
     */
    public InspectionResult inspect(Template template, List<DetectedObject> detectedObjects) {
        logger.info("Starting quality inspection with template: {}", template.getTemplateId());

        try {
            // 1. 从检测结果中生成锚点
            List<AnchorPoint> detectedAnchors = generateDetectedAnchors(detectedObjects);
            if (detectedAnchors.isEmpty()) {
                logger.warn("No anchors generated from detected objects");
                return createEmptyResult(template, "无法生成检测锚点");
            }

            // 2. 计算坐标变换
            coordinateTransformer.computeTransform(
                template.getAnchorPoints(),
                detectedAnchors
            );

            // 3. 转换检测对象坐标到模板坐标系
            List<DetectedObject> alignedObjects = new ArrayList<>();
            for (DetectedObject obj : detectedObjects) {
                DetectedObject aligned = new DetectedObject();
                aligned.setClassId(obj.getClassId());
                aligned.setClassName(obj.getClassName());
                aligned.setCenter(coordinateTransformer.transformToTemplate(obj.getCenter()));
                aligned.setWidth(obj.getWidth());
                aligned.setHeight(obj.getHeight());
                aligned.setConfidence(obj.getConfidence());
                alignedObjects.add(aligned);
            }

            // 4. 执行模板比对
            InspectionResult result = templateMatcher.match(template, alignedObjects);

            logger.info("Inspection completed: {}", result);
            return result;

        } catch (Exception e) {
            logger.error("Error during inspection", e);
            return createEmptyResult(template, "检测过程出错: " + e.getMessage());
        }
    }

    /**
     * 从检测结果中生成锚点
     * <p>
     * 使用旋转不变匹配算法，支持任意旋转角度和平移
     */
    private List<AnchorPoint> generateDetectedAnchors(List<DetectedObject> detectedObjects) {
        if (detectedObjects.isEmpty()) {
            return new ArrayList<>();
        }

        // 获取当前模板
        Template template = templateManager.getCurrentTemplate();
        if (template == null || template.getFeatures() == null || template.getFeatures().isEmpty()) {
            // 如果没有模板，只生成几何中心
            return generateBasicAnchors(detectedObjects);
        }

        // 使用旋转不变匹配器生成锚点
        try {
            List<AnchorPoint> anchors = rotationInvariantMatcher.generateDetectedAnchors(
                template, detectedObjects
            );

            if (!anchors.isEmpty()) {
                logger.info("使用旋转不变匹配生成了 {} 个锚点", anchors.size());
                return anchors;
            }
        } catch (Exception e) {
            logger.warn("旋转不变匹配失败，回退到基础锚点: {}", e.getMessage());
        }

        // 回退到基础锚点
        return generateBasicAnchors(detectedObjects);
    }

    /**
     * 生成基本锚点（只有几何中心）
     */
    private List<AnchorPoint> generateBasicAnchors(List<DetectedObject> detectedObjects) {
        // 计算边界框
        double minX = Double.MAX_VALUE;
        double maxX = Double.MIN_VALUE;
        double minY = Double.MAX_VALUE;
        double maxY = Double.MIN_VALUE;

        for (DetectedObject obj : detectedObjects) {
            Point center = obj.getCenter();
            double halfW = obj.getWidth() / 2;
            double halfH = obj.getHeight() / 2;

            minX = Math.min(minX, center.x - halfW);
            maxX = Math.max(maxX, center.x + halfW);
            minY = Math.min(minY, center.y - halfH);
            maxY = Math.max(maxY, center.y + halfH);
        }

        List<AnchorPoint> anchors = new ArrayList<>();
        Point center = new Point((minX + maxX) / 2, (minY + maxY) / 2);

        anchors.add(new AnchorPoint(
            "A0_detected",
            AnchorPoint.AnchorType.GEOMETRIC_CENTER,
            center,
            "检测几何中心"
        ));

        return anchors;
    }

    /**
     * 创建空结果（错误情况）
     */
    private InspectionResult createEmptyResult(Template template, String message) {
        InspectionResult result = new InspectionResult(template.getTemplateId());
        result.setPassed(false);
        result.setMessage(message);
        return result;
    }

    /**
     * 获取坐标转换器
     */
    public CoordinateTransformer getCoordinateTransformer() {
        return coordinateTransformer;
    }

    /**
     * 获取模板比对器
     */
    public TemplateMatcher getTemplateMatcher() {
        return templateMatcher;
    }

    /**
     * 获取模板管理器
     */
    public TemplateManager getTemplateManager() {
        return templateManager;
    }
}
