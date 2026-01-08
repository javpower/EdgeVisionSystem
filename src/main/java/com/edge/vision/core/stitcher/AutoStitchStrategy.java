package com.edge.vision.core.stitcher;

import org.opencv.calib3d.Calib3d;
import org.opencv.core.*;
import org.opencv.features2d.DescriptorMatcher;
import org.opencv.features2d.Feature2D;
import org.opencv.features2d.SIFT;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;

/**
 * 工业级自动拼接策略 (Industrial Auto Stitch)
 * 1:1 还原 Python 逻辑：使用仿射变换 (Affine) 替代透视变换，防止图像扭曲。
 * 适配 Java 业务接口 StitchStrategy。
 */
public class AutoStitchStrategy implements StitchStrategy {

    // --- 算法配置 (对应 Python __init__) ---
    private static final double OVERLAP_RATIO = 0.45;
    // 限制处理宽度，解决 Java 处理 4K 图卡死问题 (Python 底层优化好，Java 需要手动缩放)
    private static final int MAX_PROCESS_WIDTH = 1200;

    // --- 状态参数 ---
    private boolean isCalibrated = false;
    private final List<Mat> matrices = new ArrayList<>(); // 存 3x3 矩阵 (仿射变换也是 3x3 的一种特例)
    private Size canvasSize = new Size(0, 0);
    private Rect roiCrop = null;

    // --- OpenCV 组件 ---
    private Feature2D featureDetector;
    private DescriptorMatcher matcher;

    public AutoStitchStrategy() {
        // 懒加载将在使用时初始化，防止类加载过慢
    }

    @Override
    public Mat stitch(List<Mat> frames) {
        if (frames == null || frames.isEmpty()) {
            throw new IllegalArgumentException("Frames cannot be null or empty");
        }
        if (frames.size() == 1) return frames.get(0).clone();

        // 1. 智能预缩放 (解决 Java 卡顿的核心)
        List<Mat> workingFrames = new ArrayList<>();
        double scale = 1.0;
        int originalWidth = frames.get(0).cols();

        // 如果图太大，缩小处理，既快又不影响拼接精度
        if (originalWidth > MAX_PROCESS_WIDTH) {
            scale = (double) MAX_PROCESS_WIDTH / originalWidth;
            for (Mat frame : frames) {
                Mat resized = new Mat();
                Imgproc.resize(frame, resized, new Size(), scale, scale, Imgproc.INTER_AREA);
                workingFrames.add(resized);
            }
        } else {
            for (Mat frame : frames) workingFrames.add(frame.clone());
        }

        try {
            // 2. 转灰度 (工业标准)
            List<Mat> grayImages = toGrayScale(workingFrames);

            // 3. 自动校准 (第一次运行或重置后运行)
            if (!isCalibrated) {
                boolean ok = calibrate(grayImages);
                if (!ok) {
                    System.err.println("Calibration failed, returning simple stitch.");
                    return workingFrames.get(0).clone(); // 失败降级
                }
            }

            // 4. 极速变换 (Lanczos4 插值)
            List<Mat> warpedImages = new ArrayList<>();
            List<Mat> masks = new ArrayList<>();
            int w = (int) canvasSize.width;
            int h = (int) canvasSize.height;

            for (int i = 0; i < grayImages.size(); i++) {
                // 取出仿射矩阵 (2x3)
                Mat M = matrices.get(i).submat(0, 2, 0, 3);

                Mat warped = new Mat();
                // 重点：使用 warpAffine 而不是 warpPerspective
                Imgproc.warpAffine(grayImages.get(i), warped, M, new Size(w, h), Imgproc.INTER_LANCZOS4);
                warpedImages.add(warped);

                // 生成 Mask (修复之前的类型崩溃问题，保持 CV_8U)
                Mat mask = new Mat();
                Core.compare(warped, new Scalar(0), mask, Core.CMP_GT);
                masks.add(mask);

                M.release();
            }

            // 5. 线性加权融合 (无鬼影)
            Mat result = distanceBlend(warpedImages, masks);

            // 6. 还原颜色 (如果原图是彩色的)
            if (workingFrames.get(0).channels() == 3) {
                Mat resultBGR = new Mat();
                Imgproc.cvtColor(result, resultBGR, Imgproc.COLOR_GRAY2BGR);
                result.release();
                result = resultBGR;
            }

            // 7. 智能裁剪
            if (roiCrop != null && roiCrop.width > 0 && roiCrop.height > 0) {
                // 安全检查
                int safeX = Math.max(0, roiCrop.x);
                int safeY = Math.max(0, roiCrop.y);
                int safeW = Math.min(result.cols() - safeX, roiCrop.width);
                int safeH = Math.min(result.rows() - safeY, roiCrop.height);

                if (safeW > 0 && safeH > 0) {
                    Mat cropped = new Mat(result, new Rect(safeX, safeY, safeW, safeH));
                    // 必须 clone 返回，因为 result 即将释放
                    Mat finalOutput = cropped.clone();
                    cropped.release();
                    result.release();
                    return finalOutput;
                }
            }

            return result;

        } finally {
            // 清理临时资源
            for (Mat m : workingFrames) m.release();
        }
    }

