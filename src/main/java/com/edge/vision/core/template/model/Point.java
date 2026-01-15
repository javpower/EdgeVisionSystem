package com.edge.vision.core.template.model;

/**
 * 二维点
 */
public class Point {
    public double x;
    public double y;

    public Point() {
    }

    public Point(double x, double y) {
        this.x = x;
        this.y = y;
    }

    /**
     * 计算到另一个点的欧几里得距离
     */
    public double distanceTo(Point other) {
        double dx = this.x - other.x;
        double dy = this.y - other.y;
        return Math.sqrt(dx * dx + dy * dy);
    }

    /**
     * 计算到另一个点的曼哈顿距离
     */
    public double manhattanDistanceTo(Point other) {
        return Math.abs(this.x - other.x) + Math.abs(this.y - other.y);
    }

    @Override
    public String toString() {
        return String.format("(%.2f, %.2f)", x, y);
    }
}
