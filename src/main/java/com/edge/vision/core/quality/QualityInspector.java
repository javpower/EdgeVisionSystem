package com.edge.vision.core.quality;

import com.edge.vision.config.YamlConfig;
import com.edge.vision.core.template.model.DetectedObject;
import com.edge.vision.core.template.model.Template;
import com.edge.vision.core.template.model.Point;
import com.edge.vision.core.topology.CoordinateBasedMatcher;
import com.edge.vision.core.topology.TopologyTemplateMatcher;
import com.edge.vision.core.topology.fourcorner.*;
import org.opencv.core.Mat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 质量检测器
 * <p>
 * 支持三种匹配策略：
 * 1. 拓扑图匹配（topology）：基于拓扑关系（相对角度、相对距离比），使用匈牙利算法进行全局最优匹配
 * 2. 坐标直接匹配（coordinate）：每个模板特征找最近的检测点，一对一关系明确
 * 3. 四角匹配（fourcorner）：利用工件四角作为世界坐标系，通过仿射不变量（数字指纹）匹配
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

    @Autowired
    private FourCornerMatcher fourCornerMatcher;

    @Autowired
    private FourCornerDetector fourCornerDetector;

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
        logger.info("Starting quality inspection with strategy: {}", strategy);

        // 配置匹配器参数
        configureMatchers();

        try {
            InspectionResult result;

            if (strategy == MatchStrategy.COORDINATE) {
                // 使用坐标直接匹配
                result = coordinateBasedMatcher.match(template, detectedObjects);
                result.setMatchStrategy(MatchStrategy.COORDINATE);
            } else {
                // 使用拓扑图匹配（默认）
                // 注意：四角匹配使用专门的 inspectWithFourCorners() 方法
                result = topologyTemplateMatcher.match(template, detectedObjects);
                result.setMatchStrategy(MatchStrategy.TOPOLOGY);
            }

            logger.info("Inspection completed: {}", result.getMessage());
            return result;

        } catch (Exception e) {
            logger.error("Error during inspection", e);
            return createErrorResult(template, "检测过程出错: " + e.getMessage());
        }
    }

    /**
     * 使用四角匹配策略执行检测（提供检测到的四角）
     * <p>
     * 这是推荐的四角匹配接口
     *
     * @param template         模板
     * @param detectedObjects  检测到的对象
     * @param detectedCorners  检测到的四角坐标 [TL, TR, BR, BL]
     * @return 检测结果
     */
    public InspectionResult inspectWithFourCorners(Template template,
                                                   List<DetectedObject> detectedObjects,
                                                   Point[] detectedCorners) {
        logger.info("Starting four-corner inspection with {} objects", detectedObjects.size());

        // 配置匹配器参数
        configureMatchers();

        try {
            // 从 Template 提取四角坐标
            Point[] templateCorners = extractCornersFromTemplate(template);

            if (templateCorners == null) {
                logger.error("Template does not have four corners defined");
                return createErrorResult(template, "模板未定义四角坐标");
            }

            // 构建 FourCornerTemplate
            FourCornerTemplate fourCornerTemplate = buildFourCornerTemplate(template, templateCorners);

            // 执行匹配
            InspectionResult result = fourCornerMatcher.match(fourCornerTemplate, detectedCorners, detectedObjects);
            result.setMatchStrategy(MatchStrategy.FOUR_CORNER);

            return result;

        } catch (Exception e) {
            logger.error("Error during four-corner inspection", e);
            return createErrorResult(template, "四角匹配出错: " + e.getMessage());
        }
    }

    /**
     * 使用四角匹配策略执行检测（提供图像，自动检测四角）
     * <p>
     * 自动从图像中检测四角
     *
     * @param template         模板
     * @param detectedObjects  检测到的对象
     * @param image            检测图像（用于检测四角）
     * @return 检测结果
     */
    public InspectionResult inspectWithFourCornersAuto(Template template,
                                                       List<DetectedObject> detectedObjects,
                                                       Mat image) {
        logger.info("Starting four-corner inspection with auto corner detection");

        // 配置匹配器参数
        configureMatchers();

        try {
            // 从 Template 提取四角坐标
            Point[] templateCorners = extractCornersFromTemplate(template);

            if (templateCorners == null) {
                logger.error("Template does not have four corners defined");
                return createErrorResult(template, "模板未定义四角坐标");
            }

            // 自动检测四角
            Point[] detectedCorners = fourCornerDetector.detectCorners(image);

            if (detectedCorners == null) {
                logger.error("Failed to detect corners from image");
                return createErrorResult(template, "无法从图像中检测四角");
            }

            logger.info("Auto-detected corners: TL={}, TR={}, BR={}, BL={}",
                detectedCorners[0], detectedCorners[1], detectedCorners[2], detectedCorners[3]);

            // 构建 FourCornerTemplate
            FourCornerTemplate fourCornerTemplate = buildFourCornerTemplate(template, templateCorners);

            // 执行匹配
            InspectionResult result = fourCornerMatcher.match(fourCornerTemplate, detectedCorners, detectedObjects);
            result.setMatchStrategy(MatchStrategy.FOUR_CORNER);

            return result;

        } catch (Exception e) {
            logger.error("Error during four-corner inspection with auto detection", e);
            return createErrorResult(template, "四角匹配出错: " + e.getMessage());
        }
    }

    /**
     * 从 Template 的 metadata 中提取四角坐标
     */
    private Point[] extractCornersFromTemplate(Template template) {
        Object cornersObj = template.getMetadata().get("fourCorners");

        if (cornersObj == null) {
            return null;
        }

        // 期望格式：[[x1, y1], [x2, y2], [x3, y3], [x4, y4]]
        try {
            @SuppressWarnings("unchecked")
            List<List<Double>> cornersList = (List<List<Double>>) cornersObj;

            if (cornersList.size() != 4) {
                logger.error("Invalid corners count: {}, expected 4", cornersList.size());
                return null;
            }

            Point[] corners = new Point[4];
            for (int i = 0; i < 4; i++) {
                List<Double> corner = cornersList.get(i);
                if (corner.size() != 2) {
                    logger.error("Invalid corner format at index {}: {}", i, corner);
                    return null;
                }
                corners[i] = new Point(corner.get(0), corner.get(1));
            }

            return corners;

        } catch (Exception e) {
            logger.error("Failed to parse corners from metadata", e);
            return null;
        }
    }

    /**
     * 从普通 Template 构建 FourCornerTemplate
     */
    private FourCornerTemplate buildFourCornerTemplate(Template template, Point[] corners) {
        FourCornerTemplateBuilder builder = new FourCornerTemplateBuilder();

        // 构建特征定义列表
        List<FourCornerTemplateBuilder.FeatureDefinition> features = new java.util.ArrayList<>();

        for (com.edge.vision.core.template.model.TemplateFeature feature : template.getFeatures()) {
            if (!feature.isRequired()) {
                continue;
            }

            Point tolerance = new Point(
                feature.getTolerance() != null ? feature.getTolerance().getX() : template.getToleranceX(),
                feature.getTolerance() != null ? feature.getTolerance().getY() : template.getToleranceY()
            );

            FourCornerTemplateBuilder.FeatureDefinition def =
                new FourCornerTemplateBuilder.FeatureDefinition(
                    feature.getId(),
                    feature.getName(),
                    feature.getClassId(),
                    feature.getName(),  // Use name as className since TemplateFeature doesn't have className
                    feature.getPosition(),
                    true,  // required
                    tolerance
                );

            features.add(def);
        }

        return builder.buildFromFeatures(template.getTemplateId(), corners, features);
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

        // 配置四角匹配器
        fourCornerMatcher.setFingerprintTolerance(config.getFingerprintTolerance() != null ?
            config.getFingerprintTolerance() : 0.5);
        fourCornerMatcher.setUseUniqueMatching(true);
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
