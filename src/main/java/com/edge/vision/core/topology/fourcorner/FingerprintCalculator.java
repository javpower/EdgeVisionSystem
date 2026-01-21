package com.edge.vision.core.topology.fourcorner;

import com.edge.vision.core.template.model.Point;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 仿射不变量计算器
 * <p>
 * 功能：计算特征点到四个角的仿射不变量指纹
 * <p>
 * 计算的不变量：
 * 1. 距离比例 [r1,r2,r3,r4]: 归一化到最小距离=1，缩放不变
 * 2. 相对角度 [0,Δθ2,Δθ3,Δθ4]: 以角1为基准，旋转不变
 * 3. 重心坐标 [w1,w2,w3,w4]: 点在四边形内的相对位置，平移不变
 */
public class FingerprintCalculator {
    private static final Logger logger = LoggerFactory.getLogger(FingerprintCalculator.class);

    /**
     * 计算特征点的指纹
     *
     * @param featurePos  特征点位置
     * @param corners     四个角坐标 [TL, TR, BR, BL]
     * @param featureId   特征点ID
     * @return 特征点指纹
     */
    public FeatureFingerprint calculate(Point featurePos, Point[] corners, String featureId) {
        if (corners == null || corners.length != 4) {
            throw new IllegalArgumentException("Must have exactly 4 corners");
        }

        // 计算原始距离和角度
        double[] distances = new double[4];
        double[] angles = new double[4];

        for (int i = 0; i < 4; i++) {
            double dx = corners[i].x - featurePos.x;
            double dy = corners[i].y - featurePos.y;
            distances[i] = Math.sqrt(dx * dx + dy * dy);
            angles[i] = Math.atan2(dy, dx);
        }

        // 计算距离比例（归一化到最小距离=1）
        double[] distanceRatios = normalizeDistances(distances);

        // 计算相对角度（以角0为基准）
        double[] relativeAngles = normalizeAngles(angles);

        // 计算重心坐标
        double[] barycentricCoords = calculateBarycentric(featurePos, corners);

        if (logger.isTraceEnabled()) {
            logger.trace("Fingerprint for {}: ratios={}, angles={}, bary={}",
                featureId,
                java.util.Arrays.toString(distanceRatios),
                java.util.Arrays.toString(relativeAngles),
                java.util.Arrays.toString(barycentricCoords));
        }

        return new FeatureFingerprint.Builder()
            .featureId(featureId)
            .distanceRatios(distanceRatios)
            .relativeAngles(relativeAngles)
            .barycentricCoords(barycentricCoords)
            .rawDistances(distances)
            .rawAngles(angles)
            .build();
    }

    /**
     * 归一化距离（缩放不变）
     * <p>
     * 将所有距离除以最小值，得到比例
     */
    private double[] normalizeDistances(double[] distances) {
        double[] ratios = new double[4];
        double minDist = Double.MAX_VALUE;

        for (double d : distances) {
            if (d > 0 && d < minDist) {
                minDist = d;
            }
        }

        if (minDist <= 0 || minDist == Double.MAX_VALUE) {
            // 所有点重合，返回单位向量
            for (int i = 0; i < 4; i++) {
                ratios[i] = 1.0;
            }
        } else {
            for (int i = 0; i < 4; i++) {
                ratios[i] = distances[i] / minDist;
            }
        }

        return ratios;
    }

    /**
     * 归一化角度（旋转不变）
     * <p>
     * 将所有角度减去第一个角度，得到相对角度
     */
    private double[] normalizeAngles(double[] angles) {
        double[] relative = new double[4];
        double baseAngle = angles[0];

        for (int i = 0; i < 4; i++) {
            double diff = angles[i] - baseAngle;
            // 归一化到 [-π, π]
            while (diff > Math.PI) diff -= 2 * Math.PI;
            while (diff < -Math.PI) diff += 2 * Math.PI;
            relative[i] = diff;
        }

        return relative;
    }

    /**
     * 计算重心坐标（平移不变）
     * <p>
     * 对于任意四边形，使用双线性插值计算重心坐标
     * <p>
     * 原理：
     * 点 P 可以表示为：P = w0*TL + w1*TR + w2*BR + w3*BL
     * 其中 w0 + w1 + w2 + w3 = 1
     * <p>
     * 对于平行四边形，这是线性问题
     * 对于一般四边形，需要迭代求解
     */
    private double[] calculateBarycentric(Point p, Point[] corners) {
        // 简化方法：使用基于面积的权重
        // 将四边形分成4个三角形，计算面积权重

        Point tl = corners[0]; // Top-Left
        Point tr = corners[1]; // Top-Right
        Point br = corners[2]; // Bottom-Right
        Point bl = corners[3]; // Bottom-Left

        // 计算四个三角形的面积
        double areaOppositeTL = triangleArea(p, tr, br);
        double areaOppositeTR = triangleArea(p, br, bl);
        double areaOppositeBR = triangleArea(p, bl, tl);
        double areaOppositeBL = triangleArea(p, tl, tr);

        double totalArea = areaOppositeTL + areaOppositeTR +
                          areaOppositeBR + areaOppositeBL;

        if (totalArea < 1e-10) {
            // 点在边界上或四边形退化
            return new double[]{0.25, 0.25, 0.25, 0.25};
        }

        // 重心坐标与对角面积成反比
        double w0 = areaOppositeTL / totalArea; // 权重对应TL
        double w1 = areaOppositeTR / totalArea; // 权重对应TR
        double w2 = areaOppositeBR / totalArea; // 权重对应BR
        double w3 = areaOppositeBL / totalArea; // 权重对应BL

        return new double[]{w0, w1, w2, w3};
    }

    /**
     * 计算三角形面积
     */
    private double triangleArea(Point p1, Point p2, Point p3) {
        // Shoelace公式
        double area = 0.5 * Math.abs(
            p1.x * (p2.y - p3.y) +
            p2.x * (p3.y - p1.y) +
            p3.x * (p1.y - p2.y)
        );
        return area;
    }

    /**
     * 验证四边形是否有效
     *
     * @param corners 四个角坐标
     * @return 是否有效
     */
    public boolean isValidQuadrilateral(Point[] corners) {
        if (corners == null || corners.length != 4) {
            return false;
        }

        // 检查是否有重复点
        for (int i = 0; i < 4; i++) {
            for (int j = i + 1; j < 4; j++) {
                double dist = Math.sqrt(
                    Math.pow(corners[i].x - corners[j].x, 2) +
                    Math.pow(corners[i].y - corners[j].y, 2)
                );
                if (dist < 10) { // 小于10像素认为是重复点
                    logger.warn("Corner {} and {} are too close: {} pixels", i, j, dist);
                    return false;
                }
            }
        }

        // 检查四边形面积
        double area = quadrilateralArea(corners);
        if (area < 1000) { // 面积太小
            logger.warn("Quadrilateral area too small: {}", area);
            return false;
        }

        return true;
    }

    /**
     * 计算四边形面积
     */
    private double quadrilateralArea(Point[] corners) {
        double area1 = triangleArea(corners[0], corners[1], corners[2]);
        double area2 = triangleArea(corners[0], corners[2], corners[3]);
        return area1 + area2;
    }
}
