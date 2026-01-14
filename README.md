# 边缘视觉检测系统 (Edge Vision System)

基于Java + Spring Boot + OpenCV + ONNX Runtime 的工业级边缘端视觉检测系统，支持多摄像头图像拼接、YOLO目标检测、两段式质检流程。

## 功能特性

### ✅ 核心功能
- **两段式质检流程**：预检（类型识别）+ 确认（细节检测）
- **多摄像头支持**：本地USB摄像头、RTSP网络流
- **图像拼接**：Simple/Auto/Blend 三种策略模式
- **YOLO检测**：支持v8/v11 ONNX模型，CPU/GPU推理
- **数据管理**：本地存储 + 异步远程上传
- **实时视频流**：MJPEG格式，支持拼接后或单独摄像头流

### ✅ 设计模式
- **策略模式**：拼接算法可插拔
- **工厂模式**：相机源创建
- **模板方法**：ONNX推理引擎
- **建造者模式**：配置对象构建
- **观察者模式**：数据上传监听器

### ✅ 跨平台支持
- Windows (.exe)
- Linux (AppImage)
- macOS (.dmg)

## 项目结构

```
edge-vision-java/
├── src/main/java/com/edge/vision/
│   ├── EdgeVisionApplication.java          # 主应用类
│   ├── config/
│   │   └── YamlConfig.java                 # YAML配置类
│   ├── controller/
│   │   ├── CameraController.java           # 摄像头控制接口
│   │   └── InspectController.java          # 检测接口
│   ├── core/
│   │   ├── stitcher/                       # 图像拼接策略
│   │   │   ├── StitchStrategy.java
│   │   │   ├── SimpleStitchStrategy.java
│   │   │   ├── AutoStitchStrategy.java
│   │   │   └── BlendStitchDecorator.java
│   │   ├── infer/                          # ONNX推理引擎
│   │   │   ├── InferEngineTemplate.java
│   │   │   └── YOLOInferenceEngine.java
│   │   └── camera/                         # 相机源
│   │       ├── CameraSource.java
│   │       ├── CameraSourceFactory.java
│   │       ├── LocalCameraSource.java
│   │       └── RTSPCameraSource.java
│   ├── service/
│   │   ├── CameraService.java              # 摄像头服务
│   │   └── DataManager.java                # 数据管理服务
│   ├── model/                              # 数据模型
│   ├── repository/                         # 数据仓库
│   └── event/                              # 事件类
├── src/main/resources/
│   ├── application.yml                     # 配置文件
│   └── static/
│       └── index.html                      # 前端页面
├── data/                                   # 数据目录
├── models/                                 # ONNX模型
├── .github/workflows/
│   └── build.yml                           # GitHub Actions
└── pom.xml                                 # Maven配置
```

## 快速开始

### 1. 环境准备

- Java 17+
- Maven 3.8+
- OpenCV 4.8.0 (自动下载)
- ONNX Runtime 1.15.0 (自动下载)

### 2. 配置系统

编辑 `src/main/resources/application.yml`:

```yaml
edge-vision:
  system:
    device-id: "EDGE_001"           # 设备ID
    port: 8000                      # 服务端口
    save-local: true                # 是否本地保存
  
  cameras:
    sources: [0, 1]                 # 摄像头源（索引或RTSP URL）
  
  models:
    type-model: "models/type_classifier.onnx"    # 类型识别模型（可选）
    detail-model: "models/detail_detector.onnx"  # 细节检测模型（必须）
    conf-thres: 0.5                 # 置信度阈值
    iou-thres: 0.45                 # IOU阈值
    device: "CPU"                   # CPU 或 GPU
  
  # 类别名称映射
  class-names:
    type-model:
      0: "工件A"
      1: "工件B"
    detail-model:
      0: "孔缺陷"
      1: "螺丝缺陷"
  
  remote:
    upload-url: ""                  # 上传地址（空表示不上传）
    timeout: 5
```

### 3. 运行应用

#### 开发模式

```bash
# 编译
mvn clean compile

# 运行
mvn spring-boot:run

# 访问
# 前端页面: http://localhost:8000
# API文档: http://localhost:8000/swagger-ui.html
```

#### 生产模式（JAR）

