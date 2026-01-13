@echo off
chcp 65001 >nul
echo ========================================
echo 微信自动化 APK 构建脚本
echo ========================================
echo.

:: 检查 Java
echo [1/5] 检查 Java 环境...
java -version >nul 2>&1
if %errorlevel% neq 0 (
    echo ✗ 未找到 Java，请先安装 JDK 11+
    echo 下载地址: https://adoptium.net/
    pause
    exit /b 1
)
echo ✓ Java 已安装
echo.

:: 检查 Android SDK
echo [2/5] 检查 Android SDK...
if not defined ANDROID_HOME (
    echo ✗ 未设置 ANDROID_HOME 环境变量
    echo.
    echo 请选择构建方式:
    echo 1. 安装 Android Studio (推荐)
    echo 2. 手动配置 Android SDK
    echo 3. 查看详细说明
    echo.
    choice /c 123 /n /m "请选择 [1-3]: "
    
    if errorlevel 3 (
        start BUILD_APK.md
        exit /b 0
    )
    if errorlevel 2 (
        echo.
        echo 手动配置步骤:
        echo 1. 下载 Android Command Line Tools
        echo    https://developer.android.com/studio#command-tools
        echo 2. 解压到 C:\Android\sdk
        echo 3. 设置环境变量 ANDROID_HOME=C:\Android\sdk
        echo 4. 运行: sdkmanager "platforms;android-34" "build-tools;34.0.0"
        pause
        exit /b 1
    )
    if errorlevel 1 (
        echo.
        echo 正在打开 Android Studio 下载页面...
        start https://developer.android.com/studio
        echo.
        echo 安装完成后:
        echo 1. 启动 Android Studio
        echo 2. 选择 "Open an Existing Project"
        echo 3. 打开此文件夹: %~dp0
        echo 4. 等待 Gradle 同步完成
        echo 5. 菜单: Build → Build APK
        pause
        exit /b 0
    )
)
echo ✓ ANDROID_HOME: %ANDROID_HOME%
echo.

:: 检查 Gradle
echo [3/5] 检查 Gradle...
if not exist gradlew.bat (
    echo ✗ 未找到 Gradle Wrapper
    echo 正在初始化...
    gradle wrapper
    if %errorlevel% neq 0 (
        echo ✗ 初始化失败
        echo 请安装 Gradle: https://gradle.org/install/
        pause
        exit /b 1
    )
)
echo ✓ Gradle Wrapper 已就绪
echo.

:: 清理旧构建
echo [4/5] 清理旧构建...
if exist app\build\outputs\apk (
    rmdir /s /q app\build\outputs\apk
)
echo ✓ 清理完成
echo.

:: 构建 APK
echo [5/5] 开始构建 APK...
echo 这可能需要几分钟，请耐心等待...
echo.
call gradlew.bat assembleDebug

if %errorlevel% neq 0 (
    echo.
    echo ========================================
    echo ✗ 构建失败
    echo ========================================
    echo.
    echo 可能的原因:
    echo 1. Android SDK 未正确安装
    echo 2. 网络连接问题（无法下载依赖）
    echo 3. SDK 版本不匹配
    echo.
    echo 建议:
    echo 1. 使用 Android Studio 构建（最简单）
    echo 2. 查看详细说明: BUILD_APK.md
    echo 3. 检查错误信息并搜索解决方案
    echo.
    pause
    exit /b 1
)

echo.
echo ========================================
echo ✓ 构建成功！
echo ========================================
echo.

:: 查找 APK
set APK_PATH=app\build\outputs\apk\debug\app-debug.apk
if exist "%APK_PATH%" (
    echo APK 位置: %~dp0%APK_PATH%
    echo 文件大小: 
    dir "%APK_PATH%" | find "app-debug.apk"
    echo.
    echo 下一步:
    echo 1. 将 APK 复制到手机
    echo 2. 点击安装（需要允许安装未知来源）
    echo 3. 开启无障碍服务权限
    echo 4. 配置关键词并测试
    echo.
    
    :: 询问是否打开文件夹
    choice /c YN /n /m "是否打开 APK 所在文件夹? [Y/N]: "
    if errorlevel 2 goto end
    if errorlevel 1 explorer /select,"%APK_PATH%"
) else (
    echo ✗ 未找到 APK 文件
    echo 请检查构建日志
)

:end
echo.
pause
