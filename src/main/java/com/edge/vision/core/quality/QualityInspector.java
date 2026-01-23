package com.edge.vision.core.quality;

import com.edge.vision.config.YamlConfig;
import com.edge.vision.core.template.model.DetectedObject;
import com.edge.vision.core.template.model.Template;
import com.edge.vision.core.topology.CoordinateBasedMatcher;
import com.edge.vision.core.topology.TopologyTemplateMatcher;
import com.edge.vision.core.topology.croparea.CropAreaMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 质量检测器
 * <p>
 * 支持两种匹配策略：
 * 1. 拓扑图匹配（topology）：基于拓扑关系（相对角度、相对距离比），使用匈牙利算法进行全局最优匹配
 * 2. 坐标直接匹配（coordinate）：每个模板特征找最近的检测点，一对一关系明确
 */
@Component
public class QualityInspector {
    private static final Logger logger = LoggerFactory.getLogger(QualityInspector.class);

    @Autowired
    private com.edge.vision.core.template.TemplateManager templateManager;

    @Autowired
    private TopologyTemplateMatcher topologyTemplateMatcher;

    @Autowired
    private CoordinateBasedMatcher coordinateBasedMatcher;

    @Autowired(required = false)
    private CropAreaMatcher cropAreaMatcher;

    @Autowired
    private YamlConfig yamlConfig;

    /**
     * 执行质量检测（使用当前模板）
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
     * 执行质量检测（使用配置的匹配策略）
     *
     * @param template        模板
     * @param detectedObjects YOLO 检测到的对象列表
     * @return 检测结果
     */
    public InspectionResult inspect(Template template, List<DetectedObject> detectedObjects) {
        // 从配置获取匹配策略
        MatchStrategy strategy = yamlConfig.getInspection().getMatchStrategy();
        if (strategy == null) {
            strategy = MatchStrategy.TOPOLOGY;  // 默认使用拓扑匹配
        }

        return inspect(template, detectedObjects, strategy);
    }

    /**
     * 使用指定策略执行质量检测
     *
     * @param template        模板
     * @param detectedObjects YOLO 检测到的对象列表
     * @param strategy        匹配策略
     * @return 检测结果
     */
    public InspectionResult inspect(Template template, List<DetectedObject> detectedObjects, MatchStrategy strategy) {
        return inspect(template, detectedObjects, strategy, 0, 0);
    }

    /**
     * 使用指定策略执行质量检测（带实际裁剪尺寸）
     *
     * @param template         模板
     * @param detectedObjects  YOLO 检测到的对象列表
     * @param strategy         匹配策略
     * @param actualCropWidth  实际检测时的裁剪宽度
     * @param actualCropHeight 实际检测时的裁剪高度
     * @return 检测结果
     */
    public InspectionResult inspect(Template template, List<DetectedObject> detectedObjects, MatchStrategy strategy,
                                    int actualCropWidth, int actualCropHeight) {
        logger.info("Starting quality inspection with strategy: {}", strategy);
        if (actualCropWidth > 0 && actualCropHeight > 0) {
            logger.info("Actual crop dimensions: {}x{}", actualCropWidth, actualCropHeight);
        }

        // 配置匹配器参数
        configureMatchers();

        try {
            InspectionResult result;

            if (strategy == MatchStrategy.COORDINATE) {
                // 使用坐标直接匹配
                result = coordinateBasedMatcher.match(template, detectedObjects);
                result.setMatchStrategy(MatchStrategy.COORDINATE);
            } else if(strategy==MatchStrategy.TOPOLOGY){
                // 使用拓扑图匹配（默认）
                result = topologyTemplateMatcher.match(template, detectedObjects);
                result.setMatchStrategy(MatchStrategy.TOPOLOGY);
            }else {
                // CROP_AREA 策略：传递实际裁剪尺寸用于坐标归一化
                result=cropAreaMatcher.match(template, detectedObjects, actualCropWidth, actualCropHeight);
                result.setMatchStrategy(MatchStrategy.CROP_AREA);
//                result = coordinateBasedMatcher.match(template, detectedObjects);
//                result.setMatchStrategy(MatchStrategy.COORDINATE);
            }

            logger.info("Inspection completed: {}", result.getMessage());
            return result;

        } catch (Exception e) {
            logger.error("Error during inspection", e);
            return createErrorResult(template, "检测过程出错: " + e.getMessage());
        }
    }

    /**
     * 从配置中设置匹配器参数
     */
    private void configureMatchers() {
        YamlConfig.InspectionConfig config = yamlConfig.getInspection();

        // 配置坐标匹配器
        coordinateBasedMatcher.setMatchDistanceThreshold(config.getMaxMatchDistance());
        coordinateBasedMatcher.setTreatExtraAsError(config.isTreatExtraAsError());

        // 配置拓扑匹配器
        topologyTemplateMatcher.setTreatExtraAsError(config.isTreatExtraAsError());
    }

    /**
     * 创建错误结果
     */
    private InspectionResult createErrorResult(Template template, String message) {
        InspectionResult result = new InspectionResult(template.getTemplateId());
        result.setPassed(false);
        result.setMessage(message);
        return result;
    }

    /**
     * 获取模板管理器
     */
    public com.edge.vision.core.template.TemplateManager getTemplateManager() {
        return templateManager;
    }

    /**
     * 获取拓扑模板匹配器
     */
    public TopologyTemplateMatcher getTopologyTemplateMatcher() {
        return topologyTemplateMatcher;
    }

    /**
     * 获取坐标匹配器
     */
    public CoordinateBasedMatcher getCoordinateBasedMatcher() {
        return coordinateBasedMatcher;
    }
}
