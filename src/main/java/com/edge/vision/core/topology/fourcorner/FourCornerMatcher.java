package com.edge.vision.core.topology.fourcorner;

import com.edge.vision.core.quality.FeatureComparison;
import com.edge.vision.core.quality.InspectionResult;
import com.edge.vision.core.template.model.DetectedObject;
import com.edge.vision.core.template.model.Point;
import com.edge.vision.core.template.model.TemplateFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 四角匹配器
 * <p>
 * 核心原理：
 * 1. 工件四个角定义世界坐标系
 * 2. 特征点通过"数字指纹"（仿射不变量）进行匹配
 * 3. 指纹在平移、旋转、缩放下保持不变
 * <p>
 * 匹配流程：
 * 1. 检测阶段：获取工件的四个角坐标
 * 2. 计算每个检测点的指纹
 * 3. 与模板指纹库匹配
 * 4. 输出检测结果
 */
@Component
public class FourCornerMatcher {
    private static final Logger logger = LoggerFactory.getLogger(FourCornerMatcher.class);

    private final FingerprintCalculator calculator = new FingerprintCalculator();

    // 匹配容差（指纹相似度阈值，越小越严格）
    private double fingerprintTolerance = 0.5;

    // 是否使用唯一匹配（每个检测点只匹配一个模板特征）
    private boolean useUniqueMatching = true;

