package com.edge.vision.core.camera;

import MvCameraControlWrapper.CameraControlException;
import MvCameraControlWrapper.MvCameraControl;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;

import static MvCameraControlWrapper.MvCameraControlDefines.*;

/**
 * 海康威视工业相机源（MVS SDK）
 * 支持通过 IP 地址连接 GigE 相机
 *
 * 使用方式: "mvs:0
 */
public class MVSCameraSource implements CameraSource {
    private static final Logger logger = LoggerFactory.getLogger(MVSCameraSource.class);
    private static boolean sdkInitialized = false;
    private static final Object sdkLock = new Object();

    private final String index;
    private Handle hCamera;
    private boolean isOpened;
    private MV_FRAME_OUT_INFO frameInfo;
    private byte[] imageBuffer;

    /**
     * 创建 MVS 相机源
     * @param index GigE 相机的 IP 地址
     */
    public MVSCameraSource(String index) {
        this.index = index;
        this.hCamera = null;
        this.isOpened = false;
    }

    static {
        // 尝试加载本地库
        loadNativeLibrary();
    }

    /**
     * 加载 MVS SDK 本地库
     * 支持 Windows/Linux/macOS，自动从项目目录查找
     */
    private static void loadNativeLibrary() {
        String osName = System.getProperty("os.name").toLowerCase();
        String osArch = System.getProperty("os.arch").toLowerCase();
        String libFileName;

        // 根据操作系统和架构确定库文件名和路径
        if (osName.contains("win")) {
            libFileName = "MvCameraControl.dll";
        } else if (osName.contains("mac") || osName.contains("darwin")) {
            libFileName = "libMvCameraControl.dylib";
        } else {
            libFileName = "libMvCameraControl.so";
        }

        // 按优先级尝试多个路径（优先匹配架构特定的路径）
        String[] searchPaths;
        if (osName.contains("win")) {
            // Windows: 根据 JVM 架构选择对应的 DLL
            if (osArch.contains("amd64") || osArch.contains("x86_64") || osArch.contains("x64")) {
                // 64-bit JVM
                searchPaths = new String[]{
                    "./mvs/lib/win64/MvCameraControl.dll",    // 项目中的 64-bit DLL
                    "./lib/win64/MvCameraControl.dll",
                    "./mvs/lib/win64",
                    "./mvs/lib",
                    "./lib",
                    "./libs",
                    "./native",
                };
            } else {
                // 32-bit JVM
                searchPaths = new String[]{
                    "./mvs/lib/win32/MvCameraControl.dll",    // 项目中的 32-bit DLL
                    "./lib/win32/MvCameraControl.dll",
                    "./mvs/lib/win32",
                    "./mvs/lib",
                    "./lib",
                    "./libs",
                    "./native",
                };
            }
        } else if (osName.contains("mac") || osName.contains("darwin")) {
            searchPaths = new String[]{
                "./mvs/lib/macosx/libMvCameraControl.dylib",
                "./lib/macosx/libMvCameraControl.dylib",
                "./mvs/lib",
                "./lib",
                "./libs",
                "./native",
            };
        } else {
            // Linux
            if (osArch.contains("amd64") || osArch.contains("x86_64") || osArch.contains("x64")) {
                searchPaths = new String[]{
                    "./mvs/lib/linux64/libMvCameraControl.so",
                    "./lib/linux64/libMvCameraControl.so",
                    "./mvs/lib/linux64",
                    "./mvs/lib",
                    "./lib",
                    "./libs",
                    "./native",
                };
            } else {
                searchPaths = new String[]{
                    "./mvs/lib/linux32/libMvCameraControl.so",
                    "./lib/linux32/libMvCameraControl.so",
                    "./mvs/lib/linux32",
                    "./mvs/lib",
                    "./lib",
                    "./libs",
                    "./native",
                };
            }
        }

        boolean loaded = false;
        for (String path : searchPaths) {
            try {
                java.io.File file = new java.io.File(path);
                if (file.exists() && file.isFile()) {
                    System.load(file.getAbsolutePath());
                    logger.info("Loaded MVS native library from: {}", path);
                    loaded = true;
                    break;
                }
            } catch (UnsatisfiedLinkError e) {
                logger.debug("Failed to load from {}: {}", path, e.getMessage());
            } catch (Exception e) {
                logger.debug("Failed to load from {}: {}", path, e.getMessage());
            }
        }

        // 如果所有路径都失败，尝试通过 java.library.path 加载（最后手段）
        if (!loaded) {
            try {
                System.loadLibrary(libFileName.replace(".dll", "").replace(".so", "").replace(".dylib", ""));
                logger.info("Loaded MVS native library from system path");
                loaded = true;
            } catch (UnsatisfiedLinkError e) {
                logger.warn("Failed to load MVS native library: {}. Please ensure {} is in the project directory (mvs/lib/{}).",
                    libFileName, libFileName, osName.contains("win") ? "win64" : osName.contains("mac") ? "macosx" : "linux64");
                logger.debug("Load error details: {}", e.getMessage());
            }
        }
    }

