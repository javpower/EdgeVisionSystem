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
        boolean isNativeImage = System.getProperty("org.graalvm.nativeimage.imagecode") != null;

        if (isNativeImage) {
            // Native-image 模式：从 classpath 提取并加载
            loadOnnxRuntimeFromResources();
        } else {
            // JAR 模式：从文件系统加载
            loadOnnxRuntimeFromFileSystem(appDir);
        }
    }

    /**
     * 在 native-image 模式下从 classpath 资源提取并加载 ONNX Runtime
     */
    private static void loadOnnxRuntimeFromResources() {
        String osName = System.getProperty("os.name").toLowerCase();
        String arch = System.getProperty("os.arch").toLowerCase();
        String platformDir;

        // 确定平台目录
        if (osName.contains("win")) {
            if (arch.contains("64")) {
                platformDir = "win-x64";
            } else {
                platformDir = "win-x86";
            }
        } else if (osName.contains("mac")) {
            if (arch.contains("aarch64") || arch.contains("arm64")) {
                platformDir = "osx-aarch64";
            } else {
                platformDir = "osx-x64";
            }
        } else {
            // Linux
            if (arch.contains("aarch64") || arch.contains("arm64")) {
                platformDir = "linux-aarch64";
            } else {
                platformDir = "linux-x64";
            }
        }

        String jniLibName = "libonnxruntime4j_jni" + getLibraryExtension();
        String mainLibName = "libonnxruntime" + getLibraryExtension();

        logger.info("Loading ONNX Runtime for platform: {}", platformDir);

        // 创建临时目录用于存放提取的库
        Path tempDir;
        try {
            tempDir = Files.createTempDirectory("onnxruntime-");
            // 设置 JVM 退出时删除临时目录
            tempDir.toFile().deleteOnExit();
        } catch (IOException e) {
            throw new RuntimeException("Failed to create temp directory for ONNX Runtime libraries", e);
        }

        // 提取 JNI 绑定库
        Path jniLibPath = extractLibrary("ai/onnxruntime/native/" + platformDir + "/" + jniLibName, tempDir, jniLibName);
        // 提取主 ONNX Runtime 库
        Path mainLibPath = extractLibrary("ai/onnxruntime/native/" + platformDir + "/" + mainLibName, tempDir, mainLibName);

        // 重要：在 native-image 模式下，需要设置 java.library.path
        // 以便 ONNX Runtime 的内部加载机制能找到库
        System.setProperty("java.library.path", tempDir.toAbsolutePath().toString());

        // 尝试使用 ONNX Runtime 的内部加载机制
        try {
            // 首先尝试让 ONNX Runtime 自己加载（它会使用 java.library.path）
            logger.info("Attempting to load ONNX Runtime using internal loader...");
            ai.onnxruntime.OrtEnvironment.class.getClassLoader().loadClass("ai.onnxruntime.NativeLibraryName");
            logger.info("ONNX Runtime class loaded successfully");
            // 注意：实际的库加载会在 OrtEnvironment 初始化时自动进行
            return;
        } catch (Exception e) {
            logger.debug("Could not trigger ONNX Runtime auto-loading: {}", e.getMessage());
        }

        // 如果自动加载失败，手动加载 JNI 绑定库
        if (jniLibPath != null) {
            try {
                System.load(jniLibPath.toAbsolutePath().toString());
                logger.info("ONNX Runtime JNI library loaded from: {}", jniLibPath);
            } catch (UnsatisfiedLinkError e) {
                logger.warn("Failed to load JNI library: {}", e.getMessage());
            }
        }

        // 然后加载主 ONNX Runtime 库
        if (mainLibPath != null) {
            try {
                System.load(mainLibPath.toAbsolutePath().toString());
                logger.info("ONNX Runtime main library loaded from: {}", mainLibPath);
            } catch (UnsatisfiedLinkError e) {
                logger.warn("Failed to load main library: {}", e.getMessage());
            }
        }
    }

    /**
     * 在 JAR 模式下从文件系统加载 ONNX Runtime
     */
    private static void loadOnnxRuntimeFromFileSystem(String appDir) {
        String osName = System.getProperty("os.name").toLowerCase();
        String libFileName;
        String jniLibFileName;
        String altLibFileName;

        if (osName.contains("win")) {
            libFileName = "onnxruntime.dll";
            jniLibFileName = "onnxruntime4j_jni.dll";
            altLibFileName = null;
        } else if (osName.contains("mac")) {
            libFileName = "libonnxruntime.dylib";
            jniLibFileName = "libonnxruntime4j_jni.dylib";
            altLibFileName = null;
        } else {
            libFileName = "libonnxruntime.so";
            jniLibFileName = "libonnxruntime4j_jni.so";
            altLibFileName = "libonnxruntime-gpu.so";
        }

        // 1. 首先加载 JNI 库
        File jniLibFile = new File(appDir, jniLibFileName);
        if (jniLibFile.exists()) {
            try {
                System.load(jniLibFile.getAbsolutePath());
                logger.info("ONNX Runtime JNI library loaded from: {}", jniLibFile);
            } catch (UnsatisfiedLinkError e) {
                logger.warn("Failed to load JNI library: {}", e.getMessage());
            }
        }

        // 2. 然后加载主 ONNX Runtime 库
        File libFile = new File(appDir, libFileName);
        if (libFile.exists()) {
            try {
                System.load(libFile.getAbsolutePath());
                logger.info("ONNX Runtime loaded from: {}", libFile);
                return;
            } catch (UnsatisfiedLinkError e) {
                logger.warn("Failed to load ONNX Runtime: {}", e.getMessage());
            }
        }

        // 尝试备用文件名（GPU 版本）
        if (altLibFileName != null) {
            File altLibFile = new File(appDir, altLibFileName);
            if (altLibFile.exists()) {
                try {
                    System.load(altLibFile.getAbsolutePath());
                    logger.info("ONNX Runtime GPU loaded from: {}", altLibFile);
                    return;
                } catch (UnsatisfiedLinkError e) {
                    logger.warn("Failed to load ONNX Runtime GPU: {}", e.getMessage());
                }
            }
        }

        // 回退到系统库路径
        try {
            System.loadLibrary("onnxruntime");
            logger.info("ONNX Runtime loaded from system library path");
        } catch (UnsatisfiedLinkError e) {
            logger.error("Failed to load ONNX Runtime library. Please ensure {} is available", libFileName);
            throw new RuntimeException("ONNX Runtime native library not found", e);
        }
    }

    /**
     * 获取库文件扩展名
     */
    private static String getLibraryExtension() {
        String osName = System.getProperty("os.name").toLowerCase();
        if (osName.contains("win")) {
            return ".dll";
        } else if (osName.contains("mac")) {
            return ".dylib";
        } else {
            return ".so";
        }
    }

    /**
     * 从 classpath 资源提取库文件到指定目录
     */
    private static Path extractLibrary(String resourcePath, Path targetDir, String targetFileName) {
        try (var in = NativeLibraryLoader.class.getResourceAsStream("/" + resourcePath)) {
            if (in == null) {
                logger.warn("Library resource not found: {}", resourcePath);
                return null;
            }

            Path targetFile = targetDir.resolve(targetFileName);
            Files.copy(in, targetFile, StandardCopyOption.REPLACE_EXISTING);
            // 设置可执行权限（Linux/macOS）
            targetFile.toFile().setExecutable(true);
            logger.debug("Extracted library to: {}", targetFile);
            return targetFile;
        } catch (IOException e) {
            logger.warn("Failed to extract library {}: {}", resourcePath, e.getMessage());
            return null;
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
