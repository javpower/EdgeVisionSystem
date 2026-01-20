package com.edge.vision.core.topology;

import com.edge.vision.core.quality.FeatureComparison;
import com.edge.vision.core.quality.InspectionResult;
import com.edge.vision.core.template.model.DetectedObject;
import com.edge.vision.core.template.model.Point;
import com.edge.vision.core.template.model.Template;
import com.edge.vision.core.template.model.TemplateFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 基于坐标的直接匹配器（支持平移和旋转校正）
 * <p>
 * 改进策略：
 * 1. 先用所有点计算全局平移（中位数法）
 * 2. 应用变换后再进行精确匹配
 * <p>
 * 这样可以避免贪心匹配在密集特征时出错的问题
 */
@Component
public class CoordinateBasedMatcher {
    private static final Logger logger = LoggerFactory.getLogger(CoordinateBasedMatcher.class);

    // 匹配距离阈值（像素），超过此距离不匹配
    private double matchDistanceThreshold = 300.0;

    // 是否将未在模板中定义的检测对象视为错检
    private boolean treatExtraAsError = true;

    /**
     * 执行模板比对（坐标直接匹配版本，支持平移和旋转）
     *
     * @param template        模板
     * @param detectedObjects 检测到的对象（已在模板坐标系中）
     * @return 比对结果
     */
    public InspectionResult match(Template template, List<DetectedObject> detectedObjects) {
        logger.info("坐标直接匹配: 模板{} vs {} 个检测对象",
            template.getTemplateId(), detectedObjects.size());

        long startTime = System.currentTimeMillis();
        InspectionResult result = new InspectionResult(template.getTemplateId());

        // 为检测对象创建索引映射（用于标识）
        Map<DetectedObject, Integer> detectedIndexMap = new HashMap<>();
        for (int i = 0; i < detectedObjects.size(); i++) {
            detectedIndexMap.put(detectedObjects.get(i), i);
        }

        // 按类别分组检测对象
        Map<Integer, List<DetectedObject>> detectedByClass = groupDetectedByClass(detectedObjects);

        // ========== 步骤1：基于所有点计算全局变换（使用中位数法） ==========
        AffineTransform transform = calculateTransformFromAllPoints(template, detectedObjects);

        if (transform != null) {
            logger.info("计算得到全局变换: dx={}, dy={}, angle={}°",
                String.format("%.2f", transform.tx),
                String.format("%.2f", transform.ty),
                String.format("%.2f", transform.angle));
        }

        // ========== 步骤2：应用逆向变换到所有检测点（变换到模板坐标系） ==========
        Map<DetectedObject, Point> transformedPositions = new HashMap<>();
        for (DetectedObject obj : detectedObjects) {
            Point transformed = transform != null ?
                transform.applyInverse(obj.getCenter()) : obj.getCenter();
            transformedPositions.put(obj, transformed);
        }

        // ========== 步骤3：精确匹配（基于变换后的位置） ==========
        Set<DetectedObject> matchedDetectedObjects = new HashSet<>();

        for (TemplateFeature feature : template.getFeatures()) {
            if (!feature.isRequired()) {
                continue;
            }

            List<DetectedObject> sameClassDetected = detectedByClass.getOrDefault(
                feature.getClassId(), Collections.emptyList());

            if (sameClassDetected.isEmpty()) {
                // 没有同类检测对象，漏检
                Point detectedPositionForAnnotation = transform != null ?
                    transform.applyForward(feature.getPosition()) : feature.getPosition();
                FeatureComparison missingComp = FeatureComparison.missing(feature);
                missingComp.setDetectedPosition(detectedPositionForAnnotation);
                result.addComparison(missingComp);
                logger.info("漏检: 模板特征 {} ({}) 无同类检测对象, 模板位置({},{}) -> 检测图位置({},{})",
                    feature.getId(), feature.getName(),
                    (int)feature.getPosition().x, (int)feature.getPosition().y,
                    (int)detectedPositionForAnnotation.x, (int)detectedPositionForAnnotation.y);
                continue;
            }

            // 找最近的未匹配检测点（使用变换后的位置）
            BestMatchResult bestMatch = findBestMatchAfterTransform(
                feature, sameClassDetected, matchedDetectedObjects, transformedPositions);

            if (bestMatch == null) {
                // 没有找到匹配，漏检
                Point detectedPositionForAnnotation = transform != null ?
                    transform.applyForward(feature.getPosition()) : feature.getPosition();
                FeatureComparison missingComp = FeatureComparison.missing(feature);
                missingComp.setDetectedPosition(detectedPositionForAnnotation);
                result.addComparison(missingComp);
                logger.info("漏检: 模板特征 {} ({}) 无可用匹配点, 模板位置({},{}) -> 检测图位置({},{})",
                    feature.getId(), feature.getName(),
                    (int)feature.getPosition().x, (int)feature.getPosition().y,
                    (int)detectedPositionForAnnotation.x, (int)detectedPositionForAnnotation.y);
                continue;
            }

            // 匹配成功
            matchedDetectedObjects.add(bestMatch.detectedObject);

            // 计算误差（使用变换后的位置）
            double xError = Math.abs(bestMatch.transformedPos.x - feature.getPosition().x);
            double yError = Math.abs(bestMatch.transformedPos.y - feature.getPosition().y);
            double distance = Math.sqrt(xError * xError + yError * yError);

            int detectedIndex = detectedIndexMap.get(bestMatch.detectedObject);

            FeatureComparison comp;
            if (xError <= feature.getTolerance().getX() &&
                yError <= feature.getTolerance().getY()) {
                comp = FeatureComparison.passed(feature,
                    bestMatch.detectedObject.getCenter(), xError, yError);
                logger.debug("匹配成功: 模板{} -> 检测[{}], 变换后距离={}",
                    feature.getId(), detectedIndex,
                    String.format("%.1f", distance));
            } else {
                comp = FeatureComparison.deviation(feature,
                    bestMatch.detectedObject.getCenter(), xError, yError);
                logger.debug("超出容差: 模板{} -> 检测[{}], x误差={}, y误差={}",
                    feature.getId(), detectedIndex,
                    String.format("%.2f", xError),
                    String.format("%.2f", yError));
            }

            comp.setClassName(bestMatch.detectedObject.getClassName());
            result.addComparison(comp);
        }

        // ========== 步骤4：处理未匹配的检测对象（错检） ==========
        if (treatExtraAsError) {
            for (int i = 0; i < detectedObjects.size(); i++) {
                DetectedObject obj = detectedObjects.get(i);
                if (!matchedDetectedObjects.contains(obj)) {
                    // 找最近的同类模板特征作为 expectedPosition
                    Point nearestTemplatePos = findNearestTemplateFeature(
                        template, obj.getClassId(), obj.getCenter());

                    FeatureComparison comp = FeatureComparison.extra(
                        "detected_" + i,
                        obj.getClassName() != null ? obj.getClassName() : "多余特征",
                        obj.getCenter(),  // detectedPosition: 检测图坐标
                        obj.getClassId(),
                        obj.getConfidence()
                    );
                    comp.setClassName(obj.getClassName());
                    // 设置 expectedPosition（最近的模板特征位置）
                    if (nearestTemplatePos != null) {
                        comp.setExpectedPosition(nearestTemplatePos);
                        comp.setExpectedFeatureName(
                            getFeatureNameAtPosition(template, nearestTemplatePos));
                    }
                    result.addComparison(comp);
                    logger.info("错检: 检测[{}] ({}) at ({}, {}), 最近的模板特征在 ({}, {})",
                        i, obj.getClassName(),
                        (int) obj.getCenter().x, (int) obj.getCenter().y,
                        (int) nearestTemplatePos.x, (int) nearestTemplatePos.y);
                }
            }
        }

        // 设置处理时间和消息
        result.setProcessingTimeMs(System.currentTimeMillis() - startTime);
        result.setMatchStrategy(com.edge.vision.core.quality.MatchStrategy.COORDINATE);
        setResultMessage(result, transform);

        logger.info("坐标直接匹配完成: {}", result.getMessage());
        return result;
    }

