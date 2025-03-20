#include <jni.h>
#include <string>
#include <android/log.h>
#include <mutex>
#include <condition_variable>
#include <atomic>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <thread>
#include <queue>
#include <unistd.h>

extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavutil/frame.h>
#include <libavutil/mem.h>
#include <libavutil/imgutils.h>
#include <libavutil/time.h>
#include <libswscale/swscale.h> // 包含 libswscale 头文件，用于像素格式转换
}

//#include "com_example_androidplayer_MainActivity.h"


#define LOG_TAG "MyPlayer"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

#ifndef AV_TIME_BASE
#define AV_TIME_BASE 1000000
#endif


// 全局变量 (需要线程安全保护)
std::atomic<bool> g_isPaused(false); // 添加全局暂停标志
std::atomic<float> g_speed(1.0f); // 添加全局播放速度
int g_videoWidth;
int g_videoHeight;

struct ThreadArgs {
    const char *inputFilePath;
    ANativeWindow *nativeWindow;
    AVFormatContext *formatContext;
};

class PacketQueue {
public:
    PacketQueue() : abort_request(false) {}

    ~PacketQueue() {
        flush();
    }

    void put(AVPacket *pkt) {
        std::unique_lock<std::mutex> lock(mutex);
        queue.push(pkt);
        cond.notify_one();
    }

    AVPacket *get() {
        std::unique_lock<std::mutex> lock(mutex);
        while (queue.empty() && !abort_request) {
            cond.wait(lock);
        }

        if (abort_request) {
            return nullptr;
        }

        AVPacket *pkt = queue.front();
        queue.pop();
        return pkt;
    }

    void flush() {
        std::unique_lock<std::mutex> lock(mutex);
        AVPacket *pkt;
        while (!queue.empty()) {
            pkt = queue.front();
            queue.pop();
            av_packet_free(&pkt);
        }
    }

    void abort() {
        std::unique_lock<std::mutex> lock(mutex);
        abort_request = true;
        cond.notify_all();
    }

private:
    std::queue<AVPacket *> queue;
    std::mutex mutex;
    std::condition_variable cond;
    bool abort_request;
};

PacketQueue packetQueue;
std::atomic<bool> isPaused(false);
std::atomic<double> currentPosition(0.0);
std::mutex formatContextMutex; // 保护 AVFormatContext 的互斥锁

// 解封装线程 (基本不变)
void extractVideoStream(ThreadArgs *args) {
    const char *inputFilePath = args->inputFilePath;
    AVFormatContext *formatContext = nullptr;
    AVPacket *packet = nullptr;
    int videoStreamIndex = -1;
    int ret = 0;
    bool cleanup_needed = false;

    av_register_all();
    avformat_network_init();

    ret = avformat_open_input(&formatContext, inputFilePath, nullptr, nullptr);
    if (ret != 0) {
        LOGE("无法打开输入文件! 错误代码: %d", ret);
        cleanup_needed = true;
    }

    if (!cleanup_needed) {
        ret = avformat_find_stream_info(formatContext, nullptr);
        if (ret < 0) {
            LOGE("无法找到流信息! 错误代码: %d", ret);
            cleanup_needed = true;
        }
    }

    if (!cleanup_needed) {
        for (int i = 0; i < formatContext->nb_streams; i++) {
            if (formatContext->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_VIDEO) {
                videoStreamIndex = i;
                break;
            }
        }

        if (videoStreamIndex == -1) {
            LOGE("无法找到视频流!");
            cleanup_needed = true;
        }
    }

    if (!cleanup_needed) {
        packet = av_packet_alloc();
        if (!packet) {
            LOGE("无法分配 packet!");
            cleanup_needed = true;
        }
    }

    if (!cleanup_needed) {
        while (true) {
            ret = av_read_frame(formatContext, packet);
            if (ret < 0) {
                if (ret == AVERROR_EOF) {
                    LOGI("到达文件末尾.");
                } else {
                    LOGE("读取帧时出错! 错误代码: %d", ret);
                }
                break;
            }

            if (packet->stream_index == videoStreamIndex) {
                AVPacket *newPacket = av_packet_alloc();
                if (!newPacket) {
                    LOGE("在 extractVideoStream 中无法分配 packet!");
                    av_packet_unref(packet);
                    continue;
                }
                av_packet_ref(newPacket, packet);
                packetQueue.put(newPacket);

                std::unique_lock<std::mutex> lock(formatContextMutex); // 保护 formatContext
                if (formatContext->streams[videoStreamIndex]->time_base.den && formatContext->streams[videoStreamIndex]->time_base.num) {
                    currentPosition = packet->pts * av_q2d(formatContext->streams[videoStreamIndex]->time_base);
                }

            }
            av_packet_unref(packet);
        }
    }

    // 放置一个空的packet到队列，用于通知解码线程结束
    AVPacket* nullPacket = av_packet_alloc();
    if (nullPacket) {
        nullPacket->data = nullptr;
        nullPacket->size = 0;
        packetQueue.put(nullPacket);
        LOGI("extractVideoStream 完成");
    } else {
        LOGE("无法分配空 packet!");
    }

    if (formatContext) {
        avformat_close_input(&formatContext);
    }
    if (packet) {
        av_packet_free(&packet);
    }
    LOGI("extractVideoStream 退出");
    return;
}

