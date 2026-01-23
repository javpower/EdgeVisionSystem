package com.edge.vision.core.topology;

import com.edge.vision.core.template.model.Point;

/**
 * 卡尔曼滤波器
 * <p>
 * 用于平滑YOLO检测框的中心点位置，抑制高频抖动
 * <p>
 * 状态向量: x = [px, py, vx, vy]^T
 * - px, py: 位置坐标
 * - vx, vy: 速度分量
 * <p>
 * 状态转移方程:
 * x_{k+1} = F * x_k + w_k
 * <p>
 * 观测方程:
 * z_k = H * x_k + v_k
 * <p>
 * 其中:
 * - F: 状态转移矩阵 (假设匀速运动)
 * - H: 观测矩阵 (只观测位置)
 * - w_k: 过程噪声 (模型不确定性)
 * - v_k: 观测噪声 (检测误差)
 * <p>
 * 矩阵定义:
 * F = [1, 0, dt,  0]    H = [1, 0, 0, 0]
 *     [0, 1,  0, dt]        [0, 1, 0, 0]
 *     [0, 0,  1,  0]
 *     [0, 0,  0,  1]
 */
public class KalmanFilter {
    // 状态向量 [px, py, vx, vy]
    private double[] x;

    // 状态协方差矩阵 P (4x4)
    private double[][] P;

    // 状态转移矩阵 F (4x4)
    private final double[][] F;

    // 观测矩阵 H (2x4)
    private final double[][] H;

    // 过程噪声协方差 Q (4x4)
    private final double[][] Q;

    // 观测噪声协方差 R (2x2)
    private final double[][] R;

    // 时间步长
    private final double dt;

    // 初始化标记
    private boolean initialized;

    /**
     * 创建标准的2D位置跟踪卡尔曼滤波器
     *
     * @param dt           时间步长（秒）
     * @param processNoise 过程噪声标准差（位置不确定性）
     * @param measureNoise 观测噪声标准差（检测误差）
     */
    public KalmanFilter(double dt, double processNoise, double measureNoise) {
        this.dt = dt;
        this.initialized = false;

        // 初始化状态转移矩阵 F (匀速运动模型)
        this.F = new double[][]{
                {1, 0, dt, 0},
                {0, 1, 0, dt},
                {0, 0, 1, 0},
                {0, 0, 0, 1}
        };

        // 初始化观测矩阵 H (只观测位置，不观测速度)
        this.H = new double[][]{
                {1, 0, 0, 0},
                {0, 1, 0, 0}
        };

        // 初始化过程噪声协方差 Q
        // 假设过程噪声主要影响加速度
        double q = processNoise * processNoise;
        this.Q = new double[][]{
                {q * dt * dt * dt * dt / 4, 0, q * dt * dt * dt / 2, 0},
                {0, q * dt * dt * dt * dt / 4, 0, q * dt * dt * dt / 2},
                {q * dt * dt * dt / 2, 0, q * dt * dt, 0},
                {0, q * dt * dt * dt / 2, 0, q * dt * dt}
        };

        // 初始化观测噪声协方差 R
        double r = measureNoise * measureNoise;
        this.R = new double[][]{
                {r, 0},
                {0, r}
        };

        // 初始化状态协方差矩阵 P (较大值表示初始不确定性高)
        this.P = new double[][]{
                {100, 0, 0, 0},
                {0, 100, 0, 0},
                {0, 0, 100, 0},
                {0, 0, 0, 100}
        };
    }

    /**
     * 创建默认配置的卡尔曼滤波器
     * <p>
     * 默认参数适用于30fps的视频流:
     * - dt = 0.033s (约30fps)
     * - processNoise = 0.1 (较小的过程噪声)
     * - measureNoise = 5.0 (YOLO检测误差约±5像素)
     */
    public KalmanFilter() {
        this(0.033, 0.1, 5.0);
    }

    /**
     * 初始化滤波器状态
     *
     * @param initialPoint 初始位置
     */
    public void init(Point initialPoint) {
        this.x = new double[]{
                initialPoint.x,
                initialPoint.y,
                0,  // 初始速度为0
                0
        };

        // 重置协方差矩阵
        this.P = new double[][]{
                {10, 0, 0, 0},
                {0, 10, 0, 0},
                {0, 0, 10, 0},
                {0, 0, 0, 10}
        };

        this.initialized = true;
    }

