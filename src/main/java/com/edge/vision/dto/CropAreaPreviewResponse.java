package com.edge.vision.dto;

import java.util.List;

/**
 * 裁剪区域预览响应
 */
public class CropAreaPreviewResponse {
    private boolean success;
    private String message;
    private String imageUrl;              // 预览图片URL（带标注）
    private List<FeatureInfo> features;   // 识别的特征
    private int cropWidth;
    private int cropHeight;

    public static CropAreaPreviewResponse success(String imageUrl, List<FeatureInfo> features, int width, int height) {
        CropAreaPreviewResponse response = new CropAreaPreviewResponse();
        response.success = true;
        response.message = "预览生成成功";
        response.imageUrl = imageUrl;
        response.features = features;
        response.cropWidth = width;
        response.cropHeight = height;
        return response;
    }

    public static CropAreaPreviewResponse error(String message) {
        CropAreaPreviewResponse response = new CropAreaPreviewResponse();
        response.success = false;
        response.message = message;
        return response;
    }

    // Getters and Setters
    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }

    public List<FeatureInfo> getFeatures() { return features; }
    public void setFeatures(List<FeatureInfo> features) { this.features = features; }

    public int getCropWidth() { return cropWidth; }
    public void setCropWidth(int cropWidth) { this.cropWidth = cropWidth; }

    public int getCropHeight() { return cropHeight; }
    public void setCropHeight(int cropHeight) { this.cropHeight = cropHeight; }

    /**
     * 特征信息
     */
    public static class FeatureInfo {
        private String id;
        private String name;
        private int classId;
        private double x;      // 中心x（相对坐标）
        private double y;      // 中心y（相对坐标）
        private double width;
        private double height;
        private double confidence;

        public FeatureInfo() {}

        public FeatureInfo(String id, String name, int classId, double x, double y,
                          double width, double height, double confidence) {
            this.id = id;
            this.name = name;
            this.classId = classId;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.confidence = confidence;
        }

        // Getters and Setters
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public int getClassId() { return classId; }
        public void setClassId(int classId) { this.classId = classId; }

        public double getX() { return x; }
        public void setX(double x) { this.x = x; }

        public double getY() { return y; }
        public void setY(double y) { this.y = y; }

        public double getWidth() { return width; }
        public void setWidth(double width) { this.width = width; }

        public double getHeight() { return height; }
        public void setHeight(double height) { this.height = height; }

        public double getConfidence() { return confidence; }
        public void setConfidence(double confidence) { this.confidence = confidence; }
    }
}