// 解码线程
void decodeVideo(ThreadArgs *args) {
    const char *inputFilePath = args->inputFilePath;
    ANativeWindow *nativeWindow = args->nativeWindow;
    AVCodecContext *videoCodecContext = nullptr;
    AVCodec *videoCodec = nullptr;
    AVFrame *frame = nullptr;
    AVFormatContext *formatContext = args->formatContext;
    int videoStreamIndex = -1;
    int ret = 0;
    bool cleanup_needed = false;

    SwsContext *sws_ctx = nullptr; // 用于像素格式转换

    if (!formatContext) {
        LOGE("decodeVideo 中的 format context 为空!");
        return;
    }

    for (int i = 0; i < formatContext->nb_streams; i++) {
        if (formatContext->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_VIDEO) {
            videoStreamIndex = i;
            break;
        }
    }

    if (videoStreamIndex == -1) {
        LOGE("在 decodeVideo 中无法找到视频流!");
        return;
    }

    videoCodec = avcodec_find_decoder(formatContext->streams[videoStreamIndex]->codecpar->codec_id);
    if (!videoCodec) {
        LOGE("无法找到视频解码器!");
        return;
    }

    videoCodecContext = avcodec_alloc_context3(videoCodec);
    if (!videoCodecContext) {
        LOGE("无法分配视频解码器上下文!");
        return;
    }

    ret = avcodec_parameters_to_context(videoCodecContext, formatContext->streams[videoStreamIndex]->codecpar);
    if (ret < 0) {
        LOGE("无法复制解码器参数到解码器上下文! 错误代码: %d", ret);
        return;
    }

    ret = avcodec_open2(videoCodecContext, videoCodec, nullptr);
    if (ret < 0) {
        LOGE("无法打开视频解码器! 错误代码: %d", ret);
        return;
    }

    frame = av_frame_alloc();
    if (!frame) {
        LOGE("无法分配 frame!");
        return;
    }

    // 获取 Native Window 的宽高
    int windowWidth = ANativeWindow_getWidth(nativeWindow);
    int windowHeight = ANativeWindow_getHeight(nativeWindow);

    // 初始化 SwsContext，用于像素格式转换
    sws_ctx = sws_getContext(
            videoCodecContext->width, videoCodecContext->height, videoCodecContext->pix_fmt,
            windowWidth, windowHeight, AV_PIX_FMT_RGBA, // 输出为RGBA格式
            SWS_BILINEAR, nullptr, nullptr, nullptr
    );

    if (!sws_ctx) {
        LOGE("无法初始化 SwsContext!");
        return;
    }

    // 分配用于存储转换后的RGBA数据的缓冲区
    uint8_t *dst_data[4];
    int dst_linesize[4];
    av_image_alloc(dst_data, dst_linesize,
                   windowWidth, windowHeight, AV_PIX_FMT_RGBA, 1);

    AVPacket *packet = nullptr;
    int frameCounter = 0;  // 用于跳帧计数

    while (true) {
        if (isPaused) {
            av_usleep(10000);
            continue;
        }

        packet = packetQueue.get();
        if (!packet) {
            LOGE("中止请求, 退出解码循环.");
            break;
        }

        if (packet->data == nullptr && packet->size == 0) { // 空 packet，表示结束
            av_packet_free(&packet);
            break;
        }

        ret = avcodec_send_packet(videoCodecContext, packet);
        if (ret < 0) {
            LOGE("avcodec_send_packet 错误: %d", ret);
            av_packet_unref(packet);
            av_packet_free(&packet);
            packet = nullptr;
            continue;
        }

        while (true) {
            ret = avcodec_receive_frame(videoCodecContext, frame);
            if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) {
                break;
            } else if (ret < 0) {
                LOGE("avcodec_receive_frame 错误: %d", ret);
                av_frame_unref(frame);
                break;
            }

            // 获取当前播放速度
            float speed = g_speed.load();

            // 跳帧逻辑
            if (speed > 1.0f) {
                // 快速播放:  跳过一些帧
                int skipInterval = (int)speed;  // 跳帧间隔 (例如 speed=2.0, skipInterval=2，每隔一帧跳过一帧)
                if (frameCounter % skipInterval != 0) {
                    // 跳过当前帧
                    av_frame_unref(frame);
                    frameCounter++;
                    break; // 继续解码下一帧
                }
            }

            frameCounter++;

            ANativeWindow_Buffer windowBuffer;
            ret = ANativeWindow_lock(nativeWindow, &windowBuffer, nullptr);
            if (ret < 0) {
                LOGE("ANativeWindow_lock 失败: %d", ret);
                av_frame_unref(frame);
                break;
            }

            // 使用 libswscale 进行像素格式转换
            sws_scale(sws_ctx, frame->data, frame->linesize, 0, videoCodecContext->height, dst_data, dst_linesize);

            // 将RGBA数据拷贝到 windowBuffer.bits
            uint8_t *dst = (uint8_t *)windowBuffer.bits;
            for (int h = 0; h < windowHeight; ++h) {
                memcpy(dst + h * windowBuffer.stride * 4, dst_data[0] + h * dst_linesize[0], windowWidth * 4);
            }

            ANativeWindow_unlockAndPost(nativeWindow);

            av_frame_unref(frame);
        }
        if(packet){
            av_packet_unref(packet);
            av_packet_free(&packet);
            packet = nullptr;
        }
    }

    // 释放资源
    if (sws_ctx) {
        sws_freeContext(sws_ctx);
    }
    av_freep(&dst_data[0]);  // 释放缓冲区
    if (videoCodecContext) {
        avcodec_free_context(&videoCodecContext);
    }
    if (frame) {
        av_frame_free(&frame);
    }
    LOGI("decodeVideo 退出");
    return;
}

