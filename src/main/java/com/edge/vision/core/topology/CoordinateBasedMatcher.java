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
 * 迭代优化策略：
 * 1. 粗略估计：用质心法计算初始变换（对漏检/多检鲁棒）
 * 2. 贪心匹配：应用变换，找出可靠的匹配对
 * 3. 精确计算：只用可靠的匹配对重新计算偏移
 * <p>
 * 这样即使有漏检/多检导致质心偏移，第二步能找到准确匹配，第三步基于可靠对重新计算。
 */
@Component
public class CoordinateBasedMatcher {
    private static final Logger logger = LoggerFactory.getLogger(CoordinateBasedMatcher.class);

    // 匹配距离阈值（像素），超过此距离不匹配
    private double matchDistanceThreshold = 300.0;

    // 是否将未在模板中定义的检测对象视为错检
    private boolean treatExtraAsError = true;

    // 最小可靠匹配对数量（低于此数量拒绝匹配）
    private int minReliablePairs = 4;

    // 最小内点比例（RANSAC，低于此比例拒绝旋转）
    private double minInlierRatio = 0.5;

    // 最大允许遮挡率（超过此比例拒绝匹配）
    private double maxOcclusionRate = 0.7;

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

        // ========== 步骤1：基于所有点计算全局变换（迭代优化法） ==========
        AffineTransform transform = null;
        try {
            transform = calculateTransformFromAllPoints(template, detectedObjects);

            if (transform != null) {
                logger.info("计算得到全局变换: dx={}, dy={}, angle={}°",
                    String.format("%.2f", transform.tx),
                    String.format("%.2f", transform.ty),
                    String.format("%.2f", transform.angle));
            }
        } catch (IllegalStateException e) {
            // 遮挡严重或匹配质量过低，无法进行可靠匹配
            logger.error("变换计算失败: {}", e.getMessage());

            // 返回失败结果，将所有检测对象标记为错检
            result.setProcessingTimeMs(System.currentTimeMillis() - startTime);
            result.setPassed(false);
            result.setMessage(e.getMessage());

            // 标记所有模板特征为漏检
            for (TemplateFeature feature : template.getFeatures()) {
                if (feature.isRequired()) {
                    result.addComparison(FeatureComparison.missing(feature));
                }
            }

            // 标记所有检测对象为错检
            if (treatExtraAsError) {
                for (int i = 0; i < detectedObjects.size(); i++) {
                    DetectedObject obj = detectedObjects.get(i);
                    FeatureComparison comp = FeatureComparison.extra(
                        "detected_" + i,
                        obj.getClassName() != null ? obj.getClassName() : "检测对象",
                        obj.getCenter(),
                        obj.getClassId(),
                        obj.getConfidence()
                    );
                    comp.setClassName(obj.getClassName());
                    result.addComparison(comp);
                }
            }

            return result;
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

        // 记录成功的锚点：模板特征 -> 锚点信息
        Map<TemplateFeature, AnchorPoint> anchorMap = new HashMap<>();

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
                // 没有找到匹配，使用锚点映射来计算位置
                Point localAnnotationPos = findLocalAnnotationPositionUsingAnchors(feature, anchorMap, transform);

                FeatureComparison missingComp = FeatureComparison.missing(feature);
                missingComp.setDetectedPosition(localAnnotationPos);
                result.addComparison(missingComp);
                logger.info("漏检: 模板特征 {} ({}) 无可用匹配点, 模板位置({},{}) -> 锚点映射检测图位置({},{})",
                    feature.getId(), feature.getName(),
                    (int)feature.getPosition().x, (int)feature.getPosition().y,
                    (int)localAnnotationPos.x, (int)localAnnotationPos.y);
                continue;
            }

            // 获取检测索引（需要在后面的日志中使用）
            int detectedIndex = detectedIndexMap.get(bestMatch.detectedObject);

            // 计算误差（使用变换后的位置）
            double xError = Math.abs(bestMatch.transformedPos.x - feature.getPosition().x);
            double yError = Math.abs(bestMatch.transformedPos.y - feature.getPosition().y);
            double distance = Math.sqrt(xError * xError + yError * yError);

            // 计算相对误差（相对于容差的比例）
            double relativeXError = xError / (feature.getTolerance().getX() + 1e-6);
            double relativeYError = yError / (feature.getTolerance().getY() + 1e-6);
            double maxRelativeError = Math.max(relativeXError, relativeYError);

