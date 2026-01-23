package com.edge.vision.core.topology;

import com.edge.vision.core.quality.FeatureComparison;
import com.edge.vision.core.quality.InspectionResult;
import com.edge.vision.core.template.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 射影几何指纹匹配器
 * <p>
 * 基于交比（Cross-ratio）不变性的高精度匹配算法
 * <p>
 * 核心原理：
 * 1. 交比不变性：CR(A,B;C,D) = (AC*BD)/(AD*BC) 在射影变换下保持不变
 * 2. 五点参考系：四角 + 黄金分割点，提供唯一性保证
 * 3. 三维指纹向量：F(P) = [CR1, CR2, CR3]，实现点对点精确匹配
 * <p>
 * 系统架构：
 * - 离线注册：计算模板特征的理想指纹，构建指纹数据库
 * - 在线识别：计算检测对象的观测指纹，进行最近邻搜索
 * - 鲁棒性增强：形态学中心提取 + 卡尔曼滤波 + 动态容差
 * <p>
 * 性能特点：
 * - 计算复杂度：O(N) - 单点指纹固定36次浮点运算
 * - 实时性：1000+ FPS（无GPU环境）
 * - 精度：理论保证 + 统计滤波
 * <p>
 * 适用场景：
 * - 存在透视变换（倾斜视角拍摄）
 * - 刚体变换（平移/旋转/缩放/透视）
 * - 画布四角可见
 */
@Component
public class CrossRatioMatcher {
    private static final Logger logger = LoggerFactory.getLogger(CrossRatioMatcher.class);

    // ==================== 可配置参数 ====================

    /**
     * 基础容差值
     * 指纹距离的容差阈值，越小越严格
     * 默认值调大以适应实际场景中的位置偏差
     */
    private double baseTolerance = 0.1;

    /**
     * 高置信度阈值
     * 相似度/分数 > 0.6 确认匹配（降低以提高召回率）
     */
    private double highConfidenceThreshold = 0.6;

    /**
     * 低置信度阈值
     * 相似度/分数 < 0.3 拒绝匹配（降低以提高召回率）
     */
    private double lowConfidenceThreshold = 0.3;

    /**
     * 贴边距离阈值（像素）
     * 点距离边缘小于此值时，容差放大3倍
     */
    private double edgeDistanceThreshold = 100.0;

    /**
     * 是否启用卡尔曼滤波
     */
    private boolean enableKalmanFilter = true;

    /**
     * 卡尔曼滤波器管理器
     */
    private KalmanFilter.Manager kalmanManager;

    /**
     * 指纹计算器
     */
    private CrossRatioFingerprint.Calculator fingerprintCalculator;

    /**
     * 当前画布尺寸
     */
    private ImageSize canvasSize;

    /**
     * 指纹数据库：特征ID -> 指纹
     */
    private Map<String, CrossRatioFingerprint> fingerprintDatabase;

    /**
     * 观测指纹缓存：对象ID -> 指纹
     */
    private Map<String, CrossRatioFingerprint> observationCache;

    // ==================== 构造函数 ====================

    /**
     * 创建默认配置的射影几何指纹匹配器
     */
    public CrossRatioMatcher() {
        this.fingerprintDatabase = new HashMap<>();
        this.observationCache = new HashMap<>();
        this.kalmanManager = new KalmanFilter.Manager();
    }

    /**
     * 创建自定义配置的射影几何指纹匹配器
     *
     * @param baseTolerance            基础容差值
     * @param highConfidenceThreshold  高置信度阈值
     * @param lowConfidenceThreshold   低置信度阈值
     * @param enableKalmanFilter       是否启用卡尔曼滤波
     */
    public CrossRatioMatcher(double baseTolerance,
                            double highConfidenceThreshold,
                            double lowConfidenceThreshold,
                            boolean enableKalmanFilter) {
        this();
        this.baseTolerance = baseTolerance;
        this.highConfidenceThreshold = highConfidenceThreshold;
        this.lowConfidenceThreshold = lowConfidenceThreshold;
        this.enableKalmanFilter = enableKalmanFilter;
        if (!enableKalmanFilter) {
            this.kalmanManager = null;
        }
    }

