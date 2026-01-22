package com.edge.vision.core.topology.croparea;

import com.edge.vision.core.template.model.TemplateFeature;
import org.opencv.core.Mat;

import java.util.ArrayList;
import java.util.List;

/**
 * 裁剪区域模板
 * <p>
 * 用于存储基于截图的模板数据
 */
public class CropAreaTemplate {

    private String templateId;
    private String templateImagePath;    // 模板截图路径
    private double[][] corners;           // 工件四个角坐标
    private int cropWidth;
    private int cropHeight;
    private String objectTemplatePath;    // 整体检测模板路径
    private List<TemplateFeature> features;  // 模板特征（细节识别结果）

    public CropAreaTemplate() {
        this.features = new ArrayList<>();
        this.corners = new double[4][2];
    }

    // Getters and Setters
    public String getTemplateId() { return templateId; }
    public void setTemplateId(String templateId) { this.templateId = templateId; }

    public String getTemplateImagePath() { return templateImagePath; }
    public void setTemplateImagePath(String templateImagePath) { this.templateImagePath = templateImagePath; }

    public double[][] getCorners() { return corners; }
    public void setCorners(double[][] corners) { this.corners = corners; }

    public int getCropWidth() { return cropWidth; }
    public void setCropWidth(int cropWidth) { this.cropWidth = cropWidth; }

    public int getCropHeight() { return cropHeight; }
    public void setCropHeight(int cropHeight) { this.cropHeight = cropHeight; }

    public String getObjectTemplatePath() { return objectTemplatePath; }
    public void setObjectTemplatePath(String objectTemplatePath) { this.objectTemplatePath = objectTemplatePath; }

    public List<TemplateFeature> getFeatures() { return features; }
    public void setFeatures(List<TemplateFeature> features) { this.features = features; }

    public void addFeature(TemplateFeature feature) {
        this.features.add(feature);
    }

    public int getFeatureCount() {
        return features.size();
    }

    @Override
    public String toString() {
        return String.format("CropAreaTemplate[id=%s, imageSize=%dx%d, features=%d]",
            templateId, cropWidth, cropHeight, features.size());
    }
}
