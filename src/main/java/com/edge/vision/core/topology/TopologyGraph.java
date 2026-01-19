package com.edge.vision.core.topology;

import com.edge.vision.core.template.model.DetectedObject;
import com.edge.vision.core.template.model.Template;
import com.edge.vision.core.template.model.TemplateFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 拓扑图
 * <p>
 * 将模板或检测结果构建为拓扑图
 * 节点=特征，边=拓扑关系（相对角度、相对距离比）
 */
public class TopologyGraph {
    private static final Logger logger = LoggerFactory.getLogger(TopologyGraph.class);

    // 图ID
    private final String graphId;

    // 是否为模板图
    private final boolean isTemplateGraph;

    // 节点集合 (nodeId -> node)
    private final Map<String, TopologyNode> nodes;

    // 边集合 (sourceId -> List of edges)
    private final Map<String, List<TopologyEdge>> edges;

    // 参考点坐标（几何中心）[x, y]
    private double[] referencePoint;

    // 节点列表（保持顺序）
    private final List<TopologyNode> nodeList;

    // k近邻数量（从模板获取）
    private final int k;

    /**
     * 从模板创建拓扑图
     */
    public static TopologyGraph fromTemplate(Template template) {
        TopologyGraph graph = new TopologyGraph(
            template.getTemplateId(),
            true,
            template.getFeatures().size(),
            template.getTopologyK()
        );

        // 1. 创建节点
        List<TemplateFeature> features = template.getFeatures();
        for (TemplateFeature feature : features) {
            if (feature.getPosition() != null) {
                TopologyNode node = TopologyNode.fromTemplateFeature(feature);
                graph.addNode(node);
            }
        }

        // 2. 构建边（如果节点数足够）
        if (graph.nodes.size() >= 2) {
            graph.buildEdges();
        }

        logger.debug("构建模板拓扑图: {} 个节点, {} 条边, k={}",
            graph.nodes.size(), countEdges(graph.edges), graph.k);

        // 打印类别分布
        logClassDistribution(graph, "模板");

        return graph;
    }

    /**
     * 从检测对象创建拓扑图
     */
    public static TopologyGraph fromDetectedObjects(List<DetectedObject> detectedObjects, String graphId, int k) {
        TopologyGraph graph = new TopologyGraph(graphId, false, detectedObjects.size(), k);

        // 1. 创建节点
        for (int i = 0; i < detectedObjects.size(); i++) {
            TopologyNode node = TopologyNode.fromDetectedObject(detectedObjects.get(i), i);
            graph.addNode(node);
        }

        // 2. 构建边（如果节点数足够）
        if (graph.nodes.size() >= 2) {
            graph.buildEdges();
        }

        logger.debug("构建检测拓扑图: {} 个节点, {} 条边, k={}",
            graph.nodes.size(), countEdges(graph.edges), graph.k);

        // 打印类别分布
        logClassDistribution(graph, "检测");

        return graph;
    }

    private TopologyGraph(String graphId, boolean isTemplateGraph, int initialCapacity, int k) {
        this.graphId = graphId;
        this.isTemplateGraph = isTemplateGraph;
        this.nodes = new HashMap<>(initialCapacity);
        this.edges = new HashMap<>(initialCapacity);
        this.nodeList = new ArrayList<>(initialCapacity);
        this.k = k;
    }

    /**
     * 添加节点
     */
    public void addNode(TopologyNode node) {
        nodes.put(node.getNodeId(), node);
        nodeList.add(node);
    }

    /**
     * 构建拓扑图的边
     * <p>
     * 为每个节点连接到其k个最近邻节点
     * 边属性包含相对角度、相对距离比等
     */
    public void buildEdges() {
        if (nodes.isEmpty()) {
            return;
        }

        // 1. 计算几何中心作为参考点
        referencePoint = calculateGeometricCenter();

        // 2. 为每个节点找到其k个最近邻
        int actualK = Math.min(nodes.size() - 1, this.k);  // 使用配置的k值

        for (TopologyNode source : nodeList) {
            // 计算源节点到参考点的距离
            double sourceToRef = distanceToReference(source, referencePoint);

            // 计算源节点到所有其他节点的距离
            List<NeighborDistance> distances = new ArrayList<>();
            for (TopologyNode target : nodeList) {
                if (!source.getNodeId().equals(target.getNodeId())) {
                    double dist = source.distanceTo(target);
                    distances.add(new NeighborDistance(target, dist));
                }
            }

            // 按距离排序
            distances.sort(Comparator.comparingDouble(NeighborDistance::getDistance));

            // 为前k个邻居创建边
            List<TopologyEdge> sourceEdges = new ArrayList<>();
            for (int i = 0; i < Math.min(actualK, distances.size()); i++) {
                NeighborDistance nd = distances.get(i);
                TopologyEdge edge = new TopologyEdge(
                    source,
                    nd.node,
                    referencePoint,
                    sourceToRef,
                    i
                );
                sourceEdges.add(edge);
            }

            edges.put(source.getNodeId(), sourceEdges);
        }

        logger.debug("拓扑图 {} 边构建完成，参考点: ({}, {})",
            graphId, referencePoint[0], referencePoint[1]);
    }

