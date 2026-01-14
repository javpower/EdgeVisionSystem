package com.edge.vision.core.infer;

import ai.onnxruntime.*;
import com.edge.vision.model.Detection;
import org.opencv.core.Mat;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public abstract class InferEngineTemplate {
    protected OrtEnvironment env;
    protected OrtSession session;
    protected String inputName;
    protected String[] labels; // 动态加载的标签
    protected int inputH = 640; // 默认值，也会尝试从模型读取
    protected int inputW = 640;

    public InferEngineTemplate(String modelPath, String device) throws OrtException {
        // 1. 初始化环境
        this.env = OrtEnvironment.getEnvironment();

        // 2. 配置设备 (GPU/CPU)
        OrtSession.SessionOptions opts = createSessionOptions(device);

        // 3. 创建 Session
        this.session = env.createSession(modelPath, opts);

        // 4. 获取输入节点名称和尺寸
        this.inputName = session.getInputNames().iterator().next();
        Map<String, NodeInfo> inputInfo = session.getInputInfo();
        long[] shape = ((TensorInfo) inputInfo.get(inputName).getInfo()).getShape();
        // 尝试读取模型输入的固定尺寸 (如果是动态尺寸-1，则保持默认640)
        if (shape[2] > 0) this.inputH = (int) shape[2];
        if (shape[3] > 0) this.inputW = (int) shape[3];

        // 5. 动态加载 Metadata 中的类别名称 (参考你的 regex 逻辑)
        loadMetadata();
    }

    /**
     * 创建 SessionOptions，根据设备配置 GPU/CPU
     */
    private OrtSession.SessionOptions createSessionOptions(String device) throws OrtException {
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();

        // GPU 模式：支持 GPU（JAR 和 Native Image 都支持，只要有 CUDA）
        if ("GPU".equalsIgnoreCase(device)) {
            try {
                opts.addCUDA(0);
                System.out.println("GPU (CUDA) provider enabled successfully");
            } catch (Exception e) {
                System.err.println("Warning: CUDA initialization failed: " + e.getMessage());
                System.err.println("Falling back to CPU execution");
                System.err.println("To use GPU, ensure:");
                System.err.println("  1. NVIDIA GPU is installed");
                System.err.println("  2. NVIDIA driver is up to date");
                System.err.println("  3. CUDA runtime is in PATH (C:\\Program Files\\NVIDIA GPU Computing Toolkit\\CUDA\\vxx.x\\bin)");
                opts = new OrtSession.SessionOptions();
            }
        } else {
            System.out.println("Using CPU execution");
        }

        return opts;
    }

    /**
     * 从 ONNX Metadata 读取 names 字段
     */
    private void loadMetadata() throws OrtException {
        try {
            OnnxModelMetadata metadata = session.getMetadata();
            String metaStr = metadata.getCustomMetadata().get("names");

            if (metaStr != null && !metaStr.isEmpty()) {
                // 去掉首尾的大括号 {0: 'person', ...}
                if (metaStr.startsWith("{")) metaStr = metaStr.substring(1, metaStr.length() - 1);

                // 使用正则提取单引号内的内容
                List<String> labelList = new ArrayList<>();
                Pattern pattern = Pattern.compile("'([^']*)'");
                Matcher matcher = pattern.matcher(metaStr);
                while (matcher.find()) {
                    labelList.add(matcher.group(1));
                }
                this.labels = labelList.toArray(new String[0]);
                System.out.println("模型类别加载成功: " + Arrays.toString(this.labels));
            } else {
                System.err.println("警告: 未在模型 Metadata 中找到 'names'，将显示 Class ID");
            }
        } catch (Exception e) {
            System.err.println("加载 Metadata 失败: " + e.getMessage());
            this.labels = null;
        }
    }

    /**
     * 推理主入口
     */
    public List<Detection> predict(Mat img) throws OrtException {
        // 1. 预处理 (Letterbox + Normalize)
        PreProcessResult preResult = preprocess(img);

        // 2. 创建 Tensor
        long[] shape = { 1L, 3L, (long)inputH, (long)inputW };
        OnnxTensor tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(preResult.pixelData), shape);

        // 3. 运行推理
        OrtSession.Result result = session.run(Collections.singletonMap(inputName, tensor));

        // 4. 获取原始输出 (float[][][])
        // YOLOv8 输出通常是 [1, 84, 8400] (Batch, Channels, Anchors)
        float[][] outputData = ((float[][][])result.get(0).getValue())[0];

        // 5. 后处理 (转置 + NMS + 坐标还原)
        List<Detection> detections = postprocess(outputData, preResult);

        // 清理
        tensor.close();
        result.close();

        return detections;
    }

    // 内部类：用于在该类和子类之间传递预处理参数
    protected static class PreProcessResult {
        public float[] pixelData; // 归一化后的像素
        public float ratio;       // 缩放比例
        public float dw;          // x轴填充偏移
        public float dh;          // y轴填充偏移
    }

    protected abstract PreProcessResult preprocess(Mat img);

    protected abstract List<Detection> postprocess(float[][] output, PreProcessResult preResult);

    public void close() {
        try {
            if (session != null) session.close();
            if (env != null) env.close();
        } catch (OrtException e) {
            e.printStackTrace();
        }
    }

    protected String getLabelName(int id) {
        if (labels != null && id >= 0 && id < labels.length) {
            return labels[id];
        }
        return String.valueOf(id);
    }
}