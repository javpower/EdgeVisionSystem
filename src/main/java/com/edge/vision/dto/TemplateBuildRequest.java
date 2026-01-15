package com.edge.vision.dto;

/**
 * 模板构建请求
 */
public class TemplateBuildRequest {
    private String templateId;
    private String partType;
    private String description;
    private String imageUrl;
    private String yoloLabels;
    private double toleranceX = 5.0;
    private double toleranceY = 5.0;
    private boolean includeAuxiliaryAnchors = true;
    private String classNameMapping;  // JSON 格式的类别映射，如 {"0":"螺丝孔","1":"定位孔"}

    public String getTemplateId() { return templateId; }
    public void setTemplateId(String templateId) { this.templateId = templateId; }

    public String getPartType() { return partType; }
    public void setPartType(String partType) { this.partType = partType; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }

    public String getYoloLabels() { return yoloLabels; }
    public void setYoloLabels(String yoloLabels) { this.yoloLabels = yoloLabels; }

    public double getToleranceX() { return toleranceX; }
    public void setToleranceX(double toleranceX) { this.toleranceX = toleranceX; }

    public double getToleranceY() { return toleranceY; }
    public void setToleranceY(double toleranceY) { this.toleranceY = toleranceY; }

    public boolean isIncludeAuxiliaryAnchors() { return includeAuxiliaryAnchors; }
    public void setIncludeAuxiliaryAnchors(boolean includeAuxiliaryAnchors) {
        this.includeAuxiliaryAnchors = includeAuxiliaryAnchors;
    }

    public String getClassNameMapping() { return classNameMapping; }
    public void setClassNameMapping(String classNameMapping) { this.classNameMapping = classNameMapping; }
}
