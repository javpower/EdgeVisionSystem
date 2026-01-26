package com.edge.vision.core.infer;

import ai.onnxruntime.OrtException;
import com.edge.vision.model.Detection;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;

public class YOLOInferenceEngine extends InferEngineTemplate {

    private final float confThreshold;
    private final float nmsThreshold;

    public YOLOInferenceEngine(String modelPath, float conf, float nms, String device) throws OrtException {
        super(modelPath, device);
        this.confThreshold = conf;
        this.nmsThreshold = nms;
    }

    public YOLOInferenceEngine(String modelPath, float conf, float nms, String device, int inputH, int inputW) throws OrtException {
        super(modelPath, device);
        this.confThreshold = conf;
        this.nmsThreshold = nms;
        super.inputH = inputH;
        super.inputW = inputW;
    }

    @Override
    protected PreProcessResult preprocess(Mat img) {
        int width = img.cols();
        int height = img.rows();

        // 1. 计算 Letterbox 参数
        float scale = Math.min((float)inputW / width, (float)inputH / height);
        int newW = Math.round(width * scale);
        int newH = Math.round(height * scale);
        float dw = (inputW - newW) / 2.0f;
        float dh = (inputH - newH) / 2.0f;

        // 2. Resize
        Mat resized = new Mat();
        Imgproc.resize(img, resized, new Size(newW, newH));

        // 3. Padding
        Mat padded = new Mat();
        int top = Math.round(dh - 0.1f);
        int bottom = Math.round(dh + 0.1f);
        int left = Math.round(dw - 0.1f);
        int right = Math.round(dw + 0.1f);
        org.opencv.core.Core.copyMakeBorder(resized, padded, top, bottom, left, right,
                org.opencv.core.Core.BORDER_CONSTANT, new org.opencv.core.Scalar(114, 114, 114));

        // 4. BGR -> RGB
        Imgproc.cvtColor(padded, padded, Imgproc.COLOR_BGR2RGB);

        // === 极致优化区域：消除 JNI 循环 ===
        int rows = padded.rows();
        int cols = padded.cols();
        int channels = padded.channels();
        int area = rows * cols;

        // Step A: 一次性读取所有字节 (1次 JNI 调用)
        byte[] srcData = new byte[rows * cols * channels];
        padded.get(0, 0, srcData);

        // Step B: 准备目标数组
        float[] pixels = new float[channels * area];

        // Step C: 纯 Java 循环进行 HWC -> CHW 和 归一化
        // 这种连续内存访问比 Mat.get(i,j) 快 10-20 倍
        for (int i = 0; i < area; i++) {
            // R 通道 (Planar 0)
            pixels[i] = (srcData[i * 3] & 0xFF) / 255.0f;
            // G 通道 (Planar 1)
            pixels[i + area] = (srcData[i * 3 + 1] & 0xFF) / 255.0f;
            // B 通道 (Planar 2)
            pixels[i + 2 * area] = (srcData[i * 3 + 2] & 0xFF) / 255.0f;
        }

        // 释放 Mat
        resized.release();
        padded.release();

        PreProcessResult result = new PreProcessResult();
        result.pixelData = pixels;
        result.ratio = scale;
        result.dw = dw;
        result.dh = dh;
        return result;
    }

    @Override
    protected List<Detection> postprocess(float[][] outputData, PreProcessResult preResult) {
        // outputData 结构: [Channels][Anchors] -> e.g. [84][8400]
        // Row 0-3: x, y, w, h
        // Row 4-83: class scores

        int numClasses = outputData.length - 4; // 80
        int numAnchors = outputData[0].length;  // 8400

        // 使用 float数组存储候选框，避免创建 Detection 对象，节省内存
        // 结构: [x1, y1, x2, y2, score, classId]
        List<float[]> candidates = new ArrayList<>();

        // === 极致优化区域：移除转置，按列遍历 ===
        for (int i = 0; i < numAnchors; i++) {
            // 1. 寻找最大 Class Score
            // 只有当 maxScore > confThreshold 时，才去读取坐标
            float maxScore = -Float.MAX_VALUE;
            int maxClassId = -1;

            // 遍历类别分数行 (从第4行开始)
            for (int c = 0; c < numClasses; c++) {
                float score = outputData[4 + c][i];
                if (score > maxScore) {
                    maxScore = score;
                    maxClassId = c;
                }
            }

            // Fail-Fast: 阈值过滤
            if (maxScore < confThreshold) continue;

            // 2. 读取并转换坐标 (xywh -> xyxy)
            float x = outputData[0][i];
            float y = outputData[1][i];
            float w = outputData[2][i];
            float h = outputData[3][i];

            float x1 = x - w * 0.5f;
            float y1 = y - h * 0.5f;
            float x2 = x + w * 0.5f;
            float y2 = y + h * 0.5f;

            candidates.add(new float[]{x1, y1, x2, y2, maxScore, (float)maxClassId});
        }

        // 3. 执行优化的 NMS
        return nms(candidates, preResult);
    }

    /**
     * 优化的 NMS (移除 Stream，使用原生循环)
     */
    private List<Detection> nms(List<float[]> bboxes, PreProcessResult pre) {
        List<Detection> results = new ArrayList<>();
        if (bboxes.isEmpty()) return results;

        // 1. 按分数降序排序 (TimSort/DualPivotQuicksort)
        bboxes.sort((a, b) -> Float.compare(b[4], a[4]));

        int size = bboxes.size();
        boolean[] suppressed = new boolean[size]; // 标记是否被抑制

        for (int i = 0; i < size; i++) {
            if (suppressed[i]) continue;

            float[] best = bboxes.get(i);

            // 2. 将符合条件的框还原到原图坐标并输出
            // 坐标还原: (x - dw) / ratio
            float x1 = (best[0] - pre.dw) / pre.ratio;
            float y1 = (best[1] - pre.dh) / pre.ratio;
            float x2 = (best[2] - pre.dw) / pre.ratio;
            float y2 = (best[3] - pre.dh) / pre.ratio;

            results.add(new Detection(
                    getLabelName((int) best[5]),
                    (int) best[5],
                    new float[]{x1, y1, x2, y2},
                    (x1 + x2) / 2,
                    (y1 + y2) / 2,
                    best[4]
            ));

            // 3. 抑制 IoU 过高的框
            for (int j = i + 1; j < size; j++) {
                if (suppressed[j]) continue;

                float[] curr = bboxes.get(j);

                // 优化：仅对比同类别的框 (Class-Agnostic=False)
                // 如果需要 Class-Agnostic NMS (不分种类抑制)，注释掉下面这行
                if ((int)best[5] != (int)curr[5]) continue;

                if (computeIoU(best, curr) > nmsThreshold) {
                    suppressed[j] = true;
                }
            }
        }
        return results;
    }

    /**
     * 静态 IoU 计算，无对象创建
     */
    private static float computeIoU(float[] boxA, float[] boxB) {
        float xA = Math.max(boxA[0], boxB[0]);
        float yA = Math.max(boxA[1], boxB[1]);
        float xB = Math.min(boxA[2], boxB[2]);
        float yB = Math.min(boxA[3], boxB[3]);

        float interW = Math.max(0, xB - xA);
        float interH = Math.max(0, yB - yA);
        float interArea = interW * interH;

        if (interArea <= 0) return 0f;

        float boxAArea = (boxA[2] - boxA[0]) * (boxA[3] - boxA[1]);
        float boxBArea = (boxB[2] - boxB[0]) * (boxB[3] - boxB[1]);

        return interArea / (boxAArea + boxBArea - interArea);
    }
}