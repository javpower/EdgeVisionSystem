package com.edge.vision.core.topology;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 匈牙利算法实现
 * <p>
 * 用于求解分配问题的最优解
 * 在本项目中用于求解模板节点与检测节点之间的最优一一对应关系
 * <p>
 * 时间复杂度: O(n^3)，其中n是矩阵维度
 * 空间复杂度: O(n^2)
 */
public class HungarianAlgorithm {
    private static final Logger logger = LoggerFactory.getLogger(HungarianAlgorithm.class);

    /**
     * 求解最小权重匹配
     * <p>
     * 给定一个代价矩阵，找到使总代价最小的匹配方式
     *
     * @param costMatrix 代价矩阵，costMatrix[i][j] 表示将模板节点i匹配到检测节点j的代价
     *                   如果检测节点多于模板节点，会添加虚拟模板节点
     *                   如果模板节点多于检测节点，会添加虚拟检测节点
     * @return 匹配结果，result[i] = j 表示模板节点i匹配到检测节点j，-1表示未匹配
     */
    public static int[] solve(double[][] costMatrix) {
        if (costMatrix == null || costMatrix.length == 0) {
            return new int[0];
        }

        int rows = costMatrix.length;
        int cols = costMatrix[0].length;

        // 确保矩阵是方阵（添加虚拟行/列）
        int size = Math.max(rows, cols);
        double[][] squareMatrix = makeSquareMatrix(costMatrix, size);

        // 执行匈牙利算法
        int[] assignment = executeHungarian(squareMatrix);

        // 截取结果到实际行数
        int[] result = new int[rows];
        for (int i = 0; i < rows; i++) {
            if (assignment[i] < cols) {
                result[i] = assignment[i];
            } else {
                result[i] = -1;  // 匹配到虚拟节点
            }
        }

        return result;
    }

