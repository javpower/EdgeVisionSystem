package com.edge.vision.core.template.model;

/**
 * 锚点
 * 用于坐标对齐的参考点
 */
public class AnchorPoint {
    private String id;
    private AnchorType type;
    private Point position;
    private String description;

    public enum AnchorType {
        /** 几何中心（主锚点） */
        GEOMETRIC_CENTER,
        /** 上边界中心 */
        TOP_CENTER,
        /** 右边界中心 */
        RIGHT_CENTER,
        /** 下边界中心 */
        BOTTOM_CENTER,
        /** 左边界中心 */
        LEFT_CENTER,
        /** 左上角 */
        TOP_LEFT,
        /** 右上角 */
        TOP_RIGHT,
        /** 右下角 */
        BOTTOM_RIGHT,
        /** 左下角 */
        BOTTOM_LEFT
    }

    public AnchorPoint() {
    }

    public AnchorPoint(String id, AnchorType type, Point position) {
        this.id = id;
        this.type = type;
        this.position = position;
    }

    public AnchorPoint(String id, AnchorType type, Point position, String description) {
        this.id = id;
        this.type = type;
        this.position = position;
        this.description = description;
    }

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public AnchorType getType() { return type; }
    public void setType(AnchorType type) { this.type = type; }

    public Point getPosition() { return position; }
    public void setPosition(Point position) { this.position = position; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    @Override
    public String toString() {
        return String.format("AnchorPoint[%s: %s at %s]", id, type, position);
    }
}
