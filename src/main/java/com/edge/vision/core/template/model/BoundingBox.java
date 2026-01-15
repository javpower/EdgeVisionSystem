package com.edge.vision.core.template.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 边界框
 * 用于确定包含所有细节的最小范围
 */
public class BoundingBox {
    private double minX;
    private double maxX;
    private double minY;
    private double maxY;

    public BoundingBox() {
    }

    public BoundingBox(double minX, double maxX, double minY, double maxY) {
        this.minX = minX;
        this.maxX = maxX;
        this.minY = minY;
        this.maxY = maxY;
    }

    @JsonIgnore
    public double getWidth() {
        return maxX - minX;
    }

    @JsonIgnore
    public double getHeight() {
        return maxY - minY;
    }

    @JsonIgnore
    public Point getCenter() {
        return new Point((minX + maxX) / 2.0, (minY + maxY) / 2.0);
    }

    @JsonIgnore
    public Point getTopCenter() {
        return new Point(getCenter().x, minY);
    }

    @JsonIgnore
    public Point getRightCenter() {
        return new Point(maxX, getCenter().y);
    }

    @JsonIgnore
    public Point getBottomCenter() {
        return new Point(getCenter().x, maxY);
    }

    @JsonIgnore
    public Point getLeftCenter() {
        return new Point(minX, getCenter().y);
    }

    // Getters and Setters
    public double getMinX() { return minX; }
    public void setMinX(double minX) { this.minX = minX; }

    public double getMaxX() { return maxX; }
    public void setMaxX(double maxX) { this.maxX = maxX; }

    public double getMinY() { return minY; }
    public void setMinY(double minY) { this.minY = minY; }

    public double getMaxY() { return maxY; }
    public void setMaxY(double maxY) { this.maxY = maxY; }

    @Override
    public String toString() {
        return String.format("BoundingBox[%.2f,%.2f - %.2f,%.2f] (%.2f x %.2f)",
            minX, minY, maxX, maxY, getWidth(), getHeight());
    }
}
