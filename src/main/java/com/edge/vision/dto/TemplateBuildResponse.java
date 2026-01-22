package com.edge.vision.dto;

import com.edge.vision.core.template.model.Template;

import java.util.List;

/**
 * 模板构建响应
 */
public class TemplateBuildResponse {
    private boolean success;
    private String message;
    private String templateId;
    private TemplateInfo template;

    public static TemplateBuildResponse success(String templateId, Template template) {
        TemplateBuildResponse response = new TemplateBuildResponse();
        response.success = true;
        response.message = "模板构建成功";
        response.templateId = templateId;
        response.template = TemplateInfo.from(template);
        return response;
    }

    public static TemplateBuildResponse error(String message) {
        TemplateBuildResponse response = new TemplateBuildResponse();
        response.success = false;
        response.message = message;
        return response;
    }

    // Getters and Setters
    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getTemplateId() { return templateId; }
    public void setTemplateId(String templateId) { this.templateId = templateId; }

    public TemplateInfo getTemplate() { return template; }
    public void setTemplate(TemplateInfo template) { this.template = template; }

    /**
     * 模板信息摘要
     */
    public static class TemplateInfo {
        private String templateId;
        private String description;
        private int featureCount;
        private String partType;
        private int topologyK;
        private double toleranceX;
        private double toleranceY;
        private String boundingBox;

        public static TemplateInfo from(Template template) {
            TemplateInfo info = new TemplateInfo();
            info.templateId = template.getTemplateId();
            info.description = template.getDescription();
            info.featureCount = template.getFeatures().size();
            info.partType = template.getPartType();
            info.topologyK = template.getTopologyK();
            info.toleranceX = template.getToleranceX();
            info.toleranceY = template.getToleranceY();
            if (template.getBoundingBox() != null) {
                info.boundingBox = template.getBoundingBox().toString();
            }

            return info;
        }

        public String getTemplateId() { return templateId; }
        public void setTemplateId(String templateId) { this.templateId = templateId; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public int getFeatureCount() { return featureCount; }
        public void setFeatureCount(int featureCount) { this.featureCount = featureCount; }

        public String getPartType() { return partType; }
        public void setPartType(String partType) { this.partType = partType; }

        public int getTopologyK() { return topologyK; }
        public void setTopologyK(int topologyK) { this.topologyK = topologyK; }

        public double getToleranceX() { return toleranceX; }
        public void setToleranceX(double toleranceX) { this.toleranceX = toleranceX; }

        public double getToleranceY() { return toleranceY; }
        public void setToleranceY(double toleranceY) { this.toleranceY = toleranceY; }

        public String getBoundingBox() { return boundingBox; }
        public void setBoundingBox(String boundingBox) { this.boundingBox = boundingBox; }
    }
}
