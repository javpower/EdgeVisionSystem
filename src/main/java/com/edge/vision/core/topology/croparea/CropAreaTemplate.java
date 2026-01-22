package com.edge.vision.core.topology.croparea;

import com.edge.vision.core.template.model.Point;
import com.edge.vision.core.template.model.TemplateFeature;

import java.util.HashMap;
import java.util.Map;

/**
 * 裁剪区域模板
 * <p>
 * 核心思想：
 * 1. 建模时：截取工件区域 -> 在截图上标注特征 -> 保存相对坐标
 * 2. 识别时：截取工件区域 -> 在截图上检测特征 -> 直接比对相对坐标
 * <p>
 * 优势：建模图和识别图坐标系统一致，不需要复杂的仿射变换
 */
public class CropAreaTemplate {

    private final String templateId;
    private final int cropWidth;           // 裁剪区域宽度
    private final int cropHeight;          // 裁剪区域高度
    private final Map<String, TemplateFeature> features;  // 特征库（相对坐标）
    private final String objectTemplatePath;  // 整体检测模板路径（用于 IndustrialObjectDetector）
    private final String cropImagePath;        // 裁剪区域图片路径（可选，用于调试）

    private CropAreaTemplate(Builder builder) {
        this.templateId = builder.templateId;
        this.cropWidth = builder.cropWidth;
        this.cropHeight = builder.cropHeight;
        this.features = new HashMap<>(builder.features);
        this.objectTemplatePath = builder.objectTemplatePath;
        this.cropImagePath = builder.cropImagePath;
    }

    public String getTemplateId() {
        return templateId;
    }

    public int getCropWidth() {
        return cropWidth;
    }

    public int getCropHeight() {
        return cropHeight;
    }

    public TemplateFeature getFeature(String featureId) {
        return features.get(featureId);
    }

    public Map<String, TemplateFeature> getAllFeatures() {
        return new HashMap<>(features);
    }

    public int getFeatureCount() {
        return features.size();
    }

    public String getObjectTemplatePath() {
        return objectTemplatePath;
    }

    public String getCropImagePath() {
        return cropImagePath;
    }

    @Override
    public String toString() {
        return String.format("CropAreaTemplate[id=%s, size=%dx%d, features=%d, objectTemplate=%s]",
            templateId, cropWidth, cropHeight, features.size(), objectTemplatePath);
    }

    /**
     * Builder 模式构建模板
     */
    public static class Builder {
        private String templateId;
        private int cropWidth;
        private int cropHeight;
        private final Map<String, TemplateFeature> features = new HashMap<>();
        private String objectTemplatePath;  // 整体检测模板路径
        private String cropImagePath;        // 裁剪区域图片路径

        public Builder templateId(String templateId) {
            this.templateId = templateId;
            return this;
        }

        public Builder cropSize(int width, int height) {
            this.cropWidth = width;
            this.cropHeight = height;
            return this;
        }

        public Builder objectTemplatePath(String path) {
            this.objectTemplatePath = path;
            return this;
        }

        public Builder cropImagePath(String path) {
            this.cropImagePath = path;
            return this;
        }

        public Builder addFeature(TemplateFeature feature) {
            this.features.put(feature.getId(), feature);
            return this;
        }

        public Builder addFeatures(Map<String, TemplateFeature> features) {
            this.features.putAll(features);
            return this;
        }

        public CropAreaTemplate build() {
            if (templateId == null || templateId.isEmpty()) {
                throw new IllegalArgumentException("templateId cannot be null or empty");
            }
            if (cropWidth <= 0 || cropHeight <= 0) {
                throw new IllegalArgumentException("cropSize must be positive");
            }
            if (features.isEmpty()) {
                throw new IllegalArgumentException("at least one feature is required");
            }
            return new CropAreaTemplate(this);
        }
    }

    /**
     * 特征元数据
     */
    public static class FeatureMetadata {
        public final String featureId;
        public final String name;
        public final int classId;
        public final String className;
        public final Point position;       // 相对坐标（基于裁剪区域）
        public final Point tolerance;      // 容差

        public FeatureMetadata(String featureId, String name, int classId, String className,
                               Point position, Point tolerance) {
            this.featureId = featureId;
            this.name = name;
            this.classId = classId;
            this.className = className;
            this.position = position;
            this.tolerance = tolerance;
        }

        @Override
        public String toString() {
            return String.format("FeatureMetadata[id=%s, name=%s, class=%s, pos=(%.1f,%.1f), tol=(%.1f,%.1f)]",
                featureId, name, className, position.x, position.y, tolerance.x, tolerance.y);
        }
    }
}
