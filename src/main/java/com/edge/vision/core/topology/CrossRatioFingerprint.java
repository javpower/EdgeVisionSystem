package com.edge.vision.core.topology;

import com.edge.vision.core.template.model.Point;
import java.util.Objects;

/**
 * 射影几何指纹
 * <p>
 * 基于交比（Cross-ratio）不变性的三维指纹向量
 * <p>
 * 核心原理：
 * 对于任意共线四点A、B、C、D，其交比定义为：
 * CR(A,B;C,D) = (AC * BD) / (AD * BC)
 * <p>
 * 在射影变换（平移、旋转、缩放、透视）下，交比值严格保持不变。
 * <p>
 * 三维指纹向量 F(P) = [CR1, CR2, CR3]
 * - CR1: 交比 CR(P,A,B,C,D) - 基于四角参考系
 * - CR2: 交比 CR(P,A,B,C,E) - 基于五点参考系（E为黄金分割点）
 * - CR3: 交比 CR(P,A,B,C,E') - 基于对称点校验
 */
public class CrossRatioFingerprint {
    /**
     * 第一维交比：基于四角 A,B,C,D
     * CR1 = CR(P,A,B,C,D)
     */
    private final double cr1;

    /**
     * 第二维交比：基于四角 A,B,C,E
     * E为黄金分割点 (w*0.618, h*0.382)
     */
    private final double cr2;

    /**
     * 第三维交比：基于四角 A,B,C,E'
     * E'为E关于画布中心的对称点
     */
    private final double cr3;

    /**
     * 关联点的ID（用于调试）
     */
    private final String pointId;

    /**
     * 指纹的有效性标记
     * 当点位于参考点射线上时，交比退化，指纹无效
     */
    private final boolean valid;

    public CrossRatioFingerprint(double cr1, double cr2, double cr3, String pointId) {
        this.cr1 = cr1;
        this.cr2 = cr2;
        this.cr3 = cr3;
        this.pointId = pointId;
        // 检查指纹是否有效（非NaN、非无穷大）
        this.valid = isValidValue(cr1) && isValidValue(cr2) && isValidValue(cr3);
    }

    /**
     * 构造无效指纹（用于错误处理）
     */
    public static CrossRatioFingerprint invalid(String pointId) {
        return new CrossRatioFingerprint(Double.NaN, Double.NaN, Double.NaN, pointId);
    }

