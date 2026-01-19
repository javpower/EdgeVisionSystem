package com.edge.vision.core.topology;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 拓扑匹配器
 * <p>
 * 使用匈牙利算法基于拓扑结构进行最优匹配
 * 完全支持旋转、平移、尺度变化
 */
public class TopologyMatcher {
    private static final Logger logger = LoggerFactory.getLogger(TopologyMatcher.class);

    // 可配置参数
    private double toleranceX = 200.0;      // X方向容差（像素）
    private double toleranceY = 200.0;      // Y方向容差（像素）
    private double topologyThreshold = 0.5;  // 拓扑相似度阈值

    // 匹配代价阈值（超过此值的匹配被认为是无效的）
    private double maxMatchCost = 0.5;

    /**
     * 匹配结果
     */
    public static class MatchPair {
        private final TopologyNode templateNode;
        private final TopologyNode detectedNode;
        private final double cost;  // 匹配代价
        private final double maxCost; // 最大有效代价

        public MatchPair(TopologyNode templateNode, TopologyNode detectedNode, double cost, double maxCost) {
            this.templateNode = templateNode;
            this.detectedNode = detectedNode;
            this.cost = cost;
            this.maxCost = maxCost;
        }

        public TopologyNode getTemplateNode() {
            return templateNode;
        }

        public TopologyNode getDetectedNode() {
            return detectedNode;
        }

        public double getCost() {
            return cost;
        }

        public boolean isValid() {
            return cost < maxCost;
        }

        @Override
        public String toString() {
            return String.format("MatchPair[%s -> %s, cost=%.3f]",
                templateNode.getNodeId(), detectedNode.getNodeId(), cost);
        }
    }

    /**
     * 匹配结果汇总
     */
    public static class MatchResult {
        private final List<MatchPair> matches;
        private final List<TopologyNode> unmatchedTemplateNodes;
        private final List<TopologyNode> unmatchedDetectedNodes;
        private final double totalCost;

        public MatchResult(List<MatchPair> matches,
                          List<TopologyNode> unmatchedTemplateNodes,
                          List<TopologyNode> unmatchedDetectedNodes,
                          double totalCost) {
            this.matches = matches;
            this.unmatchedTemplateNodes = unmatchedTemplateNodes;
            this.unmatchedDetectedNodes = unmatchedDetectedNodes;
            this.totalCost = totalCost;
        }

        public List<MatchPair> getMatches() {
            return matches;
        }

        public List<TopologyNode> getUnmatchedTemplateNodes() {
            return unmatchedTemplateNodes;
        }

        public List<TopologyNode> getUnmatchedDetectedNodes() {
            return unmatchedDetectedNodes;
        }

        public double getTotalCost() {
            return totalCost;
        }

        public int getMatchCount() {
            return matches.size();
        }

        public int getValidMatchCount() {
            return (int) matches.stream().filter(MatchPair::isValid).count();
        }

        @Override
        public String toString() {
            return String.format("MatchResult[%d valid matches, %d unmatched template, %d unmatched detected, totalCost=%.3f]",
                getValidMatchCount(), unmatchedTemplateNodes.size(),
                unmatchedDetectedNodes.size(), totalCost);
        }
    }

