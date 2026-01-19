package com.edge.vision.core.topology;

import com.edge.vision.core.quality.FeatureComparison;
import com.edge.vision.core.quality.InspectionResult;
import com.edge.vision.core.template.model.DetectedObject;
import com.edge.vision.core.template.model.Template;
import com.edge.vision.core.template.model.TemplateFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 基于拓扑图匹配的模板比对器
 * <p>
 * 核心改进：
 * 1. 使用拓扑图结构代替绝对坐标匹配
 * 2. 使用匈牙利算法进行全局最优匹配
 * 3. 完全支持旋转、平移、尺度变化
 * 4. 高精度处理重复特征（如多个孔、螺丝）
 * <p>
 * 匹配流程：
 * 1. 构建模板拓扑图和检测拓扑图
 * 2. 使用匈牙利算法求解最优一一对应关系
 * 3. 基于拓扑相似度进行全局一致性验证
 * 4. 生成详细的质检报告
 */
@Component
public class TopologyTemplateMatcher {
    private static final Logger logger = LoggerFactory.getLogger(TopologyTemplateMatcher.class);

    // 拓扑匹配器
    private final TopologyMatcher topologyMatcher;

    // 是否将未在模板中定义的检测对象视为错检（默认 true：检测数量超过模板数量视为不合格）
    private boolean treatExtraAsError = true;

    public TopologyTemplateMatcher() {
        this.topologyMatcher = new TopologyMatcher();
    }

    /**
     * 执行模板比对（拓扑图版本）
     *
     * @param template       模板
     * @param detectedObjects 检测到的对象（已在模板坐标系中）
     * @return 比对结果
     */
    public InspectionResult match(Template template, List<DetectedObject> detectedObjects) {
        logger.info("拓扑图匹配: 模板{} vs {} 个检测对象",
            template.getTemplateId(), detectedObjects.size());

        long startTime = System.currentTimeMillis();
        InspectionResult result = new InspectionResult(template.getTemplateId());

        // 从模板读取配置参数
        double toleranceX = template.getToleranceX();
        double toleranceY = template.getToleranceY();
        double topologyThreshold = template.getTopologyThreshold();

        // 配置拓扑匹配器参数
        topologyMatcher.setToleranceX(toleranceX);
        topologyMatcher.setToleranceY(toleranceY);
        topologyMatcher.setTopologyThreshold(topologyThreshold);

        logger.debug("模板配置: toleranceX={}, toleranceY={}, topologyThreshold={}",
            String.format("%.1f", toleranceX),
            String.format("%.1f", toleranceY),
            String.format("%.2f", topologyThreshold));

        // 1. 构建拓扑图（使用模板中的k值）
        TopologyGraph templateGraph = TopologyGraph.fromTemplate(template);
        TopologyGraph detectedGraph = TopologyGraph.fromDetectedObjects(
            detectedObjects,
            "detected_" + template.getTemplateId(),
            template.getTopologyK()
        );

        logger.debug("拓扑图构建完成: {}", templateGraph);
        logger.debug("检测图构建完成: {}", detectedGraph);

        // 2. 执行拓扑匹配
        TopologyMatcher.MatchResult matchResult = topologyMatcher.match(templateGraph, detectedGraph);

        // 3. 验证全局一致性
        List<TopologyMatcher.MatchPair> suspiciousMatches =
            topologyMatcher.validateGlobalConsistency(matchResult);

        // 4. 生成特征比对结果
        processMatches(matchResult, suspiciousMatches, result, template, detectedObjects);

        // 5. 处理未匹配的检测对象（误检）
        if (treatExtraAsError) {
            processUnmatchedDetected(matchResult, result, detectedObjects);
        }

        // 6. 设置处理时间和消息
        result.setProcessingTimeMs(System.currentTimeMillis() - startTime);
        setResultMessage(result, matchResult);

        logger.info("拓扑图匹配完成: {}", result.getMessage());
        return result;
    }