    /**
     * 基于质心偏移计算全局变换
     * <p>
     * 对每个类别分别计算模板质心和检测质心，得到该类别的偏移向量。
     * 然后根据各类别的检测点数量进行加权平均，得到全局平移向量。
     * <p>
     * 这种方法的优点：
     * 1. 对多余检测点和离群点更鲁棒
     * 2. 不需要一一对应，即使检测点数量和模板点数量不同也能工作
     * 3. 整体偏移估计更准确
     */
    private AffineTransform calculateTransformFromAllPoints(Template template, List<DetectedObject> detectedObjects) {
        // 按类别分组模板特征
        Map<Integer, List<Point>> templatePositionsByClass = new HashMap<>();
        for (TemplateFeature feature : template.getFeatures()) {
            if (feature.isRequired()) {
                templatePositionsByClass
                    .computeIfAbsent(feature.getClassId(), k -> new ArrayList<>())
                    .add(feature.getPosition());
            }
        }

        // 按类别分组检测对象
        Map<Integer, List<DetectedObject>> detectedByClass = new HashMap<>();
        for (DetectedObject obj : detectedObjects) {
            detectedByClass
                .computeIfAbsent(obj.getClassId(), k -> new ArrayList<>())
                .add(obj);
        }

        int templateCount = template.getFeatures().size();
        int detectedCount = detectedObjects.size();

        logger.debug("计算变换: {} 个模板特征, {} 个检测对象", templateCount, detectedCount);

        if (templateCount == 0 || detectedCount == 0) {
            return null;
        }

        // 对每个类别，计算质心偏移
        double totalWeight = 0.0;
        double weightedTx = 0.0;
        double weightedTy = 0.0;

        for (Map.Entry<Integer, List<DetectedObject>> entry : detectedByClass.entrySet()) {
            int classId = entry.getKey();
            List<DetectedObject> classDetected = entry.getValue();
            List<Point> classTemplatePositions = templatePositionsByClass.get(classId);

            if (classTemplatePositions == null || classTemplatePositions.isEmpty()) {
                continue;  // 模板中没有这个类别，跳过
            }

            // 计算该类别的模板质心
            double templateCx = 0.0, templateCy = 0.0;
            for (Point p : classTemplatePositions) {
                templateCx += p.x;
                templateCy += p.y;
            }
            templateCx /= classTemplatePositions.size();
            templateCy /= classTemplatePositions.size();

            // 计算该类别的检测质心
            double detectedCx = 0.0, detectedCy = 0.0;
            for (DetectedObject obj : classDetected) {
                detectedCx += obj.getCenter().x;
                detectedCy += obj.getCenter().y;
            }
            detectedCx /= classDetected.size();
            detectedCy /= classDetected.size();

            // 该类别的偏移向量
            double classTx = detectedCx - templateCx;
            double classTy = detectedCy - templateCy;

            // 权重 = 该类别的检测点数量（检测点多的类别影响更大）
            double weight = classDetected.size();

            weightedTx += classTx * weight;
            weightedTy += classTy * weight;
            totalWeight += weight;

            logger.debug("类别{} 偏移: 模板质心({},{}) -> 检测质心({,{}}), t=({},{})",
                classId,
                (int)templateCx, (int)templateCy,
                (int)detectedCx, (int)detectedCy,
                String.format("%.1f", classTx), String.format("%.1f", classTy));
        }

        if (totalWeight == 0) {
            logger.warn("没有找到任何有效的类别对应关系，无法计算变换");
            return null;
        }

        // 加权平均得到全局平移
        double tx = weightedTx / totalWeight;
        double ty = weightedTy / totalWeight;

        logger.info("质心偏移法计算全局变换: tx={}, ty={}, weight={}",
            String.format("%.2f", tx), String.format("%.2f", ty), (int)totalWeight);

        return new AffineTransform(tx, ty, 0.0);
    }