    /**
     * 预测步骤（时间更新）
     * <p>
     * x_pred = F * x
     * P_pred = F * P * F^T + Q
     */
    public void predict() {
        if (!initialized) {
            throw new IllegalStateException("KalmanFilter not initialized. Call init() first.");
        }

        // 状态预测: x_pred = F * x
        double[] x_pred = matrixVectorMultiply(F, x);

        // 协方差预测: P_pred = F * P * F^T + Q
        double[][] FP = matrixMultiply(F, P);
        double[][] FPT = matrixTranspose(FP);
        double[][] P_pred = matrixAdd(FPT, Q);

        // 更新状态和协方差
        this.x = x_pred;
        this.P = P_pred;
    }

    /**
     * 更新步骤（观测更新）
     * <p>
     * y = z - H * x (创新/残差)
     * S = H * P * H^T + R (创新协方差)
     * K = P * H^T * S^-1 (卡尔曼增益)
     * x = x + K * y (状态更新)
     * P = (I - K * H) * P (协方差更新)
     *
     * @param measurement 观测位置
     */
    public void update(Point measurement) {
        if (!initialized) {
            init(measurement);
            return;
        }

        // 先执行预测步骤
        predict();

        // 计算创新: y = z - H * x
        double[] z = new double[]{measurement.x, measurement.y};
        double[] Hx = matrixVectorMultiply(H, x);
        double[] y = vectorSubtract(z, Hx);

        // 计算创新协方差: S = H * P * H^T + R
        // H is 2x4, P is 4x4, H^T is 4x2, so S should be 2x2
        double[][] HT = matrixTranspose(H);  // 4x2
        double[][] P_HT = matrixMultiply(P, HT);  // 4x4 * 4x2 = 4x2
        double[][] HP_HT = matrixMultiply(H, P_HT);  // 2x4 * 4x2 = 2x2
        double[][] S = matrixAdd(HP_HT, R);  // 2x2 + 2x2 = 2x2

        // 计算卡尔曼增益: K = P * H^T * S^-1
        double[][] PHT = P_HT;  // Already computed: 4x2
        double[][] S_inv = matrixInverse2x2(S);  // 2x2
        double[][] K = matrixMultiply(PHT, S_inv);  // 4x2 * 2x2 = 4x2

        // 状态更新: x = x + K * y
        double[] Ky = matrixVectorMultiply(K, y);
        this.x = vectorAdd(this.x, Ky);

        // 协方差更新: P = (I - K * H) * P
        double[][] KH = matrixMultiply(K, H);
        double[][] I_KH = matrixSubtract(identity4x4(), KH);
        this.P = matrixMultiply(I_KH, this.P);
    }

    /**
     * 获取滤波后的位置估计
     */
    public Point getEstimatedPosition() {
        if (!initialized) {
            throw new IllegalStateException("KalmanFilter not initialized. Call init() first.");
        }
        return new Point(x[0], x[1]);
    }

    /**
     * 获取估计的速度
     */
    public Point getEstimatedVelocity() {
        if (!initialized) {
            throw new IllegalStateException("KalmanFilter not initialized. Call init() first.");
        }
        return new Point(x[2], x[3]);
    }

    /**
     * 获取位置不确定性标准差
     */
    public double getPositionUncertainty() {
        if (!initialized) {
            throw new IllegalStateException("KalmanFilter not initialized. Call init() first.");
        }
        // 位置不确定性 = sqrt(P[0][0] + P[1][1])
        return Math.sqrt(P[0][0] + P[1][1]);
    }

    /**
     * 重置滤波器状态
     */
    public void reset() {
        this.initialized = false;
        this.x = null;
        this.P = new double[][]{
                {100, 0, 0, 0},
                {0, 100, 0, 0},
                {0, 0, 100, 0},
                {0, 0, 0, 100}
        };
    }

    // ==================== 矩阵运算工具方法 ====================

    private double[][] identity4x4() {
        return new double[][]{
                {1, 0, 0, 0},
                {0, 1, 0, 0},
                {0, 0, 1, 0},
                {0, 0, 0, 1}
        };
    }