    /**
     * 处理匹配结果，生成FeatureComparison
     */
    private void processMatches(TopologyMatcher.MatchResult matchResult,
                                List<TopologyMatcher.MatchPair> suspiciousMatches,
                                InspectionResult result,
                                Template template,
                                List<DetectedObject> detectedObjects) {
        // 创建检测对象的索引映射（用于快速查找）
        Map<String, Integer> detectedIndexMap = new HashMap<>();
        for (int i = 0; i < detectedObjects.size(); i++) {
            DetectedObject obj = detectedObjects.get(i);
            // 检测节点ID格式: "detected_<index>"
            detectedIndexMap.put("detected_" + i, i);
        }

        // 第一步：计算全局坐标偏移（用于校正不同坐标系之间的差异）
        double[] globalOffset = calculateGlobalOffset(matchResult);

        if (globalOffset != null) {
            logger.info("检测到全局坐标偏移: dx={}, dy={}",
                String.format("%.1f", globalOffset[0]),
                String.format("%.1f", globalOffset[1]));
        }

        // 处理每个匹配对
        for (TopologyMatcher.MatchPair pair : matchResult.getMatches()) {
            TopologyNode templateNode = pair.getTemplateNode();
            TopologyNode detectedNode = pair.getDetectedNode();
            double cost = pair.getCost();

            // 获取模板特征
            TemplateFeature feature = templateNode.getTemplateFeature();
            if (feature == null) {
                logger.warn("模板节点 {} 没有关联的TemplateFeature", templateNode.getNodeId());
                continue;
            }

            // 检查匹配是否有效
            boolean isSuspicious = suspiciousMatches.contains(pair);
            boolean isValid = pair.isValid();

            if (!isValid) {
                // 代价太高，视为未匹配
                if (feature.isRequired()) {
                    FeatureComparison comp = FeatureComparison.missing(feature);
                    comp.setClassName(detectedNode.getClassLabel());
                    result.addComparison(comp);
                    logger.debug("模板特征 {} 匹配代价过高({})，视为漏检",
                        feature.getId(), String.format("%.3f", cost));

                    // 同时把检测对象标记为 EXTRA（如果开启了该选项）
                    if (treatExtraAsError) {
                        int index = extractIndexFromId(detectedNode.getNodeId());
                        if (index >= 0 && index < detectedObjects.size()) {
                            DetectedObject extraObj = detectedObjects.get(index);
                            FeatureComparison extraComp = FeatureComparison.extra(
                                "extra_" + index,
                                extraObj.getClassName() != null ? extraObj.getClassName() : "多余特征_" + index,
                                extraObj.getCenter(),
                                extraObj.getClassId(),
                                extraObj.getConfidence()
                            );
                            extraComp.setClassName(extraObj.getClassName());
                            result.addComparison(extraComp);
                            logger.info("误检: 检测出{} at ({}, {}), 模板中无对应特征",
                                extraObj.getClassName(), (int)extraObj.getCenter().x, (int)extraObj.getCenter().y);
                        }
                    }
                }
                continue;
            }

            // 获取检测对象位置（当前图像坐标系）
            com.edge.vision.core.template.model.Point detectedPos =
                new com.edge.vision.core.template.model.Point(
                    detectedNode.getX(), detectedNode.getY());
            com.edge.vision.core.template.model.Point featurePos = feature.getPosition();

            // 应用全局偏移校正：将检测位置"映射"到模板坐标系进行误差计算
            double correctedDetectedX = detectedPos.x;
            double correctedDetectedY = detectedPos.y;

            if (globalOffset != null) {
                // 校正：检测位置 - 全局偏移 = 模板坐标系中的预期位置
                correctedDetectedX = detectedPos.x - globalOffset[0];
                correctedDetectedY = detectedPos.y - globalOffset[1];
            }

            // 计算位置误差（使用校正后的位置）
            double xError = Math.abs(correctedDetectedX - featurePos.x);
            double yError = Math.abs(correctedDetectedY - featurePos.y);
            double distanceError = Math.sqrt(xError * xError + yError * yError);

            // 创建比对结果
            FeatureComparison comp;
            if (isSuspicious) {
                // 可疑匹配（拓扑一致性差）
                comp = FeatureComparison.deviation(feature, detectedPos, xError, yError);
                comp.setStatus(FeatureComparison.ComparisonStatus.SUSPICIOUS);
                comp.setClassName(detectedNode.getClassLabel());
                logger.info("可疑匹配: 模板{} -> 检测{}, 代价={}, 距离误差={}",
                    feature.getId(), detectedNode.getNodeId(),
                    String.format("%.3f", cost),
                    String.format("%.2f", distanceError));
            } else if (xError <= feature.getTolerance().getX() &&
                       yError <= feature.getTolerance().getY()) {
                // 在容差范围内
                comp = FeatureComparison.passed(feature, detectedPos, xError, yError);
                comp.setClassName(detectedNode.getClassLabel());
                logger.debug("匹配成功: 模板{} -> 检测{}, 代价={}, 误差=({},{})",
                    feature.getId(), detectedNode.getNodeId(),
                    String.format("%.3f", cost),
                    String.format("%.1f,%.1f", xError, yError));
            } else {
                // 超出容差
                comp = FeatureComparison.deviation(feature, detectedPos, xError, yError);
                comp.setClassName(detectedNode.getClassLabel());
                logger.debug("超出容差: 模板{} -> 检测{}, x误差={}, y误差={}",
                    feature.getId(), detectedNode.getNodeId(),
                    String.format("%.2f", xError),
                    String.format("%.2f", yError));
            }

            result.addComparison(comp);
        }

        // 处理未匹配的模板节点（漏检）
        for (TopologyNode unmatchedNode : matchResult.getUnmatchedTemplateNodes()) {
            TemplateFeature feature = unmatchedNode.getTemplateFeature();
            if (feature != null && feature.isRequired()) {
                FeatureComparison comp = FeatureComparison.missing(feature);
                // 尝试从检测对象中获取类别名称
                String className = detectedObjects.stream()
                    .filter(obj -> obj.getClassId() == feature.getClassId())
                    .map(DetectedObject::getClassName)
                    .findFirst()
                    .orElse(null);
                if (className != null) {
                    comp.setClassName(className);
                }
                result.addComparison(comp);
                logger.info("漏检: 模板特征 {} ({}) 未找到匹配", feature.getId(), feature.getName());
            }
        }
    }