    /**
     * 校准核心：计算仿射变换矩阵
     */
    private boolean calibrate(List<Mat> grayImages) {
        int n = grayImages.size();
        matrices.clear();

        // 计算相对矩阵 (2x3 -> 3x3)
        List<Mat> relMats = new ArrayList<>();
        for (int i = 0; i < n - 1; i++) {
            Mat affine2x3 = computeAffine(grayImages.get(i), grayImages.get(i + 1));
            if (affine2x3 == null) {
                for (Mat m : relMats) m.release();
                return false;
            }
            // 扩展为 3x3 用于矩阵乘法
            Mat m3x3 = Mat.eye(3, 3, CvType.CV_64F);
            Mat top = m3x3.submat(0, 2, 0, 3);
            affine2x3.copyTo(top);
            relMats.add(m3x3);

            affine2x3.release();
            top.release();
        }

        // 全局累积 (Global[i] = Global[i-1] * Rel[i-1])
        Mat currM = Mat.eye(3, 3, CvType.CV_64F);
        matrices.add(currM.clone()); // 第一个图是基准

        for (int i = 1; i < n; i++) {
            Mat nextM = new Mat();
            Core.gemm(currM, relMats.get(i - 1), 1, new Mat(), 0, nextM);
            matrices.add(nextM);

            currM.release();
            currM = nextM.clone();
        }
        currM.release();
        for (Mat m : relMats) m.release();

        // 计算画布与偏移
        calculateCanvasSize(grayImages);

        // 应用偏移修正
        applyTranslationOffset();

        // 预计算裁剪区域
        isCalibrated = true;
        // 递归自我调用生成预览图来计算裁剪框
        Mat preview = stitchInternalForPreview(grayImages);
        roiCrop = smartCrop(preview);
        preview.release();

        return true;
    }

