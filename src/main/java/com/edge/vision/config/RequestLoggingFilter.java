package com.edge.vision.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class RequestLoggingFilter extends OncePerRequestFilter {
    private static final Logger logger = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();

        // === 🚨 关键修改：如果是视频流，直接放行，不要缓存！ ===
        if (path.contains("/api/camera/stream")) {
            // 记录简单的开始日志
            // logger.info(">>> Streaming Request: {}", path);

            // 直接传递原始的 request 和 response，不使用 Wrapper
            filterChain.doFilter(request, response);

            // 视频流结束后（用户关闭页面时）代码会走到这里
            return;
        }
        // ==================================================

        // 下面是针对普通 API (JSON) 的原有逻辑
        ContentCachingRequestWrapper requestWrapper = new ContentCachingRequestWrapper(request);
        ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(response);

        long startTime = System.currentTimeMillis();

        try {
            // 记录请求信息
            logger.info("=== Incoming Request ===");
            logger.info("Method: {} {}", request.getMethod(), request.getRequestURI());
            // ... 其他日志逻辑 ...

            // 执行请求 (使用 Wrapper)
            filterChain.doFilter(requestWrapper, responseWrapper);

        } finally {
            long duration = System.currentTimeMillis() - startTime;

            // ... 这里是你原有的日志逻辑 ...
            // 记录请求体
            if (request.getMethod().equalsIgnoreCase("POST") || request.getMethod().equalsIgnoreCase("PUT")) {
                byte[] content = requestWrapper.getContentAsByteArray();
                if (content.length > 0) {
                    try {
                        String body = new String(content, StandardCharsets.UTF_8);
                        if (body.length() > 1000) body = body.substring(0, 1000) + "...";
                        logger.info("Request Body: {}", body);
                    } catch (Exception e) { /* 忽略 */ }
                }
            }

            // 记录响应体
            byte[] responseContent = responseWrapper.getContentAsByteArray();
            if (responseContent.length > 0 && responseContent.length < 5000) {
                try {
                    String responseBody = new String(responseContent, StandardCharsets.UTF_8);
                    // 只有 Content-Type 是文本时才打印，防止乱码
                    String contentType = response.getContentType();
                    if (contentType != null && (contentType.contains("json") || contentType.contains("text"))) {
                        logger.info("Response Body: {}", responseBody);
                    }
                } catch (Exception e) { /* 忽略 */ }
            }

            // 复制响应到原始响应 (这是最重要的一步，如果没有这一步，客户端收不到数据)
            responseWrapper.copyBodyToResponse();

            logger.info("Duration: {} ms | Status: {}", duration, response.getStatus());
            logger.info("======================");
        }
    }
}