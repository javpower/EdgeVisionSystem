package com.edge.vision.service;

import com.edge.vision.config.YamlConfig;
import com.edge.vision.core.stitcher.ManualStitchStrategy;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * 拼接配置服务 (V6 通用版 - 每个摄像头支持左右切割)
 * 负责拼接参数的持久化和运行时管理
 * <p>
 * 配置说明：
 * - yml 文件：只配置拼接策略 (simple/manual，移除 auto)
 * - data/stitch-config.json：手动拼接参数配置 (x1, x2, y, h)
 * <p>
 * 参数说明：
 * - x1: 左切割线位置（保留从 x1 到右侧的区域）
 * - x2: 右切割线位置（保留从左侧到 x2 的区域）
 * - y: 截取起始 Y 坐标
 * - h: 截取高度
 * <p>
 * 拼接原理：
 * 每个摄像头保留 [y, y+h] 行，[x1, x2) 列
 * 所有切片后的图像水平拼接
 */
@Service
public class StitchConfigService {
    private static final Logger logger = LoggerFactory.getLogger(StitchConfigService.class);

    @Autowired
    private YamlConfig yamlConfig;

    @Lazy
    @Autowired
    private CameraService cameraService;

    // 运行时拼接策略实例
    private Object currentStitchStrategy;

    // 配置文件路径（放在 data 目录）
    private static final String DATA_DIR = "data";
    private static final String CONFIG_FILE_NAME = "stitch-config.json";
    private Path configFilePath;

    // 默认拼接策略
    private String currentStrategy = "manual";

    @PostConstruct
    public void init() {
        // 初始化配置文件路径 - 放在 data 目录
        String baseDir = System.getProperty("user.dir");
        Path dataDir = Paths.get(baseDir, DATA_DIR);
        configFilePath = dataDir.resolve(CONFIG_FILE_NAME);

        // 确保 data 目录存在
        try {
            if (!Files.exists(dataDir)) {
                Files.createDirectories(dataDir);
                logger.info("Created data directory: {}", dataDir);
            }
        } catch (IOException e) {
            logger.warn("Failed to create data directory: {}", e.getMessage());
        }

        // 从配置文件读取策略
        if (yamlConfig.getStitching() != null && yamlConfig.getStitching().getStrategy() != null) {
            currentStrategy = yamlConfig.getStitching().getStrategy();
        }

        // 主动创建策略实例以加载配置文件
        currentStitchStrategy = createStitchStrategy();

        logger.info("StitchConfigService initialized with strategy: {}, config file: {}",
            currentStrategy, configFilePath);
    }

    @PreDestroy
    public void destroy() {
        // 保存配置（如果是 manual 模式）
        if ("manual".equals(currentStrategy)) {
            savePersistedConfig();
        }
    }

    /**
     * 获取当前拼接策略
     */
    public String getCurrentStrategy() {
        return currentStrategy;
    }

    /**
     * 设置拼接策略（移除 auto 选项）
     */
    public synchronized void setStrategy(String strategy) {
        if (!Arrays.asList("simple", "manual").contains(strategy)) {
            throw new IllegalArgumentException("Invalid strategy: " + strategy + ". Only 'simple' and 'manual' are supported.");
        }

        // 如果从 manual 切换到其他策略，先保存配置
        if ("manual".equals(this.currentStrategy) && !"manual".equals(strategy)) {
            savePersistedConfig();
        }

        this.currentStrategy = strategy;
        this.currentStitchStrategy = null; // 清除缓存，下次获取时重新创建

        logger.info("Stitch strategy changed to: {}", strategy);
    }

    /**
     * 获取当前拼接策略实例
     */
    public Object getStitchStrategy() {
        if (currentStitchStrategy == null) {
            currentStitchStrategy = createStitchStrategy();
        }
        return currentStitchStrategy;
    }

    /**
     * 创建拼接策略实例（移除 auto）
     */
    private Object createStitchStrategy() {
        switch (currentStrategy) {
            case "simple":
                boolean enableBlend = yamlConfig.getStitching() != null && yamlConfig.getStitching().isEnableBlend();
                return new com.edge.vision.core.stitcher.SimpleStitchStrategy(enableBlend);

            case "manual":
                ManualStitchStrategy manualStrategy = new ManualStitchStrategy();
                // 从 JSON 文件加载保存的手动配置
                loadManualConfigFromJson(manualStrategy);
                return manualStrategy;

            default:
                return new com.edge.vision.core.stitcher.SimpleStitchStrategy(true);
        }
    }

    /**
     * 获取手动拼接配置
     */
    public Map<String, Object> getManualConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("strategy", currentStrategy);

