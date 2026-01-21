package com.edge.vision.core.topology.fourcorner;

import java.util.Arrays;

/**
 * 特征点数字指纹（仿射不变量）
 * <p>
 * 核心思想：
 * - 计算特征点到四角的位置关系
 * - 这些关系在仿射变换下保持不变
 * <p>
 * 不变量说明：
 * 1. 距离比例 [r1,r2,r3,r4]: 缩放不变
 * 2. 角度差 [0,Δθ2,Δθ3,Δθ4]: 旋转不变
 * 3. 重心坐标 [w1,w2,w3,w4]: 平移不变
 */
public class FeatureFingerprint {
    // 特征点ID
    public final String featureId;

    // 距离比例（归一化到最小距离=1）
    public final double[] distanceRatios; // [r1, r2, r3, r4]

    // 相对角度（以到角1的角度为基准，减去θ1）
    public final double[] relativeAngles; // [0, Δθ2, Δθ3, Δθ4] 弧度

    // 重心坐标（点在四边形内的相对位置）
    public final double[] barycentricCoords; // [w1, w2, w3, w4], sum = 1

    // 原始距离（用于调试）
    public final double[] rawDistances; // [d1, d2, d3, d4]

    // 原始角度（用于调试）
    public final double[] rawAngles; // [θ1, θ2, θ3, θ4] 弧度

    private FeatureFingerprint(Builder builder) {
        this.featureId = builder.featureId;
        this.distanceRatios = builder.distanceRatios;
        this.relativeAngles = builder.relativeAngles;
        this.barycentricCoords = builder.barycentricCoords;
        this.rawDistances = builder.rawDistances;
        this.rawAngles = builder.rawAngles;
    }

    /**
     * 计算两个指纹的相似度
     * <p>
     * 综合考虑：
     * 1. 距离比例差异
     * 2. 角度差异
     * 3. 重心坐标差异
     *
     * @param other 另一个指纹
     * @return 相似度分数（越小越相似，0表示完全相同）
     */
    public double similarity(FeatureFingerprint other) {
        // 距离比例差异（加权）
        double distDiff = 0;
        for (int i = 0; i < 4; i++) {
            distDiff += Math.abs(distanceRatios[i] - other.distanceRatios[i]);
        }

        // 角度差异（需要处理周期性）
        double angleDiff = 0;
        for (int i = 0; i < 4; i++) {
            double diff = Math.abs(relativeAngles[i] - other.relativeAngles[i]);
            // 归一化到 [0, π]
            diff = Math.min(diff, 2 * Math.PI - diff);
            angleDiff += diff;
        }

        // 重心坐标差异
        double baryDiff = 0;
        for (int i = 0; i < 4; i++) {
            baryDiff += Math.abs(barycentricCoords[i] - other.barycentricCoords[i]);
        }

        // 加权组合（根据经验调整）
        return 1.0 * distDiff + 0.5 * angleDiff + 2.0 * baryDiff;
    }

    /**
     * 判断两个指纹是否匹配
     *
     * @param other   另一个指纹
     * @param tolerance 容差阈值
     * @return 是否匹配
     */
    public boolean matches(FeatureFingerprint other, double tolerance) {
        return similarity(other) < tolerance;
    }

    @Override
    public String toString() {
        return String.format("Fingerprint[%s: ratios=%s, angles=%s, bary=%s]",
            featureId,
            Arrays.toString(distanceRatios),
            Arrays.toString(relativeAngles),
            Arrays.toString(barycentricCoords));
    }

    public static class Builder {
        private String featureId;
        private double[] distanceRatios;
        private double[] relativeAngles;
        private double[] barycentricCoords;
        private double[] rawDistances;
        private double[] rawAngles;

        public Builder featureId(String id) {
            this.featureId = id;
            return this;
        }

        public Builder distanceRatios(double[] ratios) {
            this.distanceRatios = ratios;
            return this;
        }

        public Builder relativeAngles(double[] angles) {
            this.relativeAngles = angles;
            return this;
        }

        public Builder barycentricCoords(double[] coords) {
            this.barycentricCoords = coords;
            return this;
        }

        public Builder rawDistances(double[] distances) {
            this.rawDistances = distances;
            return this;
        }

        public Builder rawAngles(double[] angles) {
            this.rawAngles = angles;
            return this;
        }

        public FeatureFingerprint build() {
            return new FeatureFingerprint(this);
        }
    }
}
