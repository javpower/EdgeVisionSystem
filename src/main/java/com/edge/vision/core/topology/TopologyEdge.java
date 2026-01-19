package com.edge.vision.core.topology;

/**
 * 拓扑图边
 * <p>
 * 边属性设计为对旋转、平移、尺度完全不变
 * 包括：相对角度、相对距离比、邻接关系
 */
public class TopologyEdge {
    // 源节点ID
    private final String sourceId;

    // 目标节点ID
    private final String targetId;

    /**
     * 相对角度（弧度）
     * 从源节点到目标节点的连线与x轴的夹角
     * 旋转后，所有边的相对角度差不变
     */
    private final double relativeAngle;

    /**
     * 相对距离比
     * 源-目标距离 / 源-参考点距离
     * 消除尺度影响
     */
    private final double distanceRatio;

    /**
     * 标准化距离
     * 源节点到目标节点的实际距离
     */
    private final double normalizedDistance;

    /**
     * 是否为最近邻
     * 目标节点是否为源节点的k个最近邻之一
     */
    private final boolean isNearestNeighbor;

    /**
     * 邻接排名
     * 目标节点在源节点所有邻居中的排名（按距离）
     */
    private final int neighborRank;

    /**
     * 创建拓扑边
     *
     * @param source           源节点
     * @param target           目标节点
     * @param referencePoint   参考点坐标（几何中心）[x, y]
     * @param sourceToRefDist  源节点到参考点的距离
     * @param neighborRank     邻接排名（0表示最近）
     */
    public TopologyEdge(TopologyNode source, TopologyNode target,
                        double[] referencePoint, double sourceToRefDist, int neighborRank) {
        this.sourceId = source.getNodeId();
        this.targetId = target.getNodeId();

        // 计算相对角度
        this.relativeAngle = source.angleTo(target);

        // 计算源-目标距离
        double sourceToTarget = source.distanceTo(target);

        // 计算相对距离比（消除尺度）
        this.distanceRatio = sourceToRefDist > 1e-6 ? sourceToTarget / sourceToRefDist : sourceToTarget;

        // 标准化距离
        this.normalizedDistance = sourceToTarget;

        this.isNearestNeighbor = neighborRank < 5;  // 前5个视为最近邻
        this.neighborRank = neighborRank;
    }

    public String getSourceId() {
        return sourceId;
    }

    public String getTargetId() {
        return targetId;
    }

    public double getRelativeAngle() {
        return relativeAngle;
    }

    public double getDistanceRatio() {
        return distanceRatio;
    }

    public double getNormalizedDistance() {
        return normalizedDistance;
    }

    public boolean isNearestNeighbor() {
        return isNearestNeighbor;
    }

    public int getNeighborRank() {
        return neighborRank;
    }

    /**
     * 计算与另一条边的拓扑相似度
     * 这是边属性差异的度量，越接近1表示越相似
     */
    public double topologySimilarity(TopologyEdge other) {
        // 角度差异（归一化到[-PI, PI]）
        double angleDiff = Math.abs(normalizeAngle(this.relativeAngle - other.relativeAngle));
        double angleSim = Math.max(0, 1.0 - angleDiff / Math.PI);

        // 距离比差异
        double ratioDiff = Math.abs(this.distanceRatio - other.distanceRatio);
        double maxRatio = Math.max(this.distanceRatio, other.distanceRatio);
        double ratioSim = maxRatio > 1e-6 ? Math.max(0, 1.0 - ratioDiff / maxRatio) : 1.0;

        // 综合相似度（角度和距离比同等重要）
        return (angleSim + ratioSim) / 2.0;
    }

    /**
     * 计算与另一条边的拓扑差异（代价）
     * 值越小表示越相似，用于匈牙利算法
     */
    public double topologyDifference(TopologyEdge other) {
        // 角度差异
        double angleDiff = Math.abs(normalizeAngle(this.relativeAngle - other.relativeAngle));

        // 距离比差异
        double ratioDiff = Math.abs(this.distanceRatio - other.distanceRatio);

        // 综合代价（归一化到[0,1]）
        return (angleDiff / Math.PI + ratioDiff) / 2.0;
    }

    /**
     * 将角度归一化到[-PI, PI]范围
     */
    private double normalizeAngle(double angle) {
        while (angle > Math.PI) angle -= 2 * Math.PI;
        while (angle < -Math.PI) angle += 2 * Math.PI;
        return angle;
    }

    @Override
    public String toString() {
        return String.format("Edge[%s->%s, angle=%.2f, ratio=%.3f, rank=%d]",
            sourceId, targetId,
            Math.toDegrees(relativeAngle),
            distanceRatio,
            neighborRank);
    }
}
