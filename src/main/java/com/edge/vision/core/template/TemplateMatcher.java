package com.edge.vision.core.template;

import com.edge.vision.core.quality.FeatureComparison;
import com.edge.vision.core.quality.InspectionResult;
import com.edge.vision.core.template.model.DetectedObject;
import com.edge.vision.core.template.model.Point;
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
 * <p>
 * 检测对象已经被 QualityInspector 转换到模板坐标系，直接使用绝对坐标进行比对
 */
@Component
public class TemplateMatcher {
    private static final Logger logger = LoggerFactory.getLogger(TemplateMatcher.class);

    // 最大匹配距离（像素）
    private double maxMatchDistance = 200.0;

    // 是否将未在模板中定义的检测对象视为错检（默认false，只检查模板定义的特征）
    private boolean treatExtraAsError = false;

    public TemplateMatcher() {
    }

    public TemplateMatcher(double maxMatchDistance) {
        this.maxMatchDistance = maxMatchDistance;
    }

    /**
     * 执行模板比对
     *
     * @param template          模板
     * @param detectedObjects   检测到的对象（已在模板坐标系中）
     * @return 比对结果
     */
    public InspectionResult match(Template template, List<DetectedObject> detectedObjects) {
        logger.info("Matching template {} with {} detected objects",
            template.getTemplateId(), detectedObjects.size());

        long startTime = System.currentTimeMillis();
        InspectionResult result = new InspectionResult(template.getTemplateId());

        // 计算质心，用于旋转不变匹配
        Point templateCentroid = calculateTemplateCentroid(template.getFeatures());
        Point detectedCentroid = calculateDetectedCentroid(detectedObjects);

        logger.info("质心 - 模板: ({}, {}), 检测: ({}, {})",
            String.format("%.2f", templateCentroid.x), String.format("%.2f", templateCentroid.y),
            String.format("%.2f", detectedCentroid.x), String.format("%.2f", detectedCentroid.y));

        // 记录已匹配的检测对象索引
        Set<Integer> matchedIndices = new HashSet<>();

        // 1. 遍历模板特征，使用旋转不变匹配（基于质心距离）
        for (TemplateFeature feature : template.getFeatures()) {
            if (feature.getPosition() == null) {
                logger.warn("No position for feature {}, skipping", feature.getId());
                continue;
            }

            MatchResult matchResult = findNearestMatchInvariant(
                feature, detectedObjects, matchedIndices, templateCentroid, detectedCentroid);

            if (matchResult != null) {
                DetectedObject matched = matchResult.object;
                boolean typeMismatch = matchResult.typeMismatch;

                com.edge.vision.core.template.model.Point detectedPos = matched.getCenter();
                com.edge.vision.core.template.model.Point featurePos = feature.getPosition();

                // 计算绝对误差
                double xError = Math.abs(detectedPos.x - featurePos.x);
                double yError = Math.abs(detectedPos.y - featurePos.y);

                // 计算质心距离（用于旋转不变检查）
                double featureDistFromCentroid = distance(featurePos, templateCentroid);
                double detectedDistFromCentroid = distance(detectedPos, detectedCentroid);
                double distError = Math.abs(featureDistFromCentroid - detectedDistFromCentroid);

                FeatureComparison comp;
                if (typeMismatch) {
                    // 类型错误：模板上是 nut，检测成了 hole（或其他类型）
                    comp = FeatureComparison.deviation(feature, detectedPos, xError, yError);
                    comp.setStatus(FeatureComparison.ComparisonStatus.TYPE_MISMATCH);
                    comp.setClassName(matched.getClassName());
                    comp.setExpectedClassName(feature.getName());
                    comp.setExpectedFeatureName(feature.getName());
                    comp.setExpectedPosition(feature.getPosition());
                    logger.info("类型错误: 模板位置({},{})预期是{}，实际检测出{} at ({},{})",
                        featurePos.x, featurePos.y,
                        feature.getName(), matched.getClassName(),
                        detectedPos.x, detectedPos.y);
                } else if (distError <= maxMatchDistance &&
                    xError <= feature.getTolerance().getX() * 2 &&
                    yError <= feature.getTolerance().getY() * 2) {
                    // 使用更宽松的容差（考虑旋转误差）
                    comp = FeatureComparison.passed(feature, detectedPos, xError, yError);
                    comp.setClassName(matched.getClassName());
                    logger.debug("特征 {} 匹配成功: 质心距离误差={}", feature.getId(),
                        String.format("%.2f", distError));
                } else {
                    // 超出容差
                    comp = FeatureComparison.deviation(feature, detectedPos, xError, yError);
                    comp.setClassName(matched.getClassName());
                    logger.debug("特征 {} 匹配失败: 质心距离误差={}, x误差={}, y误差={}",
                        feature.getId(), String.format("%.2f", distError),
                        String.format("%.2f", xError), String.format("%.2f", yError));
                }

                result.addComparison(comp);

                // 记录已匹配的索引（使用 MatchResult 中保存的索引）
                matchedIndices.add(matchResult.index);
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

        // 2. 检查多余的检测对象（错检）- 仅当启用 treatExtraAsError 时
        if (treatExtraAsError) {
            for (int i = 0; i < detectedObjects.size(); i++) {
                if (!matchedIndices.contains(i)) {
                    DetectedObject obj = detectedObjects.get(i);
                    com.edge.vision.core.template.model.Point detectedPos = obj.getCenter();

                    FeatureComparison comp = FeatureComparison.extra(
                        "extra_" + i,
                        obj.getClassName() != null ? obj.getClassName() : "多余特征_" + i,
                        detectedPos,
                        obj.getClassId(),
                        obj.getConfidence()
                    );
                    comp.setClassName(obj.getClassName());

                    // 查找模板上最接近的特征，记录预期类型（用于显示"本应该是X但检测成了Y"）
                    TemplateFeature nearestFeature = findNearestTemplateFeature(obj, template);
                    if (nearestFeature != null) {
                        comp.setExpectedClassName(nearestFeature.getName());
                        comp.setExpectedFeatureName(nearestFeature.getName());
                        comp.setExpectedPosition(nearestFeature.getPosition());
                        logger.info("错检: 检测出{} at ({}, {}), 模板上预期是{} at ({}, {})",
                            obj.getClassName(), detectedPos.x, detectedPos.y,
                            nearestFeature.getName(),
                            nearestFeature.getPosition().x, nearestFeature.getPosition().y);
                    } else {
                        logger.info("错检: 检测出{} at ({}, {}), 模板上无接近特征",
                            obj.getClassName(), detectedPos.x, detectedPos.y);
                    }

                    result.addComparison(comp);
                }
            }
        } else {
            // 记录未匹配的检测对象数量（调试用）
            int unmatchedCount = detectedObjects.size() - matchedIndices.size();
            if (unmatchedCount > 0) {
                logger.info("有 {} 个检测对象未在模板中定义（不计入错检）", unmatchedCount);
            }
        }

        // 3. 设置处理时间
        result.setProcessingTimeMs(System.currentTimeMillis() - startTime);

        // 4. 设置消息
        InspectionResult.InspectionSummary summary = result.getSummary();
        if (summary.totalFeatures == summary.passed) {
            result.setPassed(true);
            result.setMessage(String.format("检测通过 - %s", summary));
        } else {
            result.setMessage(String.format("检测失败 - %s", summary));
        }
        logger.info("Matching completed: {}", result);
        return result;
    }

    /**
     * 查找最近的匹配检测对象（旋转不变版本）
     * <p>
     * 使用距离质心的距离进行匹配，而不是绝对坐标
     * 距离质心的距离是旋转不变的
     *
     * @param feature         模板特征
     * @param detectedObjects 检测对象列表
     * @param matchedIndices  已匹配的索引
     * @param templateCentroid 模板质心
     * @param detectedCentroid 检测质心
     * @return 匹配结果（包含匹配对象、索引和是否类型错误）
     */
    private MatchResult findNearestMatchInvariant(
        TemplateFeature feature,
        List<DetectedObject> detectedObjects,
        Set<Integer> matchedIndices,
        Point templateCentroid,
        Point detectedCentroid
    ) {
        Point featurePos = feature.getPosition();
        double featureDistFromCentroid = distance(featurePos, templateCentroid);

        DetectedObject nearestSameClass = null;
        DetectedObject nearestAnyClass = null;
        int nearestSameClassIndex = -1;
        int nearestAnyClassIndex = -1;
        double minDistErrorSameClass = Double.MAX_VALUE;
        double minDistErrorAnyClass = Double.MAX_VALUE;

        List<CandidateMatch> candidates = new ArrayList<>();

        for (int i = 0; i < detectedObjects.size(); i++) {
            if (matchedIndices.contains(i)) {
                continue;
            }

            DetectedObject obj = detectedObjects.get(i);
            Point objPos = obj.getCenter();

            // 计算距质心的距离（旋转不变）
            double objDistFromCentroid = distance(objPos, detectedCentroid);
            double distError = Math.abs(featureDistFromCentroid - objDistFromCentroid);

            boolean sameClass = obj.getClassId() == feature.getClassId();
            candidates.add(new CandidateMatch(i, obj, distError, sameClass));

            if (sameClass && distError < minDistErrorSameClass) {
                minDistErrorSameClass = distError;
                nearestSameClass = obj;
                nearestSameClassIndex = i;
            }

            if (distError < minDistErrorAnyClass) {
                minDistErrorAnyClass = distError;
                nearestAnyClass = obj;
                nearestAnyClassIndex = i;
            }
        }

        // 输出调试信息
        if (!candidates.isEmpty()) {
            candidates.sort((a, b) -> Double.compare(a.distance, b.distance));
            logger.debug("特征 {} ({}) 距质心{} 的候选匹配（共{}个）:",
                feature.getId(), feature.getName(),
                String.format("%.2f", featureDistFromCentroid), candidates.size());
            for (int i = 0; i < Math.min(3, candidates.size()); i++) {
                CandidateMatch c = candidates.get(i);
                logger.debug("  候选{}: 索引={}, 距离误差={}, 类别匹配={}",
                    i + 1, c.index, String.format("%.2f", c.distance), c.sameClass);
            }
        }

        // 优先返回同类别匹配
        if (nearestSameClass != null && minDistErrorSameClass <= maxMatchDistance) {
            logger.debug("特征 {} 匹配到同类别，质心距离误差={}",
                feature.getId(), String.format("%.2f", minDistErrorSameClass));
            return new MatchResult(nearestSameClass, nearestSameClassIndex, false);
        }

        // 如果没有同类别匹配，但找到了接近的不同类别对象
        if (nearestAnyClass != null && minDistErrorAnyClass <= maxMatchDistance * 0.8) {
            logger.info("特征 {} ({}) 未找到同类别匹配，但找到不同类别对象（质心距离误差={}），标记为类型错误",
                feature.getId(), feature.getName(), String.format("%.2f", minDistErrorAnyClass));
            return new MatchResult(nearestAnyClass, nearestAnyClassIndex, true);
        }

        logger.warn("特征 {} ({}) 距质心{} 未找到匹配（阈值={})",
            feature.getId(), feature.getName(),
            String.format("%.2f", featureDistFromCentroid),
            String.format("%.2f", maxMatchDistance));
        return null;
    }

    /**
     * 计算两点之间的距离
     */
    private double distance(Point p1, Point p2) {
        return Math.sqrt(Math.pow(p1.x - p2.x, 2) + Math.pow(p1.y - p2.y, 2));
    }

    /**
     * 计算模板特征的质心
     */
    private Point calculateTemplateCentroid(List<TemplateFeature> features) {
        if (features == null || features.isEmpty()) {
            return new Point(0, 0);
        }

        double sumX = 0, sumY = 0;
        for (TemplateFeature feature : features) {
            if (feature.getPosition() != null) {
                sumX += feature.getPosition().x;
                sumY += feature.getPosition().y;
            }
        }
        return new Point(sumX / features.size(), sumY / features.size());
    }

    /**
     * 计算检测对象的质心
     */
    private Point calculateDetectedCentroid(List<DetectedObject> objects) {
        if (objects == null || objects.isEmpty()) {
            return new Point(0, 0);
        }

        double sumX = 0, sumY = 0;
        for (DetectedObject obj : objects) {
            sumX += obj.getCenter().x;
            sumY += obj.getCenter().y;
        }
        return new Point(sumX / objects.size(), sumY / objects.size());
    }

    /**
     * 查找模板上最接近的特征
     * 用于错检时显示"本应该是X但检测成了Y"
     *
     * @param detectedObj 检测对象（已在模板坐标系中）
     * @param template    模板
     * @return 最接近的模板特征，如果没有则返回 null
     */
    private TemplateFeature findNearestTemplateFeature(
        DetectedObject detectedObj,
        Template template
    ) {
        TemplateFeature nearest = null;
        double minDistance = Double.MAX_VALUE;

        com.edge.vision.core.template.model.Point objPos = detectedObj.getCenter();

        for (TemplateFeature feature : template.getFeatures()) {
            com.edge.vision.core.template.model.Point featurePos = feature.getPosition();
            if (featurePos == null) continue;

            double distance = Math.sqrt(
                Math.pow(objPos.x - featurePos.x, 2) +
                Math.pow(objPos.y - featurePos.y, 2)
            );

            if (distance < minDistance) {
                minDistance = distance;
                nearest = feature;
            }
        }

        // 只有当距离在合理范围内时才返回（避免匹配到太远的特征）
        if (minDistance <= maxMatchDistance * 1.5) {
            return nearest;
        }
        return null;
    }

    /**
     * 匹配结果（包含匹配对象、索引和是否类型错误）
     */
    private static class MatchResult {
        DetectedObject object;
        int index;  // 对象在列表中的索引
        boolean typeMismatch;  // 是否类型错误（类别不同）

        MatchResult(DetectedObject object, int index, boolean typeMismatch) {
            this.object = object;
            this.index = index;
            this.typeMismatch = typeMismatch;
        }
    }

    /**
     * 候选匹配（用于调试）
     */
    private static class CandidateMatch {
        int index;
        DetectedObject obj;
        double distance;
        boolean sameClass;  // 是否类别相同

        CandidateMatch(int index, DetectedObject obj, double distance, boolean sameClass) {
            this.index = index;
            this.obj = obj;
            this.distance = distance;
            this.sameClass = sameClass;
        }
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

    /**
     * 是否将未在模板中定义的检测对象视为错检
     */
    public boolean isTreatExtraAsError() {
        return treatExtraAsError;
    }

    /**
     * 设置是否将未在模板中定义的检测对象视为错检
     * @param treatExtraAsError true=将额外检测视为错检，false=只验证模板定义的特征（默认）
     */
    public void setTreatExtraAsError(boolean treatExtraAsError) {
        this.treatExtraAsError = treatExtraAsError;
    }
}