    /**
     * Python: _get_affine (计算仿射矩阵，非透视)
     */
    private Mat computeAffine(Mat ref, Mat mov) {
        if (featureDetector == null) featureDetector = SIFT.create();
        if (matcher == null) matcher = DescriptorMatcher.create(DescriptorMatcher.FLANNBASED);

        // 提取重叠区 ROI 提升速度
        int w = ref.cols();
        int overlapW = (int) (w * OVERLAP_RATIO);
        Rect refRect = new Rect((int) (w * (1 - OVERLAP_RATIO)), 0, overlapW, ref.rows());
        Rect movRect = new Rect(0, 0, (int) (overlapW * 1.2), mov.rows());

        Mat roiRef = new Mat(ref, refRect);
        Mat roiMov = new Mat(mov, movRect);

        MatOfKeyPoint kpRef = new MatOfKeyPoint();
        MatOfKeyPoint kpMov = new MatOfKeyPoint();
        Mat desRef = new Mat();
        Mat desMov = new Mat();

        featureDetector.detectAndCompute(roiRef, new Mat(), kpRef, desRef);
        featureDetector.detectAndCompute(roiMov, new Mat(), kpMov, desMov);

        roiRef.release(); roiMov.release();

        if (desRef.empty() || desMov.empty() || kpRef.rows() < 3 || kpMov.rows() < 3) return null;

        List<MatOfDMatch> knnMatches = new ArrayList<>();
        matcher.knnMatch(desRef, desMov, knnMatches, 2);

        List<Point> srcPts = new ArrayList<>();
        List<Point> dstPts = new ArrayList<>();
        List<KeyPoint> kpRefList = kpRef.toList();
        List<KeyPoint> kpMovList = kpMov.toList();

        for (MatOfDMatch match : knnMatches) {
            List<DMatch> m = match.toList();
            if (m.size() >= 2 && m.get(0).distance < 0.75f * m.get(1).distance) {
                srcPts.add(kpMovList.get(m.get(0).trainIdx).pt);
                // 关键：加上 ROI 的偏移量
                Point p = kpRefList.get(m.get(0).queryIdx).pt;
                p.x += refRect.x;
                dstPts.add(p);
            }
        }

        kpRef.release(); kpMov.release(); desRef.release(); desMov.release();

        if (srcPts.size() < 8) return null;

        MatOfPoint2f srcMat = new MatOfPoint2f();
        srcMat.fromList(srcPts);
        MatOfPoint2f dstMat = new MatOfPoint2f();
        dstMat.fromList(dstPts);

        // 重点：使用 estimateAffinePartial2D (对应 Python 的 affine)
        Mat inliers = new Mat();
        Mat affine = Calib3d.estimateAffinePartial2D(srcMat, dstMat, inliers, Calib3d.RANSAC, 3.0);

        srcMat.release(); dstMat.release(); inliers.release();
        return affine.empty() ? null : affine;
    }

    private void calculateCanvasSize(List<Mat> images) {
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;

        for (int i = 0; i < images.size(); i++) {
            Mat img = images.get(i);
            // 4个角点
            Mat corners = new Mat(4, 1, CvType.CV_32FC2);
            corners.put(0, 0, 0, 0);
            corners.put(1, 0, 0, img.rows());
            corners.put(2, 0, img.cols(), img.rows());
            corners.put(3, 0, img.cols(), 0);

            // 变换 (perspectiveTransform 可以处理 3x3 的仿射矩阵)
            Mat transformed = new Mat();
            Core.perspectiveTransform(corners, transformed, matrices.get(i));

            for (int k = 0; k < 4; k++) {
                double[] pt = transformed.get(k, 0);
                minX = Math.min(minX, pt[0]);
                maxX = Math.max(maxX, pt[0]);
                minY = Math.min(minY, pt[1]);
                maxY = Math.max(maxY, pt[1]);
            }
            corners.release();
            transformed.release();
        }

        int offX = (int) -minX;
        int offY = (int) -minY;

        // 存为成员变量供 applyTranslationOffset 使用
        this.translationOffsetX = offX;
        this.translationOffsetY = offY;

        canvasSize = new Size(maxX + offX, maxY + offY);
    }

    private int translationOffsetX = 0;
    private int translationOffsetY = 0;

    private void applyTranslationOffset() {
        Mat T = Mat.eye(3, 3, CvType.CV_64F);
        T.put(0, 2, translationOffsetX);
        T.put(1, 2, translationOffsetY);

        for (int i = 0; i < matrices.size(); i++) {
            Mat oldM = matrices.get(i);
            Mat newM = new Mat();
            Core.gemm(T, oldM, 1, new Mat(), 0, newM);
            matrices.set(i, newM);
            oldM.release();
        }
        T.release();
    }

