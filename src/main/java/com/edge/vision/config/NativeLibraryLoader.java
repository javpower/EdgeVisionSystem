package com.edge.vision.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Native Library Loader
 * 负责在 native-image 环境中加载 JNI 库
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

        boolean isNativeImage = System.getProperty("org.graalvm.nativeimage.imagecode") != null;

        if (!isNativeImage) {
            // JAR 模式下，使用 openpnp 的自动加载
            logger.info("Running in JAR mode, loading native libraries via openpnp...");
            try {
                nu.pattern.OpenCV.loadShared();
                logger.info("OpenCV loaded successfully via openpnp (JAR mode)");
            } catch (Exception e) {
                logger.warn("Failed to load OpenCV via openpnp: {}", e.getMessage());
            }
            // ONNX Runtime 在 JAR 模式下会自动加载
            loaded = true;
            return;
        }

        logger.info("Running in native-image mode, attempting to preload native libraries...");

        // 获取可执行文件所在目录
        String appDir = getApplicationDirectory();
        logger.info("Application directory: {}", appDir);

        // 加载 ONNX Runtime
        loadOnnxRuntime(appDir);

        // 加载 OpenCV (native-image 模式下手动加载)
        loadOpenCVNative(appDir);

        loaded = true;
    }

    /**
     * 获取应用程序目录
     */
    private static String getApplicationDirectory() {
        // 尝试多种方式获取应用目录
        String path = System.getProperty("user.dir");

        String osName = System.getProperty("os.name").toLowerCase();
        String onnxLibName;
        String opencvLibName;

        if (osName.contains("win")) {
            onnxLibName = "onnxruntime.dll";
            opencvLibName = "opencv_java470.dll";
        } else if (osName.contains("mac")) {
            onnxLibName = "libonnxruntime.dylib";
            opencvLibName = "libopencv_java470.dylib";
        } else {
            onnxLibName = "libonnxruntime.so";
            opencvLibName = "libopencv_java470.so";
        }

        // 检查当前目录
        File appDir = new File(path);
        if (new File(appDir, onnxLibName).exists() ||
            new File(appDir, opencvLibName).exists()) {
            logger.debug("Found libraries in current directory: {}", path);
            return path;
        }

        // 检查父目录（用于 macOS .app 包或其他情况）
        File parentDir = appDir.getParentFile();
        if (parentDir != null) {
            if (new File(parentDir, onnxLibName).exists() ||
                new File(parentDir, opencvLibName).exists()) {
                logger.debug("Found libraries in parent directory: {}", parentDir.getAbsolutePath());
                return parentDir.getAbsolutePath();
            }
        }

        logger.debug("Library detection: using current directory: {}", path);
        return path;
    }

    /**
     * 加载 ONNX Runtime native 库
     */
    private static void loadOnnxRuntime(String appDir) {
        String osName = System.getProperty("os.name").toLowerCase();
        String libFileName;
        String altLibFileName; // GPU版本的备用文件名

        if (osName.contains("win")) {
            libFileName = "onnxruntime.dll";
            altLibFileName = null;
        } else if (osName.contains("mac")) {
            libFileName = "libonnxruntime.dylib";
            altLibFileName = null;
        } else {
            libFileName = "libonnxruntime.so";
            altLibFileName = "libonnxruntime_gpu.so"; // Linux GPU版本可能有不同的文件名
        }

        logger.info("Attempting to load ONNX Runtime library: {} from directory: {}", libFileName, appDir);

        // 首先尝试从应用目录加载
        File libFile = new File(appDir, libFileName);
        logger.info("Looking for library at: {}", libFile.getAbsolutePath());
        logger.info("Library exists: {}", libFile.exists());

        if (libFile.exists()) {
            try {
                System.load(libFile.getAbsolutePath());
                logger.info("ONNX Runtime loaded successfully from: {}", libFile.getAbsolutePath());
                return;
            } catch (UnsatisfiedLinkError e) {
                logger.warn("Failed to load ONNX Runtime from {}: {}", libFile, e.getMessage());
                logger.warn("Error details: {}", e.toString());
            }
        }

        // 尝试备用文件名（Linux GPU版本）
        if (altLibFileName != null) {
            File altLibFile = new File(appDir, altLibFileName);
            logger.info("Looking for alternative library at: {}", altLibFile.getAbsolutePath());
            if (altLibFile.exists()) {
                try {
                    System.load(altLibFile.getAbsolutePath());
                    logger.info("ONNX Runtime loaded successfully from: {}", altLibFile.getAbsolutePath());
                    return;
                } catch (UnsatisfiedLinkError e) {
                    logger.warn("Failed to load ONNX Runtime from {}: {}", altLibFile, e.getMessage());
                }
            } else {
                logger.info("Alternative library not found at: {}", altLibFile.getAbsolutePath());
            }
        }

        // 回退到从系统库路径加载
        logger.info("Attempting to load from system library path...");
        try {
            System.loadLibrary("onnxruntime");
            logger.info("ONNX Runtime loaded successfully from system library path");
        } catch (UnsatisfiedLinkError e) {
            // 尝试GPU版本的库名（Linux）
            if (altLibFileName != null) {
                try {
                    String libName = altLibFileName.replace(".so", "").replace("lib", "");
                    System.loadLibrary(libName);
                    logger.info("ONNX Runtime GPU loaded successfully from system library path");
                    return;
                } catch (UnsatisfiedLinkError e2) {
                    // 继续抛出原始错误
                }
            }
            logger.error("Failed to load ONNX Runtime library. Please ensure {} is in the application directory or system library path", libFileName);
            logger.error("Application directory was: {}", appDir);
            logger.error("Files in application directory:");
            File dir = new File(appDir);
            if (dir.exists() && dir.isDirectory()) {
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        logger.error("  - {}", f.getName());
                    }
                }
            }
            throw new RuntimeException("ONNX Runtime native library not found: " + libFileName, e);
        }
    }

    /**
     * 加载 OpenCV native 库 (native-image 模式下手动加载)
     */
    private static void loadOpenCVNative(String appDir) {
        String osName = System.getProperty("os.name").toLowerCase();
        String libFileName;

        // openpnp 的 OpenCV 在 Windows 上没有 lib 前缀
        if (osName.contains("win")) {
            libFileName = "opencv_java470.dll";
        } else if (osName.contains("mac")) {
            libFileName = "libopencv_java470.dylib";
        } else {
            libFileName = "libopencv_java470.so";
        }

        // 首先尝试从应用目录加载
        File libFile = new File(appDir, libFileName);
        if (libFile.exists()) {
            try {
                System.load(libFile.getAbsolutePath());
                logger.info("OpenCV loaded successfully from: {}", libFile.getAbsolutePath());
                return;
            } catch (UnsatisfiedLinkError e) {
                logger.warn("Failed to load OpenCV from {}: {}", libFile, e.getMessage());
            }
        }

        // 回退到从系统库路径加载
        try {
            System.loadLibrary("opencv_java470");
            logger.info("OpenCV loaded successfully from system library path");
        } catch (UnsatisfiedLinkError e) {
            logger.error("Failed to load OpenCV library. Please ensure {} is in the application directory or system library path", libFileName);
            throw new RuntimeException("OpenCV native library not found: " + libFileName, e);
        }
    }

    /**
     * 加载 OpenCV native 库 (JAR 模式下使用 openpnp)
     */
    private static void loadOpenCV() {
        try {
            // openpnp 的 OpenCV 加载方式
            nu.pattern.OpenCV.loadShared();
            logger.info("OpenCV loaded successfully via openpnp (JAR mode)");
        } catch (UnsatisfiedLinkError e) {
            logger.error("Failed to load OpenCV library", e);
            throw new RuntimeException("Failed to load OpenCV library", e);
        } catch (Exception e) {
            logger.error("Failed to load OpenCV", e);
            throw new RuntimeException("Failed to load OpenCV", e);
        }
    }

    /**
     * 将库文件从 JAR 中提取到临时目录
     * （备用方案，如果需要的话）
     */
    private static void extractLibraryToTemp(String resourcePath, String targetFileName) {
        try {
            Path tempDir = Path.of(System.getProperty("java.io.tmpdir"), "edge-vision-native");
            Files.createDirectories(tempDir);

            Path targetFile = tempDir.resolve(targetFileName);

            if (Files.exists(targetFile)) {
                return; // 已经存在
            }

            try (var in = NativeLibraryLoader.class.getResourceAsStream(resourcePath)) {
                if (in == null) {
                    logger.warn("Library resource not found: {}", resourcePath);
                    return;
                }
                Files.copy(in, targetFile, StandardCopyOption.REPLACE_EXISTING);
                logger.info("Extracted library to: {}", targetFile);
            }
        } catch (IOException e) {
            logger.warn("Failed to extract library {}: {}", resourcePath, e.getMessage());
        }
    }
}
