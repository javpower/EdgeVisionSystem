package com.edge.vision.core.topology;

import com.edge.vision.core.template.model.DetectedObject;
import com.edge.vision.core.template.model.TemplateFeature;

/**
 * 拓扑图节点
 * <p>
 * 将模板特征或检测对象抽象为图的节点
 * 节点属性包含：类别标签、几何特征、中心坐标
 */
public class TopologyNode {
    // 节点ID
    private final String nodeId;

    // 类别标签（hole、nut等）
    private final String classLabel;

    // 类别ID
    private final int classId;

    // 中心坐标
    private final double x;
    private final double y;

    // 几何特征（如孔径、螺丝头部直径）
    private final double size;  // 取width和height的平均值

    // 是否为模板节点
    private final boolean isTemplateNode;

    // 关联的模板特征（仅模板节点）
    private final TemplateFeature templateFeature;

    // 关联的检测对象（仅检测节点）
    private final DetectedObject detectedObject;

    /**
     * 从模板特征创建节点
     * 注意：TemplateFeature没有size信息，使用默认值1.0
     */
    public static TopologyNode fromTemplateFeature(TemplateFeature feature) {
        return new TopologyNode(
            feature.getId(),
            feature.getName(),
            feature.getClassId(),
            feature.getPosition().x,
            feature.getPosition().y,
            10.0,  // 默认尺寸（模板特征没有size信息）
            true,
            feature,
            null
        );
    }

    /**
     * 从检测对象创建节点
     */
    public static TopologyNode fromDetectedObject(DetectedObject obj, int index) {
        return new TopologyNode(
            "detected_" + index,
            obj.getClassName() != null ? obj.getClassName() : "unknown_" + index,
            obj.getClassId(),
            obj.getCenter().x,
            obj.getCenter().y,
            (obj.getWidth() + obj.getHeight()) / 2.0,
            false,
            null,
            obj
        );
    }

    private TopologyNode(String nodeId, String classLabel, int classId, double x, double y,
                         double size, boolean isTemplateNode,
                         TemplateFeature templateFeature, DetectedObject detectedObject) {
        this.nodeId = nodeId;
        this.classLabel = classLabel;
        this.classId = classId;
        this.x = x;
        this.y = y;
        this.size = size;
        this.isTemplateNode = isTemplateNode;
        this.templateFeature = templateFeature;
        this.detectedObject = detectedObject;
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getClassLabel() {
        return classLabel;
    }

    public int getClassId() {
        return classId;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public double getSize() {
        return size;
    }

    public boolean isTemplateNode() {
        return isTemplateNode;
    }

    public boolean isDetectedNode() {
        return !isTemplateNode;
    }

    public TemplateFeature getTemplateFeature() {
        return templateFeature;
    }

    public DetectedObject getDetectedObject() {
        return detectedObject;
    }

    /**
     * 计算与另一个节点的欧氏距离
     */
    public double distanceTo(TopologyNode other) {
        double dx = this.x - other.x;
        double dy = this.y - other.y;
        return Math.sqrt(dx * dx + dy * dy);
    }

    /**
     * 计算与另一个节点的相对角度（弧度）
     * 从当前节点指向另一个节点
     */
    public double angleTo(TopologyNode other) {
        return Math.atan2(other.y - this.y, other.x - this.x);
    }

    /**
     * 检查类别是否相同
     */
    public boolean isSameClass(TopologyNode other) {
        return this.classId == other.classId;
    }

    /**
     * 计算几何特征相似度（基于尺寸差异）
     */
    public double geometricSimilarity(TopologyNode other) {
        double sizeDiff = Math.abs(this.size - other.size);
        double avgSize = (this.size + other.size) / 2.0;
        return 1.0 - (sizeDiff / (avgSize + 1e-6));  // 加小量避免除零
    }

    @Override
    public String toString() {
        return String.format("Node[%s, class=%s, pos=(%.2f,%.2f), size=%.2f, %s]",
            nodeId, classLabel, x, y, size,
            isTemplateNode ? "template" : "detected");
    }
}
