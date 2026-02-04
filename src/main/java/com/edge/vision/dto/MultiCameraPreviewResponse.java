package com.edge.vision.dto;

import java.util.List;

/**
 * 多摄像头模板预览响应
 */
public class MultiCameraPreviewResponse {
    private boolean success;
    private String message;
    private String partType;
    private List<CameraPreviewData> cameras;

    public MultiCameraPreviewResponse() {
    }

    public MultiCameraPreviewResponse(boolean success, String message, String partType, List<CameraPreviewData> cameras) {
        this.success = success;
        this.message = message;
        this.partType = partType;
        this.cameras = cameras;
    }

    public static MultiCameraPreviewResponse success(String partType, List<CameraPreviewData> cameras) {
        return new MultiCameraPreviewResponse(true, "预览成功", partType, cameras);
    }

    public static MultiCameraPreviewResponse error(String message) {
        return new MultiCameraPreviewResponse(false, message, null, null);
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getPartType() {
        return partType;
    }

    public void setPartType(String partType) {
        this.partType = partType;
    }

    public List<CameraPreviewData> getCameras() {
        return cameras;
    }

    public void setCameras(List<CameraPreviewData> cameras) {
        this.cameras = cameras;
    }

    /**
     * 单个摄像头预览数据
     */
    public static class CameraPreviewData {
        private int cameraId;
        private String imageUrl;  // 带检测框的图片
        private List<FeatureInfo> features;

        public CameraPreviewData() {
        }

        public CameraPreviewData(int cameraId, String imageUrl, List<FeatureInfo> features) {
            this.cameraId = cameraId;
            this.imageUrl = imageUrl;
            this.features = features;
        }

        public int getCameraId() {
            return cameraId;
        }

        public void setCameraId(int cameraId) {
            this.cameraId = cameraId;
        }

        public String getImageUrl() {
            return imageUrl;
        }

        public void setImageUrl(String imageUrl) {
            this.imageUrl = imageUrl;
        }

        public List<FeatureInfo> getFeatures() {
            return features;
        }

        public void setFeatures(List<FeatureInfo> features) {
            this.features = features;
        }
    }

    /**
     * 特征信息
     */
    public static class FeatureInfo {
        private String featureId;
        private String className;
        private int classId;
        private double centerX;
        private double centerY;
        private double width;
        private double height;
        private double confidence;

        public FeatureInfo() {
        }

        public FeatureInfo(String featureId, String className, int classId,
                          double centerX, double centerY, double width, double height, double confidence) {
            this.featureId = featureId;
            this.className = className;
            this.classId = classId;
            this.centerX = centerX;
            this.centerY = centerY;
            this.width = width;
            this.height = height;
            this.confidence = confidence;
        }

        public String getFeatureId() {
            return featureId;
        }

        public void setFeatureId(String featureId) {
            this.featureId = featureId;
        }

        public String getClassName() {
            return className;
        }

        public void setClassName(String className) {
            this.className = className;
        }

        public int getClassId() {
            return classId;
        }

        public void setClassId(int classId) {
            this.classId = classId;
        }

        public double getCenterX() {
            return centerX;
        }

        public void setCenterX(double centerX) {
            this.centerX = centerX;
        }

        public double getCenterY() {
            return centerY;
        }

        public void setCenterY(double centerY) {
            this.centerY = centerY;
        }

        public double getWidth() {
            return width;
        }

        public void setWidth(double width) {
            this.width = width;
        }

        public double getHeight() {
            return height;
        }

        public void setHeight(double height) {
            this.height = height;
        }

        public double getConfidence() {
            return confidence;
        }

        public void setConfidence(double confidence) {
            this.confidence = confidence;
        }
    }
}