            // 如果误差非常大（超过 2 倍容差），可能是错误匹配，拒绝它
            // 让其他模板特征有机会匹配这个检测对象
            if (maxRelativeError > 2.0) {
                logger.info("拒绝错误匹配: 模板{} ({}) -> 检测[{}], 误差过大 (x误差={}, y误差={}, 相对误差={}%), 跳过此匹配",
                    feature.getId(), feature.getName(), detectedIndex,
                    String.format("%.2f", xError), String.format("%.2f", yError),
                    String.format("%.1f", maxRelativeError * 100));

                FeatureComparison missingComp = FeatureComparison.missing(feature);
                Point detectedPositionForAnnotation = transform != null ?
                    transform.applyForward(feature.getPosition()) : feature.getPosition();
                missingComp.setDetectedPosition(detectedPositionForAnnotation);
                result.addComparison(missingComp);
                logger.info("漏检: 模板特征 {} ({}) 匹配被拒绝, 模板位置({},{}) -> 全局变换检测图位置({},{})",
                    feature.getId(), feature.getName(),
                    (int)feature.getPosition().x, (int)feature.getPosition().y,
                    (int)detectedPositionForAnnotation.x, (int)detectedPositionForAnnotation.y);
                continue;
            }

            // 匹配成功，标记为已匹配
            matchedDetectedObjects.add(bestMatch.detectedObject);

            // 只有高质量的匹配才记录为锚点（误差 < 容差的 30%）
            // 这样可以避免把错误匹配当作锚点使用
            boolean isHighQuality = maxRelativeError < 0.3;
            if (isHighQuality) {
                double offsetX = bestMatch.detectedObject.getCenter().x - feature.getPosition().x;
                double offsetY = bestMatch.detectedObject.getCenter().y - feature.getPosition().y;
                anchorMap.put(feature, new AnchorPoint(feature.getPosition(), bestMatch.detectedObject.getCenter(), offsetX, offsetY));
                logger.debug("记录高质量锚点: 模板" + feature.getId() + " -> 检测[" + detectedIndex + "], 相对误差=" + String.format("%.1f%%", maxRelativeError * 100));
            } else {
                logger.debug("跳过低质量匹配: 模板" + feature.getId() + " -> 检测[" + detectedIndex + "], 相对误差=" + String.format("%.1f%%", maxRelativeError * 100) + " (阈值: 30%)");
            }

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
     * 迭代优化法计算全局变换
     * <p>
     * 三步策略：
     * 1. 粗略估计：用质心法计算初始变换（对漏检/多检鲁棒）
     * 2. 贪心匹配：应用变换，找出可靠的匹配对
     * 3. 精确计算：只用可靠的匹配对重新计算偏移
     * <p>
     * 这样即使有漏检/多检导致质心偏移，第二步能找到准确匹配，第三步基于可靠对重新计算。
     *
     * @throws IllegalStateException 当遮挡严重或匹配质量过低时抛出异常
     */
    private AffineTransform calculateTransformFromAllPoints(Template template, List<DetectedObject> detectedObjects) {
        // 按类别分组
        Map<Integer, List<TemplateFeature>> templateFeaturesByClass = new HashMap<>();
        for (TemplateFeature feature : template.getFeatures()) {
            if (feature.isRequired()) {
                templateFeaturesByClass
                    .computeIfAbsent(feature.getClassId(), k -> new ArrayList<>())
                    .add(feature);
            }
        }

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

        // 检查遮挡率
        double occlusionRate = 1.0 - (double) detectedCount / templateCount;
        if (occlusionRate > maxOcclusionRate) {
            throw new IllegalStateException(String.format(
                "遮挡率过高: %.0f%% (%d/%d 特征被遮挡), 无法进行可靠的模板匹配",
                occlusionRate * 100, templateCount - detectedCount, templateCount));
        }

        if (occlusionRate > 0.5) {
            logger.warn("警告: 遮挡率较高: {:.0f}%, 匹配结果可能不准确", (int)(occlusionRate * 100));
        }

        // ========== 第一步：粗略估计（质心法） ==========
        AffineTransform initialTransform = calculateRoughTransformByCentroid(
            templateFeaturesByClass, detectedByClass);

        if (initialTransform == null) {
            logger.warn("质心法无法计算变换，尝试使用零变换");
            initialTransform = new AffineTransform(0, 0, 0);
        }

        logger.info("第一步-粗略变换: {}", initialTransform);

        // ========== 第二步：贪心匹配，找出可靠的匹配对 ==========
        List<MatchPair> reliablePairs = findReliableMatchPairs(
            templateFeaturesByClass, detectedByClass, initialTransform);

        logger.info("第二步-找到 {} 个可靠匹配对", reliablePairs.size());

        // 质量检查：确保有足够的可靠匹配对
        if (reliablePairs.size() < minReliablePairs) {
            throw new IllegalStateException(String.format(
                "可靠匹配对不足: %d 个 (最少需要 %d 个), 无法进行精确的模板匹配",
                reliablePairs.size(), minReliablePairs));
        }

        // ========== 第三步：用可靠匹配对重新计算精确偏移和旋转 ==========
        if (reliablePairs.isEmpty()) {
            logger.warn("没有找到任何可靠匹配对，使用粗略变换");
            return initialTransform;
        }

        // 1. 先计算旋转角度（必须在计算平移之前）
        Double rotationAngleDeg = calculateRotationAngle(reliablePairs);
        double finalAngle = rotationAngleDeg != null ? rotationAngleDeg : 0.0;

        // 2. 计算模板点和检测点的质心（作为旋转中心）
        double templateCx = 0.0, templateCy = 0.0;
        double detectedCx = 0.0, detectedCy = 0.0;
        for (MatchPair pair : reliablePairs) {
            templateCx += pair.templatePos.x;
            templateCy += pair.templatePos.y;
            detectedCx += pair.detectedPos.x;
            detectedCy += pair.detectedPos.y;
        }
        templateCx /= reliablePairs.size();
        templateCy /= reliablePairs.size();
        detectedCx /= reliablePairs.size();
        detectedCy /= reliablePairs.size();

        logger.info("质心: 模板=({},{}), 检测=({},{})",
            (int)templateCx, (int)templateCy, (int)detectedCx, (int)detectedCy);
        logger.info("质心平移: dx={}, dy={}",
            String.format("%.2f", detectedCx - templateCx),
            String.format("%.2f", detectedCy - templateCy));

        // 3. 基于质心计算平移量（解耦旋转-平移）
        //    关键修正：旋转是绕质心进行的，不是绕原点
        //    使用 RANSAC 过滤离群匹配对
        List<Double> refinedTxList = new ArrayList<>();
        List<Double> refinedTyList = new ArrayList<>();

        // 预先计算三角函数
        double rad = Math.toRadians(finalAngle);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);

