package com.edge.vision.core.topology.fourcorner;

import com.edge.vision.core.template.model.Point;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 四角检测器
 * <p>
 * 提供多种方法检测工件/对象的四个角：
 * 1. ArUco 标记检测（推荐，最稳定）
 * 2. 轮廓 + 凸包检测（适合规则形状）
 * 3. Harris 角点检测（通用方法）
 */
@Component
public class FourCornerDetector {
    private static final Logger logger = LoggerFactory.getLogger(FourCornerDetector.class);

    /**
     * 检测方法枚举
     */
    public enum DetectionMethod {
        ARUCO,          // ArUco 标记（推荐）
        CONTOUR,        // 轮廓 + 凸包
        HARRIS,         // Harris 角点
        AUTO            // 自动选择
    }

    /**
     * 检测四个角（自动选择方法）
     *
     * @param image 输入图像
     * @return 四个角 [TL, TR, BR, BL] 或 null
     */
    public Point[] detectCorners(Mat image) {
        return detectCorners(image, DetectionMethod.AUTO);
    }

    /**
     * 检测四个角
     *
     * @param image  输入图像
     * @param method 检测方法
     * @return 四个角 [TL, TR, BR, BL] 或 null
     */
    public Point[] detectCorners(Mat image, DetectionMethod method) {
        if (image == null || image.empty()) {
            logger.error("Input image is null or empty");
            return null;
        }

        // 自动选择方法
        if (method == DetectionMethod.AUTO) {
            // 尝试轮廓检测
            Point[] corners = detectByContour(image);
            if (corners != null) {
                return corners;
            }
            logger.info("Contour detection failed, no corners found");
            return null;
        }

        // 指定方法
        switch (method) {
            case ARUCO:
                return detectByArUco(image);
            case CONTOUR:
                return detectByContour(image);
            case HARRIS:
                return detectByHarris(image);
            default:
                return detectByContour(image);
        }
    }

    /**
     * 方法1：使用 ArUco 标记检测四角
     * <p>
     * 前提条件：工件四个角贴有 ArUco 二维码
     * <p>
     * 标记布局：
     * TL: ID 0, TR: ID 1, BR: ID 2, BL: ID 3
     *
     * @param image 输入图像
     * @return 四个角 [TL, TR, BR, BL] 或 null
     */
    public Point[] detectByArUco(Mat image) {
        try {
            // 尝试使用 ArUco 检测（需要 opencv-contrib）
            return detectByArUcoImpl(image);
        } catch (NoClassDefFoundError | NoSuchMethodError e) {
            // opencv-contrib 不可用，回退到轮廓检测
            logger.info("OpenCV contrib not available, falling back to contour detection");
            return detectByContour(image);
        } catch (Exception e) {
            logger.error("ArUco detection failed", e);
            return detectByContour(image);
        }
    }

