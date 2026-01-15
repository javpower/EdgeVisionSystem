package com.edge.vision.core.template.model;

/**
 * 模板特征
 * 需要检测的细节点
 */
public class TemplateFeature {
    private String id;
    private String name;
    private Point position;              // 绝对坐标
    private Point relativePosition;      // 相对于几何中心的坐标
    private Tolerance tolerance;
    private boolean required;
    private int classId;

    public TemplateFeature() {
        this.tolerance = new Tolerance(5.0, 5.0);
        this.required = true;
    }

    public TemplateFeature(String id, String name, Point position, int classId) {
        this.id = id;
        this.name = name;
        this.position = position;
        this.classId = classId;
        this.tolerance = new Tolerance(5.0, 5.0);
        this.required = true;
    }

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Point getPosition() { return position; }
    public void setPosition(Point position) { this.position = position; }

    public Point getRelativePosition() { return relativePosition; }
    public void setRelativePosition(Point relativePosition) { this.relativePosition = relativePosition; }

    public Tolerance getTolerance() { return tolerance; }
    public void setTolerance(Tolerance tolerance) { this.tolerance = tolerance; }

    public boolean isRequired() { return required; }
    public void setRequired(boolean required) { this.required = required; }

    public int getClassId() { return classId; }
    public void setClassId(int classId) { this.classId = classId; }

    @Override
    public String toString() {
        return String.format("Feature[%s: %s at %s, class=%d, required=%b]",
            id, name, position, classId, required);
    }

    /**
     * 容差
     */
    public static class Tolerance {
        private double x;
        private double y;

        public Tolerance() {
            this(5.0, 5.0);
        }

        public Tolerance(double x, double y) {
            this.x = x;
            this.y = y;
        }

        public double getX() { return x; }
        public void setX(double x) { this.x = x; }

        public double getY() { return y; }
        public void setY(double y) { this.y = y; }

        @Override
        public String toString() {
            return String.format("(±%.2f, ±%.2f)", x, y);
        }
    }
}
