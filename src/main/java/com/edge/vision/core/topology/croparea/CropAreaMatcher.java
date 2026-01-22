package com.edge.vision.core.topology.croparea;

import com.edge.vision.core.quality.FeatureComparison;
import com.edge.vision.core.quality.InspectionResult;
import com.edge.vision.core.template.model.DetectedObject;
import com.edge.vision.core.template.model.Point;
import com.edge.vision.core.template.model.Template;
import com.edge.vision.core.template.model.TemplateFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 裁剪区域匹配器
 * <p>
 * 使用现有的 Template 类，从 metadata 中获取裁剪区域信息
 * <p>
 * Template metadata 存储格式：
 * - cropWidth: 裁剪区域宽度
 * - cropHeight: 裁剪区域高度
 * - objectTemplatePath: 整体检测模板路径
 */
@Component
public class CropAreaMatcher {
    private static final Logger logger = LoggerFactory.getLogger(CropAreaMatcher.class);

    // 是否使用唯一匹配（每个检测点只匹配一个模板特征）
    private boolean useUniqueMatching = true;

    /**
     * 执行匹配
     *
     * @param template         模板（从 metadata 中获取裁剪区域信息）
     * @param detectedObjects  在裁剪区域中检测到的对象（相对坐标）
     * @return 检测结果
     */
    public InspectionResult match(Template template,
                                  List<DetectedObject> detectedObjects) {
        return match(template, detectedObjects, 0, 0);
    }

