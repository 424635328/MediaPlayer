# AndroidPlayer - 自定义音视频播放器

AndroidPlayer 是一个示例 Android 应用程序，展示了如何使用 FFmpeg (用于解码) 和 OpenSL ES (用于音频播放) 构建一个自定义的音视频播放器。视频被预先解码为 YUV 文件，然后逐帧读取并渲染到 `SurfaceView`。音频则直接从原始 MP4 文件通过 OpenSL ES 播放。

这个项目主要用于学习和演示以下技术点：

* JNI (Java Native Interface) 的使用。
* 通过 FFmpeg 进行视频解码。
* 将解码后的视频帧保存为 YUV 文件。
* 在 Native 层读取 YUV 文件并渲染到 Android `Surface`。
* 使用 OpenSL ES 播放音频。
* 基本的播放控制：播放、暂停、停止、倍速播放。
* 滑动进度条 (SeekBar) 实现音视频同步跳转 (Seek)。
* Android Activity 生命周期管理与播放器状态的协调。
* 多线程处理（UI线程、后台解码线程、Native渲染线程）。

## 项目结构

```bash
AndroidPlayer/
├── app/
│   ├── libs/                   # (如果需要存放预编译的 .so 文件)
│   ├── src/
│   │   ├── main/
│   │   │   ├── assets/
│   │   │   │   └── 1.mp4       # 示例MP4视频文件
│   │   │   ├── cpp/
│   │   │   │   ├── CMakeLists.txt
│   │   │   │   └── native-lib.cpp # C++ JNI 和播放器核心逻辑
│   │   │   ├── java/
│   │   │   │   └── com/example/androidplayer/
│   │   │   │       └── MainActivity.java # 主要的 Activity 和 Java 层逻辑
│   │   │   ├── res/
│   │   │   │   ├── layout/
│   │   │   │   │   └── activity_main.xml # UI 布局
│   │   │   │   └── ... (其他资源)
│   │   │   └── AndroidManifest.xml
│   │   └── build.gradle          # app 模块的 build.gradle
│   └── ...
└── build.gradle                # 项目顶层的 build.gradle
└── settings.gradle
└── ...
```

## 功能特性

* **视频播放**:
  * 从 MP4 文件解码视频流。
  * 将视频帧解码为 YUV (YUV420p) 格式并保存到本地缓存文件。
  * Native 层读取 YUV 文件，将 YUV 数据转换为 RGBA。
  * 将 RGBA 数据渲染到 `SurfaceView`。
* **音频播放**:
  * 使用 OpenSL ES 直接从 MP4 文件播放音频流。
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

* **Java/Kotlin (Android SDK)**: 应用层逻辑和 UI。
* **C/C++ (NDK)**: Native 层解码、渲染和音频播放逻辑。
* **JNI (Java Native Interface)**: 连接 Java 层和 C++ 层。
* **FFmpeg**: 用于视频解码 (提取视频帧并转换为 YUV)。
  * `libavformat`: 处理多媒体容器格式。
  * `libavcodec`: 编解码器。
  * `libavutil`: 辅助工具函数。
  * `libswscale`: (在此项目中主要用于 YUV 到 RGBA 的参考，实际转换在 Native 手动实现，但解码出的 YUV 格式依赖它)
* **OpenSL ES**: Android NDK 提供的低延迟音频 API，用于音频播放。
* **CMake**: 用于构建 Native C++ 代码。

## 如何构建和运行

### 1. 环境准备

* **Android Studio**: 最新稳定版。
* **NDK (Native Development Kit)**: 通过 Android Studio SDK Manager 安装。
* **CMake**: 通过 Android Studio SDK Manager 安装。
* **FFmpeg 预编译库**:
  * 本项目期望您已经为 Android 编译好了 FFmpeg 库 (`.so` 文件和头文件)。
  * 您可以从网络上找到预编译的 FFmpeg Android 版本，或者参考 FFmpeg官方文档/社区教程自行编译。
  * 编译时需要包含 `libavformat`, `libavcodec`, `libavutil`, `libswscale` (如果需要SwsContext转换)。
  * 将编译好的 `.so` 文件放置到 `app/libs/<ABI>` 目录下 (例如 `app/libs/arm64-v8a/libavcodec.so`)，或者更推荐的方式是通过 `CMakeLists.txt` 来链接它们 (通常是将库放在一个外部目录，然后在CMake中指定路径)。

### 2. 配置 CMakeLists.txt

您需要修改 `app/src/main/cpp/CMakeLists.txt` 文件，以正确链接到您的 FFmpeg 库。

一个示例 `CMakeLists.txt` 可能如下所示 (假设FFmpeg库在特定路径)：