        // 第一遍：计算所有匹配对的残差
        List<Double> initialTxList = new ArrayList<>();
        List<Double> initialTyList = new ArrayList<>();
        for (MatchPair pair : reliablePairs) {
            // 计算模板点相对于质心的坐标
            double relTx = pair.templatePos.x - templateCx;
            double relTy = pair.templatePos.y - templateCy;

            // 对相对坐标进行旋转
            double rotatedRelTx = relTx * cos - relTy * sin;
            double rotatedRelTy = relTx * sin + relTy * cos;

            // 旋转后的模板点在检测坐标系中的预期位置
            double rotatedTemplateX = rotatedRelTx + detectedCx;
            double rotatedTemplateY = rotatedRelTy + detectedCy;

            // 计算残差平移量
            double tx = pair.detectedPos.x - rotatedTemplateX;
            double ty = pair.detectedPos.y - rotatedTemplateY;

            initialTxList.add(tx);
            initialTyList.add(ty);
        }

        // 计算中位数作为参考
        double medianTx = median(initialTxList);
        double medianTy = median(initialTyList);

        // 过滤离群点：残差与中位数差距超过 50 像素的匹配对
        List<MatchPair> filteredPairs = new ArrayList<>();
        double outlierThreshold = 50.0;

        for (int i = 0; i < reliablePairs.size(); i++) {
            MatchPair pair = reliablePairs.get(i);
            double tx = initialTxList.get(i);
            double ty = initialTyList.get(i);

            double deviation = Math.sqrt(Math.pow(tx - medianTx, 2) + Math.pow(ty - medianTy, 2));

            if (deviation < outlierThreshold) {
                filteredPairs.add(pair);
                refinedTxList.add(tx);
                refinedTyList.add(ty);
                logger.debug("匹配对[{}]: 模板({},{}) -> 检测({},{}), 残差平移=({},{}), 偏差={}",
                    i,
                    (int)pair.templatePos.x, (int)pair.templatePos.y,
                    (int)pair.detectedPos.x, (int)pair.detectedPos.y,
                    String.format("%.2f", tx), String.format("%.2f", ty),
                    String.format("%.1f", deviation));
            } else {
                logger.info("过滤离群匹配对[{}]: 模板({},{}) -> 检测({},{}), 残差平移=({},{}), 偏差={} (阈值: {})",
                    i,
                    (int)pair.templatePos.x, (int)pair.templatePos.y,
                    (int)pair.detectedPos.x, (int)pair.detectedPos.y,
                    String.format("%.2f", tx), String.format("%.2f", ty),
                    String.format("%.1f", deviation), (int)outlierThreshold);
            }
        }

        // 如果过滤后匹配对太少，使用原始列表
        if (filteredPairs.size() < reliablePairs.size() / 2) {
            logger.warn("过滤后剩余匹配对过少 ({}), 使用原始列表", filteredPairs.size());
            filteredPairs = reliablePairs;
        }

        // 使用过滤后的匹配对重新计算质心
        if (filteredPairs.size() != reliablePairs.size()) {
            templateCx = 0.0;
            templateCy = 0.0;
            detectedCx = 0.0;
            detectedCy = 0.0;
            for (MatchPair pair : filteredPairs) {
                templateCx += pair.templatePos.x;
                templateCy += pair.templatePos.y;
                detectedCx += pair.detectedPos.x;
                detectedCy += pair.detectedPos.y;
            }
            templateCx /= filteredPairs.size();
            templateCy /= filteredPairs.size();
            detectedCx /= filteredPairs.size();
            detectedCy /= filteredPairs.size();

            logger.info("过滤后质心: 模板=({},{}), 检测=({},{})",
                (int)templateCx, (int)templateCy, (int)detectedCx, (int)detectedCy);
        }