    /**
     * 找最近的未匹配检测点（应用变换后）
     */
    private BestMatchResult findBestMatchAfterTransform(
            TemplateFeature feature,
            List<DetectedObject> sameClassDetected,
            Set<DetectedObject> matchedObjects,
            Map<DetectedObject, Point> transformedPositions) {

        DetectedObject nearest = null;
        Point nearestTransformedPos = null;
        double minDistance = Double.MAX_VALUE;

        for (DetectedObject obj : sameClassDetected) {
            // 跳过已匹配的
            if (matchedObjects.contains(obj)) {
                continue;
            }

            // 使用变换后的位置
            Point transformedPos = transformedPositions.get(obj);
            Point featurePos = feature.getPosition();

            double dx = transformedPos.x - featurePos.x;
            double dy = transformedPos.y - featurePos.y;
            double distance = Math.sqrt(dx * dx + dy * dy);

            if (distance < minDistance) {
                minDistance = distance;
                nearest = obj;
                nearestTransformedPos = transformedPos;
            }
        }

        if (nearest == null || minDistance > matchDistanceThreshold) {
            return null;
        }

        return new BestMatchResult(nearest, nearestTransformedPos);
    }

    /**
     * 找最近的同类模板特征
     */
    private Point findNearestTemplateFeature(Template template, int classId, Point detectedPos) {
        Point nearest = null;
        double minDistance = Double.MAX_VALUE;

        for (TemplateFeature feature : template.getFeatures()) {
            if (!feature.isRequired() || feature.getClassId() != classId) {
                continue;
            }

            double dx = detectedPos.x - feature.getPosition().x;
            double dy = detectedPos.y - feature.getPosition().y;
            double distance = Math.sqrt(dx * dx + dy * dy);

            if (distance < minDistance) {
                minDistance = distance;
                nearest = feature.getPosition();
            }
        }

        return nearest;
    }

    /**
     * 获取指定位置的模板特征名称
     */
    private String getFeatureNameAtPosition(Template template, Point pos) {
        for (TemplateFeature feature : template.getFeatures()) {
            if (!feature.isRequired()) {
                continue;
            }
            Point featurePos = feature.getPosition();
            double dx = pos.x - featurePos.x;
            double dy = pos.y - featurePos.y;
            double distance = Math.sqrt(dx * dx + dy * dy);

            // 如果距离很近（小于10像素），认为是同一个位置
            if (distance < 10) {
                return feature.getName();
            }
        }
        return null;
    }

