# Android 视频播放器应用

## 项目简介

本项目是一个基于 Android 平台的视频播放器应用，旨在提供高效、稳定、可定制的多媒体播放体验。 应用利用 FFmpeg 库进行视频解码，并使用 OpenSL ES 实现音频播放。 通过 ANativeWindow 将 YUV 视频渲染到 SurfaceView，实现了包括播放速度控制、暂停/恢复功能以及音视频同步等特性。

本项目采用 C++ 和 JNI 进行原生开发，以实现性能关键型任务，从而打造高效且可定制的多媒体体验。

## 主要特性

*   **跨平台支持:**  Android
*   **多种视频格式支持:** 利用 FFmpeg 进行视频解码，支持多种常见的视频格式。
*   **低延迟音频播放:** 使用 OpenSL ES 实现低延迟的音频播放。
*   **高效视频渲染:** 采用 ANativeWindow 将 YUV 帧直接渲染到 SurfaceView，优化了视频显示效率。
*   **播放速度控制:** 支持调整播放速度，例如快进。
*   **暂停/恢复功能:** 支持暂停和恢复播放。
*   **音视频同步:** 确保音频和视频同步播放。
*   **原生代码优化:** 使用 C++ 实现性能关键型任务，例如视频解码和 YUV 渲染。
*   **并发处理:** 使用 ExecutorService 管理并发任务，避免阻塞主 UI 线程，保持响应式的用户体验。
*   **错误处理:**  提供基本的错误处理机制，增强应用的健壮性。

## 技术栈

*   **Java:**  用于 UI 和应用逻辑的开发。
*   **C++:**  用于性能关键的音视频解码和处理。
*   **FFmpeg:**  用于视频解码，将各种视频格式解码为 YUV 格式。
*   **OpenSL ES:**  用于低延迟的音频播放。
*   **ANativeWindow:**  用于将 YUV 视频帧直接渲染到 SurfaceView。
*   **JNI (Java Native Interface):**  用于在 Java 代码和 C++ 代码之间进行桥接。
*   **SurfaceView:**  用于显示视频画面。
*   **ExecutorService:**  用于管理并发任务。

## 项目结构

├── app/ # Android 应用代码
│ ├── src/ # 源代码
│ │ ├── main/ # 主要代码
│ │ │ ├── java/ # Java 代码
│ │ │ ├── cpp/ # C++ 代码 (JNI)
│ │ │ ├── res/ # 资源文件 (布局、图片等)
│ ├── build.gradle # Gradle 构建文件
├── gradle/ # Gradle 相关文件
├── gradlew # Gradle 包装器
└── settings.gradle # 项目设置


## 编译和运行

1.  **安装 Android SDK 和 NDK:**  确保你已经安装了 Android SDK 和 NDK，并配置好了环境变量。
2.  **克隆代码:**  `[git clone https://github.com/424635328/MediaPlayer]`
3.  **使用 Android Studio 打开项目:**  在 Android Studio 中选择 "Open an existing Android Studio project"，然后选择项目根目录下的 `settings.gradle` 文件。
4.  **配置 NDK 路径 (如果需要):**  如果 Android Studio 没有自动检测到 NDK 路径，你需要在 `local.properties` 文件中手动配置 `ndk.dir` 属性。
5.  **构建项目:**  在 Android Studio 中点击 "Build" -> "Make Project" 或者 "Build" -> "Rebuild Project"。
6.  **运行项目:**  连接 Android 设备或启动模拟器，然后点击 "Run" -> "Run 'app'"。

## 使用说明

1.  **打开应用:**  在 Android 设备或模拟器上打开应用。
2.  **选择视频文件:**  点击 "选择文件" 按钮，选择要播放的视频文件。
3.  **播放控制:**  使用播放、暂停、恢复、快进等按钮进行播放控制。
4.  **调整播放速度:**  使用播放速度控制滑块调整播放速度。

## 贡献

欢迎大家参与本项目，贡献代码、提交 bug 报告、提出建议。

## 许可证

本项目采用 MIT License 授权。 有关详细信息，请参阅 [LICENSE](LICENSE) 文件。

## 联系方式

MiracleHcat@gmail.com
