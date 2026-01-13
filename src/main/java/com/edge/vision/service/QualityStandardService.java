package com.edge.vision.service;

import com.edge.vision.config.QualityStandardConfig;
import com.edge.vision.model.Detection;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * 质检标准服务
 * 负责质检标准的持久化和运行时管理
 *
 * 配置说明：
 * - yml 文件：不再使用（已废弃）
 * - data/quality-standards.json：质检标准配置（支持实时更新）
 */
@Service
public class QualityStandardService {
    private static final Logger logger = LoggerFactory.getLogger(QualityStandardService.class);

    // 配置文件路径（放在 data 目录）
    private static final String DATA_DIR = "data";
    private static final String CONFIG_FILE_NAME = "quality-standards.json";
    private Path configFilePath;

    // 运行时配置
    private QualityStandardConfig qualityConfig;

    // 默认配置（当文件不存在时使用）
    private static final QualityStandardConfig DEFAULT_CONFIG = createDefaultConfig();

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

        // 加载配置
        loadConfig();

        logger.info("QualityStandardService initialized, config file: {}", configFilePath);
    }

    @PreDestroy
    public void destroy() {
        // 保存配置
        saveConfig();
    }

    /**
     * 加载质检标准配置
     */
    private void loadConfig() {
        if (Files.exists(configFilePath)) {
            try {
                String content = Files.readString(configFilePath);
                ObjectMapper mapper = new ObjectMapper();
                qualityConfig = mapper.readValue(content, QualityStandardConfig.class);
                logger.info("Loaded quality standards from JSON for {} part types",
                    qualityConfig.getStandards().size());
            } catch (IOException e) {
                logger.warn("Failed to load quality standards from JSON: {}, using defaults",
                    e.getMessage());
                qualityConfig = createDefaultConfig();
            }
        } else {
            // 文件不存在，使用默认值并保存
            logger.info("Quality standards file not found, creating default config");
            qualityConfig = createDefaultConfig();
            saveConfig();
        }
    }

    /**
     * 保存质检标准配置到 JSON 文件
     */
    private void saveConfig() {
        try {
            if (qualityConfig == null) {
                qualityConfig = createDefaultConfig();
            }

            qualityConfig.setUpdatedAt(System.currentTimeMillis());

            ObjectMapper mapper = new ObjectMapper();
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(qualityConfig);
            Files.writeString(configFilePath, json);
            logger.info("Saved quality standards to: {}", configFilePath);
        } catch (IOException e) {
            logger.error("Failed to save quality standards: {}", e.getMessage());
        }
    }

    /**
     * 创建默认配置
     */
    private static QualityStandardConfig createDefaultConfig() {
        QualityStandardConfig config = new QualityStandardConfig();
        config.setVersion("1.0");
        config.setDescription("Quality inspection standards with comparison operators");

        // 默认配置示例
        List<QualityStandardConfig.DefectStandard> eksStandards = new ArrayList<>();
        eksStandards.add(new QualityStandardConfig.DefectStandard("hole", "<=", 20));
        eksStandards.add(new QualityStandardConfig.DefectStandard("nut", "<=", 7));
        config.getStandards().put("EKS", eksStandards);

        return config;
    }

    /**
     * 获取所有质检标准配置
     */
    public QualityStandardConfig getAllConfig() {
        return qualityConfig;
    }

    /**
     * 获取指定工件类型的质检标准
     */
    public List<QualityStandardConfig.DefectStandard> getPartTypeStandards(String partType) {
        if (qualityConfig == null) {
            return new ArrayList<>();
        }
        return qualityConfig.getStandards().getOrDefault(partType, new ArrayList<>());
    }

    /**
     * 获取所有工件类型名称
     */
    public Set<String> getAllPartTypes() {
        if (qualityConfig == null) {
            return new HashSet<>();
        }
        return qualityConfig.getStandards().keySet();
    }

    /**
     * 更新指定工件类型的质检标准
     */
    public synchronized void updatePartTypeStandards(String partType,
                                                      List<QualityStandardConfig.DefectStandard> standards) {
        if (qualityConfig == null) {
            logger.warn("qualityConfig is null during update, initializing with default config");
            qualityConfig = createDefaultConfig();
        }

        qualityConfig.getStandards().put(partType, standards);

        logger.info("Current config after update: {} part types", qualityConfig.getStandards().size());
        for (String key : qualityConfig.getStandards().keySet()) {
            logger.debug("  - {}: {} standards", key, qualityConfig.getStandards().get(key).size());
        }

        // 持久化配置到 JSON 文件
        saveConfig();

        logger.info("Updated quality standards for part type: {} with {} standards",
            partType, standards.size());
    }

    /**
     * 删除指定工件类型的质检标准
     */
    public synchronized void deletePartTypeStandards(String partType) {
        if (qualityConfig == null) {
            return;
        }

        qualityConfig.getStandards().remove(partType);

        // 持久化配置到 JSON 文件
        saveConfig();

        logger.info("Deleted quality standards for part type: {}", partType);
    }

    /**
     * 添加新的工件类型
     */
    public synchronized void addPartType(String partType, List<QualityStandardConfig.DefectStandard> standards) {
        if (qualityConfig == null) {
            qualityConfig = createDefaultConfig();
        }

        qualityConfig.getStandards().put(partType, standards);

        // 持久化配置到 JSON 文件
        saveConfig();

        logger.info("Added new part type: {} with {} standards", partType, standards.size());
    }

    /**
     * 重置所有配置为默认值
     */
    public synchronized void resetToDefault() {
        qualityConfig = createDefaultConfig();
        saveConfig();
        logger.info("Reset quality standards to default");
    }

    /**
     * 评估检测结果是否符合质量标准
     *
     * @param partType 工件类型
     * @param detections 检测到的缺陷列表
     * @return 质检结果对象
     */
    public QualityEvaluationResult evaluate(String partType, List<Detection> detections) {
        logger.info("Evaluating quality for part type: {} with {} detections", partType, detections.size());

        if (qualityConfig == null) {
            logger.error("CRITICAL: qualityConfig is null! This should never happen.");
            logger.info("Attempting to reload config from file...");
            loadConfig();
            if (qualityConfig == null) {
                logger.error("Failed to load config, using default rule");
                QualityEvaluationResult result = new QualityEvaluationResult();
                result.setPartType(partType);
                result.setPassed(detections.isEmpty());
                result.setMessage("配置未初始化，使用默认规则");
                return result;
            }
        }

        logger.debug("Available part types in config: {}", qualityConfig.getStandards().keySet());

        QualityEvaluationResult result = new QualityEvaluationResult();
        result.setPartType(partType);
        result.setPassed(true);
        result.setDetails(new ArrayList<>());

        if (!qualityConfig.getStandards().containsKey(partType)) {
            logger.warn("No quality standards configured for part: {}, using default rule", partType);
            // 使用默认规则：有任何缺陷即不合格
            result.setPassed(detections.isEmpty());
            result.setMessage("未配置质检标准，使用默认规则（无缺陷即合格）");
            return result;
        }

        List<QualityStandardConfig.DefectStandard> standards = qualityConfig.getStandards().get(partType);
        logger.info("Found {} standards for part type: {}", standards.size(), partType);
        for (QualityStandardConfig.DefectStandard std : standards) {
            logger.debug("  - {} {} {}", std.getDefectType(), std.getOperator(), std.getThreshold());
        }

        // 统计每种缺陷类型的数量
        Map<String, Long> defectCounts = new HashMap<>();
        for (Detection det : detections) {
            String label = det.getLabel();
            defectCounts.put(label, defectCounts.getOrDefault(label, 0L) + 1);
        }

        // 评估每个标准
        for (QualityStandardConfig.DefectStandard standard : standards) {
            String defectType = standard.getDefectType();
            int actualCount = defectCounts.getOrDefault(defectType, 0L).intValue();
            boolean passed = standard.evaluate(actualCount);

            QualityEvaluationResult.EvaluationDetail detail = new QualityEvaluationResult.EvaluationDetail();
            detail.setDefectType(defectType);
            detail.setOperator(standard.getOperator());
            detail.setThreshold(standard.getThreshold());
            detail.setActualCount(actualCount);
            detail.setPassed(passed);
            detail.setDescription(standard.getDescription());
            result.getDetails().add(detail);

            if (!passed) {
                result.setPassed(false);
            }
        }

        // 检查是否有未定义的缺陷类型
        for (Map.Entry<String, Long> entry : defectCounts.entrySet()) {
            boolean isDefined = standards.stream()
                .anyMatch(s -> s.getDefectType().equals(entry.getKey()));
            if (!isDefined) {
                QualityEvaluationResult.EvaluationDetail detail = new QualityEvaluationResult.EvaluationDetail();
                detail.setDefectType(entry.getKey());
                detail.setOperator("?");
                detail.setThreshold(-1);
                detail.setActualCount(entry.getValue().intValue());
                detail.setPassed(false);
                detail.setDescription("未定义的缺陷类型: " + entry.getKey());
                result.getDetails().add(detail);
                result.setPassed(false);
                logger.warn("Unknown defect type detected: {}", entry.getKey());
            }
        }

        result.setMessage(result.isPassed() ? "质检合格" : "质检不合格");
        return result;
    }

    /**
     * 质检评估结果
     */
    public static class QualityEvaluationResult {
        private String partType;
        private boolean passed;
        private String message;
        private List<EvaluationDetail> details;

        public String getPartType() { return partType; }
        public void setPartType(String partType) { this.partType = partType; }

        public boolean isPassed() { return passed; }
        public void setPassed(boolean passed) { this.passed = passed; }

        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }

        public List<EvaluationDetail> getDetails() { return details; }
        public void setDetails(List<EvaluationDetail> details) { this.details = details; }

        public static class EvaluationDetail {
            private String defectType;
            private String operator;
            private int threshold;
            private int actualCount;
            private boolean passed;
            private String description;

            public String getDefectType() { return defectType; }
            public void setDefectType(String defectType) { this.defectType = defectType; }

            public String getOperator() { return operator; }
            public void setOperator(String operator) { this.operator = operator; }

            public int getThreshold() { return threshold; }
            public void setThreshold(int threshold) { this.threshold = threshold; }

            public int getActualCount() { return actualCount; }
            public void setActualCount(int actualCount) { this.actualCount = actualCount; }

            public boolean isPassed() { return passed; }
            public void setPassed(boolean passed) { this.passed = passed; }

            public String getDescription() { return description; }
            public void setDescription(String description) { this.description = description; }
        }
    }
}
