package com.edge.vision.core.stitcher;

import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 手动拼接策略 (V6 通用版 - 每个摄像头支持左右切割)
 * <p>
 * 核心特性：
 * 1. 基于标定坐标直接对图像矩阵进行切片操作
 * 2. 支持任意数量的摄像头拼接
 * 3. 每个摄像头都有独立的左右切割参数
 * 4. 完全通用，适用于各种摄像头布局
 * <p>
 * 参数说明：
 * - x1: 左切割线位置（保留从 x1 到右侧的区域）
 * - x2: 右切割线位置（保留从左侧到 x2 的区域）
 * - y: 截取起始 Y 坐标
 * - h: 截取高度
 * <p>
 * 拼接原理：
 * 每个摄像头保留 [y, y+h] 行，[x1, x2) 列
 * 所有切片后的图像水平拼接
 */
public class ManualStitchStrategy implements StitchStrategy {
    private static final Logger logger = LoggerFactory.getLogger(ManualStitchStrategy.class);

    // 线程安全的配置存储
    private final Map<Integer, CameraConfig> cameraConfigs = new ConcurrentHashMap<>();

    public ManualStitchStrategy() {
        // 默认构造
    }

    public ManualStitchStrategy(List<CameraConfig> configs) {
        if (configs != null) {
            for (CameraConfig config : configs) {
                cameraConfigs.put(config.index, config);
            }
        }
    }

    @Override
    public Mat stitch(List<Mat> frames) {
        if (frames == null || frames.isEmpty()) {
            throw new IllegalArgumentException("Frames cannot be null or empty");
        }

        // 即使只有一张图，也返回原图
        if (frames.size() == 1) {
            return cropFrame(frames.get(0), 0);
        }

        try {
            // 阶段一：计算统一的目标高度
            // 需要同时考虑：1. 配置的 h 值，2. 实际图像可用高度 (frame.rows() - startY)
            // 取所有摄像头中 实际可用高度 的最小值
            int targetHeight = Integer.MAX_VALUE;
            for (int i = 0; i < frames.size(); i++) {
                Mat frame = frames.get(i);
                CameraConfig config = cameraConfigs.getOrDefault(i, new CameraConfig(i));
                int startY = config.y;
                int configH = config.h;

                // 实际可用高度（不超过图像边界）
                int availableHeight = frame.rows() - startY;
                // 两者取最小值
                int effectiveHeight = Math.min(configH, availableHeight);

                if (effectiveHeight > 0 && effectiveHeight < targetHeight) {
                    targetHeight = effectiveHeight;
                }

                logger.debug("Camera {}: config.h={}, availableHeight={}, effectiveHeight={}",
                    i, configH, availableHeight, effectiveHeight);
            }

            // 如果没有有效的 h 值，使用第一帧的高度
            if (targetHeight == Integer.MAX_VALUE) {
                targetHeight = frames.get(0).rows();
            }

            logger.info("Target height for stitching: {}", targetHeight);

            // 阶段二：切片处理（所有图像使用相同的目标高度）
            List<Mat> slicedParts = new ArrayList<>();

            for (int i = 0; i < frames.size(); i++) {
                Mat frame = frames.get(i);
                Mat cropped = cropFrameWithHeight(frame, i, targetHeight);
                if (!cropped.empty()) {
                    // 验证高度是否一致
                    if (cropped.rows() != targetHeight) {
                        logger.warn("Camera {} cropped height {} != targetHeight {}, this should not happen!",
                            i, cropped.rows(), targetHeight);
                    }
                    slicedParts.add(cropped);
                }
            }

            if (slicedParts.isEmpty()) {
                logger.warn("No valid frames after cropping");
                return new Mat();
            }

            // 阶段三：验证所有切片高度一致后再拼接
            int firstHeight = slicedParts.get(0).rows();
            for (int i = 1; i < slicedParts.size(); i++) {
                if (slicedParts.get(i).rows() != firstHeight) {
                    logger.error("Height mismatch detected! slice[0].rows()={}, slice[{}].rows()={}",
                        firstHeight, i, slicedParts.get(i).rows());
                    // 清理资源
                    for (Mat mat : slicedParts) {
                        mat.release();
                    }
                    return new Mat();
                }
            }

            // 阶段四：直接水平拼接
            Mat result = new Mat();
            Core.hconcat(slicedParts, result);

            // 清理临时 Mat
            for (Mat mat : slicedParts) {
                mat.release();
            }

            logger.debug("Stitched {} frames into result: {}x{}",
                frames.size(), result.cols(), result.rows());

            return result;

        } catch (Exception e) {
            logger.error("Stitch process failed", e);
            return new Mat();
        }
    }

