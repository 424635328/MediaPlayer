# AndroidPlayer - 自定义音视频播放器

AndroidPlayer 是一个示例 Android 应用程序，展示了如何使用 FFmpeg (用于解码) 和 OpenSL ES/AAudio (用于音频播放) 构建一个自定义的音视频播放器。视频被预先解码为 YUV 文件，然后逐帧读取并渲染到 `SurfaceView`。音频则直接从原始 MP4 文件播放。

这个项目主要用于学习和演示以下技术点：

* JNI (Java Native Interface) 的使用。
* 通过 FFmpeg 进行视频解码。
* 将解码后的视频帧保存为 YUV 文件。
* 在 Native 层读取 YUV 文件并渲染到 Android `Surface`。
* 使用 OpenSL ES (或 AAudio) 播放音频。
* 基本的播放控制：播放、暂停、停止、倍速播放。
* 滑动进度条 (SeekBar) 实现音视频同步跳转 (Seek)。
* Android Activity 生命周期管理与播放器状态的协调。
* 多线程处理（UI线程、后台解码线程、Native渲染线程）。

## 项目结构

```bash
AndroidPlayer/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── assets/
│   │   │   │   └── 1.mp4       # 示例MP4视频文件
│   │   │   ├── cpp/
│   │   │   │   ├── CMakeLists.txt
│   │   │   │   ├── include/        # FFmpeg 头文件 (例如 libavcodec/avcodec.h)
│   │   │   │   │   ├── libavcodec/
│   │   │   │   │   ├── libavformat/
│   │   │   │   │   ├── libavutil/
│   │   │   │   │   ├── libswscale/
│   │   │   │   │   └── ... (其他必要的FFmpeg头文件)
│   │   │   │   ├── AAudioRender.cpp
│   │   │   │   ├── ANWRender.cpp
│   │   │   │   └── native-lib.cpp  # C++ JNI 和播放器核心逻辑
│   │   │   ├── java/
│   │   │   │   └── com/example/androidplayer/ # 替换为你的包名
│   │   │   │       └── MainActivity.java # 主要的 Activity 和 Java 层逻辑
│   │   │   ├── jniLibs/          # 存放预编译的 .so 文件 (标准位置)
│   │   │   │   ├── arm64-v8a/
│   │   │   │   │   └── libffmpeg.so  # 合并的 FFmpeg 动态库
│   │   │   │   ├── armeabi-v7a/
│   │   │   │   │   └── libffmpeg.so
│   │   │   │   ├── x86/
│   │   │   │   │   └── libffmpeg.so
│   │   │   │   └── x86_64/
│   │   │   │       └── libffmpeg.so
│   │   │   ├── res/
│   │   │   │   ├── layout/
│   │   │   │   │   └── activity_main.xml # UI 布局
│   │   │   │   └── ... (其他资源)
│   │   │   └── AndroidManifest.xml
│   │   └── build.gradle          # app 模块的 build.gradle
│   └── ...
├── ffmpeg-src/                 # (可选) FFmpeg 源码目录，如果与项目同级
├── build_ffmpeg_android.sh     # (可选) FFmpeg 编译脚本 (如果自行编译)
└── build.gradle                # 项目顶层的 build.gradle
└── settings.gradle
└── README.md                   # 本文件
└── .gitignore
```

**重要**:

* FFmpeg 头文件期望放在 `app/src/main/cpp/include/` 目录下。
* 一个名为 `libffmpeg.so` 的合并 FFmpeg 动态库期望放在 `app/src/main/jniLibs/<ABI>/` 目录下。

## 功能特性

* **视频播放**:
  * 从 MP4 文件解码视频流。
  * 将视频帧解码为 YUV (YUV420p) 格式并保存到本地缓存文件。
  * Native 层读取 YUV 文件，将 YUV 数据转换为 RGBA。
  * 将 RGBA 数据渲染到 `SurfaceView`。
* **音频播放**:
  * 使用 OpenSL ES 或 AAudio 直接从 MP4 文件播放音频流。
* **播放控制**:
  * 播放/暂停/继续播放。
  * 停止播放。
  * 调整播放速度 (0.5x, 1.0x, 1.5x, 2.0x)。
* **进度条与跳转 (Seek)**:
  * 显示当前播放进度。
  * 允许用户通过拖动进度条跳转到视频的任意位置。
  * **实现了音视频同步跳转**：视频帧和音频流会同步到用户选择的时间点。