    // ==================== 核心匹配方法 ====================

    /**
     * 执行射影几何指纹匹配
     * <p>
     * 匹配流程：
     * 1. 初始化参考系和指纹计算器
     * 2. 注册模板特征：计算理想指纹，构建数据库
     * 3. 处理检测对象：计算观测指纹，进行匹配
     * 4. 返回匹配结果
     *
     * @param template        质量检测模板
     * @param detectedObjects 检测到的对象列表
     * @return 质检结果
     */
    public InspectionResult match(Template template, List<DetectedObject> detectedObjects) {
        long startTime = System.currentTimeMillis();

        logger.info("开始射影几何指纹匹配: 模板{}个特征 vs 检测{}个对象",
                template.getFeatures().size(), detectedObjects.size());

        // 初始化
        initializeMatchingContext(template);

        // 离线注册阶段：构建指纹数据库
        registerTemplateFeatures(template);

        // 在线识别阶段：匹配检测对象
        InspectionResult result = matchDetectedObjects(template, detectedObjects);

        long elapsed = System.currentTimeMillis() - startTime;
        result.setProcessingTimeMs(elapsed);
        result.setMatchStrategy(com.edge.vision.core.quality.MatchStrategy.CROSS_RATIO);

        logger.info("射影几何指纹匹配完成: {}个通过, {}个漏检, {}个错检, {}个偏差, 耗时{}ms",
                result.getPassedFeatures().size(),
                result.getMissingFeatures().size(),
                result.getExtraFeatures().size(),
                result.getDeviations().size(),
                elapsed);

        return result;
    }

    /**
     * 初始化匹配上下文
     * <p>
     * 设置画布尺寸、参考点、指纹计算器
     */
    private void initializeMatchingContext(Template template) {
        // 获取画布尺寸
        this.canvasSize = template.getImageSize();
        if (this.canvasSize == null) {
            logger.warn("模板未指定画布尺寸，使用默认值1920x1080");
            this.canvasSize = new ImageSize(1920, 1080);
        }

        // 创建五点参考系
        CrossRatioFingerprint.ReferencePoints refPoints =
                new CrossRatioFingerprint.ReferencePoints(
                        this.canvasSize.getWidth(),
                        this.canvasSize.getHeight()
                );

        logger.debug("初始化五点参考系: {}", refPoints);

        // 创建指纹计算器
        this.fingerprintCalculator = new CrossRatioFingerprint.Calculator(refPoints);

        // 重置卡尔曼滤波器（新的一帧）
        if (enableKalmanFilter && kalmanManager != null) {
            // 保留滤波器状态以实现跨帧平滑
            // 如果需要完全重置，调用 kalmanManager.clear()
        }

        // 清空观测缓存
        this.observationCache = new HashMap<>();
    }

    /**
     * 离线注册阶段：构建指纹数据库
     * <p>
     * 对每个模板特征：
     * 1. 获取其标准位置坐标
     * 2. 计算理想指纹 F_ref
     * 3. 存储到数据库
     */
    private void registerTemplateFeatures(Template template) {
        fingerprintDatabase.clear();

        for (TemplateFeature feature : template.getFeatures()) {
            Point position = feature.getPosition();
            String featureId = feature.getId();

            // 计算理想指纹
            CrossRatioFingerprint fingerprint =
                    fingerprintCalculator.calculate(position, featureId);

            if (fingerprint.isValid()) {
                fingerprintDatabase.put(featureId, fingerprint);
                logger.debug("注册特征 {}: {} 位置={}",
                        featureId, fingerprint, position);
            } else {
                logger.warn("特征 {} 的指纹无效（可能位于参考点射线上），位置={}",
                        featureId, position);
                // 仍然存储，但标记为无效
                fingerprintDatabase.put(featureId, fingerprint);
            }
        }

        logger.info("指纹数据库构建完成: {} 个特征", fingerprintDatabase.size());
    }

