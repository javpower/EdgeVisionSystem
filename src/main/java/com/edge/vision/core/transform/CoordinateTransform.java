package com.edge.vision.core.transform;

import com.edge.vision.core.template.model.AnchorPoint;
import com.edge.vision.core.template.model.Point;

import java.util.ArrayList;
import java.util.List;

/**
 * 坐标转换
 * 表示从检测坐标系到模板坐标系的仿射变换
 */
public class CoordinateTransform {
    private double dx;        // X 平移
    private double dy;        // Y 平移
    private double cosTheta;  // 旋转角的余弦
    private double sinTheta;  // 旋转角的正弦
    private double scaleX;    // X 缩放
    private double scaleY;    // Y 缩放

    public CoordinateTransform() {
        this.dx = 0;
        this.dy = 0;
        this.cosTheta = 1.0;
        this.sinTheta = 0.0;
        this.scaleX = 1.0;
        this.scaleY = 1.0;
    }

    public CoordinateTransform(double dx, double dy) {
        this();
        this.dx = dx;
        this.dy = dy;
    }

    /**
     * 从锚点对计算变换（仅平移，基于几何中心）
     */
    public static CoordinateTransform fromTranslation(Point templatePoint, Point detectedPoint) {
        double dx = templatePoint.x - detectedPoint.x;
        double dy = templatePoint.y - detectedPoint.y;
        return new CoordinateTransform(dx, dy);
    }

    /**
     * 将检测坐标转换到模板坐标系
     */
    public Point transformToTemplate(Point detectedPoint) {
        double x = detectedPoint.x;
        double y = detectedPoint.y;

        // 缩放
        x *= scaleX;
        y *= scaleY;

        // 旋转
        double rotatedX = x * cosTheta - y * sinTheta;
        double rotatedY = x * sinTheta + y * cosTheta;

        // 平移
        return new Point(rotatedX + dx, rotatedY + dy);
    }

    /**
     * 将模板坐标转换到检测坐标系（逆变换）
     */
    public Point transformFromTemplate(Point templatePoint) {
        // 逆平移
        double x = templatePoint.x - dx;
        double y = templatePoint.y - dy;

        // 逆旋转
        double unrotatedX = x * cosTheta + y * sinTheta;
        double unrotatedY = -x * sinTheta + y * cosTheta;

        // 逆缩放
        return new Point(unrotatedX / scaleX, unrotatedY / scaleY);
    }

    /**
     * 批量转换检测坐标到模板坐标系
     */
    public List<Point> transformToTemplate(List<Point> detectedPoints) {
        List<Point> result = new ArrayList<>(detectedPoints.size());
        for (Point p : detectedPoints) {
            result.add(transformToTemplate(p));
        }
        return result;
    }

    // Getters
    public double getDx() { return dx; }
    public double getDy() { return dy; }
    public double getCosTheta() { return cosTheta; }
    public double getSinTheta() { return sinTheta; }
    public double getScaleX() { return scaleX; }
    public double getScaleY() { return scaleY; }

    public double getRotationAngle() {
        return Math.atan2(sinTheta, cosTheta);
    }

    public void setDx(double dx) { this.dx = dx; }
    public void setDy(double dy) { this.dy = dy; }
    public void setCosTheta(double cosTheta) { this.cosTheta = cosTheta; }
    public void setSinTheta(double sinTheta) { this.sinTheta = sinTheta; }
    public void setScaleX(double scaleX) { this.scaleX = scaleX; }
    public void setScaleY(double scaleY) { this.scaleY = scaleY; }

    @Override
    public String toString() {
        return String.format("Transform[dx=%.2f, dy=%.2f, rotation=%.2f°, scale=(%.2f,%.2f)]",
            dx, dy, Math.toDegrees(getRotationAngle()), scaleX, scaleY);
    }
}
