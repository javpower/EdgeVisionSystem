//package com.edge.vision.core.stitcher;
//
//import org.opencv.core.Mat;
//import org.opencv.imgcodecs.Imgcodecs;
//
//import java.util.Arrays;
//import java.util.List;
//
//public class AutoStitchDemo {
//
//    static {
//        // 1. 加载 OpenCV 本地库
//        // Windows 下用 opencv_world4xx.dll；Linux 下用 libopencv_java4xx.so
//        // 也可以把 dll 放在工程根目录，或者使用 java.library.path
//        nu.pattern.OpenCV.loadShared();
//    }
//
//    public static void main(String[] args) {
//        // 2. 读取三张图片（顺序很重要，按从左到右排列）
//        String base = "/Volumes/macEx/Temp";
//        List<Mat> src = Arrays.asList(
//                Imgcodecs.imread(base + "/A/1.bmp"),
//                Imgcodecs.imread(base + "/B/1.bmp"),
//                Imgcodecs.imread(base + "/C/1.bmp")
//        );
//
//        // 简单校验
//        for (int i = 0; i < src.size(); i++) {
//            if (src.get(i).empty()) {
//                System.err.println("第 " + (i + 1) + " 张图片加载失败，请检查路径！");
//                return;
//            }
//        }
//
//        // 3. 执行拼接
//        StitchStrategy stitcher = new AutoStitchStrategy();
//        Mat result = stitcher.stitch(src);
//
//        // 4. 保存结果
//        String out = base + "stitched_result.jpg";
//        if (Imgcodecs.imwrite(out, result)) {
//            System.out.println("拼接完成，结果已保存到：" + out);
//        } else {
//            System.err.println("保存结果失败！");
//        }
//
//        // 5. 释放资源
//        result.release();
//        src.forEach(Mat::release);
//        if (stitcher instanceof AutoStitchStrategy) {
//            ((AutoStitchStrategy) stitcher).release();
//        }
//    }
//}