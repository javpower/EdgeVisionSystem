package com.edge.vision.core.template;

import com.edge.vision.core.quality.FeatureComparison;
import com.edge.vision.core.quality.InspectionResult;
import com.edge.vision.core.template.model.DetectedObject;
import com.edge.vision.core.template.model.Template;
import com.edge.vision.core.template.model.TemplateFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 模板比对器
 * <p>
 * 执行模板与检测结果之间的比对
 */
@Component
public class TemplateMatcher {
    private static final Logger logger = LoggerFactory.getLogger(TemplateMatcher.class);

    private double maxMatchDistance = 500.0;  // 最大匹配距离（像素，相对坐标）

    public TemplateMatcher() {
    }

    public TemplateMatcher(double maxMatchDistance) {
        this.maxMatchDistance = maxMatchDistance;
    }

    /**
     * 执行模板比对
     *
     * @param template          模板
     * @param detectedObjects   检测到的对象（原始绝对坐标）
     * @return 比对结果
     */
    public InspectionResult match(Template template, List<DetectedObject> detectedObjects) {
        logger.info("Matching template {} with {} detected objects",
            template.getTemplateId(), detectedObjects.size());

        long startTime = System.currentTimeMillis();
        InspectionResult result = new InspectionResult(template.getTemplateId());

        // 计算检测对象的几何中心
        com.edge.vision.core.template.model.Point detectedCenter = calculateDetectedCenter(detectedObjects);
        logger.info("Detected objects center: ({}, {})", detectedCenter.x, detectedCenter.y);

        // 获取模板的几何中心
        com.edge.vision.core.template.model.Point templateCenter = null;
        if (template.getBoundingBox() != null && template.getBoundingBox().getCenter() != null) {
            templateCenter = template.getBoundingBox().getCenter();
        } else if (template.getAnchorPoints() != null && !template.getAnchorPoints().isEmpty()) {
            // 使用几何中心锚点
            templateCenter = template.getAnchorPoints().stream()
                .filter(a -> a.getType() == com.edge.vision.core.template.model.AnchorPoint.AnchorType.GEOMETRIC_CENTER)
                .findFirst()
                .map(com.edge.vision.core.template.model.AnchorPoint::getPosition)
                .orElse(null);
        }
        logger.info("Template center: ({}, {})", templateCenter != null ? templateCenter.x : "null", templateCenter != null ? templateCenter.y : "null");

        // 转换为相对坐标并创建临时对象列表
        List<DetectedObject> relativeObjects = new ArrayList<>();
        for (DetectedObject obj : detectedObjects) {
            DetectedObject relativeObj = new DetectedObject();
            relativeObj.setClassId(obj.getClassId());
            relativeObj.setClassName(obj.getClassName());
            // 转换为相对坐标
            com.edge.vision.core.template.model.Point relativePos = new com.edge.vision.core.template.model.Point(
                obj.getCenter().x - detectedCenter.x,
                obj.getCenter().y - detectedCenter.y
            );
            relativeObj.setCenter(relativePos);
            relativeObj.setWidth(obj.getWidth());
            relativeObj.setHeight(obj.getHeight());
            relativeObj.setConfidence(obj.getConfidence());
            relativeObjects.add(relativeObj);
        }

        // 记录已匹配的检测对象索引
        Set<Integer> matchedIndices = new HashSet<>();

        // 1. 遍历模板特征，查找匹配的检测对象（使用相对坐标）
        for (TemplateFeature feature : template.getFeatures()) {
            // 获取或计算相对位置
            com.edge.vision.core.template.model.Point featureRelativePos = feature.getRelativePosition();

            // 如果没有存储相对位置，动态计算
            if (featureRelativePos == null && templateCenter != null && feature.getPosition() != null) {
                featureRelativePos = new com.edge.vision.core.template.model.Point(
                    feature.getPosition().x - templateCenter.x,
                    feature.getPosition().y - templateCenter.y
                );
                logger.debug("Calculated relative position for {}: ({}, {})",
                    feature.getId(), featureRelativePos.x, featureRelativePos.y);
            }

            if (featureRelativePos == null) {
                logger.warn("No relative position for feature {}, skipping", feature.getId());
                continue;
            }

            DetectedObject matched = findNearestMatch(feature, relativeObjects, matchedIndices, detectedCenter);

            if (matched != null) {
                // 计算原始绝对位置用于显示
                com.edge.vision.core.template.model.Point absolutePos = new com.edge.vision.core.template.model.Point(
                    matched.getCenter().x + detectedCenter.x,
                    matched.getCenter().y + detectedCenter.y
                );

                // 使用相对位置计算误差
                double xError = Math.abs(matched.getCenter().x - featureRelativePos.x);
                double yError = Math.abs(matched.getCenter().y - featureRelativePos.y);

                FeatureComparison comp;
                if (xError <= feature.getTolerance().getX() &&
                    yError <= feature.getTolerance().getY()) {
                    // 在容差范围内，合格
                    comp = FeatureComparison.passed(feature, absolutePos, xError, yError);
                } else {
                    // 超出容差
                    comp = FeatureComparison.deviation(feature, absolutePos, xError, yError);
                }

                // 设置实际的类别名称
                comp.setClassName(matched.getClassName());
                result.addComparison(comp);

                // 记录已匹配的索引
                matchedIndices.add(relativeObjects.indexOf(matched));
            } else {
                // 未找到匹配，检查是否必须存在
                if (feature.isRequired()) {
                    FeatureComparison comp = FeatureComparison.missing(feature);
                    // 对于 MISSING 的特征，尝试从任何检测对象中获取类别名称
                    String className = detectedObjects.stream()
                        .filter(obj -> obj.getClassId() == feature.getClassId())
                        .map(DetectedObject::getClassName)
                        .findFirst()
                        .orElse(null);
                    if (className != null) {
                        comp.setClassName(className);
                    }
                    result.addComparison(comp);
                }
            }
        }

        // 2. 检查多余的检测对象（错检）
        for (int i = 0; i < relativeObjects.size(); i++) {
            if (!matchedIndices.contains(i)) {
                DetectedObject obj = relativeObjects.get(i);
                com.edge.vision.core.template.model.Point absolutePos = new com.edge.vision.core.template.model.Point(
                    obj.getCenter().x + detectedCenter.x,
                    obj.getCenter().y + detectedCenter.y
                );
                FeatureComparison comp = FeatureComparison.extra(
                    "extra_" + i,
                    obj.getClassName() != null ? obj.getClassName() : "多余特征_" + i,
                    absolutePos,
                    obj.getClassId(),
                    obj.getConfidence()
                );
                comp.setClassName(obj.getClassName());
                result.addComparison(comp);
            }
        }

        // 3. 设置处理时间
        result.setProcessingTimeMs(System.currentTimeMillis() - startTime);

        // 4. 设置消息
        InspectionResult.InspectionSummary summary = result.getSummary();
        if (summary.totalFeatures==summary.passed) {
            result.setPassed(true);
            result.setMessage(String.format("检测通过 - %s", summary));
        }else {
            result.setMessage(String.format("检测失败 - %s", summary));
        }
        logger.info("Matching completed: {}", result);
        return result;
    }

