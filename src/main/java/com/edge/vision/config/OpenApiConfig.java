package com.edge.vision.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * OpenAPI / Swagger 配置
 *
 * 访问地址：
 * - Swagger UI: http://localhost:{port}/swagger-ui.html
 * - API 文档 (JSON): http://localhost:{port}/v3/api-docs
 * - API 文档 (YAML): http://localhost:{port}/v3/api-docs.yaml
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI edgeVisionOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Edge Vision System API")
                        .description("""
                                工业边缘视觉检测系统 API 文档

                                ## 功能概述

                                本系统提供多摄像头图像拼接和 YOLO 缺陷检测功能。

                                ### 核心功能
                                - **摄像头管理**：启动/停止摄像头，获取摄像头状态
                                - **视频流**：支持单摄像头 MJPEG 流和拼接后的全景流
                                - **图像拼接**：支持三种拼接策略
                                - **缺陷检测**：基于 YOLO 模型的工业缺陷检测
                                - **拼接配置**：支持手动调节拼接参数并持久化

                                ### 拼接策略说明
                                | 策略 | 说明 | 适用场景 |
                                |------|------|----------|
                                | `simple` | 简单水平拼接，支持融合 | 摄像头位置固定，无需对齐 |
                                | `auto` | 自动特征点检测拼接 | 摄像头位置不固定，需要自动对齐 |
                                | `manual` | 手动调节拼接参数 | 需要精确控制拼接效果 |

                                ### API 响应格式
                                所有接口返回统一的 JSON 格式：
                                ```json
                                {
                                  "status": "success | error | warning",
                                  "data": { ... },
                                  "message": "错误信息（仅错误时）"
                                }
                                ```
                                """)
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("Edge Vision Team")
                                .email("support@edge-vision.com"))
                        .license(new License()
                                .name("MIT License")
                                .url("https://opensource.org/licenses/MIT")));
    }

    /**
     * 自定义 API 响应格式
     * 为所有接口添加统一的响应示例
     */
    @Bean
    public OpenApiCustomizer globalResponseCustomizer() {
        return openApi -> {
            openApi.getPaths().forEach((path, pathItem) -> {
                // 为 GET 操作添加成功响应示例
                if (pathItem.getGet() != null) {
                    pathItem.getGet().getResponses().addApiResponse("200", createSuccessResponse());
                }
                // 为 POST 操作添加成功响应示例
                if (pathItem.getPost() != null) {
                    pathItem.getPost().getResponses().addApiResponse("200", createSuccessResponse());
                    pathItem.getPost().getResponses().addApiResponse("400", createBadRequestResponse());
                }
            });
        };
    }

    private ApiResponse createSuccessResponse() {
        Schema<?> schema = new Schema<>();
        schema.setType("object");
        schema.setProperties(Map.of(
                "status", new Schema<>().type("string").description("状态: success/error/warning").example("success"),
                "data", new Schema<>().type("object").description("响应数据"),
                "message", new Schema<>().type("string").description("消息（可选）").example("操作成功")
        ));

        return new ApiResponse()
                .description("成功")
                .content(new Content()
                        .addMediaType("application/json",
                                new MediaType().schema(schema)));
    }

    private ApiResponse createBadRequestResponse() {
        Schema<?> schema = new Schema<>();
        schema.setType("object");
        schema.setProperties(Map.of(
                "status", new Schema<>().type("string").description("状态").example("error"),
                "message", new Schema<>().type("string").description("错误信息").example("请求参数错误")
        ));

        return new ApiResponse()
                .description("请求错误")
                .content(new Content()
                        .addMediaType("application/json",
                                new MediaType().schema(schema)));
    }
}