// JNI 函数实现
extern "C" {



JNIEXPORT jstring JNICALL
Java_com_example_androidplayer_MainActivity_stringFromJNI(JNIEnv *env, jobject /* this */) {
    std::string hello = "Hello from C++";
    return env->NewStringUTF(hello.c_str());
}

JNIEXPORT void JNICALL  //若void报错，请忽略
Java_com_example_androidplayer_MainActivity_decodeVideoToFile(JNIEnv *env, jobject /* this */, jstring inputFilePath, jstring outputFilePath) {
    const char *input = env->GetStringUTFChars(inputFilePath, nullptr);
    const char *output = env->GetStringUTFChars(outputFilePath, nullptr);

    LOGI("JNI decodeVideoToFile 被调用, 输入文件: %s, 输出文件: %s", input, output);

    AVFormatContext *pFormatCtx = nullptr;
    int videoStreamIndex = -1;
    AVCodecContext *pCodecCtx = nullptr;
    AVCodec *pCodec = nullptr;
    AVFrame *pFrame = nullptr;
    AVPacket packet;
    FILE *outFile = nullptr;
    int ret = 0;

    // 初始化 FFmpeg
    av_register_all();
    avformat_network_init();

    // 1. 打开输入文件
    ret = avformat_open_input(&pFormatCtx, input, nullptr, nullptr);
    if (ret < 0) {
        LOGE("无法打开输入文件: %s, 错误代码: %d", input, ret);
        goto cleanup;
    }

    // 2. 获取流信息
    ret = avformat_find_stream_info(pFormatCtx, nullptr);
    if (ret < 0) {
        LOGE("无法找到流信息: %s, 错误代码: %d", input, ret);
        goto cleanup;
    }

    // 3. 找到第一个视频流
    for (int i = 0; i < pFormatCtx->nb_streams; i++) {
        if (pFormatCtx->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_VIDEO) {
            videoStreamIndex = i;
            break;
        }
    }
    if (videoStreamIndex == -1) {
        LOGE("在 %s 中无法找到视频流", input);
        goto cleanup;
    }

    // 4. 获取解码器参数
    pCodecCtx = avcodec_alloc_context3(nullptr);
    if (!pCodecCtx) {
        LOGE("无法分配解码器上下文");
        goto cleanup;
    }
    ret = avcodec_parameters_to_context(pCodecCtx, pFormatCtx->streams[videoStreamIndex]->codecpar);
    if (ret < 0) {
        LOGE("无法复制解码器参数到上下文, 错误代码: %d", ret);
        goto cleanup;
    }

    // 5. 查找解码器
    pCodec = avcodec_find_decoder(pCodecCtx->codec_id);
    if (!pCodec) {
        LOGE("无法找到解码器");
        goto cleanup;
    }

    // 6. 打开解码器
    ret = avcodec_open2(pCodecCtx, pCodec, nullptr);
    if (ret < 0) {
        LOGE("无法打开解码器, 错误代码: %d", ret);
        goto cleanup;
    }

    // 7. 分配 AVFrame 结构
    pFrame = av_frame_alloc();
    if (!pFrame) {
        LOGE("无法分配 frame");
        goto cleanup;
    }

    // 8. 打开输出文件
    outFile = fopen(output, "wb");
    if (!outFile) {
        LOGE("无法打开输出文件: %s", output);
        goto cleanup;
    }

    // 9. 解码循环
    av_init_packet(&packet);
    packet.data = nullptr;
    packet.size = 0;

    while (av_read_frame(pFormatCtx, &packet) >= 0) {
        if (packet.stream_index == videoStreamIndex) {
            ret = avcodec_send_packet(pCodecCtx, &packet);
            if (ret < 0) {
                LOGE("发送解码数据包出错, 错误代码: %d", ret);
                av_packet_unref(&packet);
                continue;
            }
            while (ret >= 0) {
                ret = avcodec_receive_frame(pCodecCtx, pFrame);
                if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) {
                    break;
                } else if (ret < 0) {
                    LOGE("解码时出错, 错误代码: %d", ret);
                    goto cleanup;
                }

                // 写入 YUV 数据
                // 假设是 Planar YUV 4:2:0, 大部分视频的默认格式
                fwrite(pFrame->data[0], 1, pFrame->width * pFrame->height, outFile);   // Y
                fwrite(pFrame->data[1], 1, pFrame->width * pFrame->height / 4, outFile); // U
                fwrite(pFrame->data[2], 1, pFrame->width * pFrame->height / 4, outFile); // V

                av_frame_unref(pFrame); // 清空帧，以便下次使用
            }
        }
        av_packet_unref(&packet); // 释放 packet
    }

    LOGI("解码成功完成");

    cleanup:
    // 释放资源
    if (pFormatCtx) {
        avformat_close_input(&pFormatCtx);
    }
    if (pCodecCtx) {
        avcodec_free_context(&pCodecCtx);
    }
    if (pFrame) {
        av_frame_free(&pFrame);
    }
    if (outFile) {
        fclose(outFile);
    }

    avformat_network_deinit();

    env->ReleaseStringUTFChars(inputFilePath, input);
    env->ReleaseStringUTFChars(outputFilePath, output);
}

