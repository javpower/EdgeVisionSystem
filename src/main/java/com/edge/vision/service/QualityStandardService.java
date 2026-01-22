package com.edge.vision.service;

import com.edge.vision.core.quality.FeatureComparison;
import com.edge.vision.core.quality.InspectionResult;
import com.edge.vision.core.quality.MatchStrategy;
import com.edge.vision.core.quality.QualityInspector;
import com.edge.vision.core.template.TemplateManager;
import com.edge.vision.core.template.model.DetectedObject;
import com.edge.vision.core.template.model.Point;
import com.edge.vision.core.template.model.Template;
import com.edge.vision.config.YamlConfig;
import com.edge.vision.dto.InspectionRequest;
import com.edge.vision.model.Detection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 质检服务
 * <p>
 * 基于拓扑图匹配的高精度特征一一对应方案进行质量检测
 * <p>
 * 核心特性：
 * - 完全兼容旋转、平移、尺度变化
 * - 基于拓扑关系（相对角度、相对距离比）
 * - 使用匈牙利算法进行全局最优匹配
 * - 针对弓箭孔、螺丝等重复特征匹配精度高
 */
@Service
public class QualityStandardService {
    private static final Logger logger = LoggerFactory.getLogger(QualityStandardService.class);

    @Autowired(required = false)
    private TemplateManager templateManager;

    @Autowired(required = false)
    private QualityInspector qualityInspector;

    @Autowired(required = false)
    private YamlConfig yamlConfig;

    /**
     * 使用拓扑图匹配模式进行质检评估（模型格式）
     * <p>
     * 支持：
     * - 自动构建拓扑图（节点=特征，边=拓扑关系）
     * - 旋转、平移、缩放的天然不变性
     * - 全局最优一一对应匹配
     * - 漏检/错检识别
     * - 内部根据 match-strategy 配置选择匹配器
     *
     * @param partType       工件类型（用于查找关联的模板）
     * @param detectedObjects 模型格式的检测对象列表
     * @return 质检结果
     */
    public QualityEvaluationResult evaluateWithTemplate(String partType,
                                                         List<DetectedObject> detectedObjects) {
        return evaluateWithTemplate(partType, detectedObjects, 0, 0);
    }

    /**
     * 使用模板匹配进行质检评估（带实际裁剪尺寸）
     *
     * @param partType         工件类型
     * @param detectedObjects  检测到的对象列表
     * @param actualCropWidth  实际检测时的裁剪宽度
     * @param actualCropHeight 实际检测时的裁剪高度
     * @return 质检评估结果
     */
    public QualityEvaluationResult evaluateWithTemplate(String partType,
                                                         List<DetectedObject> detectedObjects,
                                                         int actualCropWidth,
                                                         int actualCropHeight) {
        logger.info("Evaluating with template matching for part type: {}", partType);
        if (actualCropWidth > 0 && actualCropHeight > 0) {
            logger.info("Actual crop dimensions: {}x{}", actualCropWidth, actualCropHeight);
        }

        // 检查模板系统是否可用
        if (templateManager == null || qualityInspector == null) {
            logger.warn("Template system not available");
            return createErrorResult(partType, "模板系统未初始化");
        }

        // 查找与工件类型关联的模板
        Template template = findTemplateByPartType(partType);
        if (template == null) {
            logger.warn("No template found for part type: {}", partType);
            return createErrorResult(partType, "未找到匹配的模板");
        }

        try {
            // 获取匹配策略
            MatchStrategy strategy = yamlConfig != null && yamlConfig.getInspection() != null
                ? yamlConfig.getInspection().getMatchStrategy()
                : null;
            if (strategy == null) {
                strategy = MatchStrategy.TOPOLOGY;  // 默认使用拓扑匹配
            }

            // 使用指定策略进行匹配
            InspectionResult inspectionResult = qualityInspector.inspect(
                template, detectedObjects, strategy, actualCropWidth, actualCropHeight);
            return convertToQualityEvaluationResult(partType, inspectionResult);

        } catch (Exception e) {
            logger.error("Error during template-based evaluation", e);
            return createErrorResult(partType, "模板匹配失败: " + e.getMessage());
        }
    }

