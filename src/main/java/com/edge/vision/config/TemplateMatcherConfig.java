package com.edge.vision.config;

import com.edge.vision.core.template.TemplateMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 模板匹配器配置
 * <p>
 * 从 application.yml 读取配置并注入到 TemplateMatcher
 */
@Configuration
public class TemplateMatcherConfig {
    private static final Logger logger = LoggerFactory.getLogger(TemplateMatcherConfig.class);

    @Autowired
    private YamlConfig yamlConfig;

    @Bean
    public TemplateMatcher templateMatcher() {
        YamlConfig.InspectionConfig inspectionConfig = yamlConfig.getInspection();
        if (inspectionConfig == null) {
            logger.info("使用默认的 TemplateMatcher 配置");
            return new TemplateMatcher();
        }

        TemplateMatcher matcher = new TemplateMatcher(inspectionConfig.getMaxMatchDistance());
        matcher.setTreatExtraAsError(inspectionConfig.isTreatExtraAsError());

        logger.info("TemplateMatcher 配置: maxMatchDistance={}, treatExtraAsError={}",
            inspectionConfig.getMaxMatchDistance(), inspectionConfig.isTreatExtraAsError());

        return matcher;
    }
}