JNIEXPORT jint JNICALL//函数报错不用管
Java_com_example_androidplayer_MainActivity_renderYUVToSurface(JNIEnv *env, jobject /* this */, jstring yuvFilePath, jint width, jint height, jobject surface, jobject isPlaying) {
    const char *yuvFile = env->GetStringUTFChars(yuvFilePath, nullptr);
    ANativeWindow *nativeWindow = ANativeWindow_fromSurface(env, surface);
    int windowWidth = width;
    int windowHeight = height;
    int frameSize = width * height * 3 / 2;
    jclass atomicBooleanClass = env->GetObjectClass(isPlaying);
    jmethodID getMethod = env->GetMethodID(atomicBooleanClass, "get", "()Z");

    FILE *fp = nullptr;
    uint8_t *yuvData = new uint8_t[frameSize];
    int ret = 0;
    int x, y, i, j, r, g, b, Y, U, V, index;
    uint8_t *dst;

    LOGI("JNI renderYUVToSurface 被调用, YUV 文件: %s, 宽度: %d, 高度: %d", yuvFile, width, height);

    if (!nativeWindow) {
        LOGE("无法从 Surface 获取 native window!");
        ret = -1;
    } else {

        ANativeWindow_setBuffersGeometry(nativeWindow, windowWidth, windowHeight, WINDOW_FORMAT_RGBA_8888);

        fp = fopen(yuvFile, "rb");
        if (!fp) {
            LOGE("无法打开 YUV 文件: %s", yuvFile);
            ret = -1;
        } else {

            ANativeWindow_Buffer windowBuffer;
            int frameCounter = 0;

            while (env->CallBooleanMethod(isPlaying, getMethod) == JNI_TRUE) {

                //检查暂停
                if (g_isPaused) {
                    usleep(10 * 1000); // 暂停时释放 CPU 资源
                    continue;          // 跳过当前帧
                }

                if (fread(yuvData, 1, frameSize, fp) != frameSize) {
                    LOGI("到达 YUV 文件末尾, 循环回到开始.");
                    fseek(fp, 0, SEEK_SET); // 循环播放
                    if (fread(yuvData, 1, frameSize, fp) != frameSize) {
                        LOGE("循环回到开始后无法读取 YUV 数据");
                        break; // 退出循环
                    }
                }

                // 跳帧逻辑
                if (g_speed > 1.0f) {
                    // 快速播放:  跳过一些帧
                    int skipInterval = (int)g_speed;  // 跳帧间隔 (例如 speed=2.0, skipInterval=2，每隔一帧跳过一帧)
                    if (frameCounter % skipInterval != 0) {
                        // 跳过当前帧
                        frameCounter++;
                        continue; // 继续渲染下一帧
                    }
                }
                frameCounter++;

                ret = ANativeWindow_lock(nativeWindow, &windowBuffer, nullptr);
                if (ret < 0) {
                    LOGE("ANativeWindow_lock 失败: %d", ret);
                    break;
                }

                dst = (uint8_t *) windowBuffer.bits;
                int dstStride = windowBuffer.stride * 4; // RGBA

                // YUV420p to RGBA
                for (y = 0; y < height; y++) {
                    for (x = 0; x < width; x++) {
                        i = y * width + x;
                        j = y / 2 * width / 2 + x / 2;

                        Y = yuvData[i] & 0xFF;
                        U = yuvData[width * height + j] & 0xFF;
                        V = yuvData[width * height + width * height / 4 + j] & 0xFF;

                        Y = Y - 16 < 0 ? 0 : Y - 16;
                        U -= 128;
                        V -= 128;

                        r = (298 * Y + 409 * V + 128) >> 8;
                        g = (298 * Y - 100 * U - 208 * V + 128) >> 8;
                        b = (298 * Y + 516 * U + 128) >> 8;

                        r = r < 0 ? 0 : r > 255 ? 255 : r;
                        g = g < 0 ? 0 : g > 255 ? 255 : g;
                        b = b < 0 ? 0 : b > 255 ? 255 : b;

                        index = y * windowBuffer.stride + x;
                        dst[index * 4 + 0] = (uint8_t) r; // R
                        dst[index * 4 + 1] = (uint8_t) g; // G
                        dst[index * 4 + 2] = (uint8_t) b; // B
                        dst[index * 4 + 3] = (uint8_t) 255; // A
                    }
                }

                ANativeWindow_unlockAndPost(nativeWindow);
                usleep(16 * 1000);
            }

            LOGI("YUV 渲染成功完成");

        }

    }

    if (fp) {
        fclose(fp);
    }
    if (yuvData) {
        delete[] yuvData;
    }
    if (nativeWindow) {
        ANativeWindow_release(nativeWindow);
    }

    env->ReleaseStringUTFChars(yuvFilePath, yuvFile);
    return ret;
}
JNIEXPORT void JNICALL
Java_com_example_androidplayer_MainActivity_nativeSetSpeed(JNIEnv *env, jobject thiz, jfloat speed) {
    LOGI("设置播放速度为: %f", speed);
    g_speed = speed; // 更新全局速度
}
JNIEXPORT void JNICALL
Java_com_example_androidplayer_MainActivity_nativePause(JNIEnv *env, jobject thiz) {
    bool current = g_isPaused.load(); // 读取当前暂停状态
    g_isPaused.store(!current); // 切换暂停状态
    LOGI("暂停状态切换为: %d", !current);
}