    /**
     * 计算几何中心（作为参考点）
     * 返回坐标数组 [x, y]
     */
    private double[] calculateGeometricCenter() {
        if (nodes.isEmpty()) {
            return new double[]{0, 0};
        }

        double sumX = 0, sumY = 0;
        for (TopologyNode node : nodeList) {
            sumX += node.getX();
            sumY += node.getY();
        }

        return new double[]{sumX / nodeList.size(), sumY / nodeList.size()};
    }

    /**
     * 计算到参考点的距离
     */
    private double distanceToReference(TopologyNode source, double[] reference) {
        double dx = source.getX() - reference[0];
        double dy = source.getY() - reference[1];
        return Math.sqrt(dx * dx + dy * dy);
    }

    /**
     * 获取节点的k个最近邻节点
     */
    public List<TopologyNode> getKNearestNeighbors(TopologyNode node, int k) {
        List<TopologyEdge> nodeEdges = edges.get(node.getNodeId());
        if (nodeEdges == null) {
            return Collections.emptyList();
        }

        return nodeEdges.stream()
            .limit(k)
            .map(edge -> nodes.get(edge.getTargetId()))
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }

    /**
     * 获取节点的所有出边
     */
    public List<TopologyEdge> getEdges(String nodeId) {
        return edges.getOrDefault(nodeId, Collections.emptyList());
    }

    /**
     * 计算两个节点的拓扑相似度
     * 基于它们的k近邻拓扑结构
     */
    public double topologySimilarity(TopologyNode node1, TopologyNode node2, TopologyGraph otherGraph) {
        int k = Math.min(5, Math.min(
            this.getEdges(node1.getNodeId()).size(),
            otherGraph.getEdges(node2.getNodeId()).size()
        ));

        if (k == 0) {
            return 0.0;
        }

        // 获取两个节点的k近邻边
        List<TopologyEdge> edges1 = this.getEdges(node1.getNodeId()).subList(0, k);
        List<TopologyEdge> edges2 = otherGraph.getEdges(node2.getNodeId()).subList(0, k);

        // 计算最佳边匹配的平均相似度
        double totalSim = 0.0;
        int matchCount = 0;

        for (TopologyEdge edge1 : edges1) {
            double maxSim = 0.0;
            for (TopologyEdge edge2 : edges2) {
                double sim = edge1.topologySimilarity(edge2);
                maxSim = Math.max(maxSim, sim);
            }
            totalSim += maxSim;
            matchCount++;
        }

        double result = matchCount > 0 ? totalSim / matchCount : 0.0;
        logger.debug("拓扑相似度: {} <-> {} = {} (k={})", node1.getNodeId(), node2.getNodeId(), String.format("%.3f", result), k);
        return result;
    }

    /**
     * 计算两个节点的匹配代价（用于匈牙利算法）
     * 值越大表示越不相似
     * 仅使用拓扑相似度，因为模板节点没有真实的尺寸信息
     */
    public double matchingCost(TopologyNode node1, TopologyNode node2, TopologyGraph otherGraph) {
        // 1. 类别不同，直接高代价
        if (!node1.isSameClass(node2)) {
            return 1000.0;
        }

        // 2. 拓扑结构差异（核心）
        double topoSim = topologySimilarity(node1, node2, otherGraph);
        double topoCost = 1.0 - topoSim;

        // 直接使用拓扑代价
        return topoCost;
    }

    public String getGraphId() {
        return graphId;
    }

    public boolean isTemplateGraph() {
        return isTemplateGraph;
    }

    public Map<String, TopologyNode> getNodes() {
        return nodes;
    }

    public List<TopologyNode> getNodeList() {
        return nodeList;
    }

    public Map<String, List<TopologyEdge>> getEdges() {
        return edges;
    }

    public double[] getReferencePoint() {
        return referencePoint;
    }

    public int getNodeCount() {
        return nodes.size();
    }

    public int getK() {
        return k;
    }

    /**
     * 获取所有指定类别的节点
     */
    public List<TopologyNode> getNodesByClass(int classId) {
        return nodeList.stream()
            .filter(node -> node.getClassId() == classId)
            .collect(Collectors.toList());
    }

    /**
     * 获取所有指定类别的节点
     */
    public List<TopologyNode> getNodesByClass(String className) {
        return nodeList.stream()
            .filter(node -> node.getClassLabel().equals(className))
            .collect(Collectors.toList());
    }

    private static int countEdges(Map<String, List<TopologyEdge>> edges) {
        return edges.values().stream().mapToInt(List::size).sum();
    }

    /**
     * 打印类别分布（用于调试）
     */
    private static void logClassDistribution(TopologyGraph graph, String label) {
        Map<Integer, Integer> classCounts = new java.util.HashMap<>();
        for (TopologyNode node : graph.nodeList) {
            classCounts.merge(node.getClassId(), 1, Integer::sum);
        }
        logger.info("{}类别分布: {}", label, classCounts);
    }

    @Override
    public String toString() {
        return String.format("TopologyGraph[%s, %d nodes, %d edges, %s]",
            graphId, nodes.size(), countEdges(edges),
            isTemplateGraph ? "template" : "detected");
    }

    /**
     * 邻居距离（用于排序）
     */
    private static class NeighborDistance {
        final TopologyNode node;
        final double distance;

        NeighborDistance(TopologyNode node, double distance) {
            this.node = node;
            this.distance = distance;
        }

        double getDistance() {
            return distance;
        }
    }
}
