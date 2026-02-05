package com.edge.vision.dto;

import java.util.List;

/**
 * 多摄像头手动模板创建响应
 */
public class ManualMultiCameraResponse {
    private boolean success;
    private String message;
    private String partType;
    private List<CameraPreviewData> cameras;
    private List<SavedTemplateInfo> templates;

    public ManualMultiCameraResponse() {
    }

    public static ManualMultiCameraResponse success(String partType, List<CameraPreviewData> cameras) {
        ManualMultiCameraResponse response = new ManualMultiCameraResponse();
        response.success = true;
        response.message = "预览生成成功";
        response.partType = partType;
        response.cameras = cameras;
        return response;
    }

    public static ManualMultiCameraResponse saveSuccess(String partType, List<SavedTemplateInfo> templates) {
        ManualMultiCameraResponse response = new ManualMultiCameraResponse();
        response.success = true;
        response.message = "保存成功";
        response.partType = partType;
        response.templates = templates;
        return response;
    }

    public static ManualMultiCameraResponse error(String message) {
        ManualMultiCameraResponse response = new ManualMultiCameraResponse();
        response.success = false;
        response.message = message;
        return response;
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

    public List<SavedTemplateInfo> getTemplates() {
        return templates;
    }

    public void setTemplates(List<SavedTemplateInfo> templates) {
        this.templates = templates;
    }

    /**
     * 摄像头预览数据
     */
    public static class CameraPreviewData {
        private int cameraId;
        private String imageUrl;
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
        private String id;
        private String name;
        private int classId;
        private double centerX;
        private double centerY;
        private double width;
        private double height;

        public FeatureInfo() {
        }

        public FeatureInfo(String id, String name, int classId, double centerX, double centerY, double width, double height) {
            this.id = id;
            this.name = name;
            this.classId = classId;
            this.centerX = centerX;
            this.centerY = centerY;
            this.width = width;
            this.height = height;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
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
    }

    /**
     * 已保存的模板信息
     */
    public static class SavedTemplateInfo {
        private int cameraId;
        private String templateId;
        private String imagePath;
        private int featureCount;

        public SavedTemplateInfo() {
        }

        public SavedTemplateInfo(int cameraId, String templateId, String imagePath, int featureCount) {
            this.cameraId = cameraId;
            this.templateId = templateId;
            this.imagePath = imagePath;
            this.featureCount = featureCount;
        }

        public int getCameraId() {
            return cameraId;
        }

        public void setCameraId(int cameraId) {
            this.cameraId = cameraId;
        }

        public String getTemplateId() {
            return templateId;
        }

        public void setTemplateId(String templateId) {
            this.templateId = templateId;
        }

        public String getImagePath() {
            return imagePath;
        }

        public void setImagePath(String imagePath) {
            this.imagePath = imagePath;
        }

        public int getFeatureCount() {
            return featureCount;
        }

        public void setFeatureCount(int featureCount) {
            this.featureCount = featureCount;
        }
    }
}