    /**
     * 使用拓扑图匹配模式进行质检评估（DTO 格式）
     * <p>
     * 从 InspectionRequest DTO 转换后调用
     *
     * @param partType       工件类型（用于查找关联的模板）
     * @param requestDetections DTO 格式的检测对象列表
     * @return 质检结果
     */
    public QualityEvaluationResult evaluateWithTemplateFromRequest(String partType,
                                                                   List<InspectionRequest.DetectedObject> requestDetections) {
        // 转换 DTO 为模型对象后调用主方法
        List<DetectedObject> modelObjects = convertDtoToModel(requestDetections);
        return evaluateWithTemplate(partType, modelObjects);
    }

    /**
     * 将 DTO 格式的 DetectedObject 转换为模型格式
     */
    private List<DetectedObject> convertDtoToModel(List<InspectionRequest.DetectedObject> dtoObjects) {
        return dtoObjects.stream()
            .map(dto -> {
                DetectedObject obj = new DetectedObject();
                obj.setClassId(dto.getClassId());
                obj.setClassName(dto.getClassName());
                obj.setCenter(new Point(dto.getCenterX(), dto.getCenterY()));
                obj.setWidth(dto.getWidth());
                obj.setHeight(dto.getHeight());
                obj.setConfidence(dto.getConfidence());
                return obj;
            })
            .collect(Collectors.toList());
    }

    /**
     * 创建错误结果
     */
    private QualityEvaluationResult createErrorResult(String partType, String message) {
        QualityEvaluationResult result = new QualityEvaluationResult();
        result.setPartType(partType);
        result.setPassed(false);
        result.setMessage(message);
        return result;
    }