```bash
# 打包
mvn package

# 运行
java -jar target/edge-vision-system-1.0.0.jar
```

#### 生产模式（Native）

```bash
# 构建原生镜像（需要GraalVM）
mvn -Pnative package

# Windows
./target/edge-vision.exe

# Linux/macOS
./target/edge-vision
```

## API 接口

### 摄像头控制

```http
POST /api/camera/start     # 启动摄像头
POST /api/camera/stop      # 停止摄像头
GET  /api/camera/status    # 获取状态
GET  /api/camera/stream    # MJPEG流（拼接后）
GET  /api/camera/stream/{id}  # MJPEG流（单个摄像头）
```

### 检测流程

```http
POST /api/inspect/pre-check    # 预检
POST /api/inspect/confirm      # 确认检测
GET  /api/inspect/records      # 查询记录
GET  /api/inspect/stats        # 统计信息
```

### 预检流程

1. **启动摄像头**: `POST /api/camera/start`
2. **开始预检**: `POST /api/inspect/pre-check`
   - 系统自动采集图像并拼接
   - 如果配置了类型识别模型，返回建议的工件类型
   - 返回拼接后的预览图
3. **用户确认**: 填写工件信息并确认
4. **执行检测**: `POST /api/inspect/confirm`
   - 系统执行细节检测
   - 返回检测结果和标注图
   - 自动保存记录和上传数据

## 前端使用

打开浏览器访问 `http://localhost:8000`，界面功能：

1. **启动摄像头**: 初始化并启动所有配置的摄像头
2. **开始检测**: 执行预检流程，显示预览图和确认表单
3. **填写信息**: 输入工件名称、批次号、操作员等信息
4. **确认检测**: 执行细节检测并显示结果
5. **查看结果**: 显示质检状态、缺陷列表、标注图

## 模型准备

### 类型识别模型（可选）

用于识别工件类型，输出类别如"工件A"、"工件B"等。

```bash
# 将模型放置在 models/ 目录
models/
├── type_classifier.onnx    # 类型识别模型（可选）
└── detail_detector.onnx    # 细节检测模型（必须）
```

### 细节检测模型（必须）

用于检测工件缺陷，输出缺陷位置和类别。

### 模型格式要求

- **格式**: ONNX (.onnx)
- **输入**: NCHW格式，RGB图像，尺寸通常为640x640
- **输出**: YOLO格式 [x_center, y_center, width, height, conf, class_scores...]

## 打包发布

### GitHub Actions 自动打包

项目配置了GitHub Actions工作流，自动构建三平台原生应用：

- **Windows**: `edge-vision.exe`
- **Linux**: `edge-vision-x86_64.AppImage`
- **macOS**: `Edge Vision.app`

### 手动打包

```bash
# Windows (需要Visual Studio Build Tools)
mvn -Pnative package

# Linux (需要gcc, glibc-devel)
sudo apt-get install -y libopencv-dev
mvn -Pnative package

# macOS (需要Xcode)
brew install opencv
mvn -Pnative package
```

## 性能优化

### 1. 使用GPU推理

GPU版本可显著提升检测速度（通常5-10倍），支持Windows和Linux平台。

#### 下载GPU版本

从 [Releases](../../releases) 页面下载对应平台的GPU版本：

- `edge-vision-gpu-Windows.zip` - Windows GPU版本
- `edge-vision-gpu-Linux.tar.gz` - Linux GPU版本

#### 前置要求