    /**
     * 执行四角匹配
     *
     * @param template         四角模板
     * @param detectedCorners  检测到的四个角坐标 [TL, TR, BR, BL]
     * @param detectedObjects  检测到的对象列表
     * @return 检测结果
     */
    public InspectionResult match(FourCornerTemplate template,
                                  Point[] detectedCorners,
                                  List<DetectedObject> detectedObjects) {
        long startTime = System.currentTimeMillis();
        InspectionResult result = new InspectionResult(template.getTemplateId());

        logger.info("=== Four-Corner Matching ===");
        logger.info("Template: {}", template);
        logger.info("Detected corners: TL=({},{}), TR=({},{}), BR=({},{}), BL=({},{})",
            (int)detectedCorners[0].x, (int)detectedCorners[0].y,
            (int)detectedCorners[1].x, (int)detectedCorners[1].y,
            (int)detectedCorners[2].x, (int)detectedCorners[2].y,
            (int)detectedCorners[3].x, (int)detectedCorners[3].y);
        logger.info("Detected objects: {}", detectedObjects.size());

        // 验证四边形有效性
        if (!calculator.isValidQuadrilateral(detectedCorners)) {
            logger.error("Invalid detected quadrilateral");
            result.setPassed(false);
            result.setMessage("检测到的四角坐标无效");
            return result;
        }

        // 步骤1：计算所有检测点的指纹
        Map<DetectedObject, FeatureFingerprint> detectedFingerprints = new HashMap<>();
        for (DetectedObject obj : detectedObjects) {
            try {
                FeatureFingerprint fp = calculator.calculate(
                    obj.getCenter(), detectedCorners, "detected_" + detectedObjects.indexOf(obj));
                detectedFingerprints.put(obj, fp);
            } catch (Exception e) {
                logger.warn("Failed to calculate fingerprint for detected object: {}", e.getMessage());
            }
        }

        logger.info("Calculated {} fingerprints for detected objects", detectedFingerprints.size());

        // 步骤2：匹配（模板特征 -> 检测对象）
        Set<DetectedObject> matchedObjects = new HashSet<>();
        Set<String> matchedFeatures = new HashSet<>();

        for (String featureId : template.getRequiredFeatureIds()) {
            FeatureFingerprint templateFP = template.getFingerprint(featureId);
            FourCornerTemplate.FeatureMetadata metadata = template.getFeatureMetadata(featureId);

            if (templateFP == null) {
                logger.warn("No fingerprint found for feature: {}", featureId);
                continue;
            }

            // 找到最佳匹配的检测点
            DetectedObject bestMatch = null;
            double bestScore = Double.MAX_VALUE;
            FeatureFingerprint bestFingerprint = null;

            for (Map.Entry<DetectedObject, FeatureFingerprint> entry : detectedFingerprints.entrySet()) {
                DetectedObject obj = entry.getKey();

                // 检查类别是否匹配
                if (metadata.classId != obj.getClassId()) {
                    continue;
                }

                // 检查是否已匹配
                if (useUniqueMatching && matchedObjects.contains(obj)) {
                    continue;
                }

                FeatureFingerprint detectedFP = entry.getValue();
                double score = templateFP.similarity(detectedFP);

                if (score < bestScore) {
                    bestScore = score;
                    bestMatch = obj;
                    bestFingerprint = detectedFP;
                }
            }

            // 判断是否匹配成功
            if (bestMatch != null && bestScore < fingerprintTolerance) {
                // 匹配成功
                matchedObjects.add(bestMatch);
                matchedFeatures.add(featureId);

                // 计算位置误差
                Point templatePos = template.getFeaturePosition(featureId);
                Point detectedPos = bestMatch.getCenter();
                double xError = Math.abs(detectedPos.x - templatePos.x);
                double yError = Math.abs(detectedPos.y - templatePos.y);

                FeatureComparison comp = createFeatureComparison(
                    featureId, metadata, detectedPos, xError, yError,
                    (xError <= metadata.tolerance.x && yError <= metadata.tolerance.y),
                    FeatureComparison.ComparisonStatus.PASSED
                );

                result.addComparison(comp);

                if (xError <= metadata.tolerance.x && yError <= metadata.tolerance.y) {
                    logger.debug("MATCHED: {} -> detected at ({}, {}), score={:.3f}, xErr={:.1f}, yErr={:.1f}",
                        featureId, (int)detectedPos.x, (int)detectedPos.y, bestScore, xError, yError);
                } else {
                    logger.info("MATCHED (deviation): {} -> detected at ({}, {}), score={:.3f}, xErr={:.1f}, yErr={:.1f}",
                        featureId, (int)detectedPos.x, (int)detectedPos.y, bestScore, xError, yError);
                }

            } else {
                // 未找到匹配 -> 漏检
                Point templatePos = template.getFeaturePosition(featureId);

                // 计算检测图中的预期位置（通过仿射变换）
                Point expectedPos = transformPoint(templatePos,
                    template.getCorners(), detectedCorners);

                FeatureComparison comp = new FeatureComparison(featureId, metadata.name);
                comp.setTemplatePosition(templatePos);
                comp.setDetectedPosition(expectedPos);
                comp.setToleranceX(metadata.tolerance.x);
                comp.setToleranceY(metadata.tolerance.y);
                comp.setStatus(FeatureComparison.ComparisonStatus.MISSING);
                comp.setWithinTolerance(false);
                comp.setClassId(metadata.classId);
                comp.setClassName(metadata.className);

                result.addComparison(comp);

                logger.info("MISSING: {}, bestScore={:.3f} (threshold={:.2f})",
                    featureId, bestScore, fingerprintTolerance);
            }
        }

        // 步骤3：处理未匹配的检测对象 -> 错检
        for (DetectedObject obj : detectedObjects) {
            if (!matchedObjects.contains(obj)) {
                FeatureFingerprint fp = detectedFingerprints.get(obj);

                // 找最近的模板特征
                String nearestFeature = findNearestFeature(fp, template, obj.getClassId());

                FeatureComparison comp = new FeatureComparison(
                    "extra_" + detectedObjects.indexOf(obj),
                    obj.getClassName() != null ? obj.getClassName() : "多余特征"
                );
                comp.setDetectedPosition(obj.getCenter());
                comp.setStatus(FeatureComparison.ComparisonStatus.EXTRA);
                comp.setWithinTolerance(false);
                comp.setClassId(obj.getClassId());
                comp.setClassName(obj.getClassName());
                comp.setConfidence(obj.getConfidence());

                if (nearestFeature != null) {
                    FourCornerTemplate.FeatureMetadata meta = template.getFeatureMetadata(nearestFeature);
                    comp.setExpectedClassName(meta.className);
                    comp.setExpectedFeatureName(meta.name);
                    comp.setExpectedPosition(template.getFeaturePosition(nearestFeature));
                }

                result.addComparison(comp);

                logger.info("EXTRA: detected at ({}, {}), class={}",
                    (int)obj.getCenter().x, (int)obj.getCenter().y, obj.getClassId());
            }
        }

        // 设置结果
        result.setProcessingTimeMs(System.currentTimeMillis() - startTime);
        setResultMessage(result, template, matchedFeatures.size());

        logger.info("=== Matching Complete: {} ===", result.getMessage());
        return result;
    }

