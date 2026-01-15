package com.edge.vision.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConfirmResponse {
    private String status;
    private String message;
    private ConfirmData data;

    @JsonProperty("result_image")
    private String resultImage; // base64编码的结果图

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConfirmData {
        @JsonProperty("device_id")
        private String deviceId;

        private long timestamp;

        @JsonProperty("batch_info")
        private BatchInfo batchInfo;

        private AnalysisResult analysis;

        @JsonProperty("result_image")
        private String resultImage;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BatchInfo {
        @JsonProperty("part_name")
        private String partName;

        @JsonProperty("batch_id")
        private String batchId;

        private String operator;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AnalysisResult {
        @JsonProperty("defect_count")
        private int defectCount;

        private java.util.List<Detection> details;

        @JsonProperty("quality_status")
        private String qualityStatus; // PASS/FAIL

        /**
         * 模板比对结果（仅在使用模板比对模式时有值）
         * 包含每个特征的精确位置对比信息
         */
        private java.util.List<com.edge.vision.service.QualityStandardService.QualityEvaluationResult.TemplateComparison> templateComparisons;
    }
}