    /**
     * 执行拓扑匹配
     * <p>
     * 使用匈牙利算法找到模板图和检测图之间的最优一一对应关系
     * 按类别分别匹配，避免跨类别匹配
     *
     * @param templateGraph 模板拓扑图
     * @param detectedGraph 检测拓扑图
     * @return 匹配结果
     */
    public MatchResult match(TopologyGraph templateGraph, TopologyGraph detectedGraph) {
        logger.info("开始拓扑匹配: 模板图{}个节点 vs 检测图{}个节点",
            templateGraph.getNodeCount(), detectedGraph.getNodeCount());

        long startTime = System.currentTimeMillis();

        // 按类别分组
        Map<Integer, List<TopologyNode>> templateByClass = groupByClass(templateGraph.getNodeList());
        Map<Integer, List<TopologyNode>> detectedByClass = groupByClass(detectedGraph.getNodeList());

        logger.info("模板类别分组: {}", templateByClass.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> ((Map.Entry<Integer, List<TopologyNode>>) e).getValue().size())));
        logger.info("检测类别分组: {}", detectedByClass.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> ((Map.Entry<Integer, List<TopologyNode>>) e).getValue().size())));

        // 对每个类别分别进行匹配
        List<MatchPair> allMatches = new ArrayList<>();
        List<TopologyNode> allUnmatchedTemplate = new ArrayList<>();
        List<TopologyNode> allUnmatchedDetected = new ArrayList<>();
        double totalCost = 0.0;

        for (Map.Entry<Integer, List<TopologyNode>> entry : templateByClass.entrySet()) {
            int classId = entry.getKey();
            List<TopologyNode> templateNodes = entry.getValue();
            List<TopologyNode> detectedNodes = detectedByClass.getOrDefault(classId, new ArrayList<>());

            logger.debug("匹配类别 {}: 模板{}个节点 vs 检测{}个节点",
                classId, templateNodes.size(), detectedNodes.size());

            if (detectedNodes.isEmpty()) {
                // 没有对应的检测节点，全部作为漏检
                allUnmatchedTemplate.addAll(templateNodes);
                logger.warn("类别 {} 在检测结果中不存在，{} 个模板特征全部漏检", classId, templateNodes.size());
                continue;
            }

            // 为当前类别构建代价矩阵并匹配
            ClassMatchResult classResult = matchClass(templateNodes, detectedNodes, templateGraph, detectedGraph);

            allMatches.addAll(classResult.matches);
            allUnmatchedTemplate.addAll(classResult.unmatchedTemplate);
            allUnmatchedDetected.addAll(classResult.unmatchedDetected);
            totalCost += classResult.totalCost;
        }

        // 添加多余的检测节点（误检）
        Set<String> matchedDetectedIds = allMatches.stream()
            .map(m -> ((MatchPair) m).getDetectedNode().getNodeId())
            .collect(Collectors.toSet());

        for (Map.Entry<Integer, List<TopologyNode>> entry : detectedByClass.entrySet()) {
            int classId = entry.getKey();
            if (!templateByClass.containsKey(classId)) {
                // 这个类别在模板中不存在，全部作为误检
                allUnmatchedDetected.addAll(entry.getValue());
                logger.warn("类别 {} 在模板中不存在，{} 个检测特征全部误检", classId, entry.getValue().size());
            }
        }

        // 排除已匹配的检测节点
        List<TopologyNode> extraUnmatchedDetected = new ArrayList<>();
        for (TopologyNode node : allUnmatchedDetected) {
            if (!matchedDetectedIds.contains(node.getNodeId())) {
                extraUnmatchedDetected.add(node);
            }
        }
        allUnmatchedDetected = extraUnmatchedDetected;

        long elapsed = System.currentTimeMillis() - startTime;
        logger.info("拓扑匹配完成: {} 个有效匹配, {} 个漏检, {} 个误检, 耗时{}ms",
            allMatches.stream().filter(MatchPair::isValid).count(),
            allUnmatchedTemplate.size(),
            allUnmatchedDetected.size(),
            elapsed);

        return new MatchResult(allMatches, allUnmatchedTemplate, allUnmatchedDetected, totalCost);
    }

    /**
     * 按类别分组节点
     */
    private Map<Integer, List<TopologyNode>> groupByClass(List<TopologyNode> nodes) {
        return nodes.stream().collect(Collectors.groupingBy(TopologyNode::getClassId));
    }

    /**
     * 对同一类别的节点进行匹配
     */
    private ClassMatchResult matchClass(List<TopologyNode> templateNodes,
                                       List<TopologyNode> detectedNodes,
                                       TopologyGraph templateGraph,
                                       TopologyGraph detectedGraph) {
        List<MatchPair> matches = new ArrayList<>();
        List<TopologyNode> unmatchedTemplate = new ArrayList<>();
        List<TopologyNode> unmatchedDetected = new ArrayList<>(detectedNodes);
        double totalCost = 0.0;

        if (templateNodes.isEmpty()) {
            return new ClassMatchResult(matches, unmatchedTemplate, unmatchedDetected, totalCost);
        }

        // 构建当前类别的代价矩阵
        double[][] costMatrix = new double[templateNodes.size()][detectedNodes.size()];
        for (int i = 0; i < templateNodes.size(); i++) {
            for (int j = 0; j < detectedNodes.size(); j++) {
                costMatrix[i][j] = calculateMatchCost(
                    templateNodes.get(i), detectedNodes.get(j),
                    templateGraph, detectedGraph
                );
            }
        }

        // 执行匈牙利算法
        int[] assignment = HungarianAlgorithm.solve(costMatrix);

        // 解析匹配结果
        Set<Integer> matchedDetectedIndices = new HashSet<>();
        for (int i = 0; i < templateNodes.size(); i++) {
            int j = assignment[i];
            TopologyNode templateNode = templateNodes.get(i);

            if (j >= 0 && j < detectedNodes.size()) {
                TopologyNode detectedNode = detectedNodes.get(j);
                double cost = costMatrix[i][j];

                matches.add(new MatchPair(templateNode, detectedNode, cost, maxMatchCost));
                matchedDetectedIndices.add(j);
                totalCost += cost;

                if (cost < maxMatchCost) {
                    logger.debug("匹配: {} -> {}, 代价={}",
                        templateNode.getNodeId(), detectedNode.getNodeId(),
                        String.format("%.3f", cost));
                }
            } else {
                unmatchedTemplate.add(templateNode);
                logger.debug("模板节点 {} (类别 {}) 未找到匹配",
                    templateNode.getNodeId(), templateNode.getClassId());
            }
        }

        // 找出未匹配的检测节点
        unmatchedDetected = new ArrayList<>();
        for (int j = 0; j < detectedNodes.size(); j++) {
            if (!matchedDetectedIndices.contains(j)) {
                unmatchedDetected.add(detectedNodes.get(j));
                logger.debug("检测节点 {} (类别 {}) 未在模板中找到匹配",
                    detectedNodes.get(j).getNodeId(), detectedNodes.get(j).getClassId());
            }
        }

        return new ClassMatchResult(matches, unmatchedTemplate, unmatchedDetected, totalCost);
    }

    /**
     * 单个类别的匹配结果
     */
    private static class ClassMatchResult {
        final List<MatchPair> matches;
        final List<TopologyNode> unmatchedTemplate;
        final List<TopologyNode> unmatchedDetected;
        final double totalCost;

        ClassMatchResult(List<MatchPair> matches, List<TopologyNode> unmatchedTemplate,
                        List<TopologyNode> unmatchedDetected, double totalCost) {
            this.matches = matches;
            this.unmatchedTemplate = unmatchedTemplate;
            this.unmatchedDetected = unmatchedDetected;
            this.totalCost = totalCost;
        }
    }

    /**
     * 构建代价矩阵
     * <p>
     * costMatrix[i][j] 表示将模板节点i匹配到检测节点j的代价
     * 代价越小表示越相似
     */
    private double[][] buildCostMatrix(TopologyGraph templateGraph, TopologyGraph detectedGraph) {
        List<TopologyNode> templateNodes = templateGraph.getNodeList();
        List<TopologyNode> detectedNodes = detectedGraph.getNodeList();

        double[][] costMatrix = new double[templateNodes.size()][detectedNodes.size()];

        for (int i = 0; i < templateNodes.size(); i++) {
            TopologyNode templateNode = templateNodes.get(i);

            for (int j = 0; j < detectedNodes.size(); j++) {
                TopologyNode detectedNode = detectedNodes.get(j);

                // 计算匹配代价
                double cost = calculateMatchCost(templateNode, detectedNode, templateGraph, detectedGraph);
                costMatrix[i][j] = cost;
            }
        }

        return costMatrix;
    }

    /**
     * 计算两个节点之间的匹配代价
     * <p>
     * 考虑因素：
     * 1. 类别是否相同（不同类别代价极大）
     * 2. 拓扑结构相似度（核心）
     *
     * 注意：不使用几何相似度，因为模板节点没有真实的尺寸信息
     */
    private double calculateMatchCost(TopologyNode templateNode, TopologyNode detectedNode,
                                     TopologyGraph templateGraph, TopologyGraph detectedGraph) {
        // 1. 类别检查
        if (!templateNode.isSameClass(detectedNode)) {
            // Debug: 打印类别不匹配的情况（仅前几个）
            if (templateNode.getNodeId().equals("F0")) {
                logger.warn("类别不匹配: {}(classId={}, classLabel={}) vs {}(classId={}, classLabel={})",
                    templateNode.getNodeId(), templateNode.getClassId(), templateNode.getClassLabel(),
                    detectedNode.getNodeId(), detectedNode.getClassId(), detectedNode.getClassLabel());
            }
            return 1000.0;  // 类别不同，极高代价
        }

        // 2. 拓扑结构相似度（核心）
        double topoCost = templateGraph.matchingCost(templateNode, detectedNode, detectedGraph);

        // 直接使用拓扑代价作为最终代价
        return topoCost;
    }

    /**
     * 验证匹配的全局一致性
     * <p>
     * 检查所有有效匹配对是否符合同一仿射变换规则
     * 通过计算最佳仿射变换矩阵，然后检查每个匹配对的残差
     *
     * @param matchResult 匹配结果
     * @return 可疑的匹配对（残差超过阈值）
     */
    public List<MatchPair> validateGlobalConsistency(MatchResult matchResult) {
        List<MatchPair> validMatches = matchResult.getMatches().stream()
            .filter(MatchPair::isValid)
            .toList();

        if (validMatches.size() < 3) {
            logger.debug("有效匹配数量不足3个，跳过全局一致性验证");
            return Collections.emptyList();
        }

        // 第一步：计算最佳仿射变换矩阵（使用最小二乘法）
        AffineTransform transform = estimateAffineTransform(validMatches);

        if (transform == null) {
            logger.warn("无法计算仿射变换矩阵，跳过全局一致性验证");
            return Collections.emptyList();
        }

        logger.debug("全局一致性检查: 计算得到仿射变换矩阵");
        logger.debug("  [{}]", transform);

        // 第二步：计算每个匹配对的残差，识别异常点
        List<MatchPair> suspiciousMatches = new ArrayList<>();
        List<Double> residuals = new ArrayList<>();

        // 残差阈值：基于容差值，取对角线距离作为参考
        double residualThreshold = Math.sqrt(toleranceX * toleranceX + toleranceY * toleranceY);
        // 使用容差的 1.5 倍作为阈值，允许一定的变换误差
        residualThreshold = residualThreshold * 1.5;

        for (MatchPair pair : validMatches) {
            TopologyNode templateNode = pair.getTemplateNode();
            TopologyNode detectedNode = pair.getDetectedNode();

            // 应用变换：将模板点映射到检测坐标系
            double[] predicted = transform.transform(templateNode.getX(), templateNode.getY());

            // 计算残差（预测位置与实际检测位置的距离）
            double residualX = detectedNode.getX() - predicted[0];
            double residualY = detectedNode.getY() - predicted[1];
            double residual = Math.sqrt(residualX * residualX + residualY * residualY);

            residuals.add(residual);

            // 如果残差超过阈值，标记为可疑
            if (residual > residualThreshold) {
                suspiciousMatches.add(pair);
                logger.debug("可疑匹配: {} -> {}, 残差={} (阈值={})",
                    templateNode.getNodeId(),
                    detectedNode.getNodeId(),
                    String.format("%.2f", residual),
                    String.format("%.2f", residualThreshold));
            }
        }

        // 计算残差统计
        if (!residuals.isEmpty()) {
            double meanResidual = residuals.stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0);
            double maxResidual = residuals.stream()
                .mapToDouble(Double::doubleValue)
                .max()
                .orElse(0.0);

            logger.debug("残差统计: 均值={}, 最大值={}, 可疑数量={}, 容差阈值={}",
                String.format("%.2f", meanResidual),
                String.format("%.2f", maxResidual),
                suspiciousMatches.size(),
                String.format("%.2f", residualThreshold));

            // 如果残差均值过大，说明整体变换质量不佳
            if (meanResidual > residualThreshold * 2) {
                logger.warn("整体匹配质量较差，平均残差={}, 阈值={}",
                    String.format("%.2f", meanResidual),
                    String.format("%.2f", residualThreshold));
            }
        }

        return suspiciousMatches;
    }

    /**
     * 估算仿射变换矩阵（使用最小二乘法）
     * <p>
     * 求解: [a b tx] [x1]   [x2]
     *       [c d ty] [y1] = [y2]
     *       [0 0 1 ] [1 ]   [1 ]
     * <p>
     * 其中 (x1,y1) 是模板坐标，(x2,y2) 是检测坐标
     * <p>
     * 使用最小二乘法求解超定方程组
     *
     * @param matches 有效匹配对列表
     * @return 仿射变换矩阵
     */
    private AffineTransform estimateAffineTransform(List<MatchPair> matches) {
        int n = matches.size();
        if (n < 3) {
            return null;
        }

        // 构建最小二乘法的方程组
        // 对于每个点对:
        //   x2 = a*x1 + b*y1 + tx
        //   y2 = c*x1 + d*y1 + ty
        //
        // 可以写成矩阵形式: A * params = b
        //
        // 其中 params = [a, b, tx, c, d, ty]^T
        //
        // 对于 x2 方程: [x1, y1, 1, 0,  0,  0 ] * [a, b, tx, c, d, ty]^T = x2
        // 对于 y2 方程: [0,  0,  0, x1, y1, 1] * [a, b, tx, c, d, ty]^T = y2
        //
        // 构建 2n x 6 的矩阵 A 和 2n x 1 的向量 b

        double[][] A = new double[2 * n][6];
        double[] b = new double[2 * n];

        for (int i = 0; i < n; i++) {
            MatchPair pair = matches.get(i);
            double x1 = pair.getTemplateNode().getX();
            double y1 = pair.getTemplateNode().getY();
            double x2 = pair.getDetectedNode().getX();
            double y2 = pair.getDetectedNode().getY();

            // x2 方程
            A[2 * i][0] = x1;
            A[2 * i][1] = y1;
            A[2 * i][2] = 1;
            A[2 * i][3] = 0;
            A[2 * i][4] = 0;
            A[2 * i][5] = 0;
            b[2 * i] = x2;

            // y2 方程
            A[2 * i + 1][0] = 0;
            A[2 * i + 1][1] = 0;
            A[2 * i + 1][2] = 0;
            A[2 * i + 1][3] = x1;
            A[2 * i + 1][4] = y1;
            A[2 * i + 1][5] = 1;
            b[2 * i + 1] = y2;
        }

        // 求解最小二乘问题: (A^T * A) * params = A^T * b
        // params = (A^T * A)^(-1) * A^T * b

        // 计算 A^T * A (6x6 矩阵)
        double[][] AtA = new double[6][6];
        for (int i = 0; i < 6; i++) {
            for (int j = 0; j < 6; j++) {
                double sum = 0;
                for (int k = 0; k < 2 * n; k++) {
                    sum += A[k][i] * A[k][j];
                }
                AtA[i][j] = sum;
            }
        }

        // 计算 A^T * b (6x1 向量)
        double[] Atb = new double[6];
        for (int i = 0; i < 6; i++) {
            double sum = 0;
            for (int k = 0; k < 2 * n; k++) {
                sum += A[k][i] * b[k];
            }
            Atb[i] = sum;
        }

        // 求解线性方程组 (A^T * A) * params = A^T * b
        double[] params = solveLinearSystem(AtA, Atb);

        if (params == null) {
            return null;
        }

        return new AffineTransform(params[0], params[1], params[3], params[4], params[2], params[5]);
    }

    /**
     * 求解线性方程组 Ax = b
     * 使用高斯消元法
     *
     * @param A 系数矩阵 (n x n)
     * @param b 常数向量 (n)
     * @return 解向量 x，如果失败返回 null
     */
    private double[] solveLinearSystem(double[][] A, double[] b) {
        int n = A.length;

        // 创建副本，避免修改原矩阵
        double[][] mat = new double[n][n + 1];
        for (int i = 0; i < n; i++) {
            System.arraycopy(A[i], 0, mat[i], 0, n);
            mat[i][n] = b[i];
        }

        // 高斯消元
        for (int col = 0; col < n; col++) {
            // 部分主元选择
            int maxRow = col;
            double maxVal = Math.abs(mat[col][col]);
            for (int row = col + 1; row < n; row++) {
                if (Math.abs(mat[row][col]) > maxVal) {
                    maxVal = Math.abs(mat[row][col]);
                    maxRow = row;
                }
            }

            // 交换行
            if (maxRow != col) {
                double[] temp = mat[col];
                mat[col] = mat[maxRow];
                mat[maxRow] = temp;
            }

            // 检查是否奇异
            if (Math.abs(mat[col][col]) < 1e-10) {
                logger.warn("系数矩阵奇异，无法求解线性方程组");
                return null;
            }

            // 消元
            for (int row = col + 1; row < n; row++) {
                double factor = mat[row][col] / mat[col][col];
                for (int j = col; j <= n; j++) {
                    mat[row][j] -= factor * mat[col][j];
                }
            }
        }

        // 回代求解
        double[] x = new double[n];
        for (int i = n - 1; i >= 0; i--) {
            double sum = mat[i][n];
            for (int j = i + 1; j < n; j++) {
                sum -= mat[i][j] * x[j];
            }
            x[i] = sum / mat[i][i];
        }

        return x;
    }

    /**
     * 仿射变换矩阵类
     * <p>
     * 变换公式:
     *   x' = a*x + b*y + tx
     *   y' = c*x + d*y + ty
     */
    public static class AffineTransform {
        public final double a, b, c, d, tx, ty;

        public AffineTransform(double a, double b, double c, double d, double tx, double ty) {
            this.a = a;
            this.b = b;
            this.c = c;
            this.d = d;
            this.tx = tx;
            this.ty = ty;
        }

        /**
         * 应用变换
         */
        public double[] transform(double x, double y) {
            return new double[]{
                a * x + b * y + tx,
                c * x + d * y + ty
            };
        }

        /**
         * 获取旋转角度（度）
         */
        public double getRotationAngle() {
            return Math.toDegrees(Math.atan2(c, a));
        }

        /**
         * 获取缩放因子
         */
        public double getScaleX() {
            return Math.sqrt(a * a + c * c);
        }

        public double getScaleY() {
            return Math.sqrt(b * b + d * d);
        }

        /**
         * 获取平移量
         */
        public double getTranslationX() {
            return tx;
        }

        public double getTranslationY() {
            return ty;
        }

        @Override
        public String toString() {
            return String.format(
                "AffineTransform[rotate=%.1f°, scale=(%.2f,%.2f), translate=(%.1f,%.1f), matrix=[%.2f,%.2f,%.1f; %.2f,%.2f,%.1f]]",
                getRotationAngle(),
                getScaleX(), getScaleY(),
                getTranslationX(), getTranslationY(),
                a, b, tx, c, d, ty
            );
        }
    }

    /**
     * 设置匹配代价阈值
     */
    public void setMaxMatchCost(double maxCost) {
        this.maxMatchCost = maxCost;
    }

    /**
     * 设置几何特征相似度阈值
     */
    public void setMinGeometricSimilarity(double minSim) {
        // 保留以兼容，不再使用
    }

    /**
     * 设置 X 方向容差
     */
    public void setToleranceX(double tolerance) {
        this.toleranceX = tolerance;
    }

    /**
     * 设置 Y 方向容差
     */
    public void setToleranceY(double tolerance) {
        this.toleranceY = tolerance;
    }

    /**
     * 设置拓扑相似度阈值
     */
    public void setTopologyThreshold(double threshold) {
        this.topologyThreshold = threshold;
    }
}
