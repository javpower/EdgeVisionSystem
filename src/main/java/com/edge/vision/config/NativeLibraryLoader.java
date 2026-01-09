package com.edge.vision.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Native Library Loader
 * 负责在 JAR 模式下加载 JNI 库
 *
 * JNI 库（OpenCV、ONNX Runtime）会自动从依赖的 JAR 中加载：
 * - OpenCV: 通过 nu.pattern.OpenCV.loadShared() 自动加载
 * - ONNX Runtime: 通过 ai.onnxruntime.OrtEnvironment 自动加载
 */
public class NativeLibraryLoader {

    private static final Logger logger = LoggerFactory.getLogger(NativeLibraryLoader.class);

    private static boolean loaded = false;

    /**
     * 预加载所有必需的 native 库
     * 必须在任何使用这些库的代码之前调用
     */
    public static synchronized void loadNativeLibraries() {
        if (loaded) {
            return;
        }

        logger.info("Loading native libraries from JAR classpath...");

        // OpenCV 会自动从 classpath 加载对应平台的库
        try {
            nu.pattern.OpenCV.loadShared();
            logger.info("OpenCV loaded successfully");
        } catch (Exception e) {
            logger.error("Failed to load OpenCV", e);
            throw new RuntimeException("Failed to load OpenCV native library", e);
        }

        // ONNX Runtime 会在首次使用 ai.onnxruntime.OrtEnvironment 时自动加载
        logger.info("ONNX Runtime will be loaded on first use");

        loaded = true;
        logger.info("All native libraries loaded successfully");
    }
}
