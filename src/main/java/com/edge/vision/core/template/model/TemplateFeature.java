package com.edge.vision.core.template.model;

/**
 * 模板特征
 * 需要检测的细节点
 */
public class TemplateFeature {
    private String id;
    private String name;
    private Point position;              // 绝对坐标（中心点）
    private Point relativePosition;      // 相对于几何中心的坐标
    private BoundingBox bbox;            // 边界框（用于IoU匹配）
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

    public BoundingBox getBbox() { return bbox; }
    public void setBbox(BoundingBox bbox) { this.bbox = bbox; }

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

    /**
     * 边界框
     * 用于 IoU 匹配，比单纯中心点匹配更准确
     */
    public static class BoundingBox {
        private double x;      // 左上角 x
        private double y;      // 左上角 y
        private double width;  // 宽度
        private double height; // 高度

        public BoundingBox() {}

        public BoundingBox(double x, double y, double width, double height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        /**
         * 从 YOLO bbox 格式 [x1, y1, x2, y2] 创建
         */
        public static BoundingBox fromYolo(float[] yoloBbox) {
            return new BoundingBox(
                yoloBbox[0],
                yoloBbox[1],
                yoloBbox[2] - yoloBbox[0],
                yoloBbox[3] - yoloBbox[1]
            );
        }

        /**
         * 计算与另一个边界框的 IoU (Intersection over Union)
         */
        public double iou(BoundingBox other) {
            double x1 = Math.max(this.x, other.x);
            double y1 = Math.max(this.y, other.y);
            double x2 = Math.min(this.x + this.width, other.x + other.width);
            double y2 = Math.min(this.y + this.height, other.y + other.height);

            double intersectionArea = Math.max(0, x2 - x1) * Math.max(0, y2 - y1);
            double unionArea = this.width * this.height + other.width * other.height - intersectionArea;

            if (unionArea <= 0) return 0.0;
            return intersectionArea / unionArea;
        }

        public double getX() { return x; }
        public void setX(double x) { this.x = x; }

        public double getY() { return y; }
        public void setY(double y) { this.y = y; }

        public double getWidth() { return width; }
        public void setWidth(double width) { this.width = width; }

        public double getHeight() { return height; }
        public void setHeight(double height) { this.height = height; }

        @Override
        public String toString() {
            return String.format("(%.1f,%.1f,%.1fx%.1f)", x, y, width, height);
        }
    }
}