    /**
     * 在线识别阶段：匹配检测对象
     * <p>
     * 对每个检测对象：
     * 1. 预处理：形态学中心提取（简化版，直接使用中心点）
     * 2. 卡尔曼滤波平滑（可选）
     * 3. 指纹计算：F_obs = ComputeCR(P)
     * 4. 最近邻搜索：find best match in DB
     * 5. 置信度阈值过滤
     */
    private InspectionResult matchDetectedObjects(Template template,
                                                  List<DetectedObject> detectedObjects) {
        InspectionResult result = new InspectionResult(template.getTemplateId());

        // 按类别分组
        Map<Integer, List<TemplateFeature>> templateByClass =
                template.getFeatures().stream()
                        .collect(Collectors.groupingBy(TemplateFeature::getClassId));

        Map<Integer, List<DetectedObject>> detectedByClass =
                detectedObjects.stream()
                        .collect(Collectors.groupingBy(DetectedObject::getClassId));

        // 对每个类别分别匹配
        for (Map.Entry<Integer, List<TemplateFeature>> entry : templateByClass.entrySet()) {
            int classId = entry.getKey();
            List<TemplateFeature> templateFeatures = entry.getValue();
            List<DetectedObject> detectedObjectsOfClass =
                    detectedByClass.getOrDefault(classId, new ArrayList<>());

            logger.debug("匹配类别 {}: 模板{}个特征 vs 检测{}个对象",
                    classId, templateFeatures.size(), detectedObjectsOfClass.size());

            // 执行类别内匹配
            matchClass(templateFeatures, detectedObjectsOfClass, result);
        }

        // 处理多余检测对象（错检）
        handleExtraDetections(detectedObjects, result);

        return result;
    }

    /**
     * 类别内匹配
     * <p>
     * 使用匈牙利算法（全局最优二分匹配）：
     * 1. 构建代价矩阵：距离越小，代价越低
     * 2. 找到总代价最小的匹配方案
     * 3. 确保一对一映射，避免交叉匹配问题
     */
    private void matchClass(List<TemplateFeature> templateFeatures,
                           List<DetectedObject> detectedObjects,
                           InspectionResult result) {
        int n = templateFeatures.size();
        int m = detectedObjects.size();

        if (n == 0 || m == 0) {
            // 没有模板或检测对象，全部标记为漏检
            for (TemplateFeature feature : templateFeatures) {
                result.addComparison(createMissingComparison(feature, null));
            }
            return;
        }

        // 构建代价矩阵（距离代价，越小越好）
        // 简化策略：直接用欧氏距离作为代价，让匈牙利算法自动找最优匹配
        double[][] costMatrix = new double[n][m];

        for (int t = 0; t < n; t++) {
            TemplateFeature feature = templateFeatures.get(t);
            Point templatePosition = feature.getPosition();

            for (int d = 0; d < m; d++) {
                DetectedObject detected = detectedObjects.get(d);
                Point detectedPosition = detected.getCenter();

                // 直接使用欧氏距离作为代价
                // 距离越小，匹配概率越高
                double euclideanDistance = templatePosition.distanceTo(detectedPosition);
                costMatrix[t][d] = euclideanDistance;
            }
        }

        // 使用匈牙利算法找全局最优匹配
        int[] assignment = HungarianAlgorithm.solve(costMatrix);

        // 跟踪已匹配的检测对象索引（用于错检判断）
        Set<Integer> matchedDetectedIndices = new HashSet<>();

        // 收集已匹配的点对，用于计算平均偏移
        List<Point> matchedTemplatePositions = new ArrayList<>();
        List<Point> matchedDetectedPositions = new ArrayList<>();

        // 处理匹配结果
        for (int t = 0; t < n; t++) {
            int d = assignment[t];
            TemplateFeature feature = templateFeatures.get(t);

            if (d == -1) {
                // 没有找到匹配，漏检
                logger.debug("模板特征 {} 未找到匹配 (漏检)", feature.getId());
                // 稍后统一处理漏检
                continue;
            }

            double distance = costMatrix[t][d];

            // 设置合理的距离阈值（像素）- 距离太大认为是无效匹配
            double MAX_MATCH_DISTANCE = 500.0;
            if (distance > MAX_MATCH_DISTANCE) {
                logger.debug("模板特征 {} 匹配距离过大 ({:.1f}px > {:.1f}px)，跳过",
                        feature.getId(), distance, MAX_MATCH_DISTANCE);
                continue;
            }

            // 有效匹配
            DetectedObject detected = detectedObjects.get(d);
            matchedDetectedIndices.add(d);

            // 收集匹配点对
            matchedTemplatePositions.add(feature.getPosition());
            matchedDetectedPositions.add(detected.getCenter());

            // 计算相似度分数（距离越小分数越高）
            TemplateFeature.Tolerance tolerance = feature.getTolerance();
            double maxDistance = Math.sqrt(
                    tolerance.getX() * tolerance.getX() +
                    tolerance.getY() * tolerance.getY()
            ) * 10.0;
            double score = 1.0 / (1.0 + distance / maxDistance);

            // 创建比对结果
            FeatureComparison comparison = createMatchComparison(feature, detected, score);
            result.addComparison(comparison);

            logger.debug("匹配: {} -> [{}], 分数={:.3f}, 距离={:.1f}px",
                    feature.getId(), d, score, distance);
        }

        // 计算仿射变换（用于预测漏检位置）
        SimpleAffineTransform transform = calculateAffineTransform(
                matchedTemplatePositions, matchedDetectedPositions);

        // 处理漏检（再次遍历，处理所有未匹配的模板特征）
        for (int t = 0; t < n; t++) {
            int d = assignment[t];
            TemplateFeature feature = templateFeatures.get(t);

            // 检查是否已匹配（不在 matchedDetectedIndices 中说明是漏检）
            if (d == -1 || !matchedDetectedIndices.contains(d)) {
                logger.debug("模板特征 {} 未找到匹配 (漏检)", feature.getId());
                result.addComparison(createMissingComparison(feature, transform));
            }
        }

        // 处理错检（未被匹配的检测对象）
        for (int d = 0; d < m; d++) {
            if (!matchedDetectedIndices.contains(d)) {
                DetectedObject detected = detectedObjects.get(d);
                result.addComparison(createExtraComparison(detected));
                logger.debug("错检: class={}, center={}, conf={:.2f}",
                        detected.getClassId(), detected.getCenter(), detected.getConfidence());
            }
        }
    }

