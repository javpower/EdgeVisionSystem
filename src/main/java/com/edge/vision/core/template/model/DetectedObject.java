package com.edge.vision.core.template.model;

/**
 * 检测到的对象
 * 来自 YOLO 模型的检测结果
 */
public class DetectedObject {
    private int classId;
    private String className;
    private Point center;           // 中心点坐标
    private double width;           // 宽度
    private double height;          // 高度
    private double confidence;      // 置信度

    public DetectedObject() {
    }

    public DetectedObject(int classId, Point center, double width, double height) {
        this.classId = classId;
        this.center = center;
        this.width = width;
        this.height = height;
    }

    /**
     * 获取边界框的左上角坐标
     */
    public Point getTopLeft() {
        return new Point(center.x - width / 2, center.y - height / 2);
    }

    /**
     * 获取边界框的右下角坐标
     */
    public Point getBottomRight() {
        return new Point(center.x + width / 2, center.y + height / 2);
    }

    /**
     * 获取边界框对象（用于 IoU 匹配）
     */
    public TemplateFeature.BoundingBox getBbox() {
        return new TemplateFeature.BoundingBox(
            center.x - width / 2,
            center.y - height / 2,
            width,
            height
        );
    }

    /**
     * 计算与另一个检测对象的 IoU
     */
    public double iou(DetectedObject other) {
        return this.getBbox().iou(other.getBbox());
    }

    // Getters and Setters
    public int getClassId() { return classId; }
    public void setClassId(int classId) { this.classId = classId; }

    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }

    public Point getCenter() { return center; }
    public void setCenter(Point center) { this.center = center; }

    public double getWidth() { return width; }
    public void setWidth(double width) { this.width = width; }

    public double getHeight() { return height; }
    public void setHeight(double height) { this.height = height; }

    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }

    @Override
    public String toString() {
        return String.format("DetectedObject[class=%d, center=%s, size=%.2fx%.2f, conf=%.2f]",
            classId, center, width, height, confidence);
    }
}