    /**
     * 计算检测对象的几何中心
     */
    private com.edge.vision.core.template.model.Point calculateDetectedCenter(List<DetectedObject> detectedObjects) {
        if (detectedObjects.isEmpty()) {
            return new com.edge.vision.core.template.model.Point(0, 0);
        }

        double minX = Double.MAX_VALUE;
        double maxX = Double.MIN_VALUE;
        double minY = Double.MAX_VALUE;
        double maxY = Double.MIN_VALUE;

        for (DetectedObject obj : detectedObjects) {
            com.edge.vision.core.template.model.Point center = obj.getCenter();
            double halfW = obj.getWidth() / 2;
            double halfH = obj.getHeight() / 2;

            minX = Math.min(minX, center.x - halfW);
            maxX = Math.max(maxX, center.x + halfW);
            minY = Math.min(minY, center.y - halfH);
            maxY = Math.max(maxY, center.y + halfH);
        }

        return new com.edge.vision.core.template.model.Point(
            (minX + maxX) / 2,
            (minY + maxY) / 2
        );
    }

    /**
     * 查找最近的匹配检测对象
     *
     * @param feature         模板特征
     * @param detectedObjects 检测对象列表（相对坐标）
     * @param matchedIndices  已匹配的索引
     * @param detectedCenter  检测对象的几何中心
     * @return 匹配的检测对象，如果没有则返回 null
     */
    private DetectedObject findNearestMatch(
        TemplateFeature feature,
        List<DetectedObject> detectedObjects,
        Set<Integer> matchedIndices,
        com.edge.vision.core.template.model.Point detectedCenter
    ) {
        DetectedObject nearest = null;
        double minDistance = maxMatchDistance;

        // 使用相对位置进行比对
        com.edge.vision.core.template.model.Point featureRelativePos = feature.getRelativePosition();

        for (int i = 0; i < detectedObjects.size(); i++) {
            // 跳过已匹配的对象
            if (matchedIndices.contains(i)) {
                continue;
            }

            DetectedObject obj = detectedObjects.get(i);

            // 类别必须匹配
            if (obj.getClassId() != feature.getClassId()) {
                continue;
            }

            // 计算相对坐标的距离
            double distance = obj.getCenter().distanceTo(featureRelativePos);

            if (distance < minDistance) {
                minDistance = distance;
                nearest = obj;
            }
        }

        return nearest;
    }

    /**
     * 获取最大匹配距离
     */
    public double getMaxMatchDistance() {
        return maxMatchDistance;
    }

    /**
     * 设置最大匹配距离
     */
    public void setMaxMatchDistance(double maxMatchDistance) {
        this.maxMatchDistance = maxMatchDistance;
    }
}
