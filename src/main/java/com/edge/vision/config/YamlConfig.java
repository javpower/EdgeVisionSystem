package com.edge.vision.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

@Data
@Configuration
@ConfigurationProperties(prefix = "edge-vision")
public class YamlConfig {
    private SystemConfig system;
    private CameraConfig cameras;
    private ModelConfig models;
    private RemoteConfig remote;
    private StitchingConfig stitching;
    private InspectionConfig inspection;
    private Map<String, Map<String, Integer>> qualityStandards;

    @Data
    public static class SystemConfig {
        private String deviceId;
        private int port;
        private boolean saveLocal = true;
    }

    @Data
    public static class CameraConfig {
        private List<Object> sources; // 可以是Integer或String
    }

    @Data
    public static class ModelConfig {
        private String typeModel;      // 可选的类型识别模型
        private String detailModel;    // 必须的细节检测模型
        private float confThres = 0.5f;
        private float iouThres = 0.45f;
        private String device = "CPU"; // CPU 或 GPU
    }

    @Data
    public static class RemoteConfig {
        private String uploadUrl;      // 可选的上传地址
        private int timeout = 5;
    }

    @Data
    public static class StitchingConfig {
        private String strategy = "simple";  // simple, auto, manual
        private int blendWidth = 100;
        private boolean enableBlend = true;
    }

    @Data
    public static class InspectionConfig {
        // 最大匹配距离（像素，相对坐标）
        private double maxMatchDistance = 200.0;
        // 是否将未在模板中定义的检测对象视为错检
        private boolean treatExtraAsError = false;
        // 默认容差值（像素）
        private double defaultToleranceX = 20.0;
        private double defaultToleranceY = 20.0;
    }
}