    /**
     * 对单帧进行切片处理（使用指定的目标高度）
     * <p>
     * 每个摄像头保留 [y, y+h] 行，[x1, x2) 列
     *
     * @param frame         原始图像帧
     * @param cameraIndex   当前摄像头索引
     * @param targetHeight  目标高度（确保所有切片高度一致）
     * @return 切片后的图像
     */
    private Mat cropFrameWithHeight(Mat frame, int cameraIndex, int targetHeight) {
        CameraConfig config = cameraConfigs.getOrDefault(cameraIndex, new CameraConfig(cameraIndex));

        int x1 = config.x1;
        int x2 = config.x2;
        int startY = config.y;

        // 边界检查
        if (x1 < 0 || x1 >= frame.cols()) {
            logger.warn("Invalid x1 {} for camera {}, frame width: {}",
                x1, cameraIndex, frame.cols());
            return new Mat();
        }

        if (x2 <= x1 || x2 > frame.cols()) {
            logger.warn("Invalid x2 {} for camera {}, frame width: {}, x1={}",
                x2, cameraIndex, frame.cols(), x1);
            return new Mat();
        }

        // 使用目标高度，但确保不超过图像边界
        int height = Math.min(targetHeight, frame.rows() - startY);
        if (height <= 0) {
            logger.warn("Invalid height for camera {}, startY={}, targetHeight={}, frame rows={}",
                cameraIndex, startY, targetHeight, frame.rows());
            return new Mat();
        }

        try {
            // 每个摄像头都保留 [x1, x2) 列
            int roiWidth = x2 - x1;
            Rect roi = new Rect(x1, startY, roiWidth, height);

            logger.debug("Camera {}: crop region - x={}, y={}, w={}, h={}",
                cameraIndex, roi.x, roi.y, roi.width, roi.height);

            return new Mat(frame, roi).clone();

        } catch (Exception e) {
            logger.error("Failed to crop frame for camera {}", cameraIndex, e);
            return new Mat();
        }
    }

    /**
     * 对单帧进行切片处理
     */
    private Mat cropFrame(Mat frame, int cameraIndex) {
        CameraConfig config = cameraConfigs.getOrDefault(cameraIndex, new CameraConfig(cameraIndex));

        int x1 = config.x1;
        int x2 = config.x2;
        int startY = config.y;
        int height = config.h;

        // 边界检查
        if (x1 < 0 || x1 >= frame.cols()) {
            logger.warn("Invalid x1 {} for camera {}, frame width: {}",
                x1, cameraIndex, frame.cols());
            return new Mat();
        }

        if (x2 <= x1 || x2 > frame.cols()) {
            logger.warn("Invalid x2 {} for camera {}, frame width: {}, x1={}",
                x2, cameraIndex, frame.cols(), x1);
            return new Mat();
        }

        if (startY < 0 || startY >= frame.rows()) {
            logger.warn("Invalid startY {} for camera {}, frame height: {}",
                startY, cameraIndex, frame.rows());
            return new Mat();
        }

        if (height <= 0 || startY + height > frame.rows()) {
            // 自动调整高度
            height = frame.rows() - startY;
            if (height <= 0) {
                logger.warn("Invalid height for camera {}, auto-adjust failed", cameraIndex);
                return new Mat();
            }
        }

        try {
            // 每个摄像头都保留 [x1, x2) 列
            int roiWidth = x2 - x1;
            Rect roi = new Rect(x1, startY, roiWidth, height);

            logger.debug("Camera {}: crop region - x={}, y={}, w={}, h={}",
                cameraIndex, roi.x, roi.y, roi.width, roi.height);

            return new Mat(frame, roi).clone();

        } catch (Exception e) {
            logger.error("Failed to crop frame for camera {}", cameraIndex, e);
            return new Mat();
        }
    }

