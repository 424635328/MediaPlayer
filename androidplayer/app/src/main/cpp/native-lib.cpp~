#include <jni.h>
#include <string>
#include <android/log.h>
#include <mutex>
#include <condition_variable>
#include <atomic>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <thread>
#include <unistd.h> 
#include <SLES/OpenSLES.h>
#include <SLES/OpenSLES_Android.h>
#include <SLES/OpenSLES_AndroidConfiguration.h>
#include <algorithm>
#include <vector>
#include <cmath>

extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavutil/frame.h>
#include <libavutil/mem.h>
#include <libavutil/imgutils.h>
#include <libavutil/time.h>
#include <libswscale/swscale.h>
}

#define LOG_TAG "MyPlayerCPP"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__) // 错误日志宏
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__) // 信息日志宏
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__) // 警告日志宏

// --- 全局播放控制变量 ---
std::atomic<bool> g_is_video_playing_flag(false);     // 视频是否正在播放标志
std::atomic<bool> g_is_paused(false);                 // 是否暂停标志
std::atomic<float> g_playback_speed(1.0f);            // 播放速度 (影响视频帧延迟和期望的音频速率)
std::atomic<bool> g_abort_render_request(false);      // 请求终止渲染标志
std::atomic<long> g_seek_target_frame(-1);            // 视频跳转的目标帧号，-1表示无跳转请求
std::atomic<long> g_current_rendered_frame(0);      // 当前已渲染的视频帧号

// --- 视频参数 ---
int g_video_width = 0;                                // 视频宽度
int g_video_height = 0;                               // 视频高度
std::atomic<double> g_avg_frame_rate(25.0);           // 平均帧率

// --- 视频渲染线程与资源 ---
std::thread g_video_render_thread;                    // 视频渲染线程对象
FILE *g_yuv_file_ptr_for_render = nullptr;            // 用于渲染的YUV文件指针
std::mutex g_yuv_file_mutex_render;                   // YUV文件访问互斥锁 (渲染线程使用)
ANativeWindow *g_native_window_render = nullptr;      // 原生窗口指针 (用于视频渲染)
std::string g_yuv_file_path_render_str;               // YUV文件路径 (渲染线程使用)

// --- OpenSL ES 相关 ---
SLObjectItf engineObject = nullptr;                   // OpenSL ES引擎对象
SLEngineItf engineEngine = nullptr;                   // OpenSL ES引擎接口
SLObjectItf outputMixObject = nullptr;                // OpenSL ES输出混音器对象
SLObjectItf playerObject = nullptr;                   // OpenSL ES播放器对象
SLPlayItf playerPlay = nullptr;                       // OpenSL ES播放器播放接口
SLSeekItf playerSeek = nullptr;                       // OpenSL ES播放器跳转接口
SLPlaybackRateItf playerRate = nullptr;               // OpenSL ES播放器速率控制接口

std::atomic<long> g_audio_start_offset_ms(-1);        // 音频开始播放的偏移量 (毫秒)，-1表示从头播放