    /**
     * 距离加权融合 (完全对应 Python 逻辑)
     */
    private Mat distanceBlend(List<Mat> warpedImages, List<Mat> masks) {
        int w = (int) canvasSize.width;
        int h = (int) canvasSize.height;

        Mat accumulator = Mat.zeros(h, w, CvType.CV_32F);
        Mat weightAcc = Mat.zeros(h, w, CvType.CV_32F);
        Core.add(weightAcc, new Scalar(1e-6), weightAcc); // 防止除0

        for (int i = 0; i < warpedImages.size(); i++) {
            Mat mask8U = masks.get(i); // CV_8U

            // 距离变换
            Mat dist = new Mat();
            Imgproc.distanceTransform(mask8U, dist, Imgproc.CV_DIST_L2, 3);
            Core.normalize(dist, dist, 0, 1.0, Core.NORM_MINMAX);

            Mat distFloat = new Mat();
            dist.convertTo(distFloat, CvType.CV_32F);

            Mat imgFloat = new Mat();
            warpedImages.get(i).convertTo(imgFloat, CvType.CV_32F);

            // acc += img * dist
            Mat weighted = new Mat();
            Core.multiply(imgFloat, distFloat, weighted);
            Core.add(accumulator, weighted, accumulator);

            // weight += dist
            Core.add(weightAcc, distFloat, weightAcc);

            dist.release(); distFloat.release(); imgFloat.release(); weighted.release();
        }

        // result = acc / weight
        Core.divide(accumulator, weightAcc, accumulator);

        Mat result = new Mat();
        accumulator.convertTo(result, CvType.CV_8U);

        accumulator.release();
        weightAcc.release();

        return result;
    }

    private Rect smartCrop(Mat img) {
        Mat thresh = new Mat();
        Imgproc.threshold(img, thresh, 1, 255, Imgproc.THRESH_BINARY);

        Mat points = new Mat();
        Core.findNonZero(thresh, points);

        if (points.empty()) return new Rect(0,0,img.cols(), img.rows());

        Rect box = Imgproc.boundingRect(points);
        int x = box.x, y = box.y, w = box.width, h = box.height;

        Mat maskRoi = new Mat(thresh, box);
        int cx = 0, cy = 0, cw = w, ch = h;

        // 贪心收缩算法
        for(int i=0; i<500; i++) {
            if(cw <=0 || ch <=0) break;
            Mat roi = maskRoi.submat(cy, cy+ch, cx, cx+cw);
            if(Core.countNonZero(roi) == cw*ch) {
                roi.release(); break;
            }

            int nt = cw - Core.countNonZero(roi.row(0));
            int nb = cw - Core.countNonZero(roi.row(ch-1));
            int nl = ch - Core.countNonZero(roi.col(0));
            int nr = ch - Core.countNonZero(roi.col(cw-1));
            roi.release();

            int maxBlk = Math.max(Math.max(nt, nb), Math.max(nl, nr));
            if(maxBlk == 0) break;

            if(maxBlk == nt) { cy++; ch--; }
            else if(maxBlk == nb) { ch--; }
            else if(maxBlk == nl) { cx++; cw--; }
            else { cw--; }
        }

        maskRoi.release(); thresh.release(); points.release();
        return new Rect(x+cx, y+cy, cw, ch);
    }

    // 简化的内部拼接，仅用于计算裁剪框
    private Mat stitchInternalForPreview(List<Mat> grayImages) {
        int w = (int) canvasSize.width;
        int h = (int) canvasSize.height;
        List<Mat> warped = new ArrayList<>();
        List<Mat> masks = new ArrayList<>();

        for(int i=0; i<grayImages.size(); i++) {
            Mat m = new Mat();
            Mat affine = matrices.get(i).submat(0,2,0,3);
            Imgproc.warpAffine(grayImages.get(i), m, affine, new Size(w,h), Imgproc.INTER_NEAREST); // 预览用最近邻，快
            warped.add(m);
            Mat mask = new Mat();
            Core.compare(m, new Scalar(0), mask, Core.CMP_GT);
            masks.add(mask);
            affine.release();
        }
        Mat res = distanceBlend(warped, masks);
        for(Mat m : warped) m.release();
        for(Mat m : masks) m.release();
        return res;
    }

    private List<Mat> toGrayScale(List<Mat> frames) {
        List<Mat> grays = new ArrayList<>();
        for (Mat f : frames) {
            Mat g = new Mat();
            if (f.channels() == 3) Imgproc.cvtColor(f, g, Imgproc.COLOR_BGR2GRAY);
            else if (f.channels() == 4) Imgproc.cvtColor(f, g, Imgproc.COLOR_BGRA2GRAY);
            else f.copyTo(g);
            grays.add(g);
        }
        return grays;
    }

    public void resetCalibration() {
        isCalibrated = false;
        matrices.clear();
        roiCrop = null;
    }

    public void release() {
        resetCalibration();
        featureDetector = null;
        matcher = null;
    }
}