    /**
     * 根据工件类型查找关联的模板
     */
    private Template findTemplateByPartType(String partType) {
        // 首先检查当前激活的模板
        Template current = templateManager.getCurrentTemplate();
        if (current != null) {
            String associatedPartType = (String) current.getMetadata().get("partType");
            if (partType.equals(associatedPartType)) {
                return current;
            }
        }

        // 在所有模板中查找匹配的
        for (Template template : templateManager.getAllTemplates()) {
            String associatedPartType = (String) template.getMetadata().get("partType");
            if (partType.equals(associatedPartType)) {
                return template;
            }
        }

        // 尝试直接通过模板ID加载
        try {
            return templateManager.load(partType);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 将 InspectionResult 转换为 QualityEvaluationResult
     */
    private QualityEvaluationResult convertToQualityEvaluationResult(String partType,
                                                                       InspectionResult inspectionResult) {
        QualityEvaluationResult result = new QualityEvaluationResult();
        result.setPartType(partType);
        result.setPassed(inspectionResult.isPassed());
        result.setMessage(inspectionResult.getMessage());
        result.setProcessingTimeMs(inspectionResult.getProcessingTimeMs());
        result.setTemplateComparisons(new ArrayList<>());

        // 转换比对结果
        for (FeatureComparison comp : inspectionResult.getComparisons()) {
            QualityEvaluationResult.TemplateComparison tc = new QualityEvaluationResult.TemplateComparison();
            tc.setFeatureId(comp.getFeatureId());
            tc.setFeatureName(comp.getFeatureName());
            tc.setClassName(comp.getClassName());
            tc.setClassId(comp.getClassId());
            tc.setTemplatePosition(comp.getTemplatePosition());
            tc.setDetectedPosition(comp.getDetectedPosition());
            tc.setXError(comp.getXError());
            tc.setYError(comp.getYError());
            tc.setTotalError(comp.getTotalError());
            tc.setToleranceX(comp.getToleranceX());
            tc.setToleranceY(comp.getToleranceY());
            tc.setWithinTolerance(comp.isWithinTolerance());
            tc.setStatus(comp.getStatus());
            result.getTemplateComparisons().add(tc);
        }

        return result;
    }

    /**
     * 将 DetectedObject 列表转换为 Detection 列表
     */
    private List<Detection> convertDetectedObjectsToDetections(List<DetectedObject> detectedObjects) {
        return detectedObjects.stream()
            .map(obj -> {
                String label = obj.getClassName() != null ? obj.getClassName() : "class_" + obj.getClassId();
                int classId = obj.getClassId();
                float confidence = (float) obj.getConfidence();
                float centerX = (float) obj.getCenter().x;
                float centerY = (float) obj.getCenter().y;
                float[] bbox = new float[]{
                    (float) (obj.getCenter().x - obj.getWidth() / 2),
                    (float) (obj.getCenter().y - obj.getHeight() / 2),
                    (float) (obj.getCenter().x + obj.getWidth() / 2),
                    (float) (obj.getCenter().y + obj.getHeight() / 2)
                };
                return new Detection(label, classId, bbox, centerX, centerY, confidence);
            })
            .collect(Collectors.toList());
    }

    // Getters and Setters

    public void setTemplateManager(TemplateManager templateManager) {
        this.templateManager = templateManager;
    }

    public void setQualityInspector(QualityInspector qualityInspector) {
        this.qualityInspector = qualityInspector;
    }

    /**
     * 质检评估结果
     */
    public static class QualityEvaluationResult {
        private String partType;
        private boolean passed;
        private String message;
        private Long processingTimeMs;
        private List<TemplateComparison> templateComparisons;

        public String getPartType() { return partType; }
        public void setPartType(String partType) { this.partType = partType; }

        public boolean isPassed() { return passed; }
        public void setPassed(boolean passed) { this.passed = passed; }

        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }

        public Long getProcessingTimeMs() { return processingTimeMs; }
        public void setProcessingTimeMs(Long processingTimeMs) { this.processingTimeMs = processingTimeMs; }

        public List<TemplateComparison> getTemplateComparisons() { return templateComparisons; }
        public void setTemplateComparisons(List<TemplateComparison> templateComparisons) {
            this.templateComparisons = templateComparisons;
        }

        /**
         * 模板比对结果
         */
        public static class TemplateComparison {
            private String featureId;
            private String featureName;
            private String className;
            private int classId;
            private Point templatePosition;
            private Point detectedPosition;
            private double xError;
            private double yError;
            private double totalError;
            private double toleranceX;
            private double toleranceY;
            private boolean withinTolerance;
            private FeatureComparison.ComparisonStatus status;

            // Getters and Setters
            public String getFeatureId() { return featureId; }
            public void setFeatureId(String featureId) { this.featureId = featureId; }

            public String getFeatureName() { return featureName; }
            public void setFeatureName(String featureName) { this.featureName = featureName; }

            public String getClassName() { return className; }
            public void setClassName(String className) { this.className = className; }

            public int getClassId() { return classId; }
            public void setClassId(int classId) { this.classId = classId; }

            public Point getTemplatePosition() { return templatePosition; }
            public void setTemplatePosition(Point templatePosition) {
                this.templatePosition = templatePosition;
            }

            public Point getDetectedPosition() { return detectedPosition; }
            public void setDetectedPosition(Point detectedPosition) {
                this.detectedPosition = detectedPosition;
            }

            public double getXError() { return xError; }
            public void setXError(double xError) { this.xError = xError; }

            public double getYError() { return yError; }
            public void setYError(double yError) { this.yError = yError; }

            public double getTotalError() { return totalError; }
            public void setTotalError(double totalError) { this.totalError = totalError; }

            public double getToleranceX() { return toleranceX; }
            public void setToleranceX(double toleranceX) { this.toleranceX = toleranceX; }

            public double getToleranceY() { return toleranceY; }
            public void setToleranceY(double toleranceY) { this.toleranceY = toleranceY; }

            public boolean isWithinTolerance() { return withinTolerance; }
            public void setWithinTolerance(boolean withinTolerance) { this.withinTolerance = withinTolerance; }

            public FeatureComparison.ComparisonStatus getStatus() { return status; }
            public void setStatus(FeatureComparison.ComparisonStatus status) { this.status = status; }
        }
    }
}
