package com.edge.vision.core.quality;

import com.edge.vision.core.template.model.Point;
import com.edge.vision.core.template.model.TemplateFeature;

/**
 * 特征比对结果
 */
public class FeatureComparison {
    private String featureId;
    private String featureName;
    private String className;  // 实际类别名称，如 "hole", "nut"
    private int classId;
    private Point templatePosition;
    private Point detectedPosition;
    private double xError;
    private double yError;
    private double totalError;
    private double toleranceX;
    private double toleranceY;
    private boolean withinTolerance;
    private ComparisonStatus status;
    private double confidence;

    public enum ComparisonStatus {
        /** 合格：在容差范围内 */
        PASSED,
        /** 偏差过大：超出容差 */
        DEVIATION_EXCEEDED,
        /** 漏检：模板有但检测无 */
        MISSING,
        /** 错检：检测有但模板无 */
        EXTRA
    }

    public FeatureComparison() {
    }

    public FeatureComparison(String featureId, String featureName) {
        this.featureId = featureId;
        this.featureName = featureName;
    }

    public static FeatureComparison passed(
        TemplateFeature feature,
        Point detectedPosition,
        double xError,
        double yError
    ) {
        FeatureComparison comp = new FeatureComparison(feature.getId(), feature.getName());
        comp.templatePosition = feature.getPosition();
        comp.detectedPosition = detectedPosition;
        comp.xError = xError;
        comp.yError = yError;
        comp.totalError = Math.sqrt(xError * xError + yError * yError);
        comp.toleranceX = feature.getTolerance().getX();
        comp.toleranceY = feature.getTolerance().getY();
        comp.withinTolerance = true;
        comp.status = ComparisonStatus.PASSED;
        comp.classId = feature.getClassId();
        return comp;
    }

    public static FeatureComparison deviation(TemplateFeature feature, Point detectedPosition, double xError, double yError) {
        FeatureComparison comp = new FeatureComparison(feature.getId(), feature.getName());
        comp.templatePosition = feature.getPosition();
        comp.detectedPosition = detectedPosition;
        comp.xError = xError;
        comp.yError = yError;
        comp.totalError = Math.sqrt(xError * xError + yError * yError);
        comp.toleranceX = feature.getTolerance().getX();
        comp.toleranceY = feature.getTolerance().getY();
        comp.withinTolerance = false;
        comp.status = ComparisonStatus.DEVIATION_EXCEEDED;
        comp.classId = feature.getClassId();
        return comp;
    }

    public static FeatureComparison missing(TemplateFeature feature) {
        FeatureComparison comp = new FeatureComparison(feature.getId(), feature.getName());
        comp.templatePosition = feature.getPosition();
        comp.toleranceX = feature.getTolerance().getX();
        comp.toleranceY = feature.getTolerance().getY();
        comp.status = ComparisonStatus.MISSING;
        comp.withinTolerance = false;
        comp.classId = feature.getClassId();
        return comp;
    }

    public static FeatureComparison extra(String featureId, String featureName, Point position, int classId, double confidence) {
        FeatureComparison comp = new FeatureComparison(featureId, featureName);
        comp.detectedPosition = position;
        comp.status = ComparisonStatus.EXTRA;
        comp.withinTolerance = false;
        comp.classId = classId;
        comp.confidence = confidence;
        return comp;
    }

    // Getters and Setters
    public String getFeatureId() { return featureId; }
    public void setFeatureId(String featureId) { this.featureId = featureId; }

    public String getFeatureName() { return featureName; }
    public void setFeatureName(String featureName) { this.featureName = featureName; }

    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }

    public int getClassId() { return classId; }
    public void setClassId(int classId) { this.classId = classId; }

    public Point getTemplatePosition() { return templatePosition; }
    public void setTemplatePosition(Point templatePosition) { this.templatePosition = templatePosition; }

    public Point getDetectedPosition() { return detectedPosition; }
    public void setDetectedPosition(Point detectedPosition) { this.detectedPosition = detectedPosition; }

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

    public ComparisonStatus getStatus() { return status; }
    public void setStatus(ComparisonStatus status) { this.status = status; }

    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }

    @Override
    public String toString() {
        return String.format("FeatureComparison[%s: %s, status=%s, error=(%.2f,%.2f)]",
            featureId, featureName, status, xError, yError);
    }
}
