package com.edge.vision.core.infer;

import ai.onnxruntime.*;
import com.edge.vision.model.Detection;
import org.opencv.core.Mat;

import java.nio.FloatBuffer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public abstract class InferEngineTemplate {
    protected OrtEnvironment env;
    protected OrtSession session;
    protected String inputName;
    protected String[] labels; // 动态加载的标签
    protected int inputH = 640;
    protected int inputW = 640;

    public InferEngineTemplate(String modelPath, String device) throws OrtException {
        // 1. 初始化环境 (单例)
        this.env = OrtEnvironment.getEnvironment();

        // 2. 配置设备 (GPU/CPU)
        OrtSession.SessionOptions opts = createSessionOptions(device);

        // 3. 创建 Session
        this.session = env.createSession(modelPath, opts);

        // 4. 获取输入节点名称和尺寸
        this.inputName = session.getInputNames().iterator().next();
        Map<String, NodeInfo> inputInfo = session.getInputInfo();
        try {
            long[] shape = ((TensorInfo) inputInfo.get(inputName).getInfo()).getShape();
            // 尝试读取模型输入的固定尺寸 (如果是动态尺寸-1，则保持默认640)
            if (shape.length >= 4) {
                if (shape[2] > 0) this.inputH = (int) shape[2];
                if (shape[3] > 0) this.inputW = (int) shape[3];
            }
        } catch (Exception e) {
            System.err.println("Warning: Could not determine input shape from model, using default 640x640");
        }

        // 5. 动态加载 Metadata 中的类别名称
        loadMetadata();
    }

    /**
     * 配置 SessionOptions (GPU 优先)
     */
    private OrtSession.SessionOptions createSessionOptions(String device) throws OrtException {
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();

        // 开启图优化
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);

        if ("GPU".equalsIgnoreCase(device)) {
            try {
                // 尝试挂载 CUDA (Device ID 0)
                opts.addCUDA(0);
                System.out.println(">>> Inference Device: GPU (CUDA)");
            } catch (Exception e) {
                System.err.println(">>> CUDA Init Failed, falling back to CPU. Error: " + e.getMessage());
                // 如果 CUDA 失败，重新创建 opts，因为之前的可能已经脏了
                opts = new OrtSession.SessionOptions();
                opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
                // 设置 CPU 线程数 (根据具体 CPU 核数调整，太高反而慢)
                opts.setInterOpNumThreads(4);
                opts.setIntraOpNumThreads(4);
            }
        } else {
            System.out.println(">>> Inference Device: CPU");
            opts.setInterOpNumThreads(4);
            opts.setIntraOpNumThreads(4);
        }
        return opts;
    }

    private void loadMetadata() {
        try {
            OnnxModelMetadata metadata = session.getMetadata();
            String metaStr = metadata.getCustomMetadata().get("names");

            if (metaStr != null && !metaStr.isEmpty()) {
                if (metaStr.startsWith("{")) metaStr = metaStr.substring(1, metaStr.length() - 1);
                List<String> labelList = new ArrayList<>();
                Pattern pattern = Pattern.compile("'([^']*)'");
                Matcher matcher = pattern.matcher(metaStr);
                while (matcher.find()) {
                    labelList.add(matcher.group(1));
                }
                this.labels = labelList.toArray(new String[0]);
            }
        } catch (Exception e) {
            System.err.println("Warning: Failed to load metadata labels.");
            this.labels = null;
        }
    }

    /**
     * 推理主入口
     */
    public List<Detection> predict(Mat img) throws OrtException {
        if (img == null || img.empty()) return Collections.emptyList();

        // 1. 预处理 (耗时点1)
        PreProcessResult preResult = preprocess(img);

        // 2. 创建 Tensor (Java -> Native 内存拷贝)
        // 维度: [1, 3, H, W]
        long[] shape = { 1L, 3L, (long)inputH, (long)inputW };
        OnnxTensor tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(preResult.pixelData), shape);

        // 3. 运行推理 (耗时点2)
        OrtSession.Result result = session.run(Collections.singletonMap(inputName, tensor));

        // 4. 获取输出
        // YOLOv8 Output: [1, 84, 8400] -> Batch, Channels, Anchors
        float[][] outputData = ((float[][][])result.get(0).getValue())[0];

        // 5. 后处理 (耗时点3)
        List<Detection> detections = postprocess(outputData, preResult);

        // 6. 资源释放
        tensor.close();
        result.close();

        return detections;
    }

    // 内部数据结构
    protected static class PreProcessResult {
        public float[] pixelData;
        public float ratio;
        public float dw;
        public float dh;
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
        if (labels != null && id >= 0 && id < labels.length) return labels[id];
        return String.valueOf(id);
    }
}