        if ("manual".equals(currentStrategy)) {
            Object strategy = getStitchStrategy();
            if (strategy instanceof ManualStitchStrategy) {
                ManualStitchStrategy manualStrategy = (ManualStitchStrategy) strategy;
                List<Map<String, Object>> cameras = new ArrayList<>();

                for (Map.Entry<Integer, ManualStitchStrategy.CameraConfig> entry :
                        manualStrategy.getAllCameraConfigs().entrySet()) {
                    Map<String, Object> cameraConfig = new HashMap<>();
                    cameraConfig.put("index", entry.getValue().index);
                    cameraConfig.put("x1", entry.getValue().x1);
                    cameraConfig.put("x2", entry.getValue().x2);
                    cameraConfig.put("y", entry.getValue().y);
                    cameraConfig.put("h", entry.getValue().h);
                    cameras.add(cameraConfig);
                }
                config.put("cameras", cameras);
            }
        }

        return config;
    }

    /**
     * 更新手动拼接配置（通用版 - 支持 x1, x2）
     */
    public synchronized void updateManualConfig(int cameraIndex, Map<String, Object> params) {
        if (!"manual".equals(currentStrategy)) {
            throw new IllegalStateException("Manual config can only be updated when strategy is 'manual'");
        }

        Object strategy = getStitchStrategy();
        if (strategy instanceof ManualStitchStrategy) {
            ManualStitchStrategy manualStrategy = (ManualStitchStrategy) strategy;
            ManualStitchStrategy.CameraConfig config = manualStrategy.getCameraConfig(cameraIndex);

            // 更新配置（x1, x2, y, h）
            if (params.containsKey("x1")) {
                config.x1 = getNumberValue(params.get("x1")).intValue();
            }
            if (params.containsKey("x2")) {
                config.x2 = getNumberValue(params.get("x2")).intValue();
            }
            if (params.containsKey("y")) {
                config.y = getNumberValue(params.get("y")).intValue();
            }
            if (params.containsKey("h")) {
                config.h = getNumberValue(params.get("h")).intValue();
            }

            manualStrategy.updateCameraConfig(config);

            // 持久化配置到 JSON 文件
            savePersistedConfig();

            logger.info("Updated manual config for camera {}: x1={}, x2={}, y={}, h={}",
                cameraIndex, config.x1, config.x2, config.y, config.h);
        }
    }

    /**
     * 批量更新手动拼接配置（通用版 - 支持 x1, x2）
     */
    public synchronized void updateManualConfigBatch(List<Map<String, Object>> cameraConfigs) {
        if (!"manual".equals(currentStrategy)) {
            throw new IllegalStateException("Manual config can only be updated when strategy is 'manual'");
        }

        Object strategy = getStitchStrategy();
        if (strategy instanceof ManualStitchStrategy) {
            ManualStitchStrategy manualStrategy = (ManualStitchStrategy) strategy;
            List<ManualStitchStrategy.CameraConfig> configs = new ArrayList<>();

            for (Map<String, Object> params : cameraConfigs) {
                ManualStitchStrategy.CameraConfig config = new ManualStitchStrategy.CameraConfig();
                config.index = getNumberValue(params.get("index")).intValue();

                if (params.containsKey("x1")) {
                    config.x1 = getNumberValue(params.get("x1")).intValue();
                }
                if (params.containsKey("x2")) {
                    config.x2 = getNumberValue(params.get("x2")).intValue();
                }
                if (params.containsKey("y")) {
                    config.y = getNumberValue(params.get("y")).intValue();
                }
                if (params.containsKey("h")) {
                    config.h = getNumberValue(params.get("h")).intValue();
                }

                configs.add(config);
            }

            manualStrategy.setAllCameraConfigs(configs);

            // 持久化配置到 JSON 文件
            savePersistedConfig();

            logger.info("Batch updated manual config for {} cameras", cameraConfigs.size());
        }
    }

    /**
     * 重置手动拼接配置为默认值
     */
    public synchronized void resetManualConfig() {
        Object strategy = getStitchStrategy();
        if (strategy instanceof ManualStitchStrategy) {
            ManualStitchStrategy manualStrategy = (ManualStitchStrategy) strategy;
            int cameraCount = getCameraCountSafe();
            manualStrategy.resetToDefault(cameraCount > 1 ? cameraCount : 2);

            // 持久化配置到 JSON 文件
            savePersistedConfig();

            logger.info("Reset manual config to default");
        }
    }

    /**
     * 从 JSON 文件加载手动拼接配置（通用版 - 支持 x1, x2）
     */
    private void loadManualConfigFromJson(ManualStitchStrategy strategy) {
        if (Files.exists(configFilePath)) {
            try {
                String content = Files.readString(configFilePath);
                ObjectMapper mapper = new ObjectMapper();
                Map<String, Object> config = mapper.readValue(content, Map.class);

                @SuppressWarnings("unchecked")
                List<Map<String, Object>> cameraConfigs = (List<Map<String, Object>>) config.get("cameras");
                if (cameraConfigs != null && !cameraConfigs.isEmpty()) {
                    List<ManualStitchStrategy.CameraConfig> configs = new ArrayList<>();
                    for (Map<String, Object> params : cameraConfigs) {
                        ManualStitchStrategy.CameraConfig cfg = new ManualStitchStrategy.CameraConfig();
                        cfg.index = ((Number) params.get("index")).intValue();

                        // 读取通用参数 (x1, x2, y, h)
                        if (params.containsKey("x1")) {
                            cfg.x1 = ((Number) params.get("x1")).intValue();
                        }
                        if (params.containsKey("x2")) {
                            cfg.x2 = ((Number) params.get("x2")).intValue();
                        }
                        if (params.containsKey("y")) {
                            cfg.y = ((Number) params.get("y")).intValue();
                        }
                        if (params.containsKey("h")) {
                            cfg.h = ((Number) params.get("h")).intValue();
                        }

                        configs.add(cfg);
                    }
                    strategy.setAllCameraConfigs(configs);
                    logger.info("Loaded manual config from JSON for {} cameras", configs.size());
                } else {
                    // 文件存在但没有配置，使用默认值
                    int cameraCount = getCameraCountSafe();
                    strategy.resetToDefault(cameraCount > 1 ? cameraCount : 2);
                    logger.info("No manual config found in JSON, using defaults");
                }
            } catch (IOException e) {
                logger.warn("Failed to load manual config from JSON: {}, using defaults", e.getMessage());
                int cameraCount = getCameraCountSafe();
                strategy.resetToDefault(cameraCount > 1 ? cameraCount : 2);
            }
        } else {
            // 文件不存在，使用默认值
            int cameraCount = getCameraCountSafe();
            strategy.resetToDefault(cameraCount > 1 ? cameraCount : 2);
            logger.info("Manual config file not found, using defaults");
        }
    }

    /**
     * 安全地获取摄像头数量，处理 @Lazy 可能导致的未初始化情况
     */
    private int getCameraCountSafe() {
        try {
            return cameraService.getCameraCount();
        } catch (Exception e) {
            logger.debug("CameraService not ready yet, using default camera count: {}", e.getMessage());
            return 2; // 默认返回2个摄像头
        }
    }

    /**
     * 保存手动拼接配置到 JSON 文件（通用版 - 支持 x1, x2）
     */
    private void savePersistedConfig() {
        try {
            Object strategy = getStitchStrategy();
            if (!(strategy instanceof ManualStitchStrategy)) {
                return;
            }

            ManualStitchStrategy manualStrategy = (ManualStitchStrategy) strategy;
            ObjectMapper mapper = new ObjectMapper();

            Map<String, Object> configToSave = new HashMap<>();
            configToSave.put("version", "3.0");
            configToSave.put("description", "Universal manual stitch configuration - each camera with left/right crop parameters");
            configToSave.put("updatedAt", System.currentTimeMillis());

            List<Map<String, Object>> cameraConfigs = new ArrayList<>();
            for (ManualStitchStrategy.CameraConfig cfg : manualStrategy.getAllCameraConfigs().values()) {
                Map<String, Object> cfgMap = new HashMap<>();
                cfgMap.put("index", cfg.index);
                cfgMap.put("x1", cfg.x1);
                cfgMap.put("x2", cfg.x2);
                cfgMap.put("y", cfg.y);
                cfgMap.put("h", cfg.h);
                cameraConfigs.add(cfgMap);
            }
            configToSave.put("cameras", cameraConfigs);

            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(configToSave);
            Files.writeString(configFilePath, json);
            logger.info("Saved manual config to: {}", configFilePath);
        } catch (IOException e) {
            logger.error("Failed to save manual config: {}", e.getMessage());
        }
    }

    /**
     * 获取所有可用的拼接配置
     */
    public Map<String, Object> getAllConfigs() {
        Map<String, Object> result = new HashMap<>();
        result.put("currentStrategy", currentStrategy);
        result.put("availableStrategies", Arrays.asList("simple", "manual"));
        result.put("configFilePath", configFilePath.toString());

        if (yamlConfig.getStitching() != null) {
            result.put("blendWidth", yamlConfig.getStitching().getBlendWidth());
            result.put("enableBlend", yamlConfig.getStitching().isEnableBlend());
        }

        return result;
    }

    // 辅助方法：从任意类型获取 Number 值（处理字符串数字）
    private Number getNumberValue(Object value) {
        if (value instanceof Number) {
            return (Number) value;
        }
        if (value instanceof String) {
            return Double.parseDouble((String) value);
        }
        return 0;
    }
}