    /**
     * 执行匹配（带实际裁剪尺寸）
     *
     * @param template         模板（从 metadata 中获取裁剪区域信息）
     * @param detectedObjects  在裁剪区域中检测到的对象（相对坐标）
     * @param actualCropWidth  实际检测时的裁剪宽度
     * @param actualCropHeight 实际检测时的裁剪高度
     * @return 检测结果
     */
    public InspectionResult match(Template template,
                                  List<DetectedObject> detectedObjects,
                                  int actualCropWidth,
                                  int actualCropHeight) {
        long startTime = System.currentTimeMillis();
        InspectionResult result = new InspectionResult(template.getTemplateId());

        logger.info("=== Crop Area Matching ===");
        logger.info("Template: {}", template.getTemplateId());
        logger.info("Detected objects: {}", detectedObjects.size());

        // 调试：打印检测到的对象信息
        for (DetectedObject obj : detectedObjects) {
            logger.info("DetectedObject: classId={}, center=({},{}), size={}x{}",
                obj.getClassId(),
                String.format("%.1f", obj.getCenter().x),
                String.format("%.1f", obj.getCenter().y),
                String.format("%.1f", obj.getWidth()),
                String.format("%.1f", obj.getHeight()));
        }

        // 从 metadata 获取建模时的裁剪区域信息
        int templateCropWidth = getCropWidth(template);
        int templateCropHeight = getCropHeight(template);

        logger.info("Template crop size from metadata: {}x{}", templateCropWidth, templateCropHeight);

        // 如果没有 cropWidth/cropHeight，使用 imageSize
        if (templateCropWidth == 0 || templateCropHeight == 0) {
            if (template.getImageSize() != null) {
                templateCropWidth = template.getImageSize().getWidth();
                templateCropHeight = template.getImageSize().getHeight();
                logger.info("Using imageSize from template: {}x{}", templateCropWidth, templateCropHeight);
            }
        }

        // 计算缩放比例（如果提供了实际裁剪尺寸）
        double scaleX = 1.0;
        double scaleY = 1.0;
        if (actualCropWidth > 0 && actualCropHeight > 0) {
            logger.info("Actual crop size: {}x{}", actualCropWidth, actualCropHeight);
            scaleX = (double) actualCropWidth / templateCropWidth;
            scaleY = (double) actualCropHeight / templateCropHeight;
            logger.info("Scale factors: scaleX={}, scaleY={}",
                String.format("%.3f", scaleX),
                String.format("%.3f", scaleY));
        }

        Set<DetectedObject> matchedObjects = new HashSet<>();
        Set<String> matchedFeatures = new HashSet<>();

        for (TemplateFeature templateFeature : template.getFeatures()) {
            // 对模板特征坐标进行缩放（以适应实际裁剪尺寸）
            double scaledX = templateFeature.getPosition().x * scaleX;
            double scaledY = templateFeature.getPosition().y * scaleY;
            Point scaledPosition = new Point(scaledX, scaledY);

            // 创建缩放后的边界框
            TemplateFeature.BoundingBox scaledBbox = null;
            if (templateFeature.getBbox() != null) {
                scaledBbox = new TemplateFeature.BoundingBox(
                    templateFeature.getBbox().getX() * scaleX,
                    templateFeature.getBbox().getY() * scaleY,
                    templateFeature.getBbox().getWidth() * scaleX,
                    templateFeature.getBbox().getHeight() * scaleY
                );
            }

            // 找到最佳匹配的检测对象（基于类别和 IoU）
            DetectedObject bestMatch = null;
            double maxIoU = 0.0;  // IoU 范围 [0, 1]，越高越好

            // 根据是否有 bbox 决定阈值
            // 有 bbox: 使用严格的 IoU 阈值
            // 无 bbox: 使用更宽松的阈值（因为是距离近似）
            final double IOU_THRESHOLD = (scaledBbox != null) ? 0.25 : 0.15;

            for (DetectedObject obj : detectedObjects) {
                // 检查类别是否匹配
                if (templateFeature.getClassId() != obj.getClassId()) {
                    continue;
                }

                // 检查是否已匹配
                if (useUniqueMatching && matchedObjects.contains(obj)) {
                    continue;
                }

                // 计算 IoU（交并比）
                double iou = 0.0;
                if (scaledBbox != null && obj.getBbox() != null) {
                    iou = scaledBbox.iou(obj.getBbox());
                } else {
                    // 降级到中心点距离匹配（向后兼容，使用缩放后的坐标）
                    double dx = obj.getCenter().x - scaledPosition.x;
                    double dy = obj.getCenter().y - scaledPosition.y;
                    double distance = Math.sqrt(dx * dx + dy * dy);
                    // 距离转 IoU 的简单近似（仅用于兼容）
                    iou = Math.max(0, 1.0 - distance / 100.0);
                }

                if (iou > maxIoU) {
                    maxIoU = iou;
                    bestMatch = obj;
                }
            }

            // 判断是否匹配成功
            if (bestMatch != null && maxIoU >= IOU_THRESHOLD) {
                matchedObjects.add(bestMatch);
                matchedFeatures.add(templateFeature.getId());

                Point detectedPos = bestMatch.getCenter();
                // 使用缩放后的模板位置
                double xError = Math.abs(detectedPos.x - scaledPosition.x);
                double yError = Math.abs(detectedPos.y - scaledPosition.y);

                boolean withinTolerance = (xError <= templateFeature.getTolerance().getX() &&
                                          yError <= templateFeature.getTolerance().getY());

                FeatureComparison comp = new FeatureComparison(templateFeature.getId(), templateFeature.getName());
                comp.setTemplatePosition(scaledPosition);  // 使用缩放后的位置
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

                logger.debug("MATCHED: {} -> IoU={}, detected=({},{}) [expected=({},{})], xErr={}, yErr={}",
                    templateFeature.getId(),
                    String.format("%.3f", maxIoU),
                    (int)detectedPos.x, (int)detectedPos.y,
                    (int)scaledPosition.x, (int)scaledPosition.y,
                    String.format("%.1f", xError),
                    String.format("%.1f", yError));

            } else {
                // 漏检或 IoU 不达标
                // 使用缩放后的模板位置

                FeatureComparison comp = new FeatureComparison(templateFeature.getId(), templateFeature.getName());
                comp.setTemplatePosition(scaledPosition);
                comp.setDetectedPosition(scaledPosition);
                comp.setToleranceX(templateFeature.getTolerance().getX());
                comp.setToleranceY(templateFeature.getTolerance().getY());
                comp.setStatus(FeatureComparison.ComparisonStatus.MISSING);
                comp.setWithinTolerance(false);
                comp.setClassId(templateFeature.getClassId());
                comp.setClassName(templateFeature.getName());

                result.addComparison(comp);

                if (bestMatch != null) {
                    // 重要：即使 IoU 不达标，也要标记为已匹配，避免被标记为 EXTRA
                    matchedObjects.add(bestMatch);
                    logger.warn("LOW_IOU: {} -> IoU={} < {}, detected=({},{}) [expected=({},{})]",
                        templateFeature.getId(),
                        String.format("%.3f", maxIoU),
                        String.format("%.3f", IOU_THRESHOLD),
                        (int)bestMatch.getCenter().x, (int)bestMatch.getCenter().y,
                        (int)scaledPosition.x, (int)scaledPosition.y);
                } else {
                    logger.info("MISSING: {}, expected at ({}, {})",
                        templateFeature.getId(), (int)scaledPosition.x, (int)scaledPosition.y);
                }
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
        setResultMessage(result, template.getFeatures().size(), matchedFeatures.size());

        logger.info("=== Matching Complete: {} ===", result.getMessage());
        return result;
    }

    private void setResultMessage(InspectionResult result, int totalFeatures, int matchedCount) {
        InspectionResult.InspectionSummary summary = result.getSummary();
        boolean allPassed = summary.totalFeatures == summary.passed;

        if (allPassed) {
            result.setPassed(true);
            result.setMessage(String.format("区域匹配通过 - %d/%d 特征匹配成功",
                matchedCount, totalFeatures));
        } else {
            result.setPassed(false);
            result.setMessage(String.format("区域匹配失败 - %d个通过, %d个漏检, %d个偏差, %d个错检",
                summary.passed, summary.missing, summary.deviation, summary.extra));
        }
    }

    // 从 Template metadata 获取裁剪区域信息
    private int getCropWidth(Template template) {
        Object width = template.getMetadata().get("cropWidth");
        return width instanceof Integer ? (Integer) width : 0;
    }

    private int getCropHeight(Template template) {
        Object height = template.getMetadata().get("cropHeight");
        return height instanceof Integer ? (Integer) height : 0;
    }

    private String getObjectTemplatePath(Template template) {
        Object path = template.getMetadata().get("objectTemplatePath");
        return path != null ? path.toString() : null;
    }

    public boolean isUseUniqueMatching() {
        return useUniqueMatching;
    }

    public void setUseUniqueMatching(boolean useUniqueMatching) {
        this.useUniqueMatching = useUniqueMatching;
    }
}