* **UI**:
  * 使用 `SurfaceView` 显示视频。
  * 包含播放/暂停按钮、停止按钮、倍速按钮、进度条和速度显示文本。
* **错误处理与状态管理**:
  * 管理播放器的不同状态 (IDLE, PREPARING, PLAYING, PAUSED, STOPPED, ERROR)。
  * UI 根据播放器状态进行更新。
  * 处理 Activity 生命周期事件 (如 `onPause`, `onDestroy`, `surfaceDestroyed`)。

## 技术栈

* **Java (Android SDK)**: 应用层逻辑和 UI。
* **C/C++ (NDK)**: Native 层解码、渲染和音频播放逻辑。
* **JNI (Java Native Interface)**: 连接 Java 层和 C++ 层。
* **FFmpeg**: 用于视频解码 (包含 `libavformat`, `libavcodec`, `libavutil`, `libswscale` 等模块)。
* **OpenSL ES / AAudio**: Android NDK 提供的音频 API，用于音频播放。
* **CMake**: 用于构建 Native C++ 代码。

## 如何构建和运行

### 步骤 1: 准备开发环境 (Host System)

在开始之前，确保您的开发环境已设置妥当：

1. **Android Studio**: 安装最新稳定版 (例如 Hedgehog | 2023.1.1 或更高版本)。
2. **Android NDK**:
    * 通过 Android Studio 打开 `SDK Manager`。
    * 勾选 `NDK (Side by side)` 并安装一个较新的稳定版本 (例如 `25.2.9519653` 或 `26.1.10909125`。NDK r23+ 较好)。记下其安装路径。
3. **CMake**:
    * 同样在 `SDK Manager` > `SDK Tools` 中，确保 `CMake` 已安装 (例如 3.22.1 或更高)。
4. **(可选) 如果您需要自己编译 FFmpeg**:
    * **Git**: 用于下载 FFmpeg 源码。
    * **构建工具 (根据您的操作系统选择)**:
        * **Linux (推荐 Ubuntu/Debian)**: `build-essential`, `yasm`, `nasm`, `pkg-config`, `autoconf`, `automake`, `libtool`
        * **macOS**: Xcode Command Line Tools, `yasm`, `nasm`, `pkg-config`, `autoconf`, `automake`, `libtool` (通过 Homebrew 安装)
        * **Windows**: 强烈推荐使用 WSL 2 (Windows Subsystem for Linux) 并按 Linux 指引操作。

### 步骤 2: 获取并放置 FFmpeg 库和头文件

本项目期望您已经拥有为 Android 各 ABI 编译好的 `libffmpeg.so` 文件和相应的 FFmpeg 头文件。

