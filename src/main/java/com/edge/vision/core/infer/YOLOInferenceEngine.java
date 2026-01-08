package com.edge.vision.core.infer;

import ai.onnxruntime.OrtException;
import com.edge.vision.model.Detection;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.*;
import java.util.stream.Collectors;

public class YOLOInferenceEngine extends InferEngineTemplate {

    private final float confThreshold;
    private final float nmsThreshold;

    public YOLOInferenceEngine(String modelPath, float conf, float nms, String device) throws OrtException {
        super(modelPath, device);
        this.confThreshold = conf;
        this.nmsThreshold = nms;
    }

    @Override
    protected PreProcessResult preprocess(Mat img) {
        // === 1. Letterbox 实现 (保持长宽比缩放) ===
        int width = img.cols();
        int height = img.rows();

        // 计算缩放比例
        float scale = Math.min((float)inputW / width, (float)inputH / height);

        // 计算新尺寸
        int newW = Math.round(width * scale);
        int newH = Math.round(height * scale);

        // 计算填充 (Padding)
        float dw = (inputW - newW) / 2.0f;
        float dh = (inputH - newH) / 2.0f;

        // Resize
        Mat resized = new Mat();
        Imgproc.resize(img, resized, new Size(newW, newH));

        // CopyMakeBorder (填充灰色/黑色)
        Mat padded = new Mat();
        int top = Math.round(dh - 0.1f);
        int bottom = Math.round(dh + 0.1f);
        int left = Math.round(dw - 0.1f);
        int right = Math.round(dw + 0.1f);
        org.opencv.core.Core.copyMakeBorder(resized, padded, top, bottom, left, right, org.opencv.core.Core.BORDER_CONSTANT, new org.opencv.core.Scalar(114, 114, 114));

        // === 2. 转换为 float[] 并在循环中归一化 (HWC -> CHW) ===
        // 参考代码是手动循环处理的，这里保持一致
        int rows = padded.rows();
        int cols = padded.cols();
        int channels = padded.channels();
        float[] pixels = new float[channels * rows * cols];

        // 必须转为 RGB
        Imgproc.cvtColor(padded, padded, Imgproc.COLOR_BGR2RGB);

        // 这是一个比较耗时的操作，但在 Java 中如果不使用 unsafe，这样写逻辑最清晰
        // 也可以使用 padded.get(0,0, byteArr) 然后再循环，会快一点
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                double[] pixel = padded.get(i, j);
                for (int k = 0; k < channels; k++) {
                    // CHW 顺序: Planar format
                    pixels[rows * cols * k + i * cols + j] = (float) pixel[k] / 255.0f;
                }
            }
        }

        PreProcessResult result = new PreProcessResult();
        result.pixelData = pixels;
        result.ratio = scale;
        result.dw = dw;
        result.dh = dh;

        // 释放 Mat 防止内存泄漏
        resized.release();
        padded.release();

        return result;
    }

    @Override
    protected List<Detection> postprocess(float[][] outputData, PreProcessResult preResult) {
        // YOLOv8 输出是 [Channels][Anchors] -> [84][8400]
        // 我们需要转置为 [Anchors][Channels] -> [8400][84] 方便遍历
        float[][] transposed = transposeMatrix(outputData);

        Map<Integer, List<float[]>> class2Bbox = new HashMap<>();

        for (float[] row : transposed) {
            // row: [x, y, w, h, class0_score, class1_score, ...]

            // 找到最大类别分数
            // 注意：YOLOv8 没有 objectness score，直接看 class scores
            // 复制出类别分数部分 (从索引4开始)
            float[] classScores = Arrays.copyOfRange(row, 4, row.length);

            int labelId = argmax(classScores);
            float maxScore = classScores[labelId];

            if (maxScore < confThreshold) continue;

            // 暂存 bbox [x, y, w, h, score]
            float[] bbox = new float[]{row[0], row[1], row[2], row[3], maxScore};

            // 转换中心点坐标到左上角坐标 (xywh -> xyxy)
            xywh2xyxy(bbox);

            // 基础校验
            if (bbox[0] >= bbox[2] || bbox[1] >= bbox[3]) continue;

            class2Bbox.putIfAbsent(labelId, new ArrayList<>());
            class2Bbox.get(labelId).add(bbox);
        }

        List<Detection> results = new ArrayList<>();

        // 对每个类别单独做 NMS
        for (Map.Entry<Integer, List<float[]>> entry : class2Bbox.entrySet()) {
            int classId = entry.getKey();
            List<float[]> bboxes = entry.getValue();

            // 执行 NMS
            bboxes = nonMaxSuppression(bboxes, nmsThreshold);

            // 还原坐标并创建 Detection 对象
            for (float[] bbox : bboxes) {
                // 还原坐标 (逆 Letterbox)
                // bbox 是 [x1, y1, x2, y2, score]
                float x1 = (bbox[0] - preResult.dw) / preResult.ratio;
                float y1 = (bbox[1] - preResult.dh) / preResult.ratio;
                float x2 = (bbox[2] - preResult.dw) / preResult.ratio;
                float y2 = (bbox[3] - preResult.dh) / preResult.ratio;

                results.add(new Detection(
                        getLabelName(classId),
                        classId,
                        new float[]{x1, y1, x2, y2},
                        bbox[4]
                ));
            }
        }

        return results;
    }

    // === 辅助工具方法 ===

    private float[][] transposeMatrix(float[][] m) {
        float[][] temp = new float[m[0].length][m.length];
        for (int i = 0; i < m.length; i++)
            for (int j = 0; j < m[0].length; j++)
                temp[j][i] = m[i][j];
        return temp;
    }

    private void xywh2xyxy(float[] bbox) {
        float x = bbox[0];
        float y = bbox[1];
        float w = bbox[2];
        float h = bbox[3];

        bbox[0] = x - w * 0.5f;
        bbox[1] = y - h * 0.5f;
        bbox[2] = x + w * 0.5f;
        bbox[3] = y + h * 0.5f;
    }

    private int argmax(float[] a) {
        float max = -Float.MAX_VALUE;
        int idx = -1;
        for (int i = 0; i < a.length; i++) {
            if (a[i] > max) {
                max = a[i];
                idx = i;
            }
        }
        return idx;
    }

    private List<float[]> nonMaxSuppression(List<float[]> bboxes, float iouThres) {
        List<float[]> bestBboxes = new ArrayList<>();
        // 按置信度排序
        bboxes.sort(Comparator.comparing((float[] a) -> a[4])); // 升序，后面remove最后一个

        while (!bboxes.isEmpty()) {
            float[] best = bboxes.remove(bboxes.size() - 1);
            bestBboxes.add(best);

            // 移除 IoU 过高的框
            bboxes = bboxes.stream()
                    .filter(a -> computeIOU(a, best) < iouThres)
                    .collect(Collectors.toList());
        }
        return bestBboxes;
    }

    private float computeIOU(float[] box1, float[] box2) {
        float area1 = (box1[2] - box1[0]) * (box1[3] - box1[1]);
        float area2 = (box2[2] - box2[0]) * (box2[3] - box2[1]);

        float left = Math.max(box1[0], box2[0]);
        float top = Math.max(box1[1], box2[1]);
        float right = Math.min(box1[2], box2[2]);
        float bottom = Math.min(box1[3], box2[3]);

        float interArea = Math.max(right - left, 0) * Math.max(bottom - top, 0);
        float unionArea = area1 + area2 - interArea;

        return Math.max(interArea / unionArea, 1e-8f);
    }
}