    /**
     * 创建 FeatureComparison 对象
     */
    private FeatureComparison createFeatureComparison(
            String featureId,
            FourCornerTemplate.FeatureMetadata metadata,
            Point detectedPos,
            double xError,
            double yError,
            boolean withinTolerance,
            FeatureComparison.ComparisonStatus status) {

        FeatureComparison comp = new FeatureComparison(featureId, metadata.name);
        comp.setDetectedPosition(detectedPos);
        comp.setXError(xError);
        comp.setYError(yError);
        comp.setTotalError(Math.sqrt(xError * xError + yError * yError));
        comp.setToleranceX(metadata.tolerance.x);
        comp.setToleranceY(metadata.tolerance.y);
        comp.setWithinTolerance(withinTolerance);
        comp.setStatus(status);
        comp.setClassId(metadata.classId);
        comp.setClassName(metadata.className);
        return comp;
    }

    /**
     * 将模板点通过仿射变换映射到检测图
     * <p>
     * 使用重心坐标进行双线性插值
     */
    private Point transformPoint(Point templatePoint, Point[] templateCorners, Point[] detectedCorners) {
        // 计算模板点在模板四角中的重心坐标
        double[] bary = calculateBarycentricCoords(templatePoint, templateCorners);

        // 使用相同的重心坐标在检测四角中插值
        double x = bary[0] * detectedCorners[0].x +
                   bary[1] * detectedCorners[1].x +
                   bary[2] * detectedCorners[2].x +
                   bary[3] * detectedCorners[3].x;

        double y = bary[0] * detectedCorners[0].y +
                   bary[1] * detectedCorners[1].y +
                   bary[2] * detectedCorners[2].y +
                   bary[3] * detectedCorners[3].y;

        return new Point(x, y);
    }

    /**
     * 计算点在四边形中的重心坐标
     * 基于对角面积权重
     */
    private double[] calculateBarycentricCoords(Point p, Point[] corners) {
        Point tl = corners[0];
        Point tr = corners[1];
        Point br = corners[2];
        Point bl = corners[3];

        double areaOppositeTL = triangleArea(p, tr, br);
        double areaOppositeTR = triangleArea(p, br, bl);
        double areaOppositeBR = triangleArea(p, bl, tl);
        double areaOppositeBL = triangleArea(p, tl, tr);

        double totalArea = areaOppositeTL + areaOppositeTR + areaOppositeBR + areaOppositeBL;

        if (totalArea < 1e-10) {
            return new double[]{0.25, 0.25, 0.25, 0.25};
        }

        return new double[]{
            areaOppositeTL / totalArea,
            areaOppositeTR / totalArea,
            areaOppositeBR / totalArea,
            areaOppositeBL / totalArea
        };
    }

    /**
     * 计算三角形面积
     */
    private double triangleArea(Point p1, Point p2, Point p3) {
        return 0.5 * Math.abs(
            p1.x * (p2.y - p3.y) +
            p2.x * (p3.y - p1.y) +
            p3.x * (p1.y - p2.y)
        );
    }

    /**
     * 找到与给定指纹最相似的模板特征
     */
    private String findNearestFeature(FeatureFingerprint fp, FourCornerTemplate template, int classId) {
        String nearest = null;
        double minScore = Double.MAX_VALUE;

        for (String featureId : template.getFeatureIds()) {
            FourCornerTemplate.FeatureMetadata metadata = template.getFeatureMetadata(featureId);
            if (metadata.classId == classId) {
                FeatureFingerprint templateFP = template.getFingerprint(featureId);
                double score = fp.similarity(templateFP);
                if (score < minScore) {
                    minScore = score;
                    nearest = featureId;
                }
            }
        }

        return nearest;
    }

    /**
     * 设置结果消息
     */
    private void setResultMessage(InspectionResult result, FourCornerTemplate template, int matchedCount) {
        InspectionResult.InspectionSummary summary = result.getSummary();
        boolean allPassed = summary.totalFeatures == summary.passed;

        if (allPassed) {
            result.setPassed(true);
            result.setMessage(String.format("四角匹配通过 - %d/%d 特征匹配成功",
                matchedCount, template.getFeatureCount()));
        } else {
            result.setPassed(false);
            result.setMessage(String.format("四角匹配失败 - %d个通过, %d个漏检, %d个偏差, %d个错检",
                summary.passed, summary.missing, summary.deviation, summary.extra));
        }
    }

    // ========== Getters and Setters ==========

    public double getFingerprintTolerance() {
        return fingerprintTolerance;
    }

    public void setFingerprintTolerance(double fingerprintTolerance) {
        this.fingerprintTolerance = fingerprintTolerance;
    }

    public boolean isUseUniqueMatching() {
        return useUniqueMatching;
    }

    public void setUseUniqueMatching(boolean useUniqueMatching) {
        this.useUniqueMatching = useUniqueMatching;
    }
}
