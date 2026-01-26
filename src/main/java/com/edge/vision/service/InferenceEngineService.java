package com.edge.vision.service;

import com.edge.vision.config.YamlConfig;
import com.edge.vision.core.infer.YOLOInferenceEngine;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 推理引擎服务
 * <p>
 * 统一管理 YOLO 推理引擎的单例，避免重复创建和资源浪费
 */
@Service
public class InferenceEngineService {
    private static final Logger logger = LoggerFactory.getLogger(InferenceEngineService.class);

    @Autowired
    private YamlConfig config;

    // 类型识别引擎（可选）
    private YOLOInferenceEngine typeInferenceEngine;

    // 细节检测引擎（必须）
    private YOLOInferenceEngine detailInferenceEngine;

    @PostConstruct
    public void init() {
        // 初始化类型识别引擎（可选）
        if (config.getModels().getTypeModel() != null && !config.getModels().getTypeModel().isEmpty()) {
            try {
                typeInferenceEngine = new YOLOInferenceEngine(
                        config.getModels().getTypeModel(),
                        config.getModels().getConfThres(),
                        config.getModels().getIouThres(),
                        config.getModels().getDevice()
                );
                logger.info("Type inference engine initialized successfully");
            } catch (Exception e) {
                logger.warn("Failed to initialize type inference engine: {}", e.getMessage());
            }
        }

        // 初始化细节检测引擎（必须）
        if (config.getModels().getDetailModel() != null && !config.getModels().getDetailModel().isEmpty()) {
            try {
                detailInferenceEngine = new YOLOInferenceEngine(
                        config.getModels().getDetailModel(),
                        config.getModels().getConfThres(),
                        config.getModels().getIouThres(),
                        config.getModels().getDevice(),
                        1280, 1280
                );
                logger.info("Detail inference engine initialized successfully");
            } catch (Exception e) {
                logger.error("Failed to initialize detail inference engine: {}", e.getMessage());
            }
        } else {
            logger.warn("Detail model not configured - detection features will be disabled");
        }
    }

    /**
     * 获取类型识别引擎
     *
     * @return 类型识别引擎，可能为 null
     */
    public YOLOInferenceEngine getTypeInferenceEngine() {
        return typeInferenceEngine;
    }

    /**
     * 获取细节检测引擎
     *
     * @return 细节检测引擎，可能为 null
     */
    public YOLOInferenceEngine getDetailInferenceEngine() {
        return detailInferenceEngine;
    }

    /**
     * 检查类型识别引擎是否可用
     *
     * @return 是否可用
     */
    public boolean isTypeEngineAvailable() {
        return typeInferenceEngine != null;
    }

    /**
     * 检查细节检测引擎是否可用
     *
     * @return 是否可用
     */
    public boolean isDetailEngineAvailable() {
        return detailInferenceEngine != null;
    }

    @PreDestroy
    public void cleanup() {
        if (typeInferenceEngine != null) {
            typeInferenceEngine.close();
            logger.info("Type inference engine closed");
        }
        if (detailInferenceEngine != null) {
            detailInferenceEngine.close();
            logger.info("Detail inference engine closed");
        }
    }
}