//获取YUV视频总帧数
JNIEXPORT jint JNICALL
Java_com_example_androidplayer_MainActivity_getVideoDuration(JNIEnv *env, jobject thiz, jstring yuvFilePath) {
    const char *yuvFile = env->GetStringUTFChars(yuvFilePath, nullptr);
    FILE *fp = nullptr;
    int frameSize = g_videoWidth * g_videoHeight * 3 / 2; // YUV420p
    int frameCount = 0;
    uint8_t *yuvData = new uint8_t[frameSize];

    fp = fopen(yuvFile, "rb");
    if (!fp) {
        LOGE("无法打开 YUV 文件: %s", yuvFile);
        env->ReleaseStringUTFChars(yuvFilePath, yuvFile);
        delete[] yuvData;
        return 0; // 0 duration
    }
    while (fread(yuvData, 1, frameSize, fp) == frameSize) {
        frameCount++;
    }

    fclose(fp);
    delete[] yuvData;
    env->ReleaseStringUTFChars(yuvFilePath, yuvFile);

    return (jint)frameCount;
}


JNIEXPORT jint JNICALL Java_com_example_androidplayer_MainActivity_seekToPosition(JNIEnv *env, jobject thiz, jstring yuvFilePath, jint position) {
    const char *yuvFile = env->GetStringUTFChars(yuvFilePath, nullptr);
    int frameSize = g_videoWidth * g_videoHeight * 3 / 2;
    FILE *fp = nullptr;
    int ret = 0;
    long offset = (long)position * frameSize;

    LOGI("正在 seek 到位置: %d", position);
    fp = fopen(yuvFile, "rb");
    if (!fp) {
        LOGE("无法打开 YUV 文件: %s", yuvFile);
        ret = -1;
    }
    else {
        ret = fseek(fp, offset, SEEK_SET);
        if (ret != 0) {
            LOGE("fseek 失败: %d", ret);
            ret = -1;
        }
        else{
            LOGI("Seek 成功完成");
        }
        fclose(fp);
    }


    env->ReleaseStringUTFChars(yuvFilePath, yuvFile);
    return ret;
}
}