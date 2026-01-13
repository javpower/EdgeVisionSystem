package com.edge.vision.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 质检标准配置
 *
 * 支持多种比较操作符：
 * - ==: 等于（缺陷数量必须精确匹配）
 * - <=: 小于等于（缺陷数量不能超过阈值）
 * - >=: 大于等于（缺陷数量至少需要达到阈值，用于必需特征）
 * - <: 小于（缺陷数量必须少于阈值）
 * - >: 大于（缺陷数量必须多于阈值）
 *
 * 配置示例：
 * {
 *   "EKS": [
 *     {"defectType": "hole", "operator": "<=", "threshold": 20},
 *     {"defectType": "nut", "operator": "==", "threshold": 7}
 *   ],
 *   "OTHER_TYPE": [
 *     {"defectType": "scratch", "operator": "<=", "threshold": 5}
 *   ]
 * }
 */
@Data
public class QualityStandardConfig {
    private String version = "1.0";
    private String description = "Quality inspection standards with comparison operators";
    private long updatedAt = System.currentTimeMillis();

    /**
     * 工件类型与其质检标准的映射
     * key: 工件类型名称（如 "EKS"）
     * value: 该工件类型的缺陷检测标准列表
     */
    private java.util.Map<String, List<DefectStandard>> standards = new java.util.HashMap<>();

    /**
     * 单个缺陷类型的检测标准
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DefectStandard {
        /**
         * 缺陷类型名称（如 hole, nut, scratch 等）
         */
        private String defectType;

        /**
         * 比较操作符：==, <=, >=, <, >
         * - ==: 缺陷数量必须精确等于阈值（用于必需特征计数）
         * - <=: 缺陷数量不能超过阈值（常规缺陷限制）
         * - >=: 缺陷数量至少需要达到阈值（用于必需特征，如必须有至少2个孔）
         * - <: 缺陷数量必须少于阈值
         * - >: 缺陷数量必须多于阈值
         */
        private String operator = "<=";

        /**
         * 阈值
         */
        private int threshold;

        public DefectStandard() {}

        public DefectStandard(String defectType, String operator, int threshold) {
            this.defectType = defectType;
            this.operator = operator;
            this.threshold = threshold;
        }

        /**
         * 评估实际缺陷数量是否符合标准
         *
         * @param actualCount 实际检测到的缺陷数量
         * @return true 表示符合标准（合格），false 表示不符合（不合格）
         */
        public boolean evaluate(int actualCount) {
            switch (operator) {
                case "==":
                    return actualCount == threshold;
                case "<=":
                    return actualCount <= threshold;
                case ">=":
                    return actualCount >= threshold;
                case "<":
                    return actualCount < threshold;
                case ">":
                    return actualCount > threshold;
                default:
                    // 默认使用 <=
                    return actualCount <= threshold;
            }
        }

        /**
         * 获取此标准的描述
         */
        public String getDescription() {
            switch (operator) {
                case "==":
                    return defectType + " 数量必须等于 " + threshold;
                case "<=":
                    return defectType + " 数量不超过 " + threshold;
                case ">=":
                    return defectType + " 数量至少 " + threshold;
                case "<":
                    return defectType + " 数量少于 " + threshold;
                case ">":
                    return defectType + " 数量多于 " + threshold;
                default:
                    return defectType + " " + operator + " " + threshold;
            }
        }
    }
}