    // =========================================================
    // 配置管理方法 (Public Methods)
    // =========================================================

    /**
     * 获取指定索引摄像头的配置
     */
    public CameraConfig getCameraConfig(int index) {
        return cameraConfigs.getOrDefault(index, new CameraConfig(index));
    }

    /**
     * 更新单个摄像头配置
     */
    public void updateCameraConfig(CameraConfig config) {
        if (config != null) {
            cameraConfigs.put(config.index, config);
            logger.info("Updated config for camera {}: x1={}, x2={}, y={}, h={}",
                config.index, config.x1, config.x2, config.y, config.h);
        }
    }

    /**
     * 获取所有摄像头配置
     */
    public Map<Integer, CameraConfig> getAllCameraConfigs() {
        return new ConcurrentHashMap<>(cameraConfigs);
    }

    /**
     * 批量设置摄像头配置
     */
    public void setAllCameraConfigs(List<CameraConfig> configs) {
        cameraConfigs.clear();
        if (configs != null) {
            for (CameraConfig config : configs) {
                cameraConfigs.put(config.index, config);
            }
        }
        logger.info("Set configs for {} cameras", configs != null ? configs.size() : 0);
    }

    /**
     * 重置为默认配置
     */
    public void resetToDefault(int cameraCount) {
        cameraConfigs.clear();
        for (int i = 0; i < cameraCount; i++) {
            cameraConfigs.put(i, new CameraConfig(i, cameraCount));
        }
        logger.info("Reset to default config for {} cameras", cameraCount);
    }

    // =========================================================
    // 配置实体类
    // =========================================================

    /**
     * 摄像头拼接配置（通用版）
     * <p>
     * 参数说明：
     * - index: 摄像头索引
     * - x1: 左切割线位置（保留从 x1 到右侧的区域）
     * - x2: 右切割线位置（保留从左侧到 x2 的区域）
     * - y: 截取起始 Y 坐标
     * - h: 截取高度
     * <p>
     * 默认值策略（假设图像宽度5472，高度3648）：
     * - 第一个摄像头 (index=0)：x1=0, x2=4000
     * - 中间摄像头 (0 < index < N-1)：x1=1200, x2=4200
     * - 最后一个摄像头 (index=N-1)：x1=1600, x2=5472
     */
    public static class CameraConfig {
        public int index;
        public int x1 = 0;           // 左切割线位置
        public int x2 = 5472;        // 右切割线位置
        public int y = 0;            // 截取起始 Y 坐标
        public int h = 3648;         // 截取高度

        public CameraConfig() {
            this.index = 0;
        }

        public CameraConfig(int index) {
            this.index = index;
            this.x1 = 0;
            this.x2 = 5472;
            this.y = 0;
            this.h = 3648;
        }

        /**
         * 根据摄像头索引和总数创建默认配置
         * @param index 摄像头索引
         * @param totalCameras 摄像头总数
         */
        public CameraConfig(int index, int totalCameras) {
            this.index = index;
            this.y = 0;
            this.h = 3648;

            if (totalCameras == 1) {
                // 只有一个摄像头，保留全部
                this.x1 = 0;
                this.x2 = 5472;
            } else if (index == 0) {
                // 第一个摄像头：保留左侧大部分
                this.x1 = 0;
                this.x2 = 4000;
            } else if (index == totalCameras - 1) {
                // 最后一个摄像头：保留右侧大部分
                this.x1 = 1600;
                this.x2 = 5472;
            } else {
                // 中间摄像头：两边都切，保留中间部分
                this.x1 = 1200;
                this.x2 = 4200;
            }
        }
    }
}