        // 使用过滤后的匹配对重新计算旋转角度
        Double recalculatedRotation = null;
        if (filteredPairs.size() != reliablePairs.size()) {
            // 重新计算旋转（基于过滤后的匹配对）
            List<MatchPair> filteredMatchPairs = new ArrayList<>();
            for (int i = 0; i < reliablePairs.size(); i++) {
                if (filteredPairs.contains(reliablePairs.get(i))) {
                    filteredMatchPairs.add(reliablePairs.get(i));
                }
            }
            recalculatedRotation = calculateRotationAngle(filteredMatchPairs);
            if (recalculatedRotation != null) {
                finalAngle = recalculatedRotation;
                logger.info("重新计算旋转角度: {}°", String.format("%.2f", finalAngle));
            }

            // 重新计算三角函数
            rad = Math.toRadians(finalAngle);
            cos = Math.cos(rad);
            sin = Math.sin(rad);

            // 重新计算所有过滤后匹配对的残差
            refinedTxList.clear();
            refinedTyList.clear();
            for (MatchPair pair : filteredPairs) {
                double relTx = pair.templatePos.x - templateCx;
                double relTy = pair.templatePos.y - templateCy;
                double rotatedRelTx = relTx * cos - relTy * sin;
                double rotatedRelTy = relTx * sin + relTy * cos;
                double rotatedTemplateX = rotatedRelTx + detectedCx;
                double rotatedTemplateY = rotatedRelTy + detectedCy;
                double tx = pair.detectedPos.x - rotatedTemplateX;
                double ty = pair.detectedPos.y - rotatedTemplateY;
                refinedTxList.add(tx);
                refinedTyList.add(ty);
            }
        }

        // 使用中位数（更鲁棒）
        double residualTx = median(refinedTxList);
        double residualTy = median(refinedTyList);

        // 检查残差是否过大（说明变换不一致）
        double residualMagnitude = Math.sqrt(residualTx * residualTx + residualTy * residualTy);
        double maxResidualThreshold = 15.0;

        double finalTx, finalTy;
        if (residualMagnitude > maxResidualThreshold) {
            logger.warn("残差过大 ({} 像素), 可能存在系统偏差，使用质心平移而不加残差",
                String.format("%.1f", residualMagnitude));
            finalTx = detectedCx - templateCx;
            finalTy = detectedCy - templateCy;
        } else {
            finalTx = (detectedCx - templateCx) + residualTx;
            finalTy = (detectedCy - templateCy) + residualTy;
        }

        AffineTransform finalTransform = new AffineTransform(finalTx, finalTy, finalAngle, templateCx, templateCy);

        logger.info("第三步-精确变换: 基于 {} 个可靠匹配对, tx={}, ty={}, angle={}°, 残差={}px (初始: tx={}, ty={})",
            filteredPairs.size(),
            String.format("%.2f", finalTx), String.format("%.2f", finalTy), String.format("%.2f", finalAngle),
            String.format("%.1f", residualMagnitude),
            String.format("%.2f", initialTransform.tx), String.format("%.2f", initialTransform.ty));

