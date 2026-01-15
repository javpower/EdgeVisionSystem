package com.edge.vision.core.quality;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 质量检测结果
 */
public class InspectionResult {
    private String templateId;
    private boolean passed;
    private List<FeatureComparison> comparisons;
    private long processingTimeMs;
    private String message;

    public InspectionResult() {
        this.comparisons = new ArrayList<>();
        this.passed = true;
    }

    public InspectionResult(String templateId) {
        this();
        this.templateId = templateId;
    }

    /**
     * 添加比对结果
     */
    public void addComparison(FeatureComparison comparison) {
        this.comparisons.add(comparison);
        if (comparison.getStatus() != FeatureComparison.ComparisonStatus.PASSED) {
            this.passed = false;
        }
    }

    /**
     * 获取所有漏检特征
     */
    public List<FeatureComparison> getMissingFeatures() {
        return comparisons.stream()
            .filter(c -> c.getStatus() == FeatureComparison.ComparisonStatus.MISSING)
            .collect(Collectors.toList());
    }

    /**
     * 获取所有错检特征
     */
    public List<FeatureComparison> getExtraFeatures() {
        return comparisons.stream()
            .filter(c -> c.getStatus() == FeatureComparison.ComparisonStatus.EXTRA)
            .collect(Collectors.toList());
    }

    /**
     * 获取所有偏差过大的特征
     */
    public List<FeatureComparison> getDeviations() {
        return comparisons.stream()
            .filter(c -> c.getStatus() == FeatureComparison.ComparisonStatus.DEVIATION_EXCEEDED)
            .collect(Collectors.toList());
    }

    /**
     * 获取所有合格的特征
     */
    public List<FeatureComparison> getPassedFeatures() {
        return comparisons.stream()
            .filter(c -> c.getStatus() == FeatureComparison.ComparisonStatus.PASSED)
            .collect(Collectors.toList());
    }

    /**
     * 获取统计摘要
     */
    public InspectionSummary getSummary() {
        return new InspectionSummary(
            comparisons.size(),
            getPassedFeatures().size(),
            getMissingFeatures().size(),
            getExtraFeatures().size(),
            getDeviations().size()
        );
    }

    // Getters and Setters
    public String getTemplateId() { return templateId; }
    public void setTemplateId(String templateId) { this.templateId = templateId; }

    public boolean isPassed() { return passed; }
    public void setPassed(boolean passed) { this.passed = passed; }

    public List<FeatureComparison> getComparisons() { return comparisons; }
    public void setComparisons(List<FeatureComparison> comparisons) { this.comparisons = comparisons; }

    public long getProcessingTimeMs() { return processingTimeMs; }
    public void setProcessingTimeMs(long processingTimeMs) { this.processingTimeMs = processingTimeMs; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    @Override
    public String toString() {
        InspectionSummary summary = getSummary();
        return String.format("InspectionResult[%s: %s - total=%d, passed=%d, missing=%d, extra=%d, deviation=%d]",
            templateId, passed ? "PASSED" : "FAILED",
            summary.totalFeatures, summary.passed,
            summary.missing, summary.extra, summary.deviation);
    }

    /**
     * 检测摘要
     */
    public static class InspectionSummary {
        public final int totalFeatures;
        public final int passed;
        public final int missing;
        public final int extra;
        public final int deviation;

        public InspectionSummary(int total, int passed, int missing, int extra, int deviation) {
            this.totalFeatures = total;
            this.passed = passed;
            this.missing = missing;
            this.extra = extra;
            this.deviation = deviation;
        }

        @Override
        public String toString() {
            return String.format("Summary[total=%d, passed=%d, missing=%d, extra=%d, deviation=%d]",
                totalFeatures, passed, missing, extra, deviation);
        }
    }
}
