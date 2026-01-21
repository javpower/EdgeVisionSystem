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
                // 尝试列出可用的 CUDA 设备
                System.out.println("========================================");
                System.out.println("Initializing CUDA provider...");
                System.out.println("========================================");

                // 添加 CUDA provider，使用设备 0
                opts.addCUDA(0);

                System.out.println("GPU (CUDA) provider enabled successfully!");
                System.out.println("Using CUDA Device ID: 0");
                System.out.println("========================================");
                System.out.println("");

                // 设置一些性能优化选项
                opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
                opts.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
                System.out.println("GPU optimization level: ALL_OPT");
                System.out.println("Execution mode: SEQUENTIAL");
                System.out.println("");

            } catch (Exception e) {
                String errorMsg = e.getMessage();
                System.err.println("========================================");
                System.err.println("CUDA initialization failed!");
                System.err.println("========================================");
                System.err.println("Error: " + errorMsg);

                // 判断具体问题类型
                if (errorMsg != null) {
                    if (errorMsg.contains("LoadLibrary failed") || errorMsg.contains("error 126")) {
                        System.err.println("");
                        System.err.println("PROBLEM: CUDA or cuDNN libraries not found");
                        System.err.println("");
                        System.err.println("SOLUTION:");
                        System.err.println("1. Install CUDA Toolkit 12.x from: https://developer.nvidia.com/cuda-downloads");
                        System.err.println("2. Install cuDNN 9.x for CUDA 12.x from: https://developer.nvidia.com/cudnn");
                        System.err.println("3. Place CUDA/cuDNN DLLs in libs/ directory or install system-wide");
                        System.err.println("4. The run.bat script should auto-detect CUDA/cuDNN");
                        System.err.println("");
                    } else if (errorMsg.contains("not compiled with CUDA support")) {
                        System.err.println("");
                        System.err.println("PROBLEM: Wrong application version - using CPU build instead of GPU");
                        System.err.println("");
                        System.err.println("SOLUTION: Rebuild with -Pgpu profile");
                        System.err.println("");
                    } else if (errorMsg.contains("failed to create")) {
                        System.err.println("");
                        System.err.println("PROBLEM: CUDA initialization failed - possibly no GPU detected");
                        System.err.println("");
                        System.err.println("SOLUTION:");
                        System.err.println("1. Check if NVIDIA GPU is installed: nvidia-smi");
                        System.err.println("2. Check CUDA driver version matches CUDA Toolkit version");
                        System.err.println("3. Try device ID 1, 2, ... if you have multiple GPUs");
                        System.err.println("");
                    }
                }

                System.err.println("Falling back to CPU execution (slower performance)");
                System.err.println("========================================");
                System.err.println("");
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

    /**
     * 诊断方法：列出所有可用的 CUDA 设备
     * 调用此方法可以查看系统中有哪些 GPU 可用
     */
    public static void listAvailableCUDADevices() {
        try {
            OrtEnvironment env = OrtEnvironment.getEnvironment();

            System.out.println("========================================");
            System.out.println("Checking available CUDA devices...");
            System.out.println("========================================");

            // 尝试创建 CUDA provider
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();

            // 尝试不同的设备 ID
            for (int deviceId = 0; deviceId <= 3; deviceId++) {
                try {
                    OrtSession.SessionOptions testOpts = new OrtSession.SessionOptions();
                    testOpts.addCUDA(deviceId);
                    System.out.println("Device ID " + deviceId + ": Available");
                    testOpts.close();
                } catch (Exception e) {
                    if (deviceId == 0) {
                        System.out.println("Device ID " + deviceId + ": " + e.getMessage());
                    } else {
                        // 对于其他设备ID，如果失败就不再继续
                        break;
                    }
                }
            }

            opts.close();
            env.close();

            System.out.println("========================================");

            // 显示系统 nvidia-smi 信息（如果可用）
            try {
                Process process = Runtime.getRuntime().exec("nvidia-smi --query-gpu=index,name,memory.total --format=csv,noheader");
                java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()));

                System.out.println("nvidia-smi output:");
                System.out.println("========================================");
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println("  " + line);
                }
                System.out.println("========================================");

                process.waitFor();
            } catch (Exception e) {
                System.out.println("Note: nvidia-smi not available (" + e.getMessage() + ")");
            }

        } catch (Exception e) {
            System.err.println("Failed to check CUDA devices: " + e.getMessage());
        }
    }

    /**
     * 获取当前会话的提供者信息
     */
    public void printProviderInfo() {
        try {
            System.out.println("========================================");
            System.out.println("Session Provider Information");
            System.out.println("========================================");
            System.out.println("Session: " + session);
            System.out.println("Input name: " + inputName);
            System.out.println("Input size: " + inputW + "x" + inputH);
            System.out.println("========================================");
        } catch (Exception e) {
            System.err.println("Failed to get provider info: " + e.getMessage());
        }
    }

    /**
     * 测试推理性能
     */
    public void benchmarkPerformance(Mat testImage, int warmupRuns, int testRuns) throws OrtException {
        System.out.println("========================================");
        System.out.println("Performance Benchmark");
        System.out.println("========================================");
        System.out.println("Warmup runs: " + warmupRuns);
        System.out.println("Test runs: " + testRuns);
        System.out.println("");

        // Warmup
        System.out.println("Warming up...");
        for (int i = 0; i < warmupRuns; i++) {
            predict(testImage);
        }
        System.out.println("Warmup complete.");
        System.out.println("");

        // Benchmark
        System.out.println("Running benchmark...");
        long totalTime = 0;
        long[] times = new long[testRuns];

        for (int i = 0; i < testRuns; i++) {
            long start = System.currentTimeMillis();
            predict(testImage);
            long end = System.currentTimeMillis();
            times[i] = end - start;
            totalTime += times[i];
            System.out.println("  Run " + (i + 1) + ": " + times[i] + " ms");
        }

        System.out.println("");
        System.out.println("Results:");
        System.out.println("  Average: " + (totalTime / testRuns) + " ms");
        System.out.println("  Min: " + java.util.Arrays.stream(times).min().getAsLong() + " ms");
        System.out.println("  Max: " + java.util.Arrays.stream(times).max().getAsLong() + " ms");
        System.out.println("  FPS: " + String.format("%.2f", 1000.0 / (totalTime / testRuns)));
        System.out.println("========================================");
    }
}