package com.edge.vision.dto;

import java.util.List;

/**
 * 质量检测响应
 */
public class InspectionResponse {
    private boolean passed;
    private String message;
    private String partType;
    private List<FeatureComparison> comparisons;
    private long processingTimeMs;

    public static InspectionResponse success(boolean passed, String message, String partType,
                                               List<FeatureComparison> comparisons, long processingTimeMs) {
        InspectionResponse response = new InspectionResponse();
        response.passed = passed;
        response.message = message;
        response.partType = partType;
        response.comparisons = comparisons;
        response.processingTimeMs = processingTimeMs;
        return response;
    }

    // Getters and Setters
    public boolean isPassed() { return passed; }
    public void setPassed(boolean passed) { this.passed = passed; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getPartType() { return partType; }
    public void setPartType(String partType) { this.partType = partType; }

    public List<FeatureComparison> getComparisons() { return comparisons; }
    public void setComparisons(List<FeatureComparison> comparisons) { this.comparisons = comparisons; }

    public long getProcessingTimeMs() { return processingTimeMs; }
    public void setProcessingTimeMs(long processingTimeMs) { this.processingTimeMs = processingTimeMs; }

    /**
     * 特征比对结果
     */
    public static class FeatureComparison {
        private String featureId;
        private String featureName;
        private String className;  // 实际类别名称，如 "hole", "nut"
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

        public String getFeatureId() { return featureId; }
        public void setFeatureId(String featureId) { this.featureId = featureId; }

        public String getFeatureName() { return featureName; }
        public void setFeatureName(String featureName) { this.featureName = featureName; }

        public String getClassName() { return className; }
        public void setClassName(String className) { this.className = className; }

        public int getClassId() { return classId; }
        public void setClassId(int classId) { this.classId = classId; }

        public double getTemplateX() { return templateX; }
        public void setTemplateX(double templateX) { this.templateX = templateX; }

        public double getTemplateY() { return templateY; }
        public void setTemplateY(double templateY) { this.templateY = templateY; }

        public double getDetectedX() { return detectedX; }
        public void setDetectedX(double detectedX) { this.detectedX = detectedX; }

        public double getDetectedY() { return detectedY; }
        public void setDetectedY(double detectedY) { this.detectedY = detectedY; }

        public double getXError() { return xError; }
        public void setXError(double xError) { this.xError = xError; }

        public double getYError() { return yError; }
        public void setYError(double yError) { this.yError = yError; }

        public double getTotalError() { return totalError; }
        public void setTotalError(double totalError) { this.totalError = totalError; }

        public double getToleranceX() { return toleranceX; }
        public void setToleranceX(double toleranceX) { this.toleranceX = toleranceX; }

        public double getToleranceY() { return toleranceY; }
        public void setToleranceY(double toleranceY) { this.toleranceY = toleranceY; }

        public boolean isWithinTolerance() { return withinTolerance; }
        public void setWithinTolerance(boolean withinTolerance) { this.withinTolerance = withinTolerance; }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
    }
}