    /**
     * 从节点 ID 中提取索引
     */
    private int extractIndexFromId(String nodeId) {
        // 检测节点ID格式: "detected_<index>"
        try {
            String[] parts = nodeId.split("_");
            return Integer.parseInt(parts[1]);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * 获取类别名称
     */
    private String getClassName(int classId) {
        // 这里可以配置类别名称映射，暂时返回简单名称
        switch (classId) {
            case 0: return "hole";
            case 1: return "nut";
            default: return "class_" + classId;
        }
    }

    /**
     * 计算全局坐标偏移
     * 基于所有有效匹配对，计算检测坐标系到模板坐标系的平均偏移
     */
    private double[] calculateGlobalOffset(TopologyMatcher.MatchResult matchResult) {
        List<Double> offsetXList = new ArrayList<>();
        List<Double> offsetYList = new ArrayList<>();

        for (TopologyMatcher.MatchPair pair : matchResult.getMatches()) {
            if (!pair.isValid()) continue;

            TopologyNode templateNode = pair.getTemplateNode();
            TopologyNode detectedNode = pair.getDetectedNode();

            TemplateFeature feature = templateNode.getTemplateFeature();
            if (feature == null || feature.getPosition() == null) continue;

            // 计算偏移：检测位置 - 模板位置
            double dx = detectedNode.getX() - feature.getPosition().x;
            double dy = detectedNode.getY() - feature.getPosition().y;

            offsetXList.add(dx);
            offsetYList.add(dy);
        }

        if (offsetXList.isEmpty()) {
            return null;
        }

        // 使用中位数计算偏移（对异常值更鲁棒）
        double medianOffsetX = median(offsetXList);
        double medianOffsetY = median(offsetYList);

        return new double[]{medianOffsetX, medianOffsetY};
    }

    /**
     * 计算中位数
     */
    private double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compare);
        int size = sorted.size();
        if (size % 2 == 0) {
            return (sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2.0;
        } else {
            return sorted.get(size / 2);
        }
    }

    /**
     * 处理未匹配的检测对象（误检）
     */
    private void processUnmatchedDetected(TopologyMatcher.MatchResult matchResult,
                                         InspectionResult result,
                                         List<DetectedObject> detectedObjects) {
        // 创建检测对象的索引映射
        Map<String, Integer> detectedIndexMap = new HashMap<>();
        for (int i = 0; i < detectedObjects.size(); i++) {
            detectedIndexMap.put("detected_" + i, i);
        }

        for (TopologyNode unmatchedNode : matchResult.getUnmatchedDetectedNodes()) {
            Integer index = detectedIndexMap.get(unmatchedNode.getNodeId());
            if (index != null) {
                DetectedObject obj = detectedObjects.get(index);
                com.edge.vision.core.template.model.Point detectedPos = obj.getCenter();

                FeatureComparison comp = FeatureComparison.extra(
                    "extra_" + index,
                    obj.getClassName() != null ? obj.getClassName() : "多余特征_" + index,
                    detectedPos,
                    obj.getClassId(),
                    obj.getConfidence()
                );
                comp.setClassName(obj.getClassName());

                logger.info("误检: 检测出{} at ({}, {}), 模板中无对应特征",
                    obj.getClassName(), detectedPos.x, detectedPos.y);

                result.addComparison(comp);
            }
        }
    }

    /**
     * 设置结果消息
     */
    private void setResultMessage(InspectionResult result, TopologyMatcher.MatchResult matchResult) {
        InspectionResult.InspectionSummary summary = result.getSummary();

        // 判断是否通过：所有模板特征都通过，且没有错检、漏检、偏差
        boolean hasExtra = summary.extra > 0;
        boolean hasMissing = summary.missing > 0;
        boolean hasDeviation = summary.deviation > 0;
        boolean allPassed = summary.totalFeatures == summary.passed;

        if (allPassed && !hasExtra && !hasMissing && !hasDeviation) {
            result.setPassed(true);
            result.setMessage(String.format("检测通过 (拓扑匹配) - %d个特征匹配成功, 平均代价=%.3f - %s",
                matchResult.getValidMatchCount(),
                matchResult.getTotalCost() / Math.max(1, matchResult.getMatchCount()),
                summary));
        } else {
            result.setPassed(false);
            // 使用 summary 中的统计数据，确保消息与统计一致
            result.setMessage(String.format("检测失败 (拓扑匹配) - %d个特征匹配成功, %d个漏检, %d个偏差, %d个误检 - %s",
                summary.passed,
                summary.missing,
                summary.deviation,
                summary.extra,
                summary));
        }
    }

    // Getters and Setters

    public boolean isTreatExtraAsError() {
        return treatExtraAsError;
    }

    public void setTreatExtraAsError(boolean treatExtraAsError) {
        this.treatExtraAsError = treatExtraAsError;
    }

    public TopologyMatcher getTopologyMatcher() {
        return topologyMatcher;
    }
}
