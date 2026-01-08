package com.edge.vision.core.stitcher;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Rect;

import java.util.List;

public class BlendStitchDecorator {
    private final StitchStrategy delegate;
    private static final int BLEND_WIDTH = 50; // 渐变像素宽度

    public BlendStitchDecorator(StitchStrategy delegate) {
        this.delegate = delegate;
    }

    /**
     * 对拼接后的图像进行边缘渐变融合
     * @param stitched 拼接后的图像
     * @param originals 原始图像列表
     * @return 融合后的图像
     */
    public Mat blend(Mat stitched, List<Mat> originals) {
        Mat result = stitched.clone();
        int x = 0;

        for (Mat src : originals) {
            int w = src.cols();
            if (x + w >= result.cols()) {
                break;
            }

            // 对每个拼接处进行渐变融合
            for (int j = 0; j < BLEND_WIDTH; j++) {
                double alpha = (double) j / BLEND_WIDTH;
                
                // 确保不越界
                int leftX = x + j;
                int rightX = x + w - BLEND_WIDTH + j;
                
                if (leftX >= 0 && leftX < result.cols() && 
                    rightX >= 0 && rightX < result.cols()) {
                    
                    Mat left = result.submat(new Rect(leftX, 0, 1, result.rows()));
                    Mat right = result.submat(new Rect(rightX, 0, 1, result.rows()));
                    Core.addWeighted(left, 1 - alpha, right, alpha, 0, left);
                }
            }
            
            x += w;
        }

        return result;
    }
}