```cmake
cmake_minimum_required(VERSION 3.10.2) # 或者您NDK支持的更高版本

project("androidplayer")

# 设置 FFmpeg 库和头文件的路径 (根据您的实际情况修改)
set(FFMPEG_BASE_DIR /path/to/your/ffmpeg-android) # 指向FFmpeg编译产物的根目录
set(FFMPEG_INCLUDE_DIR ${FFMPEG_BASE_DIR}/include)
set(FFMPEG_LIB_DIR ${FFMPEG_BASE_DIR}/lib/${ANDROID_ABI}) # ANDROID_ABI 会自动选择

# 添加 Native 库 (native-lib.cpp)
add_library(androidplayer SHARED native-lib.cpp)

# 添加 FFmpeg 头文件目录
target_include_directories(androidplayer PRIVATE ${FFMPEG_INCLUDE_DIR})

# 链接系统库
target_link_libraries(androidplayer
    android     # Android NDK 核心库
    nativewindow # ANativeWindow
    log         # Android 日志
    OpenSLES    # OpenSL ES
    # 链接 FFmpeg 库
    ${FFMPEG_LIB_DIR}/libavformat.so
    ${FFMPEG_LIB_DIR}/libavcodec.so
    ${FFMPEG_LIB_DIR}/libavutil.so
    ${FFMPEG_LIB_DIR}/libswscale.so # 如果解码出的YUV非标准，或需转换时用
    z           # FFmpeg 可能依赖 zlib
)
```

**重要**: `set(FFMPEG_BASE_DIR ...)` 和后续的路径需要根据您存放 FFmpeg 库的实际位置进行修改。

### 3. 放置示例视频

将一个名为 `1.mp4` 的 MP4 视频文件放到 `app/src/main/assets/` 目录下。

### 4. 构建和运行

1. 在 Android Studio 中打开项目。
2. 等待 Gradle 同步完成。
3. 如果 CMake 配置正确，项目应该可以正常构建。
4. 连接一个 Android 设备或启动一个模拟器。
5. 点击 "Run" 按钮。

应用启动后，会首先将 `assets/1.mp4` 解码为 YUV 文件并存放在应用的缓存目录中，这个过程可能需要一些时间，UI上会显示 "Preparing..."。解码完成后，播放按钮将变为可用。

## 代码核心逻辑说明

### Java (MainActivity.java)

* **UI 初始化与事件监听**: 设置 `SurfaceView`, 按钮, `SeekBar` 等，并为它们添加监听器。
* **`SurfaceHolder.Callback`**: 处理 `Surface` 的创建、改变和销毁。`Surface` 准备好后才能开始 Native 渲染。
* **播放状态管理 (`PlayerState` enum)**: 维护播放器的当前状态，并据此更新UI。
* **媒体准备 (`prepareMediaInBackground`)**:
  * 将 `assets` 中的 MP4 文件复制到应用缓存目录。
  * 调用 Native 方法 `decodeVideoToFile` 将 MP4 解码为 YUV 文件。
  * 解码成功后，获取视频总帧数和帧率，并更新 `SeekBar`。
* **播放控制方法 (`handlePlayPause`, `startNewPlayback`, `pauseCurrentPlayback`, `resumeCurrentPlayback`, `handleStop`, `handleSpeedToggle`)**:
  * 调用相应的 Native 方法来控制视频和音频的播放。
* **进度条更新 (`progressUpdater` Runnable)**: 定期从 Native 获取当前视频帧，更新 `SeekBar` 进度。
* **SeekBar 事件处理**:
  * `onStartTrackingTouch`: 用户开始拖动进度条。
  * `onStopTrackingTouch`: 用户结束拖动。此时：
        1. 计算目标视频帧号和对应的音频时间戳。
        2. 如果正在播放/暂停，则先暂停音视频。
        3. 调用 `nativeSeekToFrame` 跳转视频。
        4. 调用 `nativeSeekAudioToTimestamp` 跳转音频。
        5. 如果之前是播放状态，则恢复音视频播放。
        6. 如果之前是停止/空闲状态，则记录目标位置，待下次播放时从该位置开始。
* **JNI 方法声明**: 声明所有需要与 C++ 层交互的 `native` 方法。

### C++ (native-lib.cpp)

* **全局变量**: 用于存储播放状态、视频参数、线程对象、文件指针、OpenSL ES 对象等。
* **`decodeVideoToFile`**:
  * 使用 FFmpeg `libavformat` 打开输入 MP4 文件。
  * 查找视频流，获取视频宽度、高度和平均帧率。
  * 初始化 FFmpeg 解码器 (`libavcodec`)。
  * 逐包读取视频数据，解码视频帧。
  * 将解码后的 YUV420p 帧数据写入到指定的输出 YUV 文件。
* **视频渲染 (`video_render_loop_internal` 线程函数)**:
  * 在一个单独的线程中运行。
  * 打开 YUV 文件。
  * 循环处理：
        1. **Seek 处理**: 检查 `g_seek_target_frame`，如果被设置，则使用 `fseek` 跳转到 YUV 文件中对应帧的位置。
        2. **暂停处理**: 检查 `g_is_paused` 标志。
        3. **读取YUV帧**: 从文件中读取一帧 YUV 数据。
        4. **YUV转RGBA并渲染**:
            * 锁定 `ANativeWindow` 的 buffer。
            * 手动将 YUV420p 数据转换为 RGBA8888 格式。
            * 将 RGBA 数据写入到 `ANativeWindow` 的 buffer 中。
            * 解锁并提交 buffer (`ANativeWindow_unlockAndPost`)。
        5. **速度控制**: 根据 `g_avg_frame_rate` 和 `g_playback_speed` 计算帧间延迟，使用 `usleep` 控制渲染速率。
  * 线程退出时关闭 YUV 文件。
