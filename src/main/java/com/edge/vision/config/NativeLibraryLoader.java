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
        // 定义需要查找的库名称
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

        // 1. 首先检查工作目录 (user.dir) - 最常见的情况
        String workDir = System.getProperty("user.dir");
        File dir = new File(workDir);
        if (new File(dir, onnxLibName).exists() ||
            new File(dir, opencvLibName).exists()) {
            logger.info("Found libraries in working directory: {}", workDir);
            return workDir;
        }

        // 2. 在 Native Image 中，尝试获取可执行文件所在目录
        boolean isNativeImage = System.getProperty("org.graalvm.nativeimage.imagecode") != null;

        if (isNativeImage) {
            // 尝试获取进程的可执行文件路径
            try {
                // Linux/Unix: /proc/self/exe
                File exePath = new File("/proc/self/exe");
                if (exePath.exists()) {
                    File realExe = exePath.getCanonicalFile();
                    File exeDir = realExe.getParentFile();
                    if (exeDir != null) {
                        logger.info("Found executable directory via /proc/self/exe: {}", exeDir);
                        return exeDir.getAbsolutePath();
                    }
                }
            } catch (IOException e) {
                logger.debug("Failed to resolve /proc/self/exe: {}", e.getMessage());
            }

            // 尝试使用 java.home (在 Native Image 中可能指向可执行文件)
            String javaHome = System.getProperty("java.home");
            if (javaHome != null && !javaHome.isEmpty()) {
                File homeFile = new File(javaHome);
                if (homeFile.exists()) {
                    logger.info("Using java.home as application directory: {}", javaHome);
                    return javaHome;
                }
            }
        }

        // 3. 回退到工作目录
        logger.info("Falling back to working directory: {}", workDir);
        return workDir;
    }

    /**
     * 加载 ONNX Runtime native 库
     */
    private static void loadOnnxRuntime(String appDir) {
        // Native-image 模式下，也从文件系统加载（库文件与可执行文件放在一起）
        loadOnnxRuntimeFromFileSystem(appDir);
    }

    /**
     * 从文件系统加载 ONNX Runtime 库
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
            altLibFileName = "libonnxruntime_gpu.so";
        }

        logger.info("Loading ONNX Runtime from directory: {}", appDir);

        // 1. 首先加载 JNI 库
        File jniLibFile = new File(appDir, jniLibFileName);
        logger.info("JNI library: {}, exists: {}", jniLibFile.getAbsolutePath(), jniLibFile.exists());
        if (jniLibFile.exists()) {
            try {
                logger.info("Attempting to load JNI library from: {}", jniLibFile.getAbsolutePath());
                System.load(jniLibFile.getAbsolutePath());
                logger.info("ONNX Runtime JNI library loaded successfully from: {}", jniLibFile);
            } catch (UnsatisfiedLinkError e) {
                logger.warn("Failed to load JNI library: {}", e.getMessage());
                logger.warn("Error details: ", e);
            }
        }

        // 2. 然后加载主 ONNX Runtime 库
        File libFile = new File(appDir, libFileName);
        logger.info("Main library: {}, exists: {}", libFile.getAbsolutePath(), libFile.exists());
        if (libFile.exists()) {
            try {
                logger.info("Attempting to load main library from: {}", libFile.getAbsolutePath());
                System.load(libFile.getAbsolutePath());
                logger.info("ONNX Runtime loaded successfully from: {}", libFile);
                return;
            } catch (UnsatisfiedLinkError e) {
                logger.warn("Failed to load ONNX Runtime: {}", e.getMessage());
                logger.warn("Error details: ", e);
            }
        }

        // 尝试备用文件名（GPU 版本）
        if (altLibFileName != null) {
            File altLibFile = new File(appDir, altLibFileName);
            logger.info("Alt library (GPU): {}, exists: {}", altLibFile.getAbsolutePath(), altLibFile.exists());
            if (altLibFile.exists()) {
                try {
                    logger.info("Attempting to load GPU library from: {}", altLibFile.getAbsolutePath());
                    System.load(altLibFile.getAbsolutePath());
                    logger.info("ONNX Runtime GPU loaded successfully from: {}", altLibFile);
                    return;
                } catch (UnsatisfiedLinkError e) {
                    logger.warn("Failed to load ONNX Runtime GPU: {}", e.getMessage());
                    logger.warn("Error details: ", e);
                }
            }
        }

        // 回退到系统库路径
        logger.info("Attempting to load from system library path");
        try {
            System.loadLibrary("onnxruntime");
            logger.info("ONNX Runtime loaded from system library path");
        } catch (UnsatisfiedLinkError e) {
            logger.error("Failed to load ONNX Runtime library. Please ensure {} is in the application directory or system library path", libFileName);
            throw new RuntimeException("ONNX Runtime native library not found", e);
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

        logger.info("Loading OpenCV from directory: {}, library: {}", appDir, libFileName);

        // 首先尝试从应用目录加载
        File libFile = new File(appDir, libFileName);
        logger.info("Library file exists: {}, absolute path: {}, can read: {}",
                    libFile.exists(), libFile.getAbsolutePath(), libFile.canRead());

        if (libFile.exists()) {
            try {
                logger.info("Attempting to load OpenCV from: {}", libFile.getAbsolutePath());
                System.load(libFile.getAbsolutePath());
                logger.info("OpenCV loaded successfully from: {}", libFile.getAbsolutePath());
                return;
            } catch (UnsatisfiedLinkError e) {
                logger.error("Failed to load OpenCV from {}: {}", libFile, e.getMessage());
                logger.error("Error details: ", e);
                // 继续尝试其他方法
            }
        }

        // 回退到从系统库路径加载
        logger.info("Attempting to load OpenCV from system library path");
        try {
            System.loadLibrary("opencv_java470");
            logger.info("OpenCV loaded successfully from system library path");
        } catch (UnsatisfiedLinkError e) {
            logger.error("Failed to load OpenCV library. Please ensure {} is in the application directory or system library path", libFileName);
            logger.error("java.library.path: {}", System.getProperty("java.library.path"));
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
}