    /**
     * 初始化 SDK（线程安全，只初始化一次）
     */
    private static void initializeSDK() throws Exception {
        synchronized (sdkLock) {
            if (!sdkInitialized) {
                int ret = MvCameraControl.MV_CC_Initialize();
                if (ret != MV_OK) {
                    throw new RuntimeException(String.format("Failed to initialize MVS SDK, errcode: [0x%x]", ret));
                }
                sdkInitialized = true;
                logger.info("MVS SDK initialized successfully, version: {}",
                    MvCameraControl.MV_CC_GetSDKVersion());
                // 注册关闭钩子
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try {
                        MvCameraControl.MV_CC_Finalize();
                        logger.info("MVS SDK finalized");
                    } catch (Exception e) {
                        logger.warn("Error finalizing MVS SDK", e);
                    }
                }));
            }
        }
    }

    @Override
    public boolean open() {
        try {
            // 初始化 SDK
            initializeSDK();

            // 枚举所有 GigE 设备
            ArrayList<MV_CC_DEVICE_INFO> deviceList = MvCameraControl.MV_CC_EnumDevices(MV_GIGE_DEVICE);
            if (deviceList == null || deviceList.isEmpty()) {
                logger.error("No MVS GigE devices found");
                return false;
            }

            // 查找匹配 IP 地址的设备
            MV_CC_DEVICE_INFO targetDevice = null;
            for (int i = 0; i < deviceList.size(); i++) {
                MV_CC_DEVICE_INFO device=deviceList.get(i);
                if (device.transportLayerType == MV_GIGE_DEVICE) {
                    String deviceIp = device.gigEInfo.currentIp.trim();
                    logger.debug("Found MVS device with IP: {}", deviceIp);
                    if (i==Integer.parseInt(index)) {
                        targetDevice = device;
                        logger.info("Found target MVS device: index={},IP={}, Model={}, Name={}",
                                index,
                                deviceIp,
                                device.gigEInfo.modelName,
                                device.gigEInfo.userDefinedName);
                        break;
                    }
                }
            }

            if (targetDevice == null) {
                logger.error("No MVS device found with IP: {}. Available devices: {}",
                    index, deviceList.size());
                return false;
            }

            // 创建设备句柄
            hCamera = MvCameraControl.MV_CC_CreateHandle(targetDevice);
            if (hCamera == null) {
                logger.error("Failed to create handle for MVS device: {}", index);
                return false;
            }

            // 打开设备
            int ret = MvCameraControl.MV_CC_OpenDevice(hCamera);
            if (ret != MV_OK) {
                logger.error("Failed to open MVS device: {}, errcode: [0x{}]", index, Integer.toHexString(ret));
                MvCameraControl.MV_CC_DestroyHandle(hCamera);
                hCamera = null;
                return false;
            }

            // 关闭触发模式（连续采集模式）
            ret = MvCameraControl.MV_CC_SetEnumValueByString(hCamera, "TriggerMode", "Off");
            if (ret != MV_OK) {
                logger.warn("Failed to set TriggerMode to Off, errcode: [0x{}]", Integer.toHexString(ret));
            }

            // 获取 PayloadSize（用于分配图像缓冲区）
            MVCC_INTVALUE stParam = new MVCC_INTVALUE();
            ret = MvCameraControl.MV_CC_GetIntValue(hCamera, "PayloadSize", stParam);
            if (ret == MV_OK) {
                imageBuffer = new byte[(int) stParam.curValue];
            } else {
                logger.warn("Failed to get PayloadSize, using default buffer size");
                imageBuffer = new byte[1920 * 1080 * 3];
            }

            // 开始采集
            ret = MvCameraControl.MV_CC_StartGrabbing(hCamera);
            if (ret != MV_OK) {
                logger.error("Failed to start grabbing, errcode: [0x{}]", Integer.toHexString(ret));
                MvCameraControl.MV_CC_CloseDevice(hCamera);
                MvCameraControl.MV_CC_DestroyHandle(hCamera);
                hCamera = null;
                return false;
            }

            isOpened = true;
            logger.info("Successfully opened MVS camera: {}", index);
            return true;

        } catch (CameraControlException e) {
            logger.error("CameraControlException while opening MVS camera: {}", index, e);
            return false;
        } catch (Exception e) {
            logger.error("Unexpected error while opening MVS camera: {}", index, e);
            return false;
        }
    }

    @Override
    public Mat read() {
        if (!isOpened || hCamera == null) {
            logger.warn("MVS camera is not opened: {}", index);
            return null;
        }

        try {
            // 获取一帧图像（超时 1000ms）
            MV_FRAME_OUT_INFO frameInfo = new MV_FRAME_OUT_INFO();
            int ret = MvCameraControl.MV_CC_GetOneFrameTimeout(hCamera, imageBuffer, frameInfo, 1000);
            if (ret != MV_OK) {
                logger.debug("Failed to get frame, errcode: [0x{}]", Integer.toHexString(ret));
                return null;
            }

            this.frameInfo = frameInfo;

            // 转换为 OpenCV Mat
            return convertToMat(imageBuffer, frameInfo);

        } catch (Exception e) {
            logger.error("Error reading frame from MVS camera: {}", index, e);
            return null;
        }
    }

    /**
     * 将 MVS 图像数据转换为 OpenCV Mat
     */
    private Mat convertToMat(byte[] imageData, MV_FRAME_OUT_INFO info) {
        int width = info.width;
        int height = info.height;
        MvGvspPixelType pixelType = info.pixelType;

        // 获取实际数据长度
        int dataLen = info.frameLen;

        // 根据像素格式确定处理方式
        if (pixelType == MvGvspPixelType.PixelType_Gvsp_RGB8_Packed) {
            return convertRGB8(imageData, width, height, dataLen);
        } else if (pixelType == MvGvspPixelType.PixelType_Gvsp_BGR8_Packed) {
            return convertBGR8(imageData, width, height, dataLen);
        } else if (pixelType == MvGvspPixelType.PixelType_Gvsp_Mono8) {
            return convertMono8(imageData, width, height, dataLen);
        } else if (pixelType == MvGvspPixelType.PixelType_Gvsp_BayerGB8 ||
                   pixelType == MvGvspPixelType.PixelType_Gvsp_BayerGR8 ||
                   pixelType == MvGvspPixelType.PixelType_Gvsp_BayerBG8 ||
                   pixelType == MvGvspPixelType.PixelType_Gvsp_BayerRG8) {
            return convertBayer8(imageData, width, height, dataLen, pixelType);
        } else if (pixelType == MvGvspPixelType.PixelType_Gvsp_YUV422_Packed ||
                   pixelType == MvGvspPixelType.PixelType_Gvsp_YUV422_YUYV_Packed) {
            return convertYUV422(imageData, width, height, dataLen);
        } else {
            logger.warn("Unsupported pixel format: {}, treating as Mono8", pixelType);
            return convertMono8(imageData, width, height, dataLen);
        }
    }

    private Mat convertMono8(byte[] data, int width, int height, int dataLen) {
        Mat mat = new Mat(height, width, CvType.CV_8UC1);
        mat.put(0, 0, data, 0, dataLen);
        return mat;
    }

    private Mat convertBGR8(byte[] data, int width, int height, int dataLen) {
        Mat mat = new Mat(height, width, CvType.CV_8UC3);
        mat.put(0, 0, data, 0, dataLen);
        return mat;
    }

    private Mat convertRGB8(byte[] data, int width, int height, int dataLen) {
        Mat rgbMat = new Mat(height, width, CvType.CV_8UC3);
        rgbMat.put(0, 0, data, 0, dataLen);
        Mat bgrMat = new Mat();
        Imgproc.cvtColor(rgbMat, bgrMat, Imgproc.COLOR_RGB2BGR);
        rgbMat.release();
        return bgrMat;
    }

    private Mat convertBayer8(byte[] data, int width, int height, int dataLen, MvGvspPixelType pixelType) {
        Mat bayerMat = new Mat(height, width, CvType.CV_8UC1);
        bayerMat.put(0, 0, data, 0, dataLen);

        Mat rgbMat = new Mat();
        int code;
        if (pixelType == MvGvspPixelType.PixelType_Gvsp_BayerGB8) {
            code = Imgproc.COLOR_BayerGB2BGR;
        } else if (pixelType == MvGvspPixelType.PixelType_Gvsp_BayerGR8) {
            code = Imgproc.COLOR_BayerGR2BGR;
        } else if (pixelType == MvGvspPixelType.PixelType_Gvsp_BayerBG8) {
            code = Imgproc.COLOR_BayerBG2BGR;
        } else if (pixelType == MvGvspPixelType.PixelType_Gvsp_BayerRG8) {
            code = Imgproc.COLOR_BayerRG2BGR;
        } else {
            code = Imgproc.COLOR_BayerGB2BGR;
        }
        Imgproc.cvtColor(bayerMat, rgbMat, code);
        bayerMat.release();
        return rgbMat;
    }

    private Mat convertYUV422(byte[] data, int width, int height, int dataLen) {
        Mat yuvMat = new Mat(height, width, CvType.CV_8UC2);
        yuvMat.put(0, 0, data, 0, dataLen);

        Mat bgrMat = new Mat();
        Imgproc.cvtColor(yuvMat, bgrMat, Imgproc.COLOR_YUV2BGR_YUYV);
        yuvMat.release();
        return bgrMat;
    }

    @Override
    public void close() {
        if (!isOpened || hCamera == null) {
            return;
        }

        try {
            // 停止采集
            int ret = MvCameraControl.MV_CC_StopGrabbing(hCamera);
            if (ret != MV_OK) {
                logger.warn("Failed to stop grabbing, errcode: [0x{}]", Integer.toHexString(ret));
            }

            // 关闭设备
            ret = MvCameraControl.MV_CC_CloseDevice(hCamera);
            if (ret != MV_OK) {
                logger.warn("Failed to close device, errcode: [0x{}]", Integer.toHexString(ret));
            }

            // 销毁句柄
            ret = MvCameraControl.MV_CC_DestroyHandle(hCamera);
            if (ret != MV_OK) {
                logger.warn("Failed to destroy handle, errcode: [0x{}]", Integer.toHexString(ret));
            }

        } catch (Exception e) {
            logger.error("Error closing MVS camera: {}", index, e);
        } finally {
            hCamera = null;
            isOpened = false;
            imageBuffer = null;
            logger.info("MVS camera closed: {}", index);
        }
    }

    @Override
    public boolean isOpened() {
        return isOpened && hCamera != null;
    }

    /**
     * 获取相机 index 地址
     */
    public String getindex() {
        return index;
    }
}