* **Native 播放控制 JNI 函数**:
  * `nativeStartVideoPlayback`: 获取 `ANativeWindow`，设置 buffer 几何参数，启动视频渲染线程。
  * `nativeStopVideoPlayback`: 设置停止标志，等待渲染线程结束，释放 `ANativeWindow`。
  * `nativePauseVideo` / `nativeResumeVideo`: 设置 `g_is_paused` 标志。
  * `nativeSetSpeed`: 设置 `g_playback_speed`。
  * `nativeSeekToFrame`: 设置 `g_seek_target_frame`，渲染线程会响应。
  * `nativeGetTotalFrames`: 计算YUV文件的总帧数。
  * `nativeGetCurrentFrame`: 返回当前已渲染的视频帧号。
  * `nativeGetFrameRate`: 返回视频的平均帧率。
* **OpenSL ES 音频 JNI 函数**:
  * `initAudio`: 初始化 OpenSL ES 引擎和输出混音器。
  * `startAudio`:
        1. 创建音频播放器对象 (`playerObject`)。
        2. 设置数据源为 MP4 文件 URI。
        3. 获取 `SLPlayItf` (播放接口) 和 `SLSeekItf` (跳转接口)。
        4. 如果传入了 `startOffsetMs` 或存在 `g_audio_start_offset_ms`，则在播放前尝试使用 `SLSeekItf::SetPosition` 跳转到指定时间点。
        5. 设置播放状态为 `SL_PLAYSTATE_PLAYING`。
  * `stopAudio`: 停止播放并销毁音频播放器对象。
  * `pauseAudio`: 根据参数设置播放状态为 `SL_PLAYSTATE_PAUSED` 或 `SL_PLAYSTATE_PLAYING`。
  * `nativeSeekAudioToTimestamp`:
        1. 如果正在播放，先暂停。
        2. 使用 `SLSeekItf::SetPosition` 跳转到指定的时间戳。
        3. 如果之前是播放状态，则恢复播放。
        4. 如果播放器处于停止状态，则记录此时间戳供下次 `startAudio` 使用。

## 待改进和扩展

* **硬解码**: 目前使用 FFmpeg 软解码，可以考虑集成 Android `MediaCodec` 进行硬解码以提高性能和效率。
* **音频解码到PCM**: 为了更精确的音频控制和同步，可以将音频也解码为 PCM 数据流，然后通过 OpenSL ES 播放 PCM buffer queue。
* **高级同步机制**: 实现基于时间戳的音视频同步，而不是简单地依赖帧渲染和音频播放的独立启动/跳转。可以使用一个主时钟（如音频播放时钟）来驱动视频渲染。
* **SwsContext**: 如果解码出的视频帧不是标准的 YUV420p，或者需要进行缩放，应使用 FFmpeg 的 `libswscale` (SwsContext) 进行转换。
* **错误处理**: 增强 Native 层的错误处理和反馈机制。
* **缓冲机制**: 为视频和音频数据添加缓冲，以应对网络波动或解码延迟（如果从网络流播放）。
* **UI 优化**: 更美观和用户友好的界面。
* **全面清理 OpenSL ES 资源**: 在 `onDestroy` 中确保 `engineObject` 和 `outputMixObject` 也被正确销毁。

## 常见问题及解决方案

* **`UnsatisfiedLinkError`**:
  * 检查 `System.loadLibrary("androidplayer")` 中的库名是否与 `CMakeLists.txt` 中 `add_library` 的目标名一致。
  * 确保 FFmpeg 的 `.so` 文件已正确放置并被打包到 APK 中相应 ABI 的 `lib` 目录下。
  * 检查 `CMakeLists.txt` 中链接 FFmpeg 库的路径和名称是否正确。
  * 确保设备/模拟器的 ABI 与您提供的 `.so` 文件兼容。
* **视频解码失败**:
  * 确认输入的 MP4 文件格式被 FFmpeg 支持。
  * 检查 FFmpeg 库是否完整编译，包含了所需的解码器。
  * 查看 Logcat 中来自 "MyPlayerCPP" (Native 层) 和 "MainActivity" (Java 层) 的错误日志。
* **音视频不同步 (进一步优化)**:
  * 虽然当前版本实现了基本的跳转同步，但连续播放时的细微不同步可能仍存在。需要更复杂的基于时间戳的同步策略。
* **ANativeWindow 错误**:
  * 确保 `ANativeWindow_setBuffersGeometry` 在 `Surface` 有效且视频尺寸已知时调用。
  * 确保在 `Surface` 销毁前或销毁时，Native 渲染线程已停止或不再访问 `ANativeWindow`。

## 贡献

欢迎提交 Pull Request 或提出 Issue 来改进这个项目。

---
