package com.edge.vision.core.topology.croparea;

import com.edge.vision.core.quality.FeatureComparison;
import com.edge.vision.core.quality.InspectionResult;
import com.edge.vision.core.template.model.DetectedObject;
import com.edge.vision.core.template.model.Template;
import com.edge.vision.service.QualityStandardService;
import com.edge.vision.util.VisionTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 裁剪区域匹配器
 * <p>
 * 使用现有的 Template 类，从 metadata 中获取裁剪区域信息
 */
@Component
public class CropAreaMatcher {
    private static final Logger logger = LoggerFactory.getLogger(CropAreaMatcher.class);

    // 是否使用唯一匹配（每个检测点只匹配一个模板特征）
    private boolean useUniqueMatching = true;


    /**
     * 执行匹配（带实际裁剪尺寸）
     *
     * @param template         模板（从 metadata 中获取裁剪区域信息）
     * @param detectedObjects  在裁剪区域中检测到的对象（相对坐标）
     * @return 检测结果
     */
    public InspectionResult match(Template template,
                                  List<DetectedObject> detectedObjects,
                                  List<DetectedObject> templateObjects) {
        long startTime = System.currentTimeMillis();
        InspectionResult result = new InspectionResult(template.getTemplateId());

        logger.info("=== Crop Area Matching ===");
        logger.info("Template: {}", template.getTemplateId());
        logger.info("Detected objects: {}", detectedObjects.size());

        List<QualityStandardService.QualityEvaluationResult.TemplateComparison> comp = VisionTool.compareResults(templateObjects, detectedObjects,template.getToleranceX(),template.getToleranceY());
        for (QualityStandardService.QualityEvaluationResult.TemplateComparison templateComparison : comp) {
            FeatureComparison featureComparison=new FeatureComparison();
            BeanUtils.copyProperties(templateComparison,featureComparison);
            result.addComparison(featureComparison);

        }
        // 设置结果
        result.setProcessingTimeMs(System.currentTimeMillis() - startTime);
        setResultMessage(result, template.getFeatures().size(), comp.size());

        logger.info("=== Matching Complete: {} ===", result.getMessage());
        return result;
    }

    private void setResultMessage(InspectionResult result, int totalFeatures, int matchedCount) {
        InspectionResult.InspectionSummary summary = result.getSummary();
        boolean allPassed = summary.totalFeatures == summary.passed;
        boolean hasExtra = summary.extra > 0;
        boolean hasMissing = summary.missing > 0;
        boolean hasDeviation = summary.deviation > 0;
        if (allPassed && !hasExtra && !hasMissing && !hasDeviation) {
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
