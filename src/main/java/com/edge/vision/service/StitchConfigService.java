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
 * 拼接配置服务
 * 负责拼接参数的持久化和运行时管理
 *
 * 配置说明：
 * - yml 文件：只配置拼接策略 (simple/auto/manual)
 * - data/stitch-config.json：手动拼接参数配置（仅在 manual 模式下使用）
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
    private String currentStrategy = "simple";

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

        logger.info("StitchConfigService initialized with strategy: {}, config file: {}", currentStrategy, configFilePath);
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
     * 设置拼接策略
     */
    public synchronized void setStrategy(String strategy) {
        if (!Arrays.asList("simple", "auto", "manual").contains(strategy)) {
            throw new IllegalArgumentException("Invalid strategy: " + strategy);
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
     * 创建拼接策略实例
     */
    private Object createStitchStrategy() {
        switch (currentStrategy) {
            case "simple":
                boolean enableBlend = yamlConfig.getStitching() != null && yamlConfig.getStitching().isEnableBlend();
                return new com.edge.vision.core.stitcher.SimpleStitchStrategy(enableBlend);

            case "auto":
                return new com.edge.vision.core.stitcher.AutoStitchStrategy();

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
                    cameraConfig.put("offset", entry.getValue().offset);
                    cameraConfig.put("scale", entry.getValue().scale);
                    cameraConfig.put("rotation", entry.getValue().rotation);
                    cameraConfig.put("flip", entry.getValue().flip);
                    cameraConfig.put("overlapWidth", entry.getValue().overlapWidth);
                    cameras.add(cameraConfig);
                }
                config.put("cameras", cameras);
            }
        }

        return config;
    }

    /**
     * 更新手动拼接配置
     */
    public synchronized void updateManualConfig(int cameraIndex, Map<String, Object> params) {
        if (!"manual".equals(currentStrategy)) {
            throw new IllegalStateException("Manual config can only be updated when strategy is 'manual'");
        }

        Object strategy = getStitchStrategy();
        if (strategy instanceof ManualStitchStrategy) {
            ManualStitchStrategy manualStrategy = (ManualStitchStrategy) strategy;
            ManualStitchStrategy.CameraConfig config = manualStrategy.getCameraConfig(cameraIndex);

            // 更新配置
            if (params.containsKey("offset")) {
                config.offset = parseIntArrayValue(params.get("offset"));
            }
            if (params.containsKey("scale")) {
                config.scale = getNumberValue(params.get("scale")).doubleValue();
            }
            if (params.containsKey("rotation")) {
                config.rotation = getNumberValue(params.get("rotation")).doubleValue();
            }
            if (params.containsKey("flip")) {
                config.flip = parseBooleanArrayValue(params.get("flip"));
            }
            if (params.containsKey("overlapWidth")) {
                config.overlapWidth = getNumberValue(params.get("overlapWidth")).intValue();
            }

            manualStrategy.updateCameraConfig(config);

            // 持久化配置到 JSON 文件
            savePersistedConfig();

            logger.info("Updated manual config for camera {}: {}", cameraIndex, params);
        }
    }

    /**
     * 批量更新手动拼接配置
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

                if (params.containsKey("offset")) {
                    config.offset = parseIntArrayValue(params.get("offset"));
                }
                if (params.containsKey("scale")) {
                    config.scale = getNumberValue(params.get("scale")).doubleValue();
                }
                if (params.containsKey("rotation")) {
                    config.rotation = getNumberValue(params.get("rotation")).doubleValue();
                }
                if (params.containsKey("flip")) {
                    config.flip = parseBooleanArrayValue(params.get("flip"));
                }
                if (params.containsKey("overlapWidth")) {
                    config.overlapWidth = getNumberValue(params.get("overlapWidth")).intValue();
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
            int cameraCount = cameraService.getCameraCount();
            manualStrategy.resetToDefault(cameraCount > 0 ? cameraCount : 2);

            // 持久化配置到 JSON 文件
            savePersistedConfig();

            logger.info("Reset manual config to default");
        }
    }

    /**
     * 从 JSON 文件加载手动拼接配置
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
                        cfg.offset = parseIntArray(params.get("offset"));
                        cfg.scale = ((Number) params.get("scale")).doubleValue();
                        cfg.rotation = ((Number) params.get("rotation")).doubleValue();
                        cfg.flip = parseBooleanArray(params.get("flip"));
                        cfg.overlapWidth = ((Number) params.get("overlapWidth")).intValue();
                        configs.add(cfg);
                    }
                    strategy.setAllCameraConfigs(configs);
                    logger.info("Loaded manual config from JSON for {} cameras", configs.size());
                } else {
                    // 文件存在但没有配置，使用默认值
                    int cameraCount = cameraService.getCameraCount();
                    strategy.resetToDefault(cameraCount > 0 ? cameraCount : 2);
                    logger.info("No manual config found in JSON, using defaults");
                }
            } catch (IOException e) {
                logger.warn("Failed to load manual config from JSON: {}, using defaults", e.getMessage());
                int cameraCount = cameraService.getCameraCount();
                strategy.resetToDefault(cameraCount > 0 ? cameraCount : 2);
            }
        } else {
            // 文件不存在，使用默认值
            int cameraCount = cameraService.getCameraCount();
            strategy.resetToDefault(cameraCount > 0 ? cameraCount : 2);
            logger.info("Manual config file not found, using defaults");
        }
    }

    /**
     * 保存手动拼接配置到 JSON 文件
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
            configToSave.put("version", "1.0");
            configToSave.put("description", "Manual stitch configuration for multiple cameras");
            configToSave.put("updatedAt", System.currentTimeMillis());

            List<Map<String, Object>> cameraConfigs = new ArrayList<>();
            for (ManualStitchStrategy.CameraConfig cfg : manualStrategy.getAllCameraConfigs().values()) {
                Map<String, Object> cfgMap = new HashMap<>();
                cfgMap.put("index", cfg.index);
                cfgMap.put("offset", cfg.offset);
                cfgMap.put("scale", cfg.scale);
                cfgMap.put("rotation", cfg.rotation);
                cfgMap.put("flip", cfg.flip);
                cfgMap.put("overlapWidth", cfg.overlapWidth);
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
        result.put("availableStrategies", Arrays.asList("simple", "auto", "manual"));
        result.put("configFilePath", configFilePath.toString());

        if (yamlConfig.getStitching() != null) {
            result.put("blendWidth", yamlConfig.getStitching().getBlendWidth());
            result.put("enableBlend", yamlConfig.getStitching().isEnableBlend());
        }

        return result;
    }

    // 辅助方法：解析 int 数组
    private int[] parseIntArray(Object value) {
        if (value instanceof int[]) {
            return (int[]) value;
        }
        if (value instanceof List) {
            List<?> list = (List<?>) value;
            int[] result = new int[list.size()];
            for (int i = 0; i < list.size(); i++) {
                Object item = list.get(i);
                if (item instanceof Number) {
                    result[i] = ((Number) item).intValue();
                } else if (item instanceof String) {
                    result[i] = Integer.parseInt((String) item);
                } else {
                    result[i] = 0;
                }
            }
            return result;
        }
        return new int[]{0, 0};
    }

    // 辅助方法：解析 boolean 数组
    private boolean[] parseBooleanArray(Object value) {
        if (value instanceof boolean[]) {
            return (boolean[]) value;
        }
        if (value instanceof List) {
            List<?> list = (List<?>) value;
            boolean[] result = new boolean[list.size()];
            for (int i = 0; i < list.size(); i++) {
                Object item = list.get(i);
                if (item instanceof Boolean) {
                    result[i] = (Boolean) item;
                } else if (item instanceof String) {
                    result[i] = Boolean.parseBoolean((String) item);
                } else {
                    result[i] = Boolean.TRUE.equals(item);
                }
            }
            return result;
        }
        return new boolean[]{false, false};
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

    // 辅助方法：解析 int 数组（增强版，处理字符串和 ArrayList）
    private int[] parseIntArrayValue(Object value) {
        if (value instanceof int[]) {
            return (int[]) value;
        }
        if (value instanceof List) {
            List<?> list = (List<?>) value;
            int[] result = new int[list.size()];
            for (int i = 0; i < list.size(); i++) {
                Object item = list.get(i);
                if (item instanceof Number) {
                    result[i] = ((Number) item).intValue();
                } else if (item instanceof String) {
                    result[i] = Integer.parseInt((String) item);
                } else {
                    result[i] = 0;
                }
            }
            return result;
        }
        return new int[]{0, 0};
    }

    // 辅助方法：解析 boolean 数组（增强版）
    private boolean[] parseBooleanArrayValue(Object value) {
        if (value instanceof boolean[]) {
            return (boolean[]) value;
        }
        if (value instanceof List) {
            List<?> list = (List<?>) value;
            boolean[] result = new boolean[list.size()];
            for (int i = 0; i < list.size(); i++) {
                Object item = list.get(i);
                if (item instanceof Boolean) {
                    result[i] = (Boolean) item;
                } else if (item instanceof String) {
                    result[i] = Boolean.parseBoolean((String) item);
                } else {
                    result[i] = false;
                }
            }
            return result;
        }
        return new boolean[]{false, false};
    }
}