1. **获取 `libffmpeg.so`**:
    * 您可以从第三方预编译库提供商处获取 (例如一些开源项目如 [ijkplayer](https://github.com/bilibili/ijkplayer) 的编译脚本可以生成，或者专门的 FFmpeg Android 构建项目)。
    * 或者，如果您选择自行编译 FFmpeg 并将其组件合并成一个 `libffmpeg.so`，您需要一个特定的构建脚本来完成此操作 (标准的 FFmpeg `make install` 会生成多个独立的 `.so` 文件)。
    * **将为您计划支持的每个 ABI (例如 `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`) 获取或生成的 `libffmpeg.so` 文件放置到项目的 `app/src/main/jniLibs/<ABI>/` 目录下。**
        例如: `app/src/main/jniLibs/arm64-v8a/libffmpeg.so`

2. **获取 FFmpeg 头文件**:
    * 这些头文件通常与编译产物一起提供，或者可以从与您 `.so` 版本匹配的 FFmpeg 源码的 `include` 目录中获取。
    * **将所有 FFmpeg 头文件 (例如 `libavcodec`, `libavformat` 等子目录及其中的 `.h` 文件) 复制到项目的 `app/src/main/cpp/include/` 目录下。**
        例如: `app/src/main/cpp/include/libavcodec/avcodec.h`

**(可选) 步骤 2.A: 自行编译 FFmpeg (如果需要多个独立的 .so 文件)**

如果您希望自行编译 FFmpeg 并得到多个独立的库文件 (如 `libavcodec.so`, `libavformat.so` 等)，然后修改 `CMakeLists.txt` 以链接它们 (参考上一版 README 中的方案 B)，可以使用以下脚本作为起点。**注意：此脚本不会生成单一的 `libffmpeg.so`。**

在项目根目录创建 `build_ffmpeg_android.sh`:

```bash
#!/bin/bash

# --- 配置开始 ---
export NDK_PATH="/path/to/your/android-ndk" # 修改为你的 NDK 路径
FFMPEG_SOURCE_PATH="./ffmpeg-src"            # FFmpeg 源码目录
TEMP_OUTPUT_DIR="./ffmpeg_build_temp"        # 临时输出目录
API_LEVEL=21
TARGET_ABIS=("arm64-v8a" "armeabi-v7a" "x86" "x86_64")
J_COUNT=$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)
# --- 配置结束 ---

# (脚本的路径检查、工具链设置、循环编译逻辑与先前版本中的 build_ffmpeg_android.sh 脚本基本一致)
# (请参考先前版本 README 中完整的脚本内容，确保 ./configure 部分启用你需要的组件并生成共享库)
# 编译完成后，将 TEMP_OUTPUT_DIR/<ABI>/lib/*.so 复制到 app/src/main/jniLibs/<ABI>/
# 将 TEMP_OUTPUT_DIR/<ABI>/include/* 复制到 app/src/main/cpp/include/
```

运行脚本: `chmod +x build_ffmpeg_android.sh && ./build_ffmpeg_android.sh`
然后根据脚本输出提示复制文件。

### 步骤 3: 配置 CMakeLists.txt

确保您的 `app/src/main/cpp/CMakeLists.txt` 文件内容与您提供的版本一致，它配置为链接一个名为 `libffmpeg.so` 的导入库：

```cmake
cmake_minimum_required(VERSION 3.22.1)
project("androidplayer")

# 输出构建类型和ABI，方便调试
message(STATUS "Building for ABI: ${ANDROID_ABI}")
message(STATUS "Build type: ${CMAKE_BUILD_TYPE}")

# FFmpeg 头文件目录 (相对于 app/src/main/cpp/CMakeLists.txt)
set(ffmpeg_head_dir ${CMAKE_CURRENT_SOURCE_DIR}/include) # CMAKE_CURRENT_SOURCE_DIR 指向 CMakeLists.txt 所在的目录
message(STATUS "FFmpeg include directory: ${ffmpeg_head_dir}")
# include_directories(${ffmpeg_head_dir}) # 更推荐使用 target_include_directories

# FFmpeg 预编译库目录 (CMAKE_CURRENT_SOURCE_DIR 指向 app/src/main/cpp)
# jniLibs 在 app/src/main/jniLibs，所以路径是 ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}
set(ffmpeg_lib_dir "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")
message(STATUS "FFmpeg library directory: ${ffmpeg_lib_dir}")

# 添加 FFmpeg 库 (作为 IMPORTED 库)
add_library(ffmpeg_imported SHARED IMPORTED) # 使用 ffmpeg_imported 以避免与 target_link_libraries 中的 ffmpeg 混淆
set_target_properties(ffmpeg_imported PROPERTIES IMPORTED_LOCATION "${ffmpeg_lib_dir}/libffmpeg.so")

# 查找 NDK 提供的标准库
find_library(log-lib log)
find_library(android-lib android)
find_library(nativewindow-lib nativewindow)
find_library(aaudio-lib aaudio)
find_library(opensles-lib OpenSLES)
find_library(z-lib z) # zlib

# 定义你的项目库
add_library(${CMAKE_PROJECT_NAME} SHARED
        AAudioRender.cpp    # 确保这些源文件存在于 app/src/main/cpp/
        ANWRender.cpp
        native-lib.cpp
)

# 将 FFmpeg 头文件目录添加到你的项目库
target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE ${ffmpeg_head_dir})

# 链接库到你的项目
target_link_libraries(${CMAKE_PROJECT_NAME}
        # 依赖的第三方库
        ffmpeg_imported          # 链接 FFmpeg (IMPORTED 库)

        # NDK 标准库
        ${log-lib}
        ${android-lib}
        ${nativewindow-lib}
        ${aaudio-lib}
        ${opensles-lib}
        ${z-lib}

        atomic                  # 对应 -latomic
        m                       # 对应 -lm (数学库)
)
```

### 步骤 4: 配置 app/build.gradle

打开 `app/build.gradle` 文件，确保其配置正确以支持 NDK 和 CMake。

```gradle
plugins {
    id 'com.android.application'
    // id 'org.jetbrains.kotlin.android' // 如果使用 Kotlin
}

android {
    // 确保将 'com.example.androidplayer' 替换为你的实际包名
    namespace "com.example.androidplayer"
    compileSdk 34 // 建议使用较新的 SDK

    defaultConfig {
        applicationId "com.example.androidplayer" // 替换为你的实际包名
        minSdk 21 // 必须与 FFmpeg 库兼容的最低 API 级别匹配
        targetSdk 34
        versionCode 1
        versionName "1.0"

        testInstrumentationRunner "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                cppFlags "-std=c++17" // 示例: 使用 C++17 标准
                // arguments "-DANDROID_STL=c++_shared" // 如果需要共享STL (默认c++_static通常没问题)
            }
        }
        ndk {
            // 指定你支持并已提供 libffmpeg.so 的 ABI
            abiFilters 'armeabi-v7a', 'arm64-v8a', 'x86', 'x86_64'
        }
    }

    buildTypes {
        release {
            minifyEnabled false // 为简单起见，禁用 R8/ProGuard
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        }
    }
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_1_8
        targetCompatibility JavaVersion.VERSION_1_8
    }
    // 如果使用 Kotlin
    // kotlinOptions {
    //    jvmTarget = '1.8'
    // }

    externalNativeBuild {
        cmake {
            path "src/main/cpp/CMakeLists.txt"
            version "3.22.1" // 或您 SDK Manager 中安装的 CMake 版本
        }
    }

    // 对于 app/src/main/jniLibs 目录，通常不需要显式配置 sourceSets.main.jniLibs.srcDirs
    // 因为这是 Android Gradle 插件的默认查找路径之一。
}

dependencies {
    implementation 'androidx.core:core-ktx:1.12.0'
    implementation 'androidx.appcompat:appcompat:1.6.1'
    implementation 'com.google.android.material:material:1.11.0'
    implementation 'androidx.constraintlayout:constraintlayout:2.1.4'
    testImplementation 'junit:junit:4.13.2'
    androidTestImplementation 'androidx.test.ext:junit:1.1.5'
    androidTestImplementation 'androidx.test.espresso:espresso-core:3.5.1'
}
```

### 步骤 5: 放置示例视频和 Native/Java 代码

1. **示例视频**: 将一个名为 `1.mp4` 的 MP4 视频文件放到 `app/src/main/assets/` 目录下。如果 `assets` 目录不存在，请创建它。
2. **Native 代码**:
    * 将您的 FFmpeg 头文件放入 `app/src/main/cpp/include/`。
    * 确保 `AAudioRender.cpp`, `ANWRender.cpp`, `native-lib.cpp` 文件存在于 `app/src/main/cpp/` 目录中，并包含您提供的 C++ 逻辑。
3. **Java 代码 (`MainActivity.java`)**: 将您的 Java Activity (如 `com.example.androidplayer.MainActivity`) 和播放器控制逻辑放入 `app/src/main/java/` 下对应的包名目录中。

### 步骤 6: 构建和运行

1. **同步 Gradle 项目**: 在 Android Studio 中，点击工具栏的 "Sync Project with Gradle Files" 图标。
2. 如果一切配置正确，项目应该可以正常构建。
3. 连接一个 Android 设备或启动一个模拟器 (确保其 ABI 与您提供的 `libffmpeg.so` 兼容)。
4. 点击 Android Studio 工具栏中的 "Run 'app'" 按钮。

应用启动后，它会首先尝试将 `assets/1.mp4` 解码为 YUV 文件并存放在应用的缓存目录中。这个过程可能需要一些时间，UI上通常会显示 "Preparing..." 或类似状态。解码完成后，播放按钮将变为可用。

## 代码核心逻辑说明

(这部分基于您提供的代码片段，您可以扩展它或保持原样)

### Java (MainActivity.java)

* **UI 初始化与事件监听**: 设置 `SurfaceView`, 按钮, `SeekBar` 等，并为它们添加监听器。
* **`SurfaceHolder.Callback`**: 处理 `Surface` 的创建、改变和销毁。
* **播放状态管理 (`PlayerState` enum)**: 维护播放器的当前状态，并据此更新UI。
* **媒体准备 (`prepareMediaInBackground`)**: 拷贝MP4文件，调用 Native 方法 `decodeVideoToFile`。
* **播放控制方法**: 调用相应的 Native 方法控制音视频。
* **进度条更新与跳转**: 通过 JNI 与 Native 层同步进度和 Seek 请求。
* **JNI 方法声明**: 声明所有与 C++ 层交互的 `native` 方法。

### C++ (native-lib.cpp, AAudioRender.cpp, ANWRender.cpp)

* **全局变量**: 存储播放状态、视频参数、线程对象、OpenSL ES/AAudio 对象等。
* **`decodeVideoToFile` (JNI)**:
  * 使用 FFmpeg 打开输入 MP4 文件，查找视频流，获取参数。
  * 初始化 FFmpeg 解码器。
  * 逐包读取、解码视频帧，并将 YUV420p 数据写入本地 YUV 文件。
* **视频渲染 (`video_render_loop_internal` in `ANWRender.cpp` or `native-lib.cpp`)**:
  * 在单独线程中运行。
  * 读取 YUV 文件帧。
  * 将 YUV420p 数据手动转换为 RGBA8888。
  * 使用 `ANativeWindow` 将 RGBA 数据渲染到 `Surface`。
  * 通过 `usleep` 和帧率、播放速度控制渲染速率。
* **音频播放 (OpenSL ES in `native-lib.cpp` or AAudio in `AAudioRender.cpp`)**:
  * 初始化音频引擎 (OpenSL ES `engineObject` 或 AAudio `streamBuilder`)。
  * 创建音频播放器/流，设置数据源为 MP4 文件 URI。
  * 获取播放、跳转、速率控制接口。
  * 控制播放状态 (播放、暂停、停止)。
  * 实现音频跳转 (`SetPosition` / `AAudioStream_requestStart` with offset)。
  * 实现音频速率控制。
* **JNI 接口**: 提供 Java 层调用上述功能的入口点，如开始/停止/暂停/恢复播放、设置速度、跳转等。

## 待改进和扩展

* **硬解码**: 集成 Android `MediaCodec` 进行硬解码。
* **音频解码到PCM**: 将音频解码为 PCM 数据流，通过 OpenSL ES buffer queue 或 AAudio `AAudioStream_write` 播放。
* **高级同步机制**: 实现基于时间戳的音视频同步。
* **SwsContext**: 如果解码出的视频帧非标准YUV420p或需缩放，使用 FFmpeg `libswscale`。
* **错误处理与反馈**: 增强 Native 层错误处理。
* **缓冲机制**: 为网络流添加数据缓冲。
* **UI 优化**: 改进用户界面。

## 常见问题及解决方案

* **`UnsatisfiedLinkError: dlopen failed: library "libffmpeg.so" not found`**:
  * 确保 `libffmpeg.so` 已正确放置到 `app/src/main/jniLibs/<ABI>/` 目录中。
  * 检查 `app/build.gradle` 中的 `ndk { abiFilters ... }` 是否包含了您设备/模拟器对应的 ABI，并且该 ABI 的 `libffmpeg.so` 文件存在。
  * 确保 `CMakeLists.txt` 中 `set_target_properties(ffmpeg_imported PROPERTIES IMPORTED_LOCATION "${ffmpeg_lib_dir}/libffmpeg.so")` 的路径计算正确。
* **`UnsatisfiedLinkError` (其他 JNI 方法)**:
  * 检查 `System.loadLibrary("androidplayer")` 中的库名是否与 `CMakeLists.txt` 中 `project("androidplayer")` 和 `add_library(androidplayer ...)` 的目标名一致。
  * 确保 Java 层的 `native` 方法声明与 C++ 中的 JNI 函数签名完全匹配 (包括包名和类名)。
* **视频解码失败**:
  * 确认输入的 MP4 文件有效且被您的 `libffmpeg.so` 所包含的解码器支持。
  * 检查 `libffmpeg.so` 是否完整包含了所有必要的 FFmpeg 组件 (demuxers, decoders)。
  * 查看 Logcat 中来自 "MyPlayerCPP" 和 "MainActivity" 的详细错误日志。
* **音视频不同步**: 需要更复杂的基于时间戳的同步策略。
* **ANativeWindow 或 AAudio 错误**:
  * 确保 `ANativeWindow_setBuffersGeometry` 或 AAudio流参数设置在 `Surface` 有效且视频/音频参数已知时调用。
  * 确保在 `Surface` 销毁或 AAudio流关闭前，相关 Native 线程已停止或不再访问这些资源。
* **FFmpeg 头文件找不到**:
  * 确保 FFmpeg 头文件已正确复制到 `app/src/main/cpp/include/`。
  * 检查 `CMakeLists.txt` 中 `set(ffmpeg_head_dir ${CMAKE_CURRENT_SOURCE_DIR}/include)` 是否正确指向该目录，并且 `target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE ${ffmpeg_head_dir})` 已设置。

## 贡献

欢迎提交 Pull Request 或提出 Issue 来改进这个项目。

---
