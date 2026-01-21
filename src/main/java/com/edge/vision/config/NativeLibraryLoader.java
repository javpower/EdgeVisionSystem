package com.edge.vision.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Native Library Loader
 * 负责在 JAR 模式下加载 JNI 库
 *
 * 加载顺序：
 * 1. 优先从应用目录下的 libs/ 目录加载（适用于独立部署）
 * 2. 如果 libs/ 目录不存在，从 JAR classpath 中自动加载
 *
 * JNI 库说明：
 * - OpenCV: opencv_java470 (Windows: .dll, Linux: .so, macOS: .dylib)
 * - ONNX Runtime: onnxruntime, onnxruntime4j_jni, onnxruntime_providers_cuda
 */
public class NativeLibraryLoader {

    private static final Logger logger = LoggerFactory.getLogger(NativeLibraryLoader.class);

    private static boolean loaded = false;

    // 库文件名（不含扩展名）
    private static final String OPENCV_LIB_NAME = "opencv_java470";
    private static final String ONNXRUNTIME_LIB_NAME = "onnxruntime";
    private static final String ONNXRUNTIME_JNI_LIB_NAME = "onnxruntime4j_jni";

    /**
     * 预加载所有必需的 native 库
     * 必须在任何使用这些库的代码之前调用
     */
    public static synchronized void loadNativeLibraries() {
        if (loaded) {
            return;
        }

        logger.info("Loading native libraries...");

        // 尝试从 libs 目录加载（独立部署模式）
        boolean loadFromLibs = tryLoadFromLibsDirectory();

        if (!loadFromLibs) {
            // 回退到从 JAR classpath 加载
            logger.info("Loading native libraries from JAR classpath...");
            loadFromJar();
        }

        loaded = true;
        logger.info("All native libraries loaded successfully");
    }

    /**
     * 尝试从应用目录下的 libs 目录加载本地库
     * 适用于独立部署的场景（JAR 同目录有 libs 文件夹）
     *
     * @return true 如果成功从 libs 目录加载，false 否则
     */
    private static boolean tryLoadFromLibsDirectory() {
        // 获取应用目录（JAR 所在目录）
        String appDir = getApplicationDirectory();
        if (appDir == null) {
            logger.debug("Cannot determine application directory");
            return false;
        }

        Path libsDir = Paths.get(appDir, "libs");
        if (!Files.exists(libsDir)) {
            logger.debug("libs directory not found at: {}", libsDir);
            return false;
        }

        logger.info("Found libs directory at: {}", libsDir);

        try {
            String osName = System.getProperty("os.name").toLowerCase();
            String libPrefix = osName.contains("win") ? "" : "lib";
            String libExtension = osName.contains("win") ? ".dll" :
                                 osName.contains("mac") ? ".dylib" : ".so";

            // 加载 OpenCV
            String opencvLibName = libPrefix + OPENCV_LIB_NAME + libExtension;
            Path opencvPath = libsDir.resolve(opencvLibName);
            if (Files.exists(opencvPath)) {
                logger.info("Loading OpenCV from libs: {}", opencvPath);
                System.load(opencvPath.toAbsolutePath().toString());
                logger.info("OpenCV loaded successfully from libs directory");
            } else {
                logger.warn("OpenCV library not found in libs: {}", opencvLibName);
                return false;
            }

            // 加载 ONNX Runtime 核心库
            String onnxLibName = libPrefix + ONNXRUNTIME_LIB_NAME + libExtension;
            Path onnxPath = libsDir.resolve(onnxLibName);
            if (Files.exists(onnxPath)) {
                logger.info("Loading ONNX Runtime from libs: {}", onnxPath);
                System.load(onnxPath.toAbsolutePath().toString());
            } else {
                logger.warn("ONNX Runtime library not found in libs: {}", onnxLibName);
                // 不返回 false，因为 ONNX Runtime 可以在其他位置
            }

            // 加载 ONNX Runtime JNI 库
            String onnxJniLibName = libPrefix + ONNXRUNTIME_JNI_LIB_NAME + libExtension;
            Path onnxJniPath = libsDir.resolve(onnxJniLibName);
            if (Files.exists(onnxJniPath)) {
                logger.info("Loading ONNX Runtime JNI from libs: {}", onnxJniPath);
                System.load(onnxJniPath.toAbsolutePath().toString());
            } else {
                logger.warn("ONNX Runtime JNI library not found in libs: {}", onnxJniLibName);
            }

            return true;

        } catch (Exception e) {
            logger.warn("Failed to load from libs directory: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 从 JAR classpath 中加载本地库
     * 这是默认的加载方式，适用于开发环境
     */
    private static void loadFromJar() {
        // OpenCV 会自动从 classpath 加载对应平台的库
        try {
            nu.pattern.OpenCV.loadShared();
            logger.info("OpenCV loaded successfully from JAR");
        } catch (Exception e) {
            logger.error("Failed to load OpenCV from JAR", e);
            throw new RuntimeException("Failed to load OpenCV native library", e);
        }

        // ONNX Runtime 会在首次使用 ai.onnxruntime.OrtEnvironment 时自动加载
        logger.info("ONNX Runtime will be loaded on first use from JAR");
    }

    /**
     * 获取应用程序所在的目录
     *
     * @return 应用程序目录的绝对路径，如果无法确定则返回 null
     */
    private static String getApplicationDirectory() {
        try {
            // 获取 JAR 文件所在目录
            String path = NativeLibraryLoader.class.getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI()
                    .getPath();

            File file = new File(path);
            if (file.isDirectory()) {
                // 开发环境：class 文件在 target/classes/ 目录
                return file.getAbsolutePath();
            } else {
                // 生产环境：JAR 文件所在目录
                return file.getParentFile().getAbsolutePath();
            }
        } catch (Exception e) {
            logger.debug("Cannot determine application directory: {}", e.getMessage());
            return null;
        }
    }
}