    /**
     * 检查数值是否有效
     */
    private static boolean isValidValue(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    /**
     * 计算两个指纹之间的欧氏距离
     * <p>
     * 距离 = sqrt((cr1-cr1')^2 + (cr2-cr2')^2 + (cr3-cr3')^2)
     */
    public double distanceTo(CrossRatioFingerprint other) {
        if (!this.valid || !other.valid) {
            return Double.MAX_VALUE;
        }
        double d1 = this.cr1 - other.cr1;
        double d2 = this.cr2 - other.cr2;
        double d3 = this.cr3 - other.cr3;
        return Math.sqrt(d1 * d1 + d2 * d2 + d3 * d3);
    }

    /**
     * 计算加权距离（考虑不同维度的重要性）
     * <p>
     * cr2权重更高，因为黄金分割点提供更好的唯一性
     */
    public double weightedDistanceTo(CrossRatioFingerprint other) {
        if (!this.valid || !other.valid) {
            return Double.MAX_VALUE;
        }
        double d1 = (this.cr1 - other.cr1) * 1.0;
        double d2 = (this.cr2 - other.cr2) * 1.5;  // cr2权重更高
        double d3 = (this.cr3 - other.cr3) * 0.8;
        return Math.sqrt(d1 * d1 + d2 * d2 + d3 * d3);
    }

    /**
     * 计算相似度分数（0-1之间，1表示完全相同）
     */
    public double similarityTo(CrossRatioFingerprint other, double tolerance) {
        double distance = weightedDistanceTo(other);
        // 使用高斯核函数转换距离为相似度
        // 相似度 = exp(-distance^2 / (2 * tolerance^2))
        return Math.exp(-(distance * distance) / (2 * tolerance * tolerance));
    }

    public double getCr1() {
        return cr1;
    }

    public double getCr2() {
        return cr2;
    }

    public double getCr3() {
        return cr3;
    }

    public String getPointId() {
        return pointId;
    }

    public boolean isValid() {
        return valid;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CrossRatioFingerprint that = (CrossRatioFingerprint) o;
        // 指纹相等判断使用容差而非精确相等
        final double EPSILON = 1e-6;
        return Math.abs(cr1 - that.cr1) < EPSILON &&
                Math.abs(cr2 - that.cr2) < EPSILON &&
                Math.abs(cr3 - that.cr3) < EPSILON;
    }

    @Override
    public int hashCode() {
        return Objects.hash(pointId);
    }

    @Override
    public String toString() {
        if (!valid) {
            return String.format("Fingerprint[%s: INVALID]", pointId);
        }
        return String.format("Fingerprint[%s: (%.4f, %.4f, %.4f)]",
                pointId, cr1, cr2, cr3);
    }

    /**
     * 五点参考系配置
     */
    public static class ReferencePoints {
        /** 左上角 A */
        public final Point A;
        /** 右上角 B */
        public final Point B;
        /** 右下角 C */
        public final Point C;
        /** 左下角 D */
        public final Point D;
        /** 黄金分割点 E */
        public final Point E;
        /** 对称点 E' */
        public final Point EPrime;
        /** 画布宽度 */
        public final double width;
        /** 画布高度 */
        public final double height;

        /**
         * 创建标准五点参考系
         * <p>
         * 布局：
         * A -------- B
         * |    E     |
         * |          |
         * D -------- C
         * <p>
         * E = (w * 0.618, h * 0.382) - 黄金分割点
         * E' = (w * 0.382, h * 0.618) - 中心对称点
         */
        public ReferencePoints(double width, double height) {
            this.width = width;
            this.height = height;
            this.A = new Point(0, 0);
            this.B = new Point(width, 0);
            this.C = new Point(width, height);
            this.D = new Point(0, height);
            // 黄金分割点 - 天然不对称，数值稳定
            this.E = new Point(width * 0.618, height * 0.382);
            // 中心对称点 - 用于二次校验
            this.EPrime = new Point(width * 0.382, height * 0.618);
        }

        /**
         * 创建自定义五点参考系（支持ROI）
         */
        public ReferencePoints(Point topLeft, Point topRight,
                              Point bottomRight, Point bottomLeft) {
            this.A = topLeft;
            this.B = topRight;
            this.C = bottomRight;
            this.D = bottomLeft;
            this.width = B.x - A.x;
            this.height = D.y - A.y;
            this.E = new Point(A.x + width * 0.618, A.y + height * 0.382);
            this.EPrime = new Point(A.x + width * 0.382, A.y + height * 0.618);
        }

        /**
         * 检查参考点是否有效
         */
        public boolean isValid() {
            return width > 0 && height > 0 &&
                    isValidValue(A.x) && isValidValue(A.y) &&
                    isValidValue(B.x) && isValidValue(B.y) &&
                    isValidValue(C.x) && isValidValue(C.y) &&
                    isValidValue(D.x) && isValidValue(D.y);
        }

        private static boolean isValidValue(double value) {
            return !Double.isNaN(value) && !Double.isInfinite(value);
        }

        @Override
        public String toString() {
            return String.format("ReferencePoints[w=%.1f, h=%.1f, E=(%.1f,%.1f), E'=(%.1f,%.1f)]",
                    width, height, E.x, E.y, EPrime.x, EPrime.y);
        }
    }

    /**
     * 指纹计算器
     * <p>
     * 负责计算任意点的交比指纹
     */
    public static class Calculator {
        private final ReferencePoints refPoints;

        public Calculator(ReferencePoints refPoints) {
            this.refPoints = refPoints;
        }

        /**
         * 计算点的交比指纹
         * <p>
         * F(P) = [CR1, CR2, CR3]
         * - CR1 = CR(P,A,B,C,D) = CR(PA,PB;PC,PD)
         * - CR2 = CR(P,A,B,C,E) = CR(PA,PB;PC,PE)
         * - CR3 = CR(P,A,B,C,E') = CR(PA,PB;PC,PE')
         * <p>
         * 线束交比公式：
         * CR(PA,PB;PC,PD) = sin(∠APC)/sin(∠BPC) / (sin(∠APD)/sin(∠BPD))
         */
        public CrossRatioFingerprint calculate(Point P, String pointId) {
            if (!refPoints.isValid()) {
                return CrossRatioFingerprint.invalid(pointId);
            }

            // 计算三个交比
            double cr1 = calculateCrossRatio(P, refPoints.A, refPoints.B,
                    refPoints.C, refPoints.D);
            double cr2 = calculateCrossRatio(P, refPoints.A, refPoints.B,
                    refPoints.C, refPoints.E);
            double cr3 = calculateCrossRatio(P, refPoints.A, refPoints.B,
                    refPoints.C, refPoints.EPrime);

            return new CrossRatioFingerprint(cr1, cr2, cr3, pointId);
        }

        /**
         * 计算线束交比
         * <p>
         * 对于点P和四个参考点A,B,C,D，计算：
         * CR(PA,PB;PC,PD) = sin(∠APC)/sin(∠BPC) / (sin(∠APD)/sin(∠BPD))
         * <p>
         * 这是线束交比的正确计算方式，使用角度
         */
        private double calculateCrossRatio(Point P, Point A, Point B,
                                          Point C, Point D) {
            // 使用角度计算线束交比
            double angleAPC = calculateAngle(P, A, C);
            double angleBPC = calculateAngle(P, B, C);
            double angleAPD = calculateAngle(P, A, D);
            double angleBPD = calculateAngle(P, B, D);

            double sinAPC = Math.sin(angleAPC);
            double sinBPC = Math.sin(angleBPC);
            double sinAPD = Math.sin(angleAPD);
            double sinBPD = Math.sin(angleBPD);

            if (Math.abs(sinBPC) < 1e-10 || Math.abs(sinBPD) < 1e-10) {
                return Double.NaN;  // 避免除零
            }

            double ratio1 = sinAPC / sinBPC;
            double ratio2 = sinAPD / sinBPD;

            if (Math.abs(ratio2) < 1e-10) {
                return Double.NaN;
            }

            return ratio1 / ratio2;
        }

        /**
         * 使用角度计算更精确的线束交比
         * <p>
         * CR(PA,PB;PC,PD) = sin(∠APC)/sin(∠BPC) / (sin(∠APD)/sin(∠BPD))
         */
        private double calculateAngularCrossRatio(Point P, Point A, Point B,
                                                  Point C, Point D) {
            double angleAPC = calculateAngle(P, A, C);
            double angleBPC = calculateAngle(P, B, C);
            double angleAPD = calculateAngle(P, A, D);
            double angleBPD = calculateAngle(P, B, D);

            double sinAPC = Math.sin(angleAPC);
            double sinBPC = Math.sin(angleBPC);
            double sinAPD = Math.sin(angleAPD);
            double sinBPD = Math.sin(angleBPD);

            if (Math.abs(sinBPC) < 1e-10 || Math.abs(sinBPD) < 1e-10) {
                return Double.NaN;  // 避免除零
            }

            double ratio1 = sinAPC / sinBPC;
            double ratio2 = sinAPD / sinBPD;

            if (Math.abs(ratio2) < 1e-10) {
                return Double.NaN;
            }

            return ratio1 / ratio2;
        }

        /**
         * 计算角度 ∠APB（点P处，由PA和PB形成的夹角）
         */
        private double calculateAngle(Point P, Point A, Point B) {
            double ax = A.x - P.x;
            double ay = A.y - P.y;
            double bx = B.x - P.x;
            double by = B.y - P.y;

            // 使用点积公式计算夹角
            // cos(θ) = (A·B) / (|A|*|B|)
            double dotProduct = ax * bx + ay * by;
            double magnitudeA = Math.sqrt(ax * ax + ay * ay);
            double magnitudeB = Math.sqrt(bx * bx + by * by);

            if (magnitudeA < 1e-10 || magnitudeB < 1e-10) {
                return 0.0;
            }

            double cosTheta = dotProduct / (magnitudeA * magnitudeB);
            // 限制范围以避免数值误差
            cosTheta = Math.max(-1.0, Math.min(1.0, cosTheta));

            return Math.acos(cosTheta);
        }

        public ReferencePoints getReferencePoints() {
            return refPoints;
        }
    }
}