// 视频渲染线程函数
void video_render_loop_internal() {
    LOGI("视频渲染线程启动.");
    if (!g_native_window_render) {
        LOGE("渲染循环: Native window为空!");
        g_is_video_playing_flag = false;
        return;
    }
    if (g_video_width <= 0 || g_video_height <= 0) {
        LOGE("渲染循环: 视频尺寸无效 (%dx%d)", g_video_width, g_video_height);
        g_is_video_playing_flag = false;
        return;
    }

    int yuv_frame_size = g_video_width * g_video_height * 3 / 2; // YUV420p格式每帧字节数
    std::vector<uint8_t> yuv_data_buffer(yuv_frame_size);      // YUV帧数据缓冲区

    ANativeWindow_Buffer window_buffer;                        // 原生窗口缓冲区信息
    long current_file_frame_pos = 0;                           // 文件指针当前对应的帧号

    {
        std::lock_guard<std::mutex> lock(g_yuv_file_mutex_render); // 加锁保护文件操作
        if (g_yuv_file_ptr_for_render) {
            fclose(g_yuv_file_ptr_for_render);
            g_yuv_file_ptr_for_render = nullptr;
        }
        g_yuv_file_ptr_for_render = fopen(g_yuv_file_path_render_str.c_str(), "rb"); // 以只读二进制方式打开YUV文件
        if (!g_yuv_file_ptr_for_render) {
            LOGE("渲染循环: 打开YUV文件失败: %s", g_yuv_file_path_render_str.c_str());
            g_is_video_playing_flag = false;
            return;
        }
    }

    g_is_video_playing_flag = true; // 标记视频开始播放

    long initial_seek_frame = g_seek_target_frame.load(); // 获取初始跳转帧
    if (initial_seek_frame != -1) {
        std::lock_guard<std::mutex> lock(g_yuv_file_mutex_render);
        if (g_yuv_file_ptr_for_render) {
            long offset = initial_seek_frame * yuv_frame_size; // 计算文件偏移量
            if (fseek(g_yuv_file_ptr_for_render, offset, SEEK_SET) == 0) { // 跳转到指定帧
                current_file_frame_pos = initial_seek_frame;
                g_current_rendered_frame = initial_seek_frame;
                LOGI("渲染循环: 初始跳转到帧 %ld 成功", initial_seek_frame);
            } else {
                LOGE("渲染循环: 初始fseek到帧 %ld 失败. 将从头开始.", initial_seek_frame);
                fseek(g_yuv_file_ptr_for_render, 0, SEEK_SET); // 跳转失败则从头开始
                current_file_frame_pos = 0;
                g_current_rendered_frame = 0;
            }
        }
    } else { // 没有初始跳转请求，从头开始
        g_current_rendered_frame = 0;
        current_file_frame_pos = 0;
        std::lock_guard<std::mutex> lock(g_yuv_file_mutex_render);
        if (g_yuv_file_ptr_for_render) {
            fseek(g_yuv_file_ptr_for_render, 0, SEEK_SET);
        }
    }

    while (!g_abort_render_request.load()) { // 循环直到收到终止请求
        long seek_to_frame = g_seek_target_frame.exchange(-1); // 检查是否有新的跳转请求
        if (seek_to_frame != -1) { // 处理跳转请求
            std::lock_guard<std::mutex> lock(g_yuv_file_mutex_render);
            if (g_yuv_file_ptr_for_render) {
                long offset = seek_to_frame * yuv_frame_size;
                if (fseek(g_yuv_file_ptr_for_render, offset, SEEK_SET) == 0) {
                    current_file_frame_pos = seek_to_frame;
                    g_current_rendered_frame = seek_to_frame;
                    LOGI("渲染循环: 跳转到帧 %ld 成功", seek_to_frame);
                } else {
                    LOGE("渲染循环: fseek到帧 %ld 失败", seek_to_frame);
                }
            }
        }

        if (g_is_paused.load()) { // 如果暂停，则休眠并继续下一轮循环
            usleep(50 * 1000); // 休眠50毫秒
            continue;
        }

        size_t bytes_read;
        {
            std::lock_guard<std::mutex> lock(g_yuv_file_mutex_render);
            if (!g_yuv_file_ptr_for_render) { // 文件指针可能在外部被关闭
                LOGW("渲染循环: 读取时YUV文件指针为空.");
                break;
            }
            bytes_read = fread(yuv_data_buffer.data(), 1, yuv_frame_size, g_yuv_file_ptr_for_render); // 从文件读取一帧YUV数据
        }

        if (bytes_read != yuv_frame_size) { // 检查读取的字节数
            if (feof(g_yuv_file_ptr_for_render)) {
                LOGI("渲染循环: 到达YUV文件末尾.");
            } else if (bytes_read == 0 && !ferror(g_yuv_file_ptr_for_render)) {
                LOGI("渲染循环: 到达YUV文件末尾 (fread返回0, 无错误).");
            }
            else {
                LOGE("渲染循环: 从YUV文件读取错误. 期望 %d, 得到 %zu. 错误: %s", yuv_frame_size, bytes_read, strerror(errno));
            }
            break; // 文件结束或读取错误，退出循环
        }
        g_current_rendered_frame = current_file_frame_pos; // 更新当前渲染的帧号
        current_file_frame_pos++; // 文件帧位置前进


        if (ANativeWindow_lock(g_native_window_render, &window_buffer, nullptr) < 0) { // 锁定原生窗口缓冲区
            LOGE("渲染循环: 无法锁定原生窗口");
            break;
        }

        uint8_t *dst_bits = (uint8_t *) window_buffer.bits; // 目标缓冲区（RGBA）
        int dst_stride_bytes = window_buffer.stride * 4;    // 目标缓冲区每行字节数

        // YUV420p分量指针
        uint8_t* src_y = yuv_data_buffer.data();
        uint8_t* src_u = src_y + g_video_width * g_video_height;
        uint8_t* src_v = src_u + g_video_width * g_video_height / 4;

        // YUV420p 转 RGBA8888 (简易实现，可能未优化)
        for (int y_coord = 0; y_coord < g_video_height; y_coord++) {
            uint8_t *dst_line = dst_bits + y_coord * dst_stride_bytes;
            for (int x_coord = 0; x_coord < g_video_width; x_coord++) {
                int y_idx = y_coord * g_video_width + x_coord;
                int uv_x = x_coord / 2;
                int uv_y = y_coord / 2;
                int u_idx = uv_y * (g_video_width / 2) + uv_x;

                uint8_t Y_val = src_y[y_idx];
                uint8_t U_val = src_u[u_idx];
                uint8_t V_val = src_v[u_idx];

                int C = Y_val - 16;
                int D = U_val - 128;
                int E = V_val - 128;

                int R_val = (298 * C + 409 * E + 128) >> 8;
                int G_val = (298 * C - 100 * D - 208 * E + 128) >> 8;
                int B_val = (298 * C + 516 * D + 128) >> 8;

                dst_line[x_coord * 4 + 0] = static_cast<uint8_t>(std::max(0, std::min(255, R_val))); // R
                dst_line[x_coord * 4 + 1] = static_cast<uint8_t>(std::max(0, std::min(255, G_val))); // G
                dst_line[x_coord * 4 + 2] = static_cast<uint8_t>(std::max(0, std::min(255, B_val))); // B
                dst_line[x_coord * 4 + 3] = 255; // Alpha
            }
        }
        ANativeWindow_unlockAndPost(g_native_window_render); // 解锁并提交缓冲区进行显示

        double frame_rate_val = g_avg_frame_rate.load(); // 获取当前帧率
        if (frame_rate_val > 0.01) { // 帧率有效
            float current_speed = g_playback_speed.load(); // 获取当前播放速度
            if (current_speed <= 0.01f) current_speed = 0.01f; // 防止速度过小导致除零
            long delay_us = (long) (1000000.0 / (frame_rate_val * current_speed)); // 计算帧间隔 (微秒)
            if (delay_us > 1000) { // 最小延迟1毫秒
                usleep(delay_us);
            } else {
                usleep(1000);
            }
        } else { // 帧率无效，使用默认延迟
            usleep(33000); // 大约30fps的延迟
        }
    }

    { // 清理资源
        std::lock_guard<std::mutex> lock(g_yuv_file_mutex_render);
        if (g_yuv_file_ptr_for_render) {
            fclose(g_yuv_file_ptr_for_render);
            g_yuv_file_ptr_for_render = nullptr;
        }
    }
    LOGI("视频渲染线程结束.");
    g_is_video_playing_flag = false; // 标记视频播放结束
}


