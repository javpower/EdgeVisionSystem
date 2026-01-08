#!/bin/bash

# 开发环境启动脚本

echo "启动边缘视觉检测系统（开发模式）..."
echo ""

# 检查Java环境
if ! command -v java &> /dev/null; then
    echo "错误: 未找到Java环境，请先安装Java 17或更高版本"
    exit 1
fi

# 检查Maven环境
if ! command -v mvn &> /dev/null; then
    echo "错误: 未找到Maven环境，请先安装Maven 3.8或更高版本"
    exit 1
fi

# 显示Java版本
echo "Java版本:"
java -version
echo ""

# 显示Maven版本
echo "Maven版本:"
mvn -version
echo ""

# 创建必要的目录
mkdir -p logs
mkdir -p data/images
mkdir -p models

# 检查模型文件
if [ ! -f "models/detail_detector.onnx" ]; then
    echo "警告: 未找到细节检测模型文件 models/detail_detector.onnx"
    echo "请下载YOLO ONNX模型并放置到models目录"
    echo ""
fi

# 启动应用
echo "正在启动应用..."
echo "应用将运行在: http://localhost:8000"
echo "按 Ctrl+C 停止应用"
echo ""

# 使用开发配置
export SPRING_PROFILES_ACTIVE=dev
mvn spring-boot:run