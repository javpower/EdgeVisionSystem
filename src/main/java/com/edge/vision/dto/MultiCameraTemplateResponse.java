package com.edge.vision.dto;

import java.util.List;

/**
 * 多摄像头模板保存响应
 */
public class MultiCameraTemplateResponse {
    private boolean success;
    private String message;
    private String partType;
    private List<SavedTemplateInfo> templates;

    public MultiCameraTemplateResponse() {
    }

    public MultiCameraTemplateResponse(boolean success, String message, String partType, List<SavedTemplateInfo> templates) {
        this.success = success;
        this.message = message;
        this.partType = partType;
        this.templates = templates;
    }

    public static MultiCameraTemplateResponse success(String partType, List<SavedTemplateInfo> templates) {
        return new MultiCameraTemplateResponse(true, "保存成功", partType, templates);
    }

    public static MultiCameraTemplateResponse error(String message) {
        return new MultiCameraTemplateResponse(false, message, null, null);
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

    public List<SavedTemplateInfo> getTemplates() {
        return templates;
    }

    public void setTemplates(List<SavedTemplateInfo> templates) {
        this.templates = templates;
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