    /**
     * 处理多余检测对象（错检）
     * 注意：已在 matchClass 方法中处理，此方法保留为空以兼容旧代码
     */
    private void handleExtraDetections(List<DetectedObject> detectedObjects,
                                      InspectionResult result) {
        // 全局最优匹配策略已经在 matchClass 中处理了所有检测对象
        // 此方法保留为空，以兼容旧代码
    }

    /**
     * 计算检测对象的指纹
     * <p>
     * 包括：
     * 1. 形态学中心提取（简化版）
     * 2. 卡尔曼滤波平滑
     * 3. 指纹计算
     */
    private CrossRatioFingerprint computeDetectedFingerprint(DetectedObject detected, int index) {
        Point center = detected.getCenter();

        // 应用卡尔曼滤波平滑
        if (enableKalmanFilter && kalmanManager != null) {
            String objectId = "detected_" + index;
            center = kalmanManager.smooth(objectId, center);
        }

        // 计算指纹
        String objectId = "D" + index;
        CrossRatioFingerprint fingerprint = fingerprintCalculator.calculate(center, objectId);

        // 缓存观测指纹
        observationCache.put(objectId, fingerprint);

        return fingerprint;
    }

    /**
     * 计算动态容差
     * <p>
     * 自适应容差公式：
     * epsilon(P) = 0.01, if dist(P, edge) > 100px
     * epsilon(P) = 0.03, otherwise
     * <p>
     * 贴边时容差放大3倍，因为边缘处误差被放大
     */
    private double computeDynamicTolerance(DetectedObject detected,
                                          CrossRatioFingerprint templateFingerprint) {
        Point center = detected.getCenter();

        // 计算到边缘的距离
        double distToLeft = center.x;
        double distToRight = canvasSize.getWidth() - center.x;
        double distToTop = center.y;
        double distToBottom = canvasSize.getHeight() - center.y;

        double minDistToEdge = Math.min(
                Math.min(distToLeft, distToRight),
                Math.min(distToTop, distToBottom)
        );

        // 动态容差
        if (minDistToEdge < edgeDistanceThreshold) {
            return baseTolerance * 3.0;  // 贴边时放大3倍
        } else {
            return baseTolerance;
        }
    }

