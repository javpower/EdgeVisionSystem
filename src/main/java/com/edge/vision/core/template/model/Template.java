package com.edge.vision.core.template.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 质量检测模板
 * 包含标准工件的所有信息
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Template {
    private String templateId;
    private String description;
    private LocalDateTime createdAt;
    private ImageSize imageSize;
    private String imagePath;
    private BoundingBox boundingBox;
    private List<AnchorPoint> anchorPoints;
    private List<TemplateFeature> features;
    private Map<String, Object> metadata;

    // 容差配置
    private double toleranceX = 5.0;
    private double toleranceY = 5.0;

    public Template() {
        this.createdAt = LocalDateTime.now();
        this.anchorPoints = new ArrayList<>();
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

    public List<AnchorPoint> getAnchorPoints() { return anchorPoints; }
    public void setAnchorPoints(List<AnchorPoint> anchorPoints) { this.anchorPoints = anchorPoints; }

    public void addAnchorPoint(AnchorPoint anchor) {
        this.anchorPoints.add(anchor);
    }

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

    /**
     * 获取几何中心锚点
     */
    public AnchorPoint getGeometricCenterAnchor() {
        return anchorPoints.stream()
            .filter(a -> a.getType() == AnchorPoint.AnchorType.GEOMETRIC_CENTER)
            .findFirst()
            .orElse(null);
    }

    @Override
    public String toString() {
        return String.format("Template[%s, %d anchors, %d features]",
            templateId, anchorPoints.size(), features.size());
    }
}
