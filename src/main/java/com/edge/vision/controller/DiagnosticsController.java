package com.edge.vision.controller;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.edge.vision.config.YamlConfig;
import com.edge.vision.core.infer.InferEngineTemplate;
import com.edge.vision.core.infer.YOLOInferenceEngine;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.opencv.core.Mat;
import org.opencv.imgcodecs.Imgcodecs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;

/**
 * 系统诊断 API
 * <p>
 * 提供 GPU 设备诊断、性能测试等功能
 */
@Tag(name = "系统诊断", description = "GPU设备诊断和性能测试")
@RestController
@RequestMapping("/api/diagnostics")
public class DiagnosticsController {

    private static final Logger logger = LoggerFactory.getLogger(DiagnosticsController.class);

    @Autowired
    private YamlConfig yamlConfig;

    /**
     * 列出所有可用的 CUDA 设备
     */
    @Operation(summary = "列出可用的 CUDA 设备", description = "检测并列出系统中所有可用的 CUDA 设备")
    @GetMapping("/cuda-devices")
    public ResponseEntity<Map<String, Object>> listCUDADevices() {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, String>> devices = new ArrayList<>();

        try {
            // 1. 获取 nvidia-smi 信息
            List<String> nvidiaSmiOutput = new ArrayList<>();
            try {
                Process process = Runtime.getRuntime().exec("nvidia-smi --query-gpu=index,name,driver_version,memory.total,compute_cap --format=csv,noheader");
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    nvidiaSmiOutput.add(line);
                    // 解析 CSV 格式: 0, NVIDIA GeForce RTX 3090, 531.18, 24576 MiB, 8.6
                    String[] parts = line.split(",\\s*");
                    if (parts.length >= 5) {
                        Map<String, String> deviceInfo = new LinkedHashMap<>();
                        deviceInfo.put("index", parts[0].trim());
                        deviceInfo.put("name", parts[1].trim());
                        deviceInfo.put("driver_version", parts[2].trim());
                        deviceInfo.put("memory_total", parts[3].trim());
                        deviceInfo.put("compute_cap", parts[4].trim());
                        devices.add(deviceInfo);
                    }
                }
                process.waitFor();
            } catch (Exception e) {
                nvidiaSmiOutput.add("nvidia-smi not available: " + e.getMessage());
            }

            // 2. 尝试检测 ONNX Runtime CUDA 支持
            Map<String, String> onnxRuntimeInfo = new LinkedHashMap<>();
            try {
                OrtEnvironment env = OrtEnvironment.getEnvironment();
                onnxRuntimeInfo.put("version", env.getVersion());

                // 尝试创建 CUDA provider
                for (int deviceId = 0; deviceId <= 3; deviceId++) {
                    try {
                        OrtSession.SessionOptions testOpts = new OrtSession.SessionOptions();
                        testOpts.addCUDA(deviceId);
                        onnxRuntimeInfo.put("cuda_device_" + deviceId, "Available");
                        testOpts.close();
                    } catch (Exception e) {
                        if (deviceId == 0) {
                            onnxRuntimeInfo.put("cuda_device_0_error", e.getMessage());
                        }
                        break;
                    }
                }
                env.close();
            } catch (Exception e) {
                onnxRuntimeInfo.put("error", e.getMessage());
            }

            result.put("nvidia_smi_available", !nvidiaSmiOutput.isEmpty());
            result.put("nvidia_smi_output", nvidiaSmiOutput);
            result.put("devices", devices);
            result.put("onnxruntime_cuda", onnxRuntimeInfo);

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            result.put("error", e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * 获取当前推理引擎的诊断信息
     */
    @Operation(summary = "获取推理引擎诊断信息", description = "显示当前使用的设备、提供者、性能配置等信息")
    @GetMapping("/engine-info")
    public ResponseEntity<Map<String, Object>> getEngineInfo() {
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            // 检查配置的设备
            String configuredDevice = yamlConfig.getModels() != null ? yamlConfig.getModels().getDevice() : "not configured";
            result.put("configured_device", configuredDevice);

            // 检查 ONNX Runtime 提供者
            Map<String, String> providers = new LinkedHashMap<>();
            try {
                OrtEnvironment env = OrtEnvironment.getEnvironment();
                providers.put("version", env.getVersion());
                providers.put("available_providers", String.valueOf(env.getAvailableProviders()));

                // 检查 CUDA provider 是否可用
                try {
                    OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
                    opts.addCUDA(0);
                    providers.put("cuda_provider", "Available");
                    opts.close();
                } catch (Exception e) {
                    providers.put("cuda_provider", "Not Available: " + e.getMessage());
                }

                env.close();
            } catch (Exception e) {
                providers.put("error", e.getMessage());
            }

            result.put("providers", providers);

            // 检查 OpenCV
            Map<String, String> opencvInfo = new LinkedHashMap<>();
            try {
                opencvInfo.put("version", org.opencv.core.Core.VERSION);
                opencvInfo.put("loaded", "true");
            } catch (Exception e) {
                opencvInfo.put("error", e.getMessage());
            }
            result.put("opencv", opencvInfo);

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            result.put("error", e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * 性能测试端点
     */
    @Operation(summary = "运行性能测试", description = "使用指定的模型进行推理性能测试")
    @PostMapping("/benchmark")
    public ResponseEntity<Map<String, Object>> runBenchmark(@RequestParam(defaultValue = "0") int warmupRuns,
                                                             @RequestParam(defaultValue = "5") int testRuns,
                                                             @RequestParam(defaultValue = "false") boolean useCamera) {
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            // 获取测试图像
            Mat testImage;
            if (useCamera) {
                // TODO: 从相机获取图像
                result.put("error", "Camera capture not implemented for benchmark");
                return ResponseEntity.status(501).body(result);
            } else {
                // 创建一个测试图像 (640x640 随机像素)
                testImage = new Mat(640, 640, 16); // CV_8UC3
                testImage.setTo(new org.opencv.core.Scalar(128, 128, 128));
            }

            // 创建推理引擎
            String modelPath = yamlConfig.getModels().getDetailModel();
            String device = yamlConfig.getModels().getDevice();
            float confThres = yamlConfig.getModels().getConfThres();
            float iouThres = yamlConfig.getModels().getIouThres();
            YOLOInferenceEngine engine = new YOLOInferenceEngine(modelPath, confThres, iouThres, device, 640, 640);

            // 预热
            for (int i = 0; i < warmupRuns; i++) {
                engine.predict(testImage);
            }

            // 测试
            long[] times = new long[testRuns];
            for (int i = 0; i < testRuns; i++) {
                long start = System.currentTimeMillis();
                engine.predict(testImage);
                long end = System.currentTimeMillis();
                times[i] = end - start;
            }

            // 统计
            Arrays.sort(times);
            long min = times[0];
            long max = times[testRuns - 1];
            long avg = Arrays.stream(times).sum() / testRuns;
            double fps = 1000.0 / avg;

            result.put("device", device);
            result.put("warmup_runs", warmupRuns);
            result.put("test_runs", testRuns);
            result.put("min_time_ms", min);
            result.put("max_time_ms", max);
            result.put("avg_time_ms", avg);
            result.put("fps", String.format("%.2f", fps));
            result.put("times_per_run", times);

            // 性能评估
            String performanceLevel;
            if (fps >= 30) {
                performanceLevel = "Excellent (>= 30 FPS)";
            } else if (fps >= 15) {
                performanceLevel = "Good (15-30 FPS)";
            } else if (fps >= 10) {
                performanceLevel = "Fair (10-15 FPS)";
            } else {
                performanceLevel = "Slow (< 10 FPS) - Check if GPU is being used!";
            }
            result.put("performance_level", performanceLevel);

            engine.close();
            testImage.release();

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            result.put("error", e.getMessage());
            result.put("stack_trace", Arrays.stream(e.getStackTrace())
                    .map(StackTraceElement::toString)
                    .toList());
            return ResponseEntity.status(500).body(result);
        }
    }
}
