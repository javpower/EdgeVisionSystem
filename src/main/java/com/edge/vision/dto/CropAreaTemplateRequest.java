package com.edge.vision.dto;

import java.util.List;

/**
 * 裁剪区域模板请求
 */
public class CropAreaTemplateRequest {
    private String templateId;
    private String objectTemplatePath;   // 整体检测模板路径
    private List<Double> corners;        // 四个角坐标 [x1,y1,x2,y2,x3,y3,x4,y4]
    private double toleranceX = 5.0;
    private double toleranceY = 5.0;

    public String getTemplateId() { return templateId; }
    public void setTemplateId(String templateId) { this.templateId = templateId; }

    public String getObjectTemplatePath() { return objectTemplatePath; }
    public void setObjectTemplatePath(String objectTemplatePath) { this.objectTemplatePath = objectTemplatePath; }

    public List<Double> getCorners() { return corners; }
    public void setCorners(List<Double> corners) { this.corners = corners; }

    public double getToleranceX() { return toleranceX; }
    public void setToleranceX(double toleranceX) { this.toleranceX = toleranceX; }

    public double getToleranceY() { return toleranceY; }
    public void setToleranceY(double toleranceY) { this.toleranceY = toleranceY; }
}