        return finalTransform;
    }

    /**
     * 第一步：用质心法计算粗略变换
     */
    private AffineTransform calculateRoughTransformByCentroid(
            Map<Integer, List<TemplateFeature>> templateFeaturesByClass,
            Map<Integer, List<DetectedObject>> detectedByClass) {

        double totalWeight = 0.0;
        double weightedTx = 0.0;
        double weightedTy = 0.0;

        for (Map.Entry<Integer, List<DetectedObject>> entry : detectedByClass.entrySet()) {
            int classId = entry.getKey();
            List<DetectedObject> classDetected = entry.getValue();
            List<TemplateFeature> classTemplateFeatures = templateFeaturesByClass.get(classId);

            if (classTemplateFeatures == null || classTemplateFeatures.isEmpty()) {
                continue;
            }

            // 计算模板质心
            double templateCx = 0.0, templateCy = 0.0;
            for (TemplateFeature f : classTemplateFeatures) {
                templateCx += f.getPosition().x;
                templateCy += f.getPosition().y;
            }
            templateCx /= classTemplateFeatures.size();
            templateCy /= classTemplateFeatures.size();

            // 计算检测质心
            double detectedCx = 0.0, detectedCy = 0.0;
            for (DetectedObject obj : classDetected) {
                detectedCx += obj.getCenter().x;
                detectedCy += obj.getCenter().y;
            }
            detectedCx /= classDetected.size();
            detectedCy /= classDetected.size();

            double classTx = detectedCx - templateCx;
            double classTy = detectedCy - templateCy;
            double weight = classDetected.size();

            weightedTx += classTx * weight;
            weightedTy += classTy * weight;
            totalWeight += weight;

            logger.debug("类别{} 质心偏移: 模板({},{}) -> 检测({},{}), t=({},{})",
                classId,
                (int)templateCx, (int)templateCy,
                (int)detectedCx, (int)detectedCy,
                String.format("%.1f", classTx), String.format("%.1f", classTy));
        }

        if (totalWeight == 0) {
            return null;
        }

        return new AffineTransform(weightedTx / totalWeight, weightedTy / totalWeight, 0.0);
    }

    /**
     * 计算旋转角度（使用 RANSAC 剔除离群点）
     * <p>
     * RANSAC（随机采样一致性）步骤：
     * 1. 随机选择2个匹配对
     * 2. 计算这两个匹配对的旋转角度
     * 3. 计算有多少匹配对与这个旋转角度一致（在阈值内）
     * 4. 重复多次，选择支持点最多的旋转角度
     * 5. 用所有内点重新计算最终的旋转角度
     *
     * @param pairs 匹配对列表
     * @return 旋转角度（度），如果没有足够的匹配对则返回 null
     */
    private Double calculateRotationAngle(List<MatchPair> pairs) {
        if (pairs.size() < 3) {
            // 需要至少3个匹配对才能进行RANSAC
            return null;
        }

        // RANSAC参数
        final int maxIterations = Math.min(100, pairs.size() * 10);
        final double angleThreshold = Math.toRadians(3.0); // 3度阈值（更严格）
        final double requiredInlierRatio = this.minInlierRatio; // 使用配置的内点比例

        Random random = new Random(42); // 使用固定种子保证可重复性
        double bestRotation = 0.0;
        int maxInliers = 0;
        List<MatchPair> bestInliers = new ArrayList<>();

        // 计算质心（用于角度计算）
        double templateCx = 0.0, templateCy = 0.0;
        double detectedCx = 0.0, detectedCy = 0.0;
        for (MatchPair pair : pairs) {
            templateCx += pair.templatePos.x;
            templateCy += pair.templatePos.y;
            detectedCx += pair.detectedPos.x;
            detectedCy += pair.detectedPos.y;
        }
        templateCx /= pairs.size();
        templateCy /= pairs.size();
        detectedCx /= pairs.size();
        detectedCy /= pairs.size();

        // RANSAC迭代
        for (int iter = 0; iter < maxIterations; iter++) {
            // 1. 随机选择2个不同的匹配对
            int idx1 = random.nextInt(pairs.size());
            int idx2;
            do {
                idx2 = random.nextInt(pairs.size());
            } while (idx2 == idx1);

            MatchPair pair1 = pairs.get(idx1);
            MatchPair pair2 = pairs.get(idx2);

            // 2. 计算这两个匹配对的旋转角度
            Double rotationFromPair = calculateRotationFromTwoPairs(
                pair1, pair2, templateCx, templateCy, detectedCx, detectedCy);

            if (rotationFromPair == null) {
                continue;
            }

            // 3. 计算内点（与这个旋转角度一致的匹配对）
            List<MatchPair> inliers = new ArrayList<>();
            for (MatchPair pair : pairs) {
                double pairRotation = calculateRotationForPair(
                    pair, templateCx, templateCy, detectedCx, detectedCy);

                double angleDiff = pairRotation - rotationFromPair;
                // 归一化到 [-π, π]
                while (angleDiff > Math.PI) angleDiff -= 2 * Math.PI;
                while (angleDiff < -Math.PI) angleDiff += 2 * Math.PI;

                if (Math.abs(angleDiff) < angleThreshold) {
                    inliers.add(pair);
                }
            }

            // 4. 更新最佳模型
            if (inliers.size() > maxInliers) {
                maxInliers = inliers.size();
                bestInliers = inliers;
                bestRotation = rotationFromPair;
            }
        }

        // 检查是否有足够的内点
        double inlierRatio = (double) maxInliers / pairs.size();
        if (inlierRatio < requiredInlierRatio) {
            logger.info("RANSAC: 内点比例不足 (" + String.format("%.1f%%", inlierRatio * 100) + " < " + String.format("%.1f%%", requiredInlierRatio * 100) + ")，忽略旋转");
            return 0.0;
        }

        // 5. 用所有内点重新计算最终的旋转角度
        double finalRotation = calculateRotationFromInliers(bestInliers, templateCx, templateCy, detectedCx, detectedCy);
        double rotationDeg = Math.toDegrees(finalRotation);

        logger.info("RANSAC旋转计算: " + maxInliers + "/" + pairs.size() + " 个内点 (" + String.format("%.1f%%", inlierRatio * 100) + "), 旋转角度=" + String.format("%.2f", rotationDeg) + "°");

        // 只有当旋转角度足够大时才应用
        if (Math.abs(rotationDeg) < 0.5) {
            logger.info("旋转角度很小 (" + String.format("%.2f", rotationDeg) + "°)，忽略旋转");
            return 0.0;
        }

        return rotationDeg;
    }

    /**
     * 从两个匹配对计算旋转角度
     */
    private Double calculateRotationFromTwoPairs(MatchPair pair1, MatchPair pair2,
                                                  double templateCx, double templateCy,
                                                  double detectedCx, double detectedCy) {
        double rot1 = calculateRotationForPair(pair1, templateCx, templateCy, detectedCx, detectedCy);
        double rot2 = calculateRotationForPair(pair2, templateCx, templateCy, detectedCx, detectedCy);

        // 取平均值
        double avg = (rot1 + rot2) / 2.0;
        return avg;
    }

    /**
     * 计算单个匹配对的旋转角度
     */
    private double calculateRotationForPair(MatchPair pair,
                                             double templateCx, double templateCy,
                                             double detectedCx, double detectedCy) {
        // 模板点相对于模板质心的角度
        double templateDx = pair.templatePos.x - templateCx;
        double templateDy = pair.templatePos.y - templateCy;
        double templateAngle = Math.atan2(templateDy, templateDx);

        // 检测点相对于检测质心的角度
        double detectedDx = pair.detectedPos.x - detectedCx;
        double detectedDy = pair.detectedPos.y - detectedCy;
        double detectedAngle = Math.atan2(detectedDy, detectedDx);

        // 角度差（检测 - 模板）
        double angleDiff = detectedAngle - templateAngle;

        // 归一化到 [-π, π]
        while (angleDiff > Math.PI) angleDiff -= 2 * Math.PI;
        while (angleDiff < -Math.PI) angleDiff += 2 * Math.PI;

        return angleDiff;
    }

    /**
     * 从所有内点计算最终的旋转角度（中位数）
     */
    private double calculateRotationFromInliers(List<MatchPair> inliers,
                                                 double templateCx, double templateCy,
                                                 double detectedCx, double detectedCy) {
        List<Double> rotations = new ArrayList<>();
        for (MatchPair pair : inliers) {
            Double rot = calculateRotationForPair(pair, templateCx, templateCy, detectedCx, detectedCy);
            if (rot != null) {
                rotations.add(rot);
            }
        }
        return median(rotations);
    }

    /**
     * 第二步：应用粗略变换，找出可靠的匹配对
     * <p>
     * 优化的两阶段匹配策略：
     * 1. 第一阶段：收集所有可能的候选匹配对
     * 2. 第二阶段：基于距离分布和全局一致性，智能选择最佳匹配
     */
    private List<MatchPair> findReliableMatchPairs(
            Map<Integer, List<TemplateFeature>> templateFeaturesByClass,
            Map<Integer, List<DetectedObject>> detectedByClass,
            AffineTransform initialTransform) {

        // 计算检测对象数量，判断遮挡程度
        int totalDetected = 0;
        for (List<DetectedObject> list : detectedByClass.values()) {
            totalDetected += list.size();
        }

        int totalTemplate = 0;
        for (List<TemplateFeature> list : templateFeaturesByClass.values()) {
            totalTemplate += list.size();
        }

        // 遮挡率：被遮挡的特征比例
        double occlusionRate = 1.0 - (double) totalDetected / totalTemplate;

        // 使用固定的宽松阈值（500px），RANSAC会过滤离群点
        // 自适应阈值容易引入错误匹配
        double collectionThreshold = 500.0;

        logger.info("第二步匹配: 模板特征={}, 检测对象={}, 遮挡率={}%, 收集阈值={}",
            totalTemplate, totalDetected, (int)(occlusionRate * 100), (int)collectionThreshold);

        // 第一阶段：收集所有可能的候选匹配对
        List<CandidateMatch> allCandidates = new ArrayList<>();

        for (Map.Entry<Integer, List<TemplateFeature>> entry : templateFeaturesByClass.entrySet()) {
            int classId = entry.getKey();
            List<TemplateFeature> classTemplateFeatures = entry.getValue();
            List<DetectedObject> classDetected = detectedByClass.get(classId);

            if (classDetected == null || classDetected.isEmpty()) {
                continue;
            }

            // 为每个模板特征找最近的检测点（暂不标记为已匹配）
            for (TemplateFeature feature : classTemplateFeatures) {
                Point featurePos = feature.getPosition();
                DetectedObject nearest = null;
                double minDistance = Double.MAX_VALUE;

                for (DetectedObject obj : classDetected) {
                    // 应用逆向变换，将检测点变换到模板坐标系
                    Point transformedDetectedPos = initialTransform.applyInverse(obj.getCenter());

                    double dx = transformedDetectedPos.x - featurePos.x;
                    double dy = transformedDetectedPos.y - featurePos.y;
                    double distance = Math.sqrt(dx * dx + dy * dy);

                    if (distance < minDistance) {
                        minDistance = distance;
                        nearest = obj;
                    }
                }

                if (nearest != null && minDistance < collectionThreshold) {
                    allCandidates.add(new CandidateMatch(
                        feature, featurePos, nearest, nearest.getCenter(), minDistance));
                }
            }
        }

        if (allCandidates.isEmpty()) {
            logger.debug("第二步：没有找到任何候选匹配对");
            return new ArrayList<>();
        }

        // 第二阶段：基于距离分布智能选择匹配
        // 先计算所有候选的距离统计
        List<Double> distances = new ArrayList<>();
        for (CandidateMatch candidate : allCandidates) {
            distances.add(candidate.distance);
        }

        double medianDistance = median(distances);
        // 固定过滤阈值：超过中位数2倍的视为可疑
        double maxAcceptableDistance = medianDistance * 2.0;

        logger.info("第二步候选匹配统计: 总数={}, 中位数距离={}, 最大可接受距离={}",
            allCandidates.size(), String.format("%.1f", medianDistance),
            String.format("%.1f", maxAcceptableDistance));

        // 分类候选匹配：正常和可疑
        List<CandidateMatch> normalCandidates = new ArrayList<>();
        List<CandidateMatch> suspiciousCandidates = new ArrayList<>();

        for (CandidateMatch candidate : allCandidates) {
            if (candidate.distance <= maxAcceptableDistance) {
                normalCandidates.add(candidate);
            } else {
                suspiciousCandidates.add(candidate);
            }
        }

        logger.info("第二步候选分类: 正常={}, 可疑={}",
            normalCandidates.size(), suspiciousCandidates.size());

        // 首先处理正常候选：贪心选择，确保一个检测点只被匹配一次
        Set<DetectedObject> usedDetected = new HashSet<>();
        List<MatchPair> finalPairs = new ArrayList<>();

        // 按距离排序正常候选，优先选择距离小的
        normalCandidates.sort((a, b) -> Double.compare(a.distance, b.distance));

        for (CandidateMatch candidate : normalCandidates) {
            if (!usedDetected.contains(candidate.detectedObject)) {
                finalPairs.add(new MatchPair(candidate.templatePos, candidate.detectedPos));
                usedDetected.add(candidate.detectedObject);
                logger.debug("第二步匹配(正常): 模板特征 '{}' ({},{}) -> 检测位置({},{}), 距离={}",
                    candidate.feature.getName(),
                    (int)candidate.templatePos.x, (int)candidate.templatePos.y,
                    (int)candidate.detectedPos.x, (int)candidate.detectedPos.y,
                    String.format("%.1f", candidate.distance));
            }
        }

        // 然后处理可疑候选：只选择那些检测点未被使用的
        for (CandidateMatch candidate : suspiciousCandidates) {
            if (!usedDetected.contains(candidate.detectedObject)) {
                // 可疑匹配：距离不超过中位数3倍的接受
                double suspiciousThreshold = 3.0;
                if (candidate.distance < medianDistance * suspiciousThreshold) {
                    finalPairs.add(new MatchPair(candidate.templatePos, candidate.detectedPos));
                    usedDetected.add(candidate.detectedObject);
                    logger.info("第二步匹配(可疑但接受): 模板特征 '{}' ({},{}) -> 检测位置({},{}), 距离={} (中位数={})",
                        candidate.feature.getName(),
                        (int)candidate.templatePos.x, (int)candidate.templatePos.y,
                        (int)candidate.detectedPos.x, (int)candidate.detectedPos.y,
                        String.format("%.1f", candidate.distance),
                        String.format("%.1f", medianDistance));
                } else {
                    logger.info("第二步拒绝距离过大的匹配: 模板特征 '{}' ({},{}) -> 检测位置({},{}), 距离={} > 3x中位数({})",
                        candidate.feature.getName(),
                        (int)candidate.templatePos.x, (int)candidate.templatePos.y,
                        (int)candidate.detectedPos.x, (int)candidate.detectedPos.y,
                        String.format("%.1f", candidate.distance),
                        String.format("%.1f", medianDistance));
                }
            }
        }

        logger.debug("第二步共找到 {} 个可靠匹配对", finalPairs.size());
        return finalPairs;
    }

    /**
     * 候选匹配：用于两阶段匹配
     */
    private static class CandidateMatch {
        final TemplateFeature feature;
        final Point templatePos;
        final DetectedObject detectedObject;
        final Point detectedPos;
        final double distance;

        CandidateMatch(TemplateFeature feature, Point templatePos,
                       DetectedObject detectedObject, Point detectedPos, double distance) {
            this.feature = feature;
            this.templatePos = templatePos;
            this.detectedObject = detectedObject;
            this.detectedPos = detectedPos;
            this.distance = distance;
        }
    }

    /**
     * 匹配对：模板位置 + 检测位置
     */
    private static class MatchPair {
        final Point templatePos;
        final Point detectedPos;

        MatchPair(Point templatePos, Point detectedPos) {
            this.templatePos = templatePos;
            this.detectedPos = detectedPos;
        }
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
     * 为漏检特征查找标注位置
     * <p>
     * 策略：
     * 1. 优先使用全局变换（更可靠）
     * 2. 如果全局变换不可用，才使用锚点映射
     *
     * @param feature    漏检的模板特征
     * @param anchorMap   锚点 Map（模板特征 -> 锚点信息）
     * @param transform   全局变换
     */
    private Point findLocalAnnotationPositionUsingAnchors(TemplateFeature feature,
                                                          Map<TemplateFeature, AnchorPoint> anchorMap,
                                                          AffineTransform transform) {
        Point featurePos = feature.getPosition();

        // 优先使用全局变换（更可靠，不会受锚点偏差影响）
        if (transform != null) {
            Point globalPos = transform.applyForward(featurePos);
            logger.info("漏检特征 {} 使用全局变换 -> ({}, {})",
                feature.getId(), (int)globalPos.x, (int)globalPos.y);
            return globalPos;
        }

        // 降级：没有全局变换时使用锚点映射
        logger.warn("漏检特征 {} 无全局变换，使用模板坐标", feature.getId());
        return featurePos;
    }

    /**
     * 锚点：用于局部映射的合格特征点
     */
    private static class AnchorPoint {
        final Point templatePos;      // 模板位置
        final Point detectedPos;      // 检测位置
        final double offsetX;         // X偏移（检测位置 - 模板位置）
        final double offsetY;         // Y偏移（检测位置 - 模板位置）

        AnchorPoint(Point templatePos, Point detectedPos, double offsetX, double offsetY) {
            this.templatePos = templatePos;
            this.detectedPos = detectedPos;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
        }
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

    public int getMinReliablePairs() {
        return minReliablePairs;
    }

    public void setMinReliablePairs(int minReliablePairs) {
        this.minReliablePairs = minReliablePairs;
    }

    public double getMinInlierRatio() {
        return minInlierRatio;
    }

    public void setMinInlierRatio(double minInlierRatio) {
        this.minInlierRatio = minInlierRatio;
    }

    public double getMaxOcclusionRate() {
        return maxOcclusionRate;
    }

    public void setMaxOcclusionRate(double maxOcclusionRate) {
        this.maxOcclusionRate = maxOcclusionRate;
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
     * - templateCx, templateCy: 模板质心（旋转中心）
     * <p>
     * 旋转是绕模板质心进行的，不是绕原点。
     * <p>
     * 提供两个方向的变换方法：
     * - applyForward(): 模板 → 检测图（用于标注漏检位置）
     * - applyInverse(): 检测图 → 模板（用于匹配）
     */
    public static class AffineTransform {
        public final double tx;           // X方向平移
        public final double ty;           // Y方向平移
        public final double angle;        // 旋转角度（度）
        public final double templateCx;   // 模板质心 X（旋转中心）
        public final double templateCy;   // 模板质心 Y（旋转中心）

        public AffineTransform(double tx, double ty, double angle) {
            this(tx, ty, angle, 0.0, 0.0);
        }

        public AffineTransform(double tx, double ty, double angle, double templateCx, double templateCy) {
            this.tx = tx;
            this.ty = ty;
            this.angle = angle;
            this.templateCx = templateCx;
            this.templateCy = templateCy;
        }

        /**
         * 正向变换：模板坐标 → 检测图坐标
         * 用于标注漏检位置：在检测图上显示"模板这个位置应该有特征"
         * <p>
         * 变换步骤：
         * 1. 平移使模板质心在原点: (x - templateCx, y - templateCy)
         * 2. 绕原点旋转
         * 3. 加上平移量
         */
        public Point applyForward(Point p) {
            double rad = Math.toRadians(angle);
            double cos = Math.cos(rad);
            double sin = Math.sin(rad);

            // 正向变换：先平移到原点（相对质心），旋转，再平移回去
            double relX = p.x - templateCx;
            double relY = p.y - templateCy;

            double x = relX * cos - relY * sin + templateCx + tx;
            double y = relX * sin + relY * cos + templateCy + ty;

            return new Point(x, y);
        }

        /**
         * 逆向变换：检测图坐标 → 模板坐标
         * 用于匹配：将检测点变换到模板坐标系后比较
         * <p>
         * 逆向变换步骤：
         * 1. 减去平移量
         * 2. 平移使模板质心在原点
         * 3. 逆向旋转
         * 4. 平移回去
         */
        public Point applyInverse(Point p) {
            double rad = Math.toRadians(angle);
            double cos = Math.cos(rad);
            double sin = Math.sin(rad);

            // 逆向变换：先减去平移和模板质心，逆向旋转，再加上模板质心
            double dx = p.x - tx - templateCx;
            double dy = p.y - ty - templateCy;

            double x = dx * cos + dy * sin + templateCx;
            double y = -dx * sin + dy * cos + templateCy;

            return new Point(x, y);
        }

        @Override
        public String toString() {
            return String.format("AffineTransform[dx=%.2f, dy=%.2f, angle=%.2f°, center=(%.0f,%.0f)]",
                tx, ty, angle, templateCx, templateCy);
        }
    }
}
