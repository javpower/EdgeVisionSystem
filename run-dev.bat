@echo off
chcp 65001
cls
echo 启动边缘视觉检测系统（开发模式）...
echo.

REM 检查Java环境
java -version >nul 2>&1
if errorlevel 1 (
    echo 错误: 未找到Java环境，请先安装Java 17或更高版本
    pause
    exit /b 1
)

REM 检查Maven环境
mvn -version >nul 2>&1
if errorlevel 1 (
    echo 错误: 未找到Maven环境，请先安装Maven 3.8或更高版本
    pause
    exit /b 1
)

echo Java版本:
java -version
echo.

echo Maven版本:
mvn -version
echo.

REM 创建必要的目录
if not exist logs mkdir logs
if not exist data\images mkdir data\images
if not exist models mkdir models

REM 检查模型文件
if not exist models\detail_detector.onnx (
    echo 警告: 未找到细节检测模型文件 models\detail_detector.onnx
    echo 请下载YOLO ONNX模型并放置到models目录
    echo.
)

REM 启动应用
echo 正在启动应用...
echo 应用将运行在: http://localhost:8000
echo 按 Ctrl+C 停止应用
echo.

REM 使用开发配置
set SPRING_PROFILES_ACTIVE=dev
call mvn spring-boot:run

pause