    private double[] matrixVectorMultiply(double[][] matrix, double[] vector) {
        int rows = matrix.length;
        int cols = matrix[0].length;
        double[] result = new double[rows];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                result[i] += matrix[i][j] * vector[j];
            }
        }
        return result;
    }

    private double[][] matrixMultiply(double[][] A, double[][] B) {
        int rowsA = A.length;
        int colsA = A[0].length;
        int colsB = B[0].length;
        double[][] result = new double[rowsA][colsB];
        for (int i = 0; i < rowsA; i++) {
            for (int j = 0; j < colsB; j++) {
                for (int k = 0; k < colsA; k++) {
                    result[i][j] += A[i][k] * B[k][j];
                }
            }
        }
        return result;
    }

    private double[][] matrixTranspose(double[][] matrix) {
        int rows = matrix.length;
        int cols = matrix[0].length;
        double[][] result = new double[cols][rows];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                result[j][i] = matrix[i][j];
            }
        }
        return result;
    }

    private double[] vectorAdd(double[] a, double[] b) {
        double[] result = new double[a.length];
        for (int i = 0; i < a.length; i++) {
            result[i] = a[i] + b[i];
        }
        return result;
    }

    private double[] vectorSubtract(double[] a, double[] b) {
        double[] result = new double[a.length];
        for (int i = 0; i < a.length; i++) {
            result[i] = a[i] - b[i];
        }
        return result;
    }

    private double[][] matrixAdd(double[][] A, double[][] B) {
        int rows = A.length;
        int cols = A[0].length;
        double[][] result = new double[rows][cols];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                result[i][j] = A[i][j] + B[i][j];
            }
        }
        return result;
    }

    private double[][] matrixSubtract(double[][] A, double[][] B) {
        int rows = A.length;
        int cols = A[0].length;
        double[][] result = new double[rows][cols];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                result[i][j] = A[i][j] - B[i][j];
            }
        }
        return result;
    }

    /**
     * 2x2矩阵求逆
     * <p>
     * 对于矩阵 A = [a b]
     * [c d]
     * <p>
     * A^-1 = (1/det(A)) * [d -b]
     * [-c a]
     * <p>
     * 其中 det(A) = ad - bc
     */
    private double[][] matrixInverse2x2(double[][] matrix) {
        double a = matrix[0][0];
        double b = matrix[0][1];
        double c = matrix[1][0];
        double d = matrix[1][1];

        double det = a * d - b * c;
        if (Math.abs(det) < 1e-10) {
            // 矩阵奇异，返回伪逆（添加小正则化项）
            det = (det >= 0 ? 1e-10 : -1e-10);
        }

        double invDet = 1.0 / det;
        return new double[][]{
                {d * invDet, -b * invDet},
                {-c * invDet, a * invDet}
        };
    }

    /**
     * 批量滤波器管理器
     * <p>
     * 用于管理多个对象的卡尔曼滤波器
     */
    public static class Manager {
        private final java.util.Map<String, KalmanFilter> filters;
        private final double dt;
        private final double processNoise;
        private final double measureNoise;

        public Manager(double dt, double processNoise, double measureNoise) {
            this.filters = new java.util.HashMap<>();
            this.dt = dt;
            this.processNoise = processNoise;
            this.measureNoise = measureNoise;
        }

        public Manager() {
            this(0.033, 0.1, 5.0);
        }

        /**
         * 更新指定对象的滤波器并返回平滑后的位置
         */
        public Point smooth(String objectId, Point measurement) {
            KalmanFilter filter = filters.get(objectId);
            if (filter == null) {
                filter = new KalmanFilter(dt, processNoise, measureNoise);
                filter.init(measurement);
                filters.put(objectId, filter);
                return measurement;
            }

            filter.update(measurement);
            return filter.getEstimatedPosition();
        }

        /**
         * 预测指定对象的下一位置
         */
        public Point predict(String objectId) {
            KalmanFilter filter = filters.get(objectId);
            if (filter == null || !filter.initialized) {
                return null;
            }
            filter.predict();
            return filter.getEstimatedPosition();
        }

        /**
         * 移除指定对象的滤波器
         */
        public void remove(String objectId) {
            filters.remove(objectId);
        }

        /**
         * 清空所有滤波器
         */
        public void clear() {
            filters.clear();
        }

        /**
         * 获取滤波器数量
         */
        public int size() {
            return filters.size();
        }
    }
}