extern "C" {

// JNI函数：解码视频文件到YUV文件
JNIEXPORT jint JNICALL
Java_com_example_androidplayer_MainActivity_decodeVideoToFile(JNIEnv *env, jobject thiz,
                                                              jstring inputFilePath,
                                                              jstring outputFilePath) {
    const char *input_c = env->GetStringUTFChars(inputFilePath, nullptr);   // 输入文件路径
    const char *output_c = env->GetStringUTFChars(outputFilePath, nullptr); // 输出YUV文件路径

    AVFormatContext *pFormatCtx = nullptr;    // FFmpeg格式上下文
    AVCodecContext *pCodecCtxOrig = nullptr;  // FFmpeg原始解码器上下文
    AVCodec *pCodec = nullptr;                // FFmpeg解码器
    AVFrame *pFrame = nullptr;                // FFmpeg解码后的帧
    AVPacket packet;                          // FFmpeg数据包
    FILE *outFileYUV = nullptr;               // 输出YUV文件指针
    int videoStreamIdx = -1;                  // 视频流索引
    int ret = 0;                              // 返回值

    // 打开输入文件
    if (avformat_open_input(&pFormatCtx, input_c, nullptr, nullptr) != 0) {
        LOGE("无法打开输入文件: %s", input_c);
        ret = -1;
    }
    // 查找流信息
    if (avformat_find_stream_info(pFormatCtx, nullptr) < 0) {
        LOGE("无法找到 %s 的流信息", input_c);
        ret = -2;
    }

    // 查找视频流并获取参数
    for (unsigned int i = 0; i < pFormatCtx->nb_streams; i++) {
        if (pFormatCtx->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_VIDEO) {
            videoStreamIdx = i;
            g_video_width = pFormatCtx->streams[i]->codecpar->width;   // 获取视频宽度
            g_video_height = pFormatCtx->streams[i]->codecpar->height; // 获取视频高度
            AVRational avg_rate = pFormatCtx->streams[i]->avg_frame_rate;
            AVRational r_rate = pFormatCtx->streams[i]->r_frame_rate;
            if (avg_rate.num != 0 && avg_rate.den != 0) g_avg_frame_rate = av_q2d(avg_rate); // 获取平均帧率
            else if (r_rate.num != 0 && r_rate.den != 0) g_avg_frame_rate = av_q2d(r_rate); // 或实际帧率
            else g_avg_frame_rate = 25.0; // 默认帧率
            LOGI("视频流: %dx%d @ %f fps", g_video_width, g_video_height, g_avg_frame_rate.load());
            break;
        }
    }
    if (videoStreamIdx == -1) {
        LOGE("%s 中没有视频流", input_c);
        ret = -3;
    }

    // 获取解码器参数
    AVCodecParameters *pCodecPar = pFormatCtx->streams[videoStreamIdx]->codecpar;
    pCodec = avcodec_find_decoder(pCodecPar->codec_id); // 查找解码器
    if (!pCodec) {
        LOGE("不支持的解码器ID: %d", pCodecPar->codec_id);
        ret = -4; goto cleanup_decode;
    }
    pCodecCtxOrig = avcodec_alloc_context3(pCodec); // 分配解码器上下文
    if (!pCodecCtxOrig) {
        LOGE("无法分配解码器上下文");
        ret = -5; goto cleanup_decode;
    }
    if (avcodec_parameters_to_context(pCodecCtxOrig, pCodecPar) < 0) { // 拷贝解码器参数到上下文
        LOGE("无法拷贝解码器参数到上下文");
        ret = -6; goto cleanup_decode;
    }
    if (avcodec_open2(pCodecCtxOrig, pCodec, nullptr) < 0) { // 打开解码器
        LOGE("无法打开解码器");
        ret = -7; goto cleanup_decode;
    }
    pFrame = av_frame_alloc(); // 分配AVFrame
    if (!pFrame) {
        LOGE("无法分配帧");
        ret = -8; goto cleanup_decode;
    }
    outFileYUV = fopen(output_c, "wb"); // 打开输出YUV文件
    if (!outFileYUV) {
        LOGE("无法打开输出YUV文件: %s", output_c);
        ret = -9; goto cleanup_decode;
    }
    if (pCodecCtxOrig->pix_fmt != AV_PIX_FMT_YUV420P) { // 检查像素格式，期望YUV420P
        LOGW("视频流不是YUV420P格式: %s.", av_get_pix_fmt_name(pCodecCtxOrig->pix_fmt));
        // 此处未做转换，如果不是YUV420P，直接写入可能导致后续渲染问题
    }

    av_init_packet(&packet); // 初始化AVPacket
    packet.data = nullptr; packet.size = 0;

    // 读取视频帧并解码写入YUV文件
    while (av_read_frame(pFormatCtx, &packet) >= 0) {
        if (packet.stream_index == videoStreamIdx) { // 只处理视频流的包
            if (avcodec_send_packet(pCodecCtxOrig, &packet) == 0) { // 发送包到解码器
                while (avcodec_receive_frame(pCodecCtxOrig, pFrame) == 0) { // 从解码器接收帧
                    // 将YUV420P数据写入文件 (Y分量, U分量, V分量)
                    for(int i = 0; i < pFrame->height; i++) fwrite(pFrame->data[0] + i * pFrame->linesize[0], 1, pFrame->width, outFileYUV);
                    for(int i = 0; i < pFrame->height / 2; i++) fwrite(pFrame->data[1] + i * pFrame->linesize[1], 1, pFrame->width / 2, outFileYUV);
                    for(int i = 0; i < pFrame->height / 2; i++) fwrite(pFrame->data[2] + i * pFrame->linesize[2], 1, pFrame->width / 2, outFileYUV);
                    av_frame_unref(pFrame); // 释放帧引用
                }
            }
        }
        av_packet_unref(&packet); // 释放包引用
    }
    // 冲洗解码器中剩余的帧
    avcodec_send_packet(pCodecCtxOrig, nullptr);
    while (avcodec_receive_frame(pCodecCtxOrig, pFrame) == 0) {
        for(int i = 0; i < pFrame->height; i++) fwrite(pFrame->data[0] + i * pFrame->linesize[0], 1, pFrame->width, outFileYUV);
        for(int i = 0; i < pFrame->height / 2; i++) fwrite(pFrame->data[1] + i * pFrame->linesize[1], 1, pFrame->width / 2, outFileYUV);
        for(int i = 0; i < pFrame->height / 2; i++) fwrite(pFrame->data[2] + i * pFrame->linesize[2], 1, pFrame->width / 2, outFileYUV);
        av_frame_unref(pFrame);
    }
    LOGI("解码到YUV完成.");
    ret = 0; // 解码成功

    cleanup_decode: // 清理资源
    if (outFileYUV) fclose(outFileYUV);
    if (pFrame) av_frame_free(&pFrame);
    if (pCodecCtxOrig) avcodec_free_context(&pCodecCtxOrig);
    if (pFormatCtx) avformat_close_input(&pFormatCtx);
    env->ReleaseStringUTFChars(inputFilePath, input_c);
    env->ReleaseStringUTFChars(outputFilePath, output_c);
    return ret;
}


// JNI函数：停止本地视频播放
JNIEXPORT void JNICALL
Java_com_example_androidplayer_MainActivity_nativeStopVideoPlayback(JNIEnv *env, jobject thiz) {
    LOGI("请求停止本地视频播放.");
    g_abort_render_request = true; // 设置终止渲染请求标志
    g_is_paused = false;           // 清除暂停标志，以防线程卡在暂停状态
    if (g_video_render_thread.joinable()) { // 如果渲染线程可加入
        g_video_render_thread.join();       // 等待渲染线程结束
        LOGI("视频渲染线程已加入.");
    } else {
        LOGI("视频渲染线程不可加入.");
    }
    g_is_video_playing_flag = false; // 标记视频播放结束
    if (g_native_window_render) {    // 释放原生窗口
        ANativeWindow_release(g_native_window_render);
        g_native_window_render = nullptr;
        LOGI("原生窗口已释放.");
    }
    g_current_rendered_frame = 0; // 重置当前渲染帧
    g_seek_target_frame = -1;     // 重置跳转目标帧
    LOGI("本地视频播放已停止.");
}

// JNI函数：开始本地视频播放
JNIEXPORT void JNICALL
Java_com_example_androidplayer_MainActivity_nativeStartVideoPlayback(JNIEnv *env, jobject thiz,
                                                                     jstring yuv_file_path_java,
                                                                     jobject surface) {
    if (g_is_video_playing_flag.load()) { // 如果视频已在播放，先停止旧的
        LOGW("视频播放已在运行. 正在停止上一个.");
        Java_com_example_androidplayer_MainActivity_nativeStopVideoPlayback(env, thiz);
    }
    const char* yuv_path_c_str = env->GetStringUTFChars(yuv_file_path_java, nullptr); // 获取YUV文件路径
    g_yuv_file_path_render_str = yuv_path_c_str; // 保存到全局变量
    env->ReleaseStringUTFChars(yuv_file_path_java, yuv_path_c_str);

    if (g_native_window_render) ANativeWindow_release(g_native_window_render); // 释放旧的原生窗口（如果存在）
    g_native_window_render = ANativeWindow_fromSurface(env, surface); // 从Java Surface获取原生窗口
    if (!g_native_window_render) { LOGE("获取原生窗口失败."); return; }

    if(g_video_width > 0 && g_video_height > 0) { // 检查视频尺寸是否有效
        // 设置原生窗口缓冲区几何属性
        if (ANativeWindow_setBuffersGeometry(g_native_window_render, g_video_width, g_video_height, WINDOW_FORMAT_RGBA_8888) < 0) {
            LOGE("设置原生窗口缓冲区几何属性失败.");
            ANativeWindow_release(g_native_window_render); g_native_window_render = nullptr; return;
        }
        LOGI("原生窗口缓冲区几何属性已设置: %dx%d", g_video_width, g_video_height);
    } else {
        LOGE("视频尺寸无效: %dx%d.", g_video_width, g_video_height);
        ANativeWindow_release(g_native_window_render); g_native_window_render = nullptr; return;
    }
    g_abort_render_request = false; // 清除终止请求标志
    g_is_paused = false;            // 清除暂停标志
    if (g_video_render_thread.joinable()) g_video_render_thread.join(); // 等待旧线程结束（如果存在）
    g_video_render_thread = std::thread(video_render_loop_internal);    // 创建并启动新的渲染线程
    LOGI("本地视频播放线程已启动.");
}

// JNI函数：暂停本地视频播放
JNIEXPORT void JNICALL Java_com_example_androidplayer_MainActivity_nativePauseVideo(JNIEnv *env, jobject thiz) {
    g_is_paused = true; // 设置暂停标志
    LOGI("本地视频已暂停.");
}
// JNI函数：恢复本地视频播放
JNIEXPORT void JNICALL Java_com_example_androidplayer_MainActivity_nativeResumeVideo(JNIEnv *env, jobject thiz) {
    g_is_paused = false; // 清除暂停标志
    LOGI("本地视频已恢复.");
}

// JNI函数：设置本地视频播放速度
JNIEXPORT void JNICALL
Java_com_example_androidplayer_MainActivity_nativeSetSpeed(JNIEnv *env, jobject thiz, jfloat speed) {
    if (speed > 0.0f) { // 速度必须大于0
        g_playback_speed = speed; // 设置播放速度
        LOGI("本地视频帧速度因子已设置为: %f", speed);
        // 注意：此速度也用于 nativeSetAudioPlaybackRate
    } else {
        LOGW("无效的视频速度因子: %f.", speed);
    }
}

// JNI函数：本地视频跳转到指定帧
JNIEXPORT void JNICALL
Java_com_example_androidplayer_MainActivity_nativeSeekToFrame(JNIEnv *env, jobject thiz, jint frame_num) {
    if (frame_num >= 0) { // 帧号必须非负
        g_seek_target_frame = frame_num; // 设置跳转目标帧
        LOGI("本地视频跳转到帧: %d", frame_num);
    }
    else {
        LOGW("无效的跳转帧: %d", frame_num);
    }
}

// JNI函数：获取本地视频总帧数
JNIEXPORT jint JNICALL
Java_com_example_androidplayer_MainActivity_nativeGetTotalFrames(JNIEnv *env, jobject thiz, jstring yuv_file_path_java) {
    if (g_video_width <= 0 || g_video_height <= 0) { LOGE("无法获取总帧数: 视频尺寸无效."); return 0; }
    const char *yuv_c = env->GetStringUTFChars(yuv_file_path_java, nullptr); // 获取YUV文件路径
    FILE *fp = fopen(yuv_c, "rb"); // 打开文件
    if (!fp) { LOGE("无法打开YUV文件 '%s' 以获取总帧数.", yuv_c); env->ReleaseStringUTFChars(yuv_file_path_java, yuv_c); return 0; }
    fseek(fp, 0, SEEK_END);      // 跳转到文件末尾
    long file_size = ftell(fp);  // 获取文件大小
    fclose(fp);                  // 关闭文件
    env->ReleaseStringUTFChars(yuv_file_path_java, yuv_c);
    int single_frame_size = g_video_width * g_video_height * 3 / 2; // 计算单帧大小 (YUV420p)
    if (single_frame_size == 0) { LOGE("无法获取总帧数: 帧大小为零."); return 0; }
    return (jint) (file_size / single_frame_size); // 总帧数 = 文件大小 / 单帧大小
}

// JNI函数：获取本地视频当前播放帧号
JNIEXPORT jint JNICALL Java_com_example_androidplayer_MainActivity_nativeGetCurrentFrame(JNIEnv *env, jobject thiz) {
    return (jint) g_current_rendered_frame.load(); // 返回当前渲染的帧号
}
// JNI函数：获取本地视频帧率
JNIEXPORT jdouble JNICALL Java_com_example_androidplayer_MainActivity_nativeGetFrameRate(JNIEnv *env, jobject thiz) {
    return g_avg_frame_rate.load(); // 返回平均帧率
}


// --- 音频部分 ---
// JNI函数：初始化OpenSL ES音频引擎
JNIEXPORT jint JNICALL
Java_com_example_androidplayer_MainActivity_initAudio(JNIEnv *env, jobject thiz, jstring inputFilePath) {
    SLresult result;
    // 清理旧的引擎和输出混音器 (如果存在)
    if (outputMixObject) { (*outputMixObject)->Destroy(outputMixObject); outputMixObject = nullptr; }
    if (engineObject) { (*engineObject)->Destroy(engineObject); engineObject = nullptr; engineEngine = nullptr; }

    // 创建引擎对象
    result = slCreateEngine(&engineObject, 0, nullptr, 0, nullptr, nullptr);
    if (result!=SL_RESULT_SUCCESS) {LOGE("slCreateEngine失败: %u",result); return -1;}
    // 实现引擎对象
    result = (*engineObject)->Realize(engineObject, SL_BOOLEAN_FALSE);
    if (result!=SL_RESULT_SUCCESS) {LOGE("引擎Realize失败: %u",result); (*engineObject)->Destroy(engineObject); engineObject=nullptr; return -1;}
    // 获取引擎接口
    result = (*engineObject)->GetInterface(engineObject, SL_IID_ENGINE, &engineEngine);
    if (result!=SL_RESULT_SUCCESS) {LOGE("引擎GetInterface失败: %u",result); (*engineObject)->Destroy(engineObject); engineObject=nullptr; return -1;}
    // 创建输出混音器对象
    result = (*engineEngine)->CreateOutputMix(engineEngine, &outputMixObject, 0, nullptr, nullptr);
    if (result!=SL_RESULT_SUCCESS) {LOGE("CreateOutputMix失败: %u",result); (*engineObject)->Destroy(engineObject); engineObject=nullptr; return -1;}
    // 实现输出混音器对象
    result = (*outputMixObject)->Realize(outputMixObject, SL_BOOLEAN_FALSE);
    if (result!=SL_RESULT_SUCCESS) {LOGE("输出混音器Realize失败: %u",result); (*outputMixObject)->Destroy(outputMixObject); outputMixObject=nullptr; (*engineObject)->Destroy(engineObject); engineObject=nullptr; return -1;}
    LOGI("OpenSL ES音频引擎已初始化.");
    return 0;
}


// JNI函数：设置音频播放速率
JNIEXPORT void JNICALL
Java_com_example_androidplayer_MainActivity_nativeSetAudioPlaybackRate(JNIEnv *env, jobject thiz, jfloat rateFactor) {
    if (playerRate != nullptr) { // 检查播放速率接口是否有效
        // 将浮点速率因子 (例如 1.0, 1.5) 转换为 SLpermille (千分之几，如 1000, 1500)
        SLpermille ratePermille = static_cast<SLpermille>(roundf(rateFactor * 1000.0f)); // 使用 roundf 处理浮点数

        SLpermille minRate, maxRate, stepSize;
        SLuint32 capabilities;
        // 获取支持的速率范围
        SLresult result = (*playerRate)->GetRateRange(playerRate, 0, &minRate, &maxRate, &stepSize, &capabilities);

        if (result == SL_RESULT_SUCCESS) { // 成功获取范围
            if (ratePermille < minRate) { // 检查是否低于最小速率
                LOGW("请求速率 %d permille 低于最小速率 %d. 将限制到最小速率.", ratePermille, minRate);
                ratePermille = minRate;
            } else if (ratePermille > maxRate) { // 检查是否高于最大速率
                LOGW("请求速率 %d permille 高于最大速率 %d. 将限制到最大速率.", ratePermille, maxRate);
                ratePermille = maxRate;
            }
        } else {
            LOGW("获取速率范围失败 (错误: %u). 将继续使用请求速率 %d permille.", result, ratePermille);
        }

        // 尝试设置速率
        result = (*playerRate)->SetRate(playerRate, ratePermille);
        if (result == SL_RESULT_SUCCESS) {
            LOGI("音频播放速率已设置为 %d permille (%.2fx)", ratePermille, rateFactor);
        } else {
            LOGE("设置音频播放速率到 %d permille 失败. 错误: %u", ratePermille, result);
        }
    } else {
        LOGW("音频播放器速率接口 (playerRate) 为空. 无法设置播放速率.");
    }
}

// JNI函数：开始播放音频
JNIEXPORT void JNICALL
Java_com_example_androidplayer_MainActivity_startAudio(JNIEnv *env, jobject thiz, jstring inputFilePath, jlong startOffsetMs) {
    if (!engineEngine) { LOGE("音频引擎未初始化!"); return; } // 检查引擎是否初始化
    SLresult result;
    const char *input_c = env->GetStringUTFChars(inputFilePath, nullptr); // 获取音频文件路径

    // 清理旧的播放器相关对象 (如果存在)
    if (playerObject != nullptr) {
        (*playerObject)->Destroy(playerObject);
        playerObject = nullptr;
        playerPlay = nullptr;
        playerSeek = nullptr;
        playerRate = nullptr;
    }

    // 配置数据源 (URI)
    SLDataLocator_URI loc_uri = {SL_DATALOCATOR_URI, (SLchar *) input_c};
    SLDataFormat_MIME format_mime = {SL_DATAFORMAT_MIME, nullptr, SL_CONTAINERTYPE_UNSPECIFIED};
    SLDataSource audioSrc = {&loc_uri, &format_mime};

    // 配置数据接收器 (输出混音器)
    SLDataLocator_OutputMix loc_outmix = {SL_DATALOCATOR_OUTPUTMIX, outputMixObject};
    SLDataSink audioSnk = {&loc_outmix, nullptr};

    // 请求播放、跳转和速率控制接口
    const SLInterfaceID ids[3] = {SL_IID_PLAY, SL_IID_SEEK, SL_IID_PLAYBACKRATE};
    const SLboolean req[3] = {SL_BOOLEAN_TRUE, SL_BOOLEAN_TRUE, SL_BOOLEAN_TRUE};
    int numInterfaces = 3;

    // 创建音频播放器对象
    result = (*engineEngine)->CreateAudioPlayer(engineEngine, &playerObject, &audioSrc, &audioSnk, numInterfaces, ids, req);
    if (result != SL_RESULT_SUCCESS) { LOGE("创建音频播放器失败: %u", result); env->ReleaseStringUTFChars(inputFilePath, input_c); return; }

    // 实现音频播放器对象
    result = (*playerObject)->Realize(playerObject, SL_BOOLEAN_FALSE);
    if (result != SL_RESULT_SUCCESS) { LOGE("实现音频播放器失败: %u", result); (*playerObject)->Destroy(playerObject); playerObject = nullptr; env->ReleaseStringUTFChars(inputFilePath, input_c); return; }

    // 获取播放接口
    result = (*playerObject)->GetInterface(playerObject, SL_IID_PLAY, &playerPlay);
    if (result != SL_RESULT_SUCCESS) { LOGE("获取播放接口失败: %u", result); (*playerObject)->Destroy(playerObject); playerObject = nullptr; env->ReleaseStringUTFChars(inputFilePath, input_c); return; }

    // 获取跳转接口
    result = (*playerObject)->GetInterface(playerObject, SL_IID_SEEK, &playerSeek);
    if (result != SL_RESULT_SUCCESS) {
        LOGW("获取跳转接口失败: %u. 音频跳转将不可用.", result);
        playerSeek = nullptr; // 标记跳转接口不可用
    }

    // 获取速率控制接口
    result = (*playerObject)->GetInterface(playerObject, SL_IID_PLAYBACKRATE, &playerRate);
    if (result != SL_RESULT_SUCCESS) {
        LOGW("获取播放速率接口失败: %u. 音频速度控制将不可用.", result);
        playerRate = nullptr; // 标记速率控制接口不可用
    } else {
        // (可选) 查询并打印支持的速率范围和能力
        SLpermille minRateVal, maxRateVal, stepSizeVal;
        SLuint32 capabilitiesVal;
        (*playerRate)->GetRateRange(playerRate, 0, &minRateVal, &maxRateVal, &stepSizeVal, &capabilitiesVal);
        LOGI("音频播放速率范围: min=%d, max=%d, step=%d, capabilities=0x%X", minRateVal, maxRateVal, stepSizeVal, capabilitiesVal);
    }

    // 确定实际的开始偏移量
    long actualStartOffset = (startOffsetMs >= 0) ? startOffsetMs : g_audio_start_offset_ms.exchange(-1);

    if (actualStartOffset >= 0 && playerSeek != nullptr) { // 如果需要跳转且跳转接口可用
        LOGI("音频播放前跳转到 %ld ms.", actualStartOffset);
        result = (*playerPlay)->SetPlayState(playerPlay, SL_PLAYSTATE_PAUSED); // 先暂停播放器
        if (result == SL_RESULT_SUCCESS) {
            result = (*playerSeek)->SetLoop(playerSeek, SL_BOOLEAN_FALSE, 0, SL_TIME_UNKNOWN); // 设置不循环
            if (result != SL_RESULT_SUCCESS) LOGW("跳转前设置循环为false失败: %u", result);

            result = (*playerSeek)->SetPosition(playerSeek, (SLmillisecond)actualStartOffset, SL_SEEKMODE_ACCURATE); // 执行跳转
            if (result != SL_RESULT_SUCCESS) {
                LOGE("音频跳转到 %ld ms失败, 错误: %u. 将从头播放.", actualStartOffset, result);
            } else {
                LOGI("音频跳转到 %ld ms成功.", actualStartOffset);
            }
        } else {
            LOGE("跳转前设置音频为PAUSED失败, 错误: %u.", result);
        }
    } else if (actualStartOffset >= 0) { // 需要跳转但接口不可用
        LOGW("请求音频跳转到 %ld ms, 但跳转接口不可用或偏移无效.", actualStartOffset);
    }

    // 在开始播放前应用当前的全局播放速度到音频
    // g_playback_speed 由Java的nativeSetSpeed控制，也用于视频帧延迟
    Java_com_example_androidplayer_MainActivity_nativeSetAudioPlaybackRate(env, thiz, g_playback_speed.load());


    // 设置播放状态为播放中
    result = (*playerPlay)->SetPlayState(playerPlay, SL_PLAYSTATE_PLAYING);
    if (result != SL_RESULT_SUCCESS) {
        LOGE("设置播放状态为playing失败: %u", result);
        (*playerObject)->Destroy(playerObject); // 清理播放器对象
        playerObject = nullptr;
        playerPlay = nullptr;
        playerSeek = nullptr;
        playerRate = nullptr;
        env->ReleaseStringUTFChars(inputFilePath, input_c);
        return;
    }

    LOGI("音频播放已从文件开始: %s", input_c);
    env->ReleaseStringUTFChars(inputFilePath, input_c);
}

// JNI函数：停止音频播放
JNIEXPORT void JNICALL
Java_com_example_androidplayer_MainActivity_stopAudio(JNIEnv *env, jobject thiz) {
    if (playerObject != nullptr) { // 检查播放器对象是否存在
        if (playerPlay != nullptr) { // 检查播放接口是否存在
            SLuint32 state;
            (*playerPlay)->GetPlayState(playerPlay, &state); // 获取当前播放状态
            if (state != SL_PLAYSTATE_STOPPED) { // 如果不是已停止状态
                (*playerPlay)->SetPlayState(playerPlay, SL_PLAYSTATE_STOPPED); // 设置为停止状态
            }
        }
        (*playerObject)->Destroy(playerObject); // 销毁播放器对象
        playerObject = nullptr;
        playerPlay = nullptr;
        playerSeek = nullptr;
        playerRate = nullptr;
        LOGI("音频播放器已停止并销毁.");
    }
    g_audio_start_offset_ms = -1; // 重置音频开始偏移
}

// JNI函数：暂停或恢复音频播放
JNIEXPORT void JNICALL
Java_com_example_androidplayer_MainActivity_pauseAudio(JNIEnv *env, jobject thiz, jboolean do_pause) {
    if (playerPlay != nullptr) { // 检查播放接口是否存在
        SLuint32 targetState = do_pause ? SL_PLAYSTATE_PAUSED : SL_PLAYSTATE_PLAYING; // 确定目标状态
        SLuint32 currentState;
        SLresult result_getState = (*playerPlay)->GetPlayState(playerPlay,&currentState); // 获取当前状态

        if (result_getState == SL_RESULT_SUCCESS) { // 获取状态成功
            if (currentState != targetState) { // 如果当前状态不是目标状态
                SLresult result_setState = (*playerPlay)->SetPlayState(playerPlay, targetState); // 设置目标状态
                if (result_setState == SL_RESULT_SUCCESS) {
                    LOGI("音频播放状态已设置为 %s", do_pause ? "PAUSED" : "PLAYING");
                } else {
                    LOGE("设置音频播放状态为 %s 失败, 错误: %u", do_pause ? "PAUSED" : "PLAYING", result_setState);
                }
            } else { // 已处于目标状态
                LOGI("音频已处于期望状态: %s", do_pause ? "PAUSED" : "PLAYING");
            }
        } else { // 获取状态失败
            LOGE("在pauseAudio中获取当前音频播放状态失败, 错误: %u", result_getState);
        }
    } else { // 播放器未初始化
        LOGE("音频播放器未初始化, 无法 %s.", do_pause ? "暂停" : "恢复");
    }
}

// JNI函数：音频跳转到指定时间戳
JNIEXPORT void JNICALL
Java_com_example_androidplayer_MainActivity_nativeSeekAudioToTimestamp(JNIEnv *env, jobject thiz, jlong timeMs) {
    if (timeMs < 0) { // 时间戳必须非负
        LOGW("无效的音频跳转时间戳: %lld ms. 已忽略.", timeMs);
        return;
    }

    if (playerPlay != nullptr && playerSeek != nullptr) { // 检查播放和跳转接口是否有效
        SLuint32 currentState;
        SLresult result_getState = (*playerPlay)->GetPlayState(playerPlay,&currentState); // 获取当前状态
        if (result_getState != SL_RESULT_SUCCESS) {
            LOGE("在nativeSeekAudioToTimestamp中获取跳转前当前音频状态失败: %u.", result_getState);
        }

        bool was_playing = (result_getState == SL_RESULT_SUCCESS && currentState == SL_PLAYSTATE_PLAYING); // 判断跳转前是否在播放
        SLresult result;

        if (was_playing) { // 如果在播放，先暂停
            result = (*playerPlay)->SetPlayState(playerPlay, SL_PLAYSTATE_PAUSED);
            if (result != SL_RESULT_SUCCESS) {
                LOGW("跳转前暂停音频失败: %u. 跳转可能不太准确.", result);
            }
        }

        result = (*playerSeek)->SetLoop(playerSeek, SL_BOOLEAN_FALSE, 0, SL_TIME_UNKNOWN); // 设置不循环
        if (result != SL_RESULT_SUCCESS) LOGW("跳转时设置循环为false失败: %u", result);

        result = (*playerSeek)->SetPosition(playerSeek, (SLmillisecond)timeMs, SL_SEEKMODE_ACCURATE); // 执行跳转
        if (result == SL_RESULT_SUCCESS) {
            LOGI("音频已跳转到 %lld ms.", timeMs);
        } else {
            LOGE("音频跳转到 %lld ms失败, 错误: %u", timeMs, result);
        }

        if (was_playing) { // 如果跳转前在播放，则恢复播放
            result = (*playerPlay)->SetPlayState(playerPlay, SL_PLAYSTATE_PLAYING);
            if (result != SL_RESULT_SUCCESS) {
                LOGE("跳转后恢复音频播放失败: %u.", result);
            }
        }
        // 如果音频原本是停止状态，跳转后不自动播放，而是记录偏移供下次startAudio使用
        if (result_getState == SL_RESULT_SUCCESS && currentState == SL_PLAYSTATE_STOPPED) {
            g_audio_start_offset_ms = timeMs; // 记录跳转位置
            LOGI("音频已停止. 播放器跳转到 %lld ms, 下次启动时将从该位置播放.", timeMs);
        }

    } else { // 播放器未准备好或跳转接口不可用
        g_audio_start_offset_ms = timeMs; // 存储目标时间戳供下次播放时使用
        LOGW("音频播放器未准备好跳转 (或跳转接口不可用). 已存储目标 %lld ms 供下次播放.", timeMs);
    }
}

} // extern "C"