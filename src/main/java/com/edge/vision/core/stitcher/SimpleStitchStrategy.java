package com.edge.vision.core.stitcher;

import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 简单水平拼接策略
 * 适用于相机位置固定、图像已大致对齐的场景
 */
public class SimpleStitchStrategy implements StitchStrategy {
    private final boolean blend;
    private static final int BLEND_WIDTH = 100; // 融合区域宽度

    public SimpleStitchStrategy(boolean blend) {
        this.blend = blend;
    }

    @Override
    public Mat stitch(List<Mat> frames) {
        if (frames == null || frames.isEmpty()) {
            throw new IllegalArgumentException("Frames cannot be null or empty");
        }

        if (frames.size() == 1) {
            return frames.get(0).clone();
        }

        // 找到最小的高度
        int minH = frames.stream().mapToInt(Mat::rows).min().orElse(0);
        if (minH == 0) {
            throw new IllegalArgumentException("Invalid frame dimensions");
        }

        // 调整所有图像到相同高度
        List<Mat> resized = frames.stream()
                .map(f -> f.rows() == minH ? f.clone() : resize(f, minH))
                .collect(Collectors.toList());

        // 水平拼接
        Mat out;
        if (blend && resized.size() >= 2) {
            out = blendStitch(resized);
        } else {
            out = simpleStitch(resized);
        }

        // 清理
        for (Mat m : resized) {
            m.release();
        }

        return out;
    }

    /**
     * 简单水平拼接（无融合）
     */
    private Mat simpleStitch(List<Mat> frames) {
        // 计算总宽度
        int totalWidth = frames.stream().mapToInt(Mat::cols).sum();
        int height = frames.get(0).rows();
        int type = frames.get(0).type();

        Mat result = new Mat(height, totalWidth, type, new Scalar(0));

        int xOffset = 0;
        for (Mat frame : frames) {
            Mat roi = new Mat(result, new Rect(xOffset, 0, frame.cols(), frame.rows()));
            frame.copyTo(roi);
            roi.release();
            xOffset += frame.cols();
        }

        return result;
    }

    /**
     * 带渐变融合的水平拼接
     */
    private Mat blendStitch(List<Mat> frames) {
        // 计算总宽度（考虑重叠）
        int totalWidth = 0;
        int overlapWidth = Math.min(BLEND_WIDTH, frames.get(0).cols() / 3);

        for (int i = 0; i < frames.size(); i++) {
            if (i == 0) {
                totalWidth += frames.get(i).cols();
            } else {
                totalWidth += frames.get(i).cols() - overlapWidth;
            }
        }

        int height = frames.get(0).rows();
        int type = frames.get(0).type();
        Mat result = new Mat(height, totalWidth, type, new Scalar(0));

        // 放置第一张图片
        Mat roi1 = new Mat(result, new Rect(0, 0, frames.get(0).cols(), height));
        frames.get(0).copyTo(roi1);
        roi1.release();

        int currentX = frames.get(0).cols();

        // 拼接剩余图片（带融合）
        for (int i = 1; i < frames.size(); i++) {
            Mat currentFrame = frames.get(i);
            int frameWidth = currentFrame.cols();

            // 融合区域在结果中的位置
            int blendStartX = currentX - overlapWidth;
            int blendEndX = currentX;

            // 获取融合区域
            Mat resultBlend = new Mat(result, new Rect(blendStartX, 0, overlapWidth, height));
            Mat frameBlend = new Mat(currentFrame, new Rect(0, 0, overlapWidth, height));

            // 创建渐变权重
            Mat alpha1 = new Mat();
            Mat alpha2 = new Mat();
            createBlendWeights(overlapWidth, height, alpha1, alpha2);

            // 融合
            Mat blended = new Mat();
            addWeighted(resultBlend, alpha1, frameBlend, alpha2, blended);

            // 将融合结果写回
            blended.copyTo(resultBlend);

            // 放置非融合部分
            if (frameWidth > overlapWidth) {
                Mat roiNonBlend = new Mat(result, new Rect(currentX, 0, frameWidth - overlapWidth, height));
                Mat frameNonBlend = new Mat(currentFrame, new Rect(overlapWidth, 0, frameWidth - overlapWidth, height));
                frameNonBlend.copyTo(roiNonBlend);
                roiNonBlend.release();
                frameNonBlend.release();
            }

            // 清理
            resultBlend.release();
            frameBlend.release();
            alpha1.release();
            alpha2.release();
            blended.release();

            currentX += frameWidth - overlapWidth;
        }

        return result;
    }

    /**
     * 创建渐变权重图
     */
    private void createBlendWeights(int width, int height, Mat alpha1, Mat alpha2) {
        alpha1.create(height, width, CvType.CV_32F);
        alpha2.create(height, width, CvType.CV_32F);

        // 创建渐变：从左到右，alpha1 从 1 到 0，alpha2 从 0 到 1
        for (int x = 0; x < width; x++) {
            float a1 = 1.0f - (float) x / width;
            float a2 = (float) x / width;
            alpha1.col(x).setTo(new Scalar(a1));
            alpha2.col(x).setTo(new Scalar(a2));
        }
    }

    /**
     * 带权重的图像融合
     */
    private void addWeighted(Mat src1, Mat alpha1, Mat src2, Mat alpha2, Mat dst) {
        // 转换为 float
        Mat src1f = new Mat();
        Mat src2f = new Mat();
        src1.convertTo(src1f, CvType.CV_32F);
        src2.convertTo(src2f, CvType.CV_32F);

        // 分离通道
        List<Mat> channels1 = new ArrayList<>();
        List<Mat> channels2 = new ArrayList<>();
        Core.split(src1f, channels1);
        Core.split(src2f, channels2);

        List<Mat> resultChannels = new ArrayList<>();

        // 对每个通道进行加权融合
        for (int c = 0; c < channels1.size(); c++) {
            Mat weighted = new Mat();
            Core.multiply(channels1.get(c), alpha1, weighted);
            Core.multiply(channels2.get(c), alpha2, channels2.get(c));
            Core.add(weighted, channels2.get(c), weighted);
            resultChannels.add(weighted);
        }

        // 合并通道
        Core.merge(resultChannels, dst);

        // 转换回原始类型
        dst.convertTo(dst, src1.type());

        // 清理
        src1f.release();
        src2f.release();
        for (Mat m : channels1) m.release();
        for (Mat m : channels2) m.release();
        for (Mat m : resultChannels) m.release();
    }

    /**
     * 调整图像高度
     */
    private Mat resize(Mat src, int targetH) {
        double scale = targetH / (double) src.rows();
        Mat dst = new Mat();
        Imgproc.resize(src, dst, new Size(), scale, scale, Imgproc.INTER_AREA);
        return dst;
    }
}