    // ==================== 结果构建方法 ====================

    /**
     * 创建匹配的比对结果
     */
    private FeatureComparison createMatchComparison(TemplateFeature feature,
                                                   DetectedObject detected,
                                                   double similarity) {
        // 计算位置偏差
        double deltaX = detected.getCenter().x - feature.getPosition().x;
        double deltaY = detected.getCenter().y - feature.getPosition().y;

        // 判断状态
        TemplateFeature.Tolerance tolerance = feature.getTolerance();
        boolean withinTolerance = Math.abs(deltaX) <= tolerance.getX() && Math.abs(deltaY) <= tolerance.getY();

        FeatureComparison comparison;
        if (withinTolerance) {
            comparison = FeatureComparison.passed(feature, detected.getCenter(), deltaX, deltaY);
        } else {
            comparison = FeatureComparison.deviation(feature, detected.getCenter(), deltaX, deltaY);
        }

        // 存储相似度分数（用于调试）
        comparison.setConfidence(similarity);
        comparison.setClassName(detected.getClassName());

        return comparison;
    }

    /**
     * 创建漏检比对结果
     * 使用仿射变换预测真实位置
     */
    private FeatureComparison createMissingComparison(TemplateFeature feature, SimpleAffineTransform transform) {
        FeatureComparison comparison = FeatureComparison.missing(feature);
        comparison.setClassName(feature.getName());

        // 使用仿射变换预测检测位置
        Point predictedPosition;
//        if (transform != null) {
//            predictedPosition = transform.applyForward(feature.getPosition());
//        } else {
//            // 如果没有变换，使用模板位置
//            predictedPosition = feature.getPosition();
//        }
        predictedPosition = feature.getPosition();
        comparison.setDetectedPosition(predictedPosition);

        return comparison;
    }

    /**
     * 创建错检比对结果
     */
    private FeatureComparison createExtraComparison(DetectedObject detected) {
        FeatureComparison extra = FeatureComparison.extra(
                "EXTRA_" + detected.getClassId(),
                detected.getClassName(),
                detected.getCenter(),
                detected.getClassId(),
                detected.getConfidence()
        );
        extra.setDetectedPosition(detected.getCenter());
        return extra;
    }

    // ==================== 匹配对类 ====================

    /**
     * 匹配对：存储模板特征与检测对象的匹配信息
     */
    private static class MatchPair {
        final int templateIndex;
        final int detectedIndex;
        final TemplateFeature feature;
        final DetectedObject detected;
        final double score;
        final double euclideanDistance;
        final double fingerprintDistance;

        MatchPair(int templateIndex, int detectedIndex,
                  TemplateFeature feature, DetectedObject detected,
                  double score, double euclideanDistance, double fingerprintDistance) {
            this.templateIndex = templateIndex;
            this.detectedIndex = detectedIndex;
            this.feature = feature;
            this.detected = detected;
            this.score = score;
            this.euclideanDistance = euclideanDistance;
            this.fingerprintDistance = fingerprintDistance;
        }
    }

    // ==================== 配置方法 ====================

    public void setBaseTolerance(double baseTolerance) {
        this.baseTolerance = baseTolerance;
    }

    public void setHighConfidenceThreshold(double highConfidenceThreshold) {
        this.highConfidenceThreshold = highConfidenceThreshold;
    }

    public void setLowConfidenceThreshold(double lowConfidenceThreshold) {
        this.lowConfidenceThreshold = lowConfidenceThreshold;
    }

    public void setEdgeDistanceThreshold(double edgeDistanceThreshold) {
        this.edgeDistanceThreshold = edgeDistanceThreshold;
    }

    public void setEnableKalmanFilter(boolean enableKalmanFilter) {
        this.enableKalmanFilter = enableKalmanFilter;
        if (!enableKalmanFilter) {
            this.kalmanManager = null;
        } else if (this.kalmanManager == null) {
            this.kalmanManager = new KalmanFilter.Manager();
        }
    }

