package com.edge.vision.core.topology.croparea;

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
 * 裁剪区域匹配器
 * <p>
 * 模板坐标和检测坐标都在裁剪截图的坐标系中，直接比对即可
 */
@Component
public class CropAreaMatcher {
    private static final Logger logger = LoggerFactory.getLogger(CropAreaMatcher.class);

    // 是否使用唯一匹配（每个检测点只匹配一个模板特征）
    private boolean useUniqueMatching = true;

    /**
     * 执行匹配
     *
     * @param template         裁剪区域模板
     * @param detectedObjects  在裁剪区域中检测到的对象（相对坐标）
     * @return 检测结果
     */
    public InspectionResult match(CropAreaTemplate template,
                                  List<DetectedObject> detectedObjects) {
        long startTime = System.currentTimeMillis();
        InspectionResult result = new InspectionResult(template.getTemplateId());

        logger.info("=== Crop Area Matching ===");
        logger.info("Template: {}", template);
        logger.info("Detected objects: {}", detectedObjects.size());

        Set<DetectedObject> matchedObjects = new HashSet<>();
        Set<String> matchedFeatures = new HashSet<>();

        for (TemplateFeature templateFeature : template.getFeatures()) {
            // 找到最佳匹配的检测点（基于类别和距离）
            DetectedObject bestMatch = null;
            double minDistance = Double.MAX_VALUE;

            for (DetectedObject obj : detectedObjects) {
                // 检查类别是否匹配
                if (templateFeature.getClassId() != obj.getClassId()) {
                    continue;
                }

                // 检查是否已匹配
                if (useUniqueMatching && matchedObjects.contains(obj)) {
                    continue;
                }

                // 计算欧氏距离（在同一坐标系下）
                double dx = obj.getCenter().x - templateFeature.getPosition().x;
                double dy = obj.getCenter().y - templateFeature.getPosition().y;
                double distance = Math.sqrt(dx * dx + dy * dy);

                if (distance < minDistance) {
                    minDistance = distance;
                    bestMatch = obj;
                }
            }

            // 判断是否匹配成功
            if (bestMatch != null) {
                matchedObjects.add(bestMatch);
                matchedFeatures.add(templateFeature.getId());

                Point detectedPos = bestMatch.getCenter();
                Point templatePos = templateFeature.getPosition();
                double xError = Math.abs(detectedPos.x - templatePos.x);
                double yError = Math.abs(detectedPos.y - templatePos.y);

                boolean withinTolerance = (xError <= templateFeature.getTolerance().getX() &&
                                          yError <= templateFeature.getTolerance().getY());

                FeatureComparison comp = new FeatureComparison(templateFeature.getId(), templateFeature.getName());
                comp.setTemplatePosition(templatePos);
                comp.setDetectedPosition(detectedPos);
                comp.setXError(xError);
                comp.setYError(yError);
                comp.setTotalError(Math.sqrt(xError * xError + yError * yError));
                comp.setToleranceX(templateFeature.getTolerance().getX());
                comp.setToleranceY(templateFeature.getTolerance().getY());
                comp.setWithinTolerance(withinTolerance);
                comp.setStatus(withinTolerance ?
                    FeatureComparison.ComparisonStatus.PASSED :
                    FeatureComparison.ComparisonStatus.DEVIATION_EXCEEDED);
                comp.setClassId(templateFeature.getClassId());
                comp.setClassName(templateFeature.getName());
                comp.setConfidence(bestMatch.getConfidence());

                result.addComparison(comp);

                logger.debug("MATCHED: {} -> detected=({},{}) [expected=({},{})], dist={}, xErr={}, yErr={}",
                    templateFeature.getId(),
                    (int)detectedPos.x, (int)detectedPos.y,
                    (int)templatePos.x, (int)templatePos.y,
                    String.format("%.1f", minDistance),
                    String.format("%.1f", xError),
                    String.format("%.1f", yError));

            } else {
                // 漏检
                Point templatePos = templateFeature.getPosition();

                FeatureComparison comp = new FeatureComparison(templateFeature.getId(), templateFeature.getName());
                comp.setTemplatePosition(templatePos);
                comp.setDetectedPosition(templatePos);
                comp.setToleranceX(templateFeature.getTolerance().getX());
                comp.setToleranceY(templateFeature.getTolerance().getY());
                comp.setStatus(FeatureComparison.ComparisonStatus.MISSING);
                comp.setWithinTolerance(false);
                comp.setClassId(templateFeature.getClassId());
                comp.setClassName(templateFeature.getName());

                result.addComparison(comp);

                logger.info("MISSING: {}, expected at ({}, {})",
                    templateFeature.getId(), (int)templatePos.x, (int)templatePos.y);
            }
        }

        // 处理未匹配的检测对象 -> 错检
        for (DetectedObject obj : detectedObjects) {
            if (!matchedObjects.contains(obj)) {
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

    private void setResultMessage(InspectionResult result, CropAreaTemplate template, int matchedCount) {
        InspectionResult.InspectionSummary summary = result.getSummary();
        boolean allPassed = summary.totalFeatures == summary.passed;

        if (allPassed) {
            result.setPassed(true);
            result.setMessage(String.format("区域匹配通过 - %d/%d 特征匹配成功",
                matchedCount, template.getFeatureCount()));
        } else {
            result.setPassed(false);
            result.setMessage(String.format("区域匹配失败 - %d个通过, %d个漏检, %d个偏差, %d个错检",
                summary.passed, summary.missing, summary.deviation, summary.extra));
        }
    }

    public boolean isUseUniqueMatching() {
        return useUniqueMatching;
    }

    public void setUseUniqueMatching(boolean useUniqueMatching) {
        this.useUniqueMatching = useUniqueMatching;
    }
}
