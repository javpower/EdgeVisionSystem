package com.edge.vision.dto;

import com.edge.vision.model.Detection;

import java.util.List;

/**
 * 多摄像头质检响应
 */
public class MultiCameraInspectionResponse {
    private boolean success;
    private String message;
    private String partType;

    private Boolean passed;
    private List<CameraInspectionResult> cameraResults;

    public Boolean getPassed() {
        return passed;
    }

    public void setPassed(Boolean passed) {
        this.passed = passed;
    }

    public MultiCameraInspectionResponse() {
    }

    public MultiCameraInspectionResponse(boolean success, String message, String partType, List<CameraInspectionResult> cameraResults,Boolean passed) {
        this.success = success;
        this.message = message;
        this.partType = partType;
        this.cameraResults = cameraResults;
        this.passed=passed;
    }

    public static MultiCameraInspectionResponse success(String partType, List<CameraInspectionResult> cameraResults,Boolean passed) {
        return new MultiCameraInspectionResponse(true, "质检完成", partType, cameraResults,passed);
    }

    public static MultiCameraInspectionResponse error(String message) {
        return new MultiCameraInspectionResponse(false, message, null, null,false);
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

    public List<CameraInspectionResult> getCameraResults() {
        return cameraResults;
    }

    public void setCameraResults(List<CameraInspectionResult> cameraResults) {
        this.cameraResults = cameraResults;
    }

    /**
     * 单个摄像头质检结果
     */
    public static class CameraInspectionResult {
        private int cameraId;
        private String templateId;
        private boolean passed;
        private String imageUrl;  // 带框的图片
        private String errorMessage;  // 错误信息
        private List<FeatureComparison> templateComparisons;

        private List<Detection> details;

        public List<FeatureComparison> getTemplateComparisons() {
            return templateComparisons;
        }

        public void setTemplateComparisons(List<FeatureComparison> templateComparisons) {
            this.templateComparisons = templateComparisons;
        }

        public CameraInspectionResult() {
        }

        public CameraInspectionResult(int cameraId, String templateId, boolean passed, String imageUrl, String errorMessage, List<FeatureComparison> features, List<Detection> details) {
            this.cameraId = cameraId;
            this.templateId = templateId;
            this.passed = passed;
            this.imageUrl = imageUrl;
            this.errorMessage = errorMessage;
            this.templateComparisons = features;
            this.details = details;
        }
        public List<Detection> getDetails() {
            return details;
        }

        public void setDetails(List<Detection> details) {
            this.details = details;
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

        public boolean isPassed() {
            return passed;
        }

        public void setPassed(boolean passed) {
            this.passed = passed;
        }

        public String getImageUrl() {
            return imageUrl;
        }

        public void setImageUrl(String imageUrl) {
            this.imageUrl = imageUrl;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public void setErrorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
        }

        public List<FeatureComparison> getFeatures() {
            return templateComparisons;
        }

        public void setFeatures(List<FeatureComparison> features) {
            this.templateComparisons = features;
        }
    }

    /**
     * 特征比对结果
     */
    public static class FeatureComparison {
        private String featureId;
        private String featureName;
        private String className;
        private int classId;
        private double templateX;
        private double templateY;
        private double detectedX;
        private double detectedY;
        private double xError;
        private double yError;
        private double totalError;
        private double toleranceX;
        private double toleranceY;
        private boolean withinTolerance;
        private String status;

        public FeatureComparison() {
        }

        public String getFeatureId() {
            return featureId;
        }

        public void setFeatureId(String featureId) {
            this.featureId = featureId;
        }

        public String getFeatureName() {
            return featureName;
        }

        public void setFeatureName(String featureName) {
            this.featureName = featureName;
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

        public double getTemplateX() {
            return templateX;
        }

        public void setTemplateX(double templateX) {
            this.templateX = templateX;
        }

        public double getTemplateY() {
            return templateY;
        }

        public void setTemplateY(double templateY) {
            this.templateY = templateY;
        }

        public double getDetectedX() {
            return detectedX;
        }

        public void setDetectedX(double detectedX) {
            this.detectedX = detectedX;
        }

        public double getDetectedY() {
            return detectedY;
        }

        public void setDetectedY(double detectedY) {
            this.detectedY = detectedY;
        }

        public double getXError() {
            return xError;
        }

        public void setXError(double xError) {
            this.xError = xError;
        }

        public double getYError() {
            return yError;
        }

        public void setYError(double yError) {
            this.yError = yError;
        }

        public double getTotalError() {
            return totalError;
        }

        public void setTotalError(double totalError) {
            this.totalError = totalError;
        }

        public double getToleranceX() {
            return toleranceX;
        }

        public void setToleranceX(double toleranceX) {
            this.toleranceX = toleranceX;
        }

        public double getToleranceY() {
            return toleranceY;
        }

        public void setToleranceY(double toleranceY) {
            this.toleranceY = toleranceY;
        }

        public boolean isWithinTolerance() {
            return withinTolerance;
        }

        public void setWithinTolerance(boolean withinTolerance) {
            this.withinTolerance = withinTolerance;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }
    }
}