    /**
     * ArUco 检测实现
     * <p>
     * 使用反射调用 ArUco API，以便在 opencv-contrib 不可用时优雅降级
     */
    @SuppressWarnings("unchecked")
    private Point[] detectByArUcoImpl(Mat image) {
        try {
            // 准备图像（灰度）
            Mat gray = new Mat();
            if (image.channels() > 1) {
                Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGR2GRAY);
            } else {
                gray = image;
            }

            // 使用反射调用 ArUco API（避免编译时依赖 opencv-contrib）
            // ArUco.detectMarkers(gray, dictionary, corners, ids)
            Class<?> arucoClass = Class.forName("org.opencv.aruco.Aruco");
            Class<?> dictClass = Class.forName("org.opencv.aruco.Dictionary");

            // 获取预定义字典 (DICT_4X4_50)
            Object dictionary = dictClass.getMethod("get", int.class).invoke(null, 0);

            // 准备输出参数
            List<Mat> markerCorners = new ArrayList<>();
            Mat markerIds = new Mat();

            // 调用 detectMarkers
            arucoClass.getMethod("detectMarkers",
                Mat.class, dictClass, List.class, Mat.class, Mat.class, Class.forName("org.opencv.aruco.DetectorParameters"))
                .invoke(null, gray, dictionary, markerCorners, markerIds, new Mat(), null);

            // 释放资源
            if (gray != image) {
                gray.release();
            }

            // 检查是否检测到标记
            if (markerIds.empty() || markerIds.rows() < 4) {
                logger.warn("ArUco: detected {} markers, need at least 4", markerIds.rows());
                markerIds.release();
                return null;
            }

            // 提取标记ID和角点
            float[] idsData = new float[(int) markerIds.size().height];
            markerIds.get(0, 0, idsData);

            // 存储标记位置：ID -> 中心点
            java.util.Map<Integer, Point> markerCenters = new java.util.HashMap<>();

            for (int i = 0; i < idsData.length; i++) {
                int id = (int) idsData[i];
                if (id < 0 || id > 3) continue;  // 只关注 ID 0-3

                Mat cornersMat = markerCorners.get(i);
                float[] cornersData = new float[(int) cornersMat.total() * 4];
                cornersMat.get(0, 0, cornersData);

                // 计算标记中心（4个角点的平均值）
                double cx = 0, cy = 0;
                for (int j = 0; j < 8; j += 2) {
                    cx += cornersData[j];
                    cy += cornersData[j + 1];
                }
                cx /= 4;
                cy /= 4;

                markerCenters.put(id, new Point(cx, cy));
            }

            markerIds.release();

            // 检查是否找到了所有4个标记
            if (markerCenters.size() < 4) {
                logger.warn("ArUco: found {}/4 required markers", markerCenters.size());
                return null;
            }

            // 按 ID 顺序返回：TL(0), TR(1), BR(2), BL(3)
            Point[] corners = new Point[4];
            corners[0] = markerCenters.get(0);  // TL
            corners[1] = markerCenters.get(1);  // TR
            corners[2] = markerCenters.get(2);  // BR
            corners[3] = markerCenters.get(3);  // BL

            logger.info("ArUco: detected all 4 corners - TL=({},{}), TR=({},{}), BR=({},{}), BL=({},{})",
                (int)corners[0].x, (int)corners[0].y,
                (int)corners[1].x, (int)corners[1].y,
                (int)corners[2].x, (int)corners[2].y,
                (int)corners[3].x, (int)corners[3].y);

            return corners;

        } catch (ClassNotFoundException e) {
            throw new NoClassDefFoundError("opencv-contrib not available");
        } catch (Exception e) {
            logger.error("ArUco implementation error", e);
            return null;
        }
    }

    /**
     * 方法2：使用轮廓 + 凸包检测四角
     * <p>
     * 适用场景：工件形状规则（矩形/近似矩形）
     *
     * @param image 输入图像
     * @return 四个角 [TL, TR, BR, BL] 或 null
     */
    public Point[] detectByContour(Mat image) {
        try {
            // 预处理
            Mat gray = new Mat();
            Mat blurred = new Mat();
            Mat edges = new Mat();

            if (image.channels() > 1) {
                Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGR2GRAY);
            } else {
                gray = image.clone();
            }

            // 高斯模糊
            Imgproc.GaussianBlur(gray, blurred, new Size(5, 5), 0);

            // Canny 边缘检测
            Imgproc.Canny(blurred, edges, 50, 150);

            // 查找轮廓
            List<MatOfPoint> contours = new ArrayList<>();
            Mat hierarchy = new Mat();
            Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

            if (contours.isEmpty()) {
                logger.warn("No contours found");
                gray.release();
                blurred.release();
                edges.release();
                hierarchy.release();
                return null;
            }

            // 找到最大轮廓
            MatOfPoint largestContour = null;
            double maxArea = 0;

            for (MatOfPoint contour : contours) {
                double area = Imgproc.contourArea(contour);
                if (area > maxArea) {
                    maxArea = area;
                    largestContour = contour;
                }
            }

            if (largestContour == null || maxArea < 1000) {
                logger.warn("Largest contour too small: {}", maxArea);
                gray.release();
                blurred.release();
                edges.release();
                hierarchy.release();
                return null;
            }

            // 多边形近似（寻找4个角）
            MatOfPoint2f contour2f = new MatOfPoint2f(largestContour.toArray());
            MatOfPoint2f approxCurve = new MatOfPoint2f();

            double epsilon = 0.02 * Imgproc.arcLength(contour2f, true);
            Imgproc.approxPolyDP(contour2f, approxCurve, epsilon, true);

            org.opencv.core.Point[] approxPoints = approxCurve.toArray();

            contour2f.release();
            approxCurve.release();

            if (approxPoints.length < 4) {
                logger.warn("Approximate polygon has {} corners, need at least 4", approxPoints.length);
                gray.release();
                blurred.release();
                edges.release();
                hierarchy.release();
                return null;
            }

            // 转换为 com.edge.vision.core.template.model.Point
            Point[] corners = new Point[Math.min(4, approxPoints.length)];
            for (int i = 0; i < corners.length; i++) {
                corners[i] = new Point(approxPoints[i].x, approxPoints[i].y);
            }

            // 排序：TL, TR, BR, BL
            Point[] sorted = sortCornersClockwise(corners);

            gray.release();
            blurred.release();
            edges.release();
            hierarchy.release();

            logger.info("Detected corners via contour: TL=({},{}), TR=({},{}), BR=({},{}), BL=({},{})",
                (int)sorted[0].x, (int)sorted[0].y,
                (int)sorted[1].x, (int)sorted[1].y,
                (int)sorted[2].x, (int)sorted[2].y,
                (int)sorted[3].x, (int)sorted[3].y);

            return sorted;

        } catch (Exception e) {
            logger.error("Contour detection failed", e);
            return null;
        }
    }

    /**
     * 方法3：Harris 角点检测
     *
     * @param image 输入图像
     * @return 四个角 [TL, TR, BR, BL] 或 null
     */
    public Point[] detectByHarris(Mat image) {
        try {
            Mat gray = new Mat();
            if (image.channels() > 1) {
                Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGR2GRAY);
            } else {
                gray = image;
            }

            Mat corners = new Mat();
            Mat grayFloat = new Mat();
            gray.convertTo(grayFloat, CvType.CV_32F);

            Imgproc.cornerHarris(grayFloat, corners, 2, 3, 0.04);

            // 找最强的4个角点
            List<Point> cornerPoints = new ArrayList<>();
            // TODO: 实现局部最大值检测

            grayFloat.release();
            corners.release();
            gray.release();

            if (cornerPoints.size() < 4) {
                logger.warn("Harris detection found only {} corners", cornerPoints.size());
                return null;
            }

            Point[] cornersArray = cornerPoints.subList(0, 4).toArray(new Point[4]);
            return sortCornersClockwise(cornersArray);

        } catch (Exception e) {
            logger.error("Harris detection failed", e);
            return null;
        }
    }

    /**
     * 将四个角按顺时针排序：TL -> TR -> BR -> BL
     */
    private Point[] sortCornersClockwise(Point[] corners) {
        if (corners.length != 4) {
            throw new IllegalArgumentException("Need exactly 4 corners");
        }

        // 计算质心
        double cx = 0, cy = 0;
        for (Point p : corners) {
            cx += p.x;
            cy += p.y;
        }
        cx /= 4;
        cy /= 4;

        // 计算每个角相对于质心的角度
        double[] angles = new double[4];
        for (int i = 0; i < 4; i++) {
            angles[i] = Math.atan2(corners[i].y - cy, corners[i].x - cx);
        }

        // 按角度排序（从 -π 到 π）
        Integer[] indices = {0, 1, 2, 3};
        java.util.Arrays.sort(indices, (a, b) -> Double.compare(angles[a], angles[b]));

        Point[] sorted = new Point[4];
        for (int i = 0; i < 4; i++) {
            sorted[i] = corners[indices[i]];
        }

        // 排序后应该是：右上角开始顺时针
        // 我们需要调整为：TL -> TR -> BR -> BL
        // 找到 y 最小的点（上方），其中 x 最小的是 TL
        Point tl = sorted[0];
        int tlIndex = 0;
        for (int i = 1; i < 4; i++) {
            if (sorted[i].y < tl.y || (sorted[i].y == tl.y && sorted[i].x < tl.x)) {
                tl = sorted[i];
                tlIndex = i;
            }
        }

        // 重新排列，使 TL 在第一个
        Point[] result = new Point[4];
        for (int i = 0; i < 4; i++) {
            result[i] = sorted[(tlIndex + i) % 4];
        }

        return result;
    }
}