1. **NVIDIA GPU** - 支持CUDA 11.x或12.x的显卡
2. **显卡驱动** - 最新版本（>= 535 for CUDA 12.x）
3. **CUDA Toolkit** - [下载地址](https://developer.nvidia.com/cuda-downloads)
4. **cuDNN** - [下载地址](https://developer.nvidia.com/cudnn)

#### 安装步骤

**Windows:**

1. 安装 [CUDA Toolkit](https://developer.nvidia.com/cuda-downloads)（推荐12.x版本）
2. 安装 [cuDNN](https://developer.nvidia.com/cudnn)（选择与CUDA匹配的版本）
   - 下载Windows exe安装程序
   - 运行安装程序，按默认选项完成安装
3. 解压GPU版本压缩包
4. 双击运行 `run.bat`

**Linux:**

```bash
# 1. 安装CUDA Toolkit
wget https://developer.download.nvidia.com/compute/cuda/12.x.x/local_installers/cuda_12.x.x_linux.run
sudo sh cuda_12.x.x_linux.run

# 2. 安装cuDNN（下载tar.gz版本）
tar -xvf cudnn-linux-x86_64-*.tar.xz
sudo cp -R cudnn-linux-x86_64-*/include/* /usr/local/cuda/include
sudo cp -R cudnn-linux-x86_64-*/lib64/* /usr/local/cuda/lib64
sudo ldconfig

# 3. 运行GPU版本
cd edge-vision-gpu-Linux
./run.sh
```

#### 自动配置功能

启动脚本会自动完成以下操作：

1. ✅ 检测CUDA和cuDNN安装位置
2. ✅ 自动复制所需库文件到应用目录
3. ✅ 配置运行时环境变量
4. ✅ 启动GPU模式

首次运行时会自动配置，后续启动直接使用。

#### 验证GPU模式

启动成功后，日志会显示：

```
Found CUDA: C:\Program Files\NVIDIA GPU Computing Toolkit\CUDA\v12.0
Found cuDNN: C:\Program Files\NVIDIA\CUDNN\v9.17\bin\12.9
GPU (CUDA) provider enabled successfully
```

如果显示 `Falling back to CPU execution`，请检查CUDA/cuDNN是否正确安装。

#### 故障排查

**问题：显示 "CUDA initialization failed"**

- 确认CUDA Toolkit已安装：`nvcc --version`
- 确认cuDNN已安装：检查安装目录下是否有 `cudart64_*.dll`（Windows）或 `libcudart.so*`（Linux）
- Windows用户：运行 `dir "C:\Program Files\NVIDIA\CUDNN\v9.*\bin\*\cudnn*.dll"` 查找cuDNN
- Linux用户：运行 `ldconfig -p | grep cudnn` 查找cuDNN

**问题：找不到cudnn64_9.dll**

- cuDNN 9.x安装在版本目录中（如 `v9.17\bin\12.9`）
- 启动脚本会自动检测并复制，无需手动操作

**问题：CPU模式回退**

- 检查显卡驱动是否最新
- 确认CUDA版本与驱动兼容
- 查看完整错误日志

#### 手动复制库文件（备用方案）

如果自动复制失败，可以手动复制：

**Windows:**
```powershell
# 复制CUDA runtime
copy "C:\Program Files\NVIDIA GPU Computing Toolkit\CUDA\v12.0\bin\cudart64_*.dll" D:\edge-vision-gpu-Windows\

# 复制cuDNN
copy "C:\Program Files\NVIDIA\CUDNN\v9.17\bin\12.9\*.dll" D:\edge-vision-gpu-Windows\
```

**Linux:**
```bash
# 复制到应用目录
cp /usr/local/cuda/lib64/libcudart.so* /path/to/app/
cp /usr/local/cuda/lib64/libcudnn*.so* /path/to/app/
```

### 2. 调整摄像头分辨率

修改摄像头源配置，使用合适的分辨率以平衡性能和效果。

### 3. 调整检测阈值

```yaml
models:
  conf-thres: 0.5   # 降低提高速度，但可能漏检
  iou-thres: 0.45   # 调整NMS阈值
```

### 4. 使用原生镜像

GraalVM原生镜像相比JAR包启动更快、内存占用更少。

## 故障排查

### 摄像头无法打开

1. 检查摄像头是否被其他程序占用
2. 检查摄像头索引是否正确
3. 对于Linux，检查权限: `sudo usermod -a -G video $USER`

### 模型加载失败

1. 检查模型文件路径是否正确
2. 检查模型格式是否为ONNX
3. 查看日志获取详细错误信息

### OpenCV相关问题

1. 确保系统已安装OpenCV依赖
2. 对于Linux: `sudo apt-get install libopencv-dev`
3. 对于macOS: `brew install opencv`

## 许可证

MIT License

## 贡献

欢迎提交Issue和Pull Request！

## 联系方式

如有问题或建议，请通过以下方式联系：

- 提交GitHub Issue
- 发送邮件到开发者邮箱