    /**
     * 将矩阵转换为方阵（添加虚拟行/列）
     * 虚拟元素的代价设为一个较大的值
     */
    private static double[][] makeSquareMatrix(double[][] matrix, int size) {
        double[][] result = new double[size][size];
        double largeValue = 1e6;  // 较大的代价

        // 初始化为大值
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                result[i][j] = largeValue;
            }
        }

        // 复制原始矩阵
        int rows = matrix.length;
        int cols = matrix[0].length;
        for (int i = 0; i < rows; i++) {
            System.arraycopy(matrix[i], 0, result[i], 0, cols);
        }

        return result;
    }

    /**
     * 执行匈牙利算法核心逻辑
     * <p>
     * 基于Munkres算法的实现
     */
    private static int[] executeHungarian(double[][] costMatrix) {
        int n = costMatrix.length;
        final int MAX_ITERATIONS = n * n * 10;  // 防止无限循环
        int iteration = 0;
        int lastMatchCount = 0;
        int stagnantCount = 0;  // 连续未改进的次数

        // 步骤1: 行约简
        for (int i = 0; i < n; i++) {
            double minVal = findMinInRow(costMatrix, i);
            for (int j = 0; j < n; j++) {
                costMatrix[i][j] -= minVal;
            }
        }

        // 步骤2: 列约简
        for (int j = 0; j < n; j++) {
            double minVal = findMinInCol(costMatrix, j);
            for (int i = 0; i < n; i++) {
                costMatrix[i][j] -= minVal;
            }
        }

        // 标记数组
        boolean[] rowMarked = new boolean[n];
        boolean[] colMarked = new boolean[n];
        int[] rowAssignment = new int[n];  // rowAssignment[i] = j表示行i分配给列j
        int[] colAssignment = new int[n];  // colAssignment[j] = i表示列j分配给行i
        for (int i = 0; i < n; i++) {
            rowAssignment[i] = -1;
            colAssignment[i] = -1;
        }

        // 步骤3-6: 寻找最大匹配
        while (iteration < MAX_ITERATIONS) {
            iteration++;

            // 步骤3: 寻找独立零点
            markZeros(costMatrix, rowMarked, colMarked, rowAssignment, colAssignment);

            int matchCount = countMatches(rowAssignment);
            if (matchCount == n) {
                logger.debug("匈牙利算法完成: 迭代次数={}, 完美匹配", iteration);
                break;  // 找到完美匹配
            }

            // 检查是否停滞
            if (matchCount == lastMatchCount) {
                stagnantCount++;
                if (stagnantCount > n) {
                    logger.warn("匈牙利算法停滞: 匹配数={}, 迭代次数={}", matchCount, iteration);
                    break;
                }
            } else {
                stagnantCount = 0;
            }
            lastMatchCount = matchCount;

            // 步骤4: 覆盖所有零
            boolean[] rowCovered = new boolean[n];
            boolean[] colCovered = new boolean[n];
            coverZeros(costMatrix, rowAssignment, rowCovered, colCovered);

            // 步骤5: 创建新零
            double minUncovered = findMinUncovered(costMatrix, rowCovered, colCovered);

            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    if (rowCovered[i] && colCovered[j]) {
                        costMatrix[i][j] += minUncovered;
                    } else if (!rowCovered[i] && !colCovered[j]) {
                        costMatrix[i][j] -= minUncovered;
                    }
                }
            }
        }

        if (iteration >= MAX_ITERATIONS) {
            logger.warn("匈牙利算法达到最大迭代次数({}), 当前匹配数: {}", MAX_ITERATIONS, countMatches(rowAssignment));
        }

        return rowAssignment;
    }

    /**
     * 寻找并标记独立零点
     */
    private static void markZeros(double[][] matrix, boolean[] rowMarked, boolean[] colMarked,
                                  int[] rowAssignment, int[] colAssignment) {
        int n = matrix.length;

        // 重置标记
        for (int i = 0; i < n; i++) {
            rowMarked[i] = false;
            colMarked[i] = false;
            rowAssignment[i] = -1;
            colAssignment[i] = -1;
        }

        // 对每行寻找未覆盖的零
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (Math.abs(matrix[i][j]) < 1e-10 && !colMarked[j]) {
                    rowAssignment[i] = j;
                    colAssignment[j] = i;
                    colMarked[j] = true;
                    break;
                }
            }
        }
    }

    /**
     * 覆盖所有零
     * 返回是否所有行都被覆盖
     */
    private static void coverZeros(double[][] matrix, int[] rowAssignment,
                                   boolean[] rowCovered, boolean[] colCovered) {
        int n = matrix.length;

        // 初始化覆盖
        for (int i = 0; i < n; i++) {
            rowCovered[i] = false;
            colCovered[i] = false;
        }

        // 从rowAssignment构建colAssignment
        int[] colAssignment = new int[n];
        for (int i = 0; i < n; i++) {
            colAssignment[i] = -1;
        }
        for (int i = 0; i < n; i++) {
            if (rowAssignment[i] != -1) {
                colAssignment[rowAssignment[i]] = i;
            }
        }

        // 标记未分配的行
        for (int i = 0; i < n; i++) {
            if (rowAssignment[i] == -1) {
                rowCovered[i] = true;
            }
        }

        boolean changed;
        do {
            changed = false;

            // 标记有零在已标记行中的列
            for (int i = 0; i < n; i++) {
                if (rowCovered[i]) {
                    for (int j = 0; j < n; j++) {
                        if (Math.abs(matrix[i][j]) < 1e-10 && !colCovered[j]) {
                            colCovered[j] = true;
                            changed = true;
                        }
                    }
                }
            }

            // 标记有分配在已标记列中的行
            for (int j = 0; j < n; j++) {
                if (colCovered[j] && colAssignment[j] != -1) {
                    int i = colAssignment[j];
                    if (!rowCovered[i]) {
                        rowCovered[i] = true;
                        changed = true;
                    }
                }
            }
        } while (changed);
    }

    /**
     * 找出未覆盖元素中的最小值
     */
    private static double findMinUncovered(double[][] matrix, boolean[] rowCovered, boolean[] colCovered) {
        int n = matrix.length;
        double minVal = Double.MAX_VALUE;

        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (!rowCovered[i] && !colCovered[j]) {
                    minVal = Math.min(minVal, matrix[i][j]);
                }
            }
        }

        return minVal;
    }

    private static double findMinInRow(double[][] matrix, int row) {
        double min = matrix[row][0];
        for (int j = 1; j < matrix[row].length; j++) {
            min = Math.min(min, matrix[row][j]);
        }
        return min;
    }

    private static double findMinInCol(double[][] matrix, int col) {
        double min = matrix[0][col];
        for (int i = 1; i < matrix.length; i++) {
            min = Math.min(min, matrix[i][col]);
        }
        return min;
    }

    private static int countMatches(int[] assignment) {
        int count = 0;
        for (int a : assignment) {
            if (a != -1) count++;
        }
        return count;
    }

    /**
     * 计算匹配的总代价
     */
    public static double calculateTotalCost(double[][] costMatrix, int[] assignment) {
        double total = 0.0;
        for (int i = 0; i < assignment.length; i++) {
            int j = assignment[i];
            if (j >= 0 && j < costMatrix[i].length) {
                total += costMatrix[i][j];
            }
        }
        return total;
    }
}