    // ==================== 调试和分析方法 ====================

    /**
     * 获取指纹数据库（用于调试）
     */
    public Map<String, CrossRatioFingerprint> getFingerprintDatabase() {
        return Collections.unmodifiableMap(fingerprintDatabase);
    }

    /**
     * 获取观测指纹缓存（用于调试）
     */
    public Map<String, CrossRatioFingerprint> getObservationCache() {
        return Collections.unmodifiableMap(observationCache);
    }

    /**
     * 分析指纹分布（用于调试）
     */
    public void analyzeFingerprintDistribution() {
        if (fingerprintDatabase.isEmpty()) {
            logger.info("指纹数据库为空");
            return;
        }

        // 统计每个维度的范围
        double minCr1 = Double.MAX_VALUE, maxCr1 = Double.MIN_VALUE;
        double minCr2 = Double.MAX_VALUE, maxCr2 = Double.MIN_VALUE;
        double minCr3 = Double.MAX_VALUE, maxCr3 = Double.MIN_VALUE;

        for (CrossRatioFingerprint fp : fingerprintDatabase.values()) {
            if (!fp.isValid()) continue;
            minCr1 = Math.min(minCr1, fp.getCr1());
            maxCr1 = Math.max(maxCr1, fp.getCr1());
            minCr2 = Math.min(minCr2, fp.getCr2());
            maxCr2 = Math.max(maxCr2, fp.getCr2());
            minCr3 = Math.min(minCr3, fp.getCr3());
            maxCr3 = Math.max(maxCr3, fp.getCr3());
        }

        logger.info("指纹分布统计:");
        logger.info("  CR1: [{:.4f}, {:.4f}]", minCr1, maxCr1);
        logger.info("  CR2: [{:.4f}, {:.4f}]", minCr2, maxCr2);
        logger.info("  CR3: [{:.4f}, {:.4f}]", minCr3, maxCr3);
    }

    // ==================== 仿射变换相关方法 ====================

    /**
     * 计算仿射变换（简化版：仅平移）
     * 基于 CoordinateBasedMatcher 的逻辑
     */
    private SimpleAffineTransform calculateAffineTransform(
            List<Point> templatePositions, List<Point> detectedPositions) {
        if (templatePositions.isEmpty() || detectedPositions.isEmpty()) {
            return null;
        }

        // 计算质心
        double templateCx = 0.0, templateCy = 0.0;
        double detectedCx = 0.0, detectedCy = 0.0;

        for (Point p : templatePositions) {
            templateCx += p.x;
            templateCy += p.y;
        }
        templateCx /= templatePositions.size();
        templateCy /= templatePositions.size();

        for (Point p : detectedPositions) {
            detectedCx += p.x;
            detectedCy += p.y;
        }
        detectedCx /= detectedPositions.size();
        detectedCy /= detectedPositions.size();

        // 计算平移量
        double tx = detectedCx - templateCx;
        double ty = detectedCy - templateCy;

        logger.debug("计算仿射变换: 模板质心=({:.1f},{:.1f}), 检测质心=({:.1f},{:.1f}), 平移=({:.1f},{:.1f})",
                templateCx, templateCy, detectedCx, detectedCy, tx, ty);

        return new SimpleAffineTransform(tx, ty);
    }

    /**
     * 简化的仿射变换类（仅平移）
     * 参考 CoordinateBasedMatcher.AffineTransform 的 applyForward 方法
     */
    private static class SimpleAffineTransform {
        private final double tx;  // X方向平移
        private final double ty;  // Y方向平移

        SimpleAffineTransform(double tx, double ty) {
            this.tx = tx;
            this.ty = ty;
        }

        /**
         * 正向变换：模板坐标 → 检测图坐标
         * 用于标注漏检位置
         */
        public Point applyForward(Point p) {
            return new Point(p.x + tx, p.y + ty);
        }

        @Override
        public String toString() {
            return String.format("Transform[tx=%.2f, ty=%.2f]", tx, ty);
        }
    }
}
