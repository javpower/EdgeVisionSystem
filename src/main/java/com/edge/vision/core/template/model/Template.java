package com.edge.vision.core.template.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 质量检测模板（拓扑匹配版本）
 * <p>
 * 基于拓扑图匹配的高精度特征一一对应方案
 * <p>
 * 核心字段：
 * - features: 特征列表（节点=特征，包含位置、类别、容差）
 * - metadata: 元数据（工件类型等）
 * <p>
 * 不再需要 anchorPoints（拓扑匹配天然支持旋转/平移/尺度）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Template {
    private String templateId;
    private String description;
    private LocalDateTime createdAt;
    private ImageSize imageSize;
    private String imagePath;
    private BoundingBox boundingBox;

    // 核心数据：特征列表
    private List<TemplateFeature> features;

    // 元数据：用于存储工件类型等信息
    private Map<String, Object> metadata;

    // 全局容差配置（每个特征也可以单独设置）
    private double toleranceX = 5.0;
    private double toleranceY = 5.0;

    // 拓扑匹配参数
    private int topologyK = 10;           // k近邻数量（构建拓扑图时使用）
    private double topologyThreshold = 0.5; // 拓扑相似度阈值

    public Template() {
        this.createdAt = LocalDateTime.now();
        this.features = new ArrayList<>();
        this.metadata = new HashMap<>();
    }

    public Template(String templateId) {
        this();
        this.templateId = templateId;
    }

    // Getters and Setters
    public String getTemplateId() { return templateId; }
    public void setTemplateId(String templateId) { this.templateId = templateId; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public ImageSize getImageSize() { return imageSize; }
    public void setImageSize(ImageSize imageSize) { this.imageSize = imageSize; }

    public String getImagePath() { return imagePath; }
    public void setImagePath(String imagePath) { this.imagePath = imagePath; }

    public BoundingBox getBoundingBox() { return boundingBox; }
    public void setBoundingBox(BoundingBox boundingBox) { this.boundingBox = boundingBox; }

    public List<TemplateFeature> getFeatures() { return features; }
    public void setFeatures(List<TemplateFeature> features) { this.features = features; }

    public void addFeature(TemplateFeature feature) {
        this.features.add(feature);
    }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

    public void putMetadata(String key, Object value) {
        this.metadata.put(key, value);
    }

    public double getToleranceX() { return toleranceX; }
    public void setToleranceX(double toleranceX) { this.toleranceX = toleranceX; }

    public double getToleranceY() { return toleranceY; }
    public void setToleranceY(double toleranceY) { this.toleranceY = toleranceY; }

    public int getTopologyK() { return topologyK; }
    public void setTopologyK(int topologyK) { this.topologyK = topologyK; }

    public double getTopologyThreshold() { return topologyThreshold; }
    public void setTopologyThreshold(double topologyThreshold) { this.topologyThreshold = topologyThreshold; }

    /**
     * 获取关联的工件类型
     */
    public String getPartType() {
        return (String) metadata.get("partType");
    }

    /**
     * 设置关联的工件类型
     */
    public void setPartType(String partType) {
        metadata.put("partType", partType);
    }

    @Override
    public String toString() {
        return String.format("Template[%s, %d features, partType=%s]",
            templateId, features.size(), getPartType());
    }
}