    /**
     * 计算中位数
     */
    private double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int size = sorted.size();
        if (size % 2 == 0) {
            return (sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2.0;
        } else {
            return sorted.get(size / 2);
        }
    }

    /**
     * 按类别分组检测对象
     */
    private Map<Integer, List<DetectedObject>> groupDetectedByClass(List<DetectedObject> detectedObjects) {
        Map<Integer, List<DetectedObject>> grouped = new HashMap<>();
        for (DetectedObject obj : detectedObjects) {
            grouped.computeIfAbsent(obj.getClassId(), k -> new ArrayList<>()).add(obj);
        }
        return grouped;
    }

    /**
     * 设置结果消息
     */
    private void setResultMessage(InspectionResult result, AffineTransform transform) {
        InspectionResult.InspectionSummary summary = result.getSummary();

        boolean hasExtra = summary.extra > 0;
        boolean hasMissing = summary.missing > 0;
        boolean hasDeviation = summary.deviation > 0;
        boolean allPassed = summary.totalFeatures == summary.passed;

        String transformInfo = transform != null ?
            String.format(", 变换: dx=%.1f, dy=%.1f, angle=%.1f°",
                transform.tx, transform.ty, transform.angle) : "";

        if (allPassed && !hasExtra && !hasMissing && !hasDeviation) {
            result.setPassed(true);
            result.setMessage(String.format("检测通过 (坐标匹配) - %d个特征匹配成功%s - %s",
                summary.passed, transformInfo, summary));
        } else {
            result.setPassed(false);
            result.setMessage(String.format("检测失败 (坐标匹配) - %d个通过, %d个漏检, %d个偏差, %d个错检%s - %s",
                summary.passed, summary.missing, summary.deviation, summary.extra,
                transformInfo, summary));
        }
    }

    // Getters and Setters

    public double getMatchDistanceThreshold() {
        return matchDistanceThreshold;
    }

    public void setMatchDistanceThreshold(double matchDistanceThreshold) {
        this.matchDistanceThreshold = matchDistanceThreshold;
    }

    public boolean isTreatExtraAsError() {
        return treatExtraAsError;
    }

    public void setTreatExtraAsError(boolean treatExtraAsError) {
        this.treatExtraAsError = treatExtraAsError;
    }

    // ============ 内部类 ============

    /**
     * 最佳匹配结果（用于第二轮精确匹配）
     */
    private static class BestMatchResult {
        DetectedObject detectedObject;
        Point transformedPos;

        BestMatchResult(DetectedObject detectedObject, Point transformedPos) {
            this.detectedObject = detectedObject;
            this.transformedPos = transformedPos;
        }
    }

    /**
     * 仿射变换（简化版：仅平移+旋转）
     * <p>
     * 变换参数表示从模板到检测图的变换：
     * - tx, ty: 平移量（模板坐标系到检测图坐标系）
     * - angle: 旋转角度（模板坐标系到检测图坐标系）
     * <p>
     * 提供两个方向的变换方法：
     * - applyForward(): 模板 → 检测图（用于标注漏检位置）
     * - applyInverse(): 检测图 → 模板（用于匹配）
     */
    public static class AffineTransform {
        public final double tx;       // X方向平移
        public final double ty;       // Y方向平移
        public final double angle;    // 旋转角度（度）

        public AffineTransform(double tx, double ty, double angle) {
            this.tx = tx;
            this.ty = ty;
            this.angle = angle;
        }

        /**
         * 正向变换：模板坐标 → 检测图坐标
         * 用于标注漏检位置：在检测图上显示"模板这个位置应该有特征"
         */
        public Point applyForward(Point p) {
            double rad = Math.toRadians(angle);
            double cos = Math.cos(rad);
            double sin = Math.sin(rad);

            // 正向变换：先旋转，再平移
            double x = p.x * cos - p.y * sin + tx;
            double y = p.x * sin + p.y * cos + ty;

            return new Point(x, y);
        }

        /**
         * 逆向变换：检测图坐标 → 模板坐标
         * 用于匹配：将检测点变换到模板坐标系后比较
         */
        public Point applyInverse(Point p) {
            double rad = Math.toRadians(angle);
            double cos = Math.cos(rad);
            double sin = Math.sin(rad);

            // 逆向变换：先减去平移，再逆向旋转
            double dx = p.x - tx;
            double dy = p.y - ty;

            double x = dx * cos + dy * sin;
            double y = -dx * sin + dy * cos;

            return new Point(x, y);
        }

        @Override
        public String toString() {
            return String.format("AffineTransform[dx=%.2f, dy=%.2f, angle=%.2f°]",
                tx, ty, angle);
        }
    }
}
