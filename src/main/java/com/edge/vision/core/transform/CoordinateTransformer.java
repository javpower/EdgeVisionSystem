package com.edge.vision.core.transform;

import com.edge.vision.core.template.model.AnchorPoint;
import com.edge.vision.core.template.model.Point;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 坐标转换器
 * <p>
 * 基于锚点对计算仿射变换矩阵，实现坐标系之间的转换
 */
@Component
public class CoordinateTransformer {
    private static final Logger logger = LoggerFactory.getLogger(CoordinateTransformer.class);

    private CoordinateTransform transform;
    private List<AnchorPoint> templateAnchors;
    private List<AnchorPoint> detectedAnchors;

    public CoordinateTransformer() {
        this.transform = new CoordinateTransform();
    }

    /**
     * 基于几何中心锚点计算变换（仅平移）
     *
     * @param templateCenter 模板几何中心
     * @param detectedCenter 检测几何中心
     */
    public void computeFromCenters(Point templateCenter, Point detectedCenter) {
        this.transform = CoordinateTransform.fromTranslation(templateCenter, detectedCenter);
        logger.info("Computed transform from centers: {}", transform);
    }

    /**
     * 基于多个锚点对计算仿射变换（支持旋转、缩放）
     * <p>
     * 使用最小二乘法拟合最优变换
     *
     * @param templateAnchors 模板锚点列表
     * @param detectedAnchors 检测锚点列表（与模板锚点一一对应）
     */
    public void computeTransform(
        List<AnchorPoint> templateAnchors,
        List<AnchorPoint> detectedAnchors
    ) {
        if (templateAnchors.size() != detectedAnchors.size()) {
            throw new IllegalArgumentException(
                "Template and detected anchor counts must match: " +
                templateAnchors.size() + " vs " + detectedAnchors.size()
            );
        }

        if (templateAnchors.size() < 1) {
            throw new IllegalArgumentException("At least 1 anchor pair required");
        }

        this.templateAnchors = templateAnchors;
        this.detectedAnchors = detectedAnchors;

        if (templateAnchors.size() == 1) {
            // 只有一个锚点，仅计算平移
            computeFromCenters(
                templateAnchors.get(0).getPosition(),
                detectedAnchors.get(0).getPosition()
            );
            return;
        }

        // 多个锚点，计算完整的仿射变换
        computeAffineTransform(templateAnchors, detectedAnchors);
        logger.info("Computed affine transform: {}", transform);
    }

    /**
     * 计算仿射变换（支持旋转、缩放）
     */
    private void computeAffineTransform(
        List<AnchorPoint> templateAnchors,
        List<AnchorPoint> detectedAnchors
    ) {
        int n = templateAnchors.size();

        // 计算质心
        Point templateCentroid = computeCentroid(templateAnchors);
        Point detectedCentroid = computeCentroid(detectedAnchors);

        // 计算平移
        double dx = templateCentroid.x - detectedCentroid.x;
        double dy = templateCentroid.y - detectedCentroid.y;

        // 计算旋转和缩放（基于 Procrustes 分析）
        double numXX = 0, numXY = 0, numYX = 0, numYY = 0;
        double denXX = 0, denYY = 0;

        for (int i = 0; i < n; i++) {
            Point tp = templateAnchors.get(i).getPosition();
            Point dp = detectedAnchors.get(i).getPosition();

            double tx = tp.x - templateCentroid.x;
            double ty = tp.y - templateCentroid.y;
            double dx_local = dp.x - detectedCentroid.x;
            double dy_local = dp.y - detectedCentroid.y;

            numXX += tx * dx_local;
            numXY += ty * dx_local;
            numYX += tx * dy_local;
            numYY += ty * dy_local;

            denXX += dx_local * dx_local;
            denYY += dy_local * dy_local;
        }

        // 计算旋转和缩放
        // 假设 scaleX = scaleY (各向同性缩放)
        double sigma = numXX + numYY;
        double delta = numXY - numYX;

        double scale = Math.sqrt(sigma * sigma + delta * delta) / (denXX + denYY);
        double theta = Math.atan2(delta, sigma);

        this.transform = new CoordinateTransform(dx, dy);
        this.transform.setCosTheta(Math.cos(theta));
        this.transform.setSinTheta(Math.sin(theta));
        this.transform.setScaleX(scale);
        this.transform.setScaleY(scale);
    }

    /**
     * 计算点的质心
     */
    private Point computeCentroid(List<AnchorPoint> anchors) {
        double sumX = 0, sumY = 0;
        for (AnchorPoint anchor : anchors) {
            sumX += anchor.getPosition().x;
            sumY += anchor.getPosition().y;
        }
        return new Point(sumX / anchors.size(), sumY / anchors.size());
    }

    /**
     * 将检测坐标转换到模板坐标系
     */
    public Point transformToTemplate(Point detectedPoint) {
        return transform.transformToTemplate(detectedPoint);
    }

    /**
     * 将模板坐标转换到检测坐标系
     */
    public Point transformFromTemplate(Point templatePoint) {
        return transform.transformFromTemplate(templatePoint);
    }

    /**
     * 批量转换检测坐标到模板坐标系
     */
    public List<Point> transformToTemplate(List<Point> detectedPoints) {
        return transform.transformToTemplate(detectedPoints);
    }

    /**
     * 获取当前变换
     */
    public CoordinateTransform getTransform() {
        return transform;
    }

    /**
     * 重置变换
     */
    public void reset() {
        this.transform = new CoordinateTransform();
        this.templateAnchors = null;
        this.detectedAnchors = null;
    }
}
