package com.edge.vision.core.stitcher;

import org.opencv.core.Mat;

import java.util.List;

public interface StitchStrategy {
    /**
     * 拼接多个图像帧
     * @param frames 图像帧列表
     * @return 拼接后的图像
     */
    Mat stitch(List<Mat> frames);
}