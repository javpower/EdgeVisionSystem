package com.edge.vision.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置
 * <p>
 * 配置静态资源映射，让外部可以访问存储的图片
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /**
     * 配置静态资源映射
     * <p>
     * 映射规则：
     * - /api/images/** -> data/images/
     * <p>
     * 前端访问示例：
     * - 图片存储路径：data/images/2024-01-15/EKS/EKS_1705300600.jpg
     * - 前端访问URL：http://服务器地址/api/images/2024-01-15/EKS/EKS_1705300600.jpg
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 映射图片目录
        registry.addResourceHandler("/api/images/**")
                .addResourceLocations("file:./data/images/");

        // 设置缓存控制（可选，根据需求调整）
        // 开发环境可以禁用缓存，生产环境可以启用
        // registry.addResourceHandler("/api/images/**")
        //         .setCacheControl(CacheControl.maxAge(7, TimeUnit.DAYS));
    }
}
