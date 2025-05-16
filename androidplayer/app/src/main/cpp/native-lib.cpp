#include <jni.h>
#include <string>
#include <android/log.h>
#include <mutex>
#include <condition_variable>
#include <atomic>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <thread>
#include <unistd.h> // For usleep
#include <SLES/OpenSLES.h>
#include <SLES/OpenSLES_Android.h>
#include <SLES/OpenSLES_AndroidConfiguration.h>
#include <algorithm> // For std::max, std::min
#include <vector>

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
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

// --- 全局播放控制变量 ---
std::atomic<bool> g_is_video_playing_flag(false);
std::atomic<bool> g_is_paused(false); // 由Java层控制，native层响应
std::atomic<float> g_playback_speed(1.0f);
std::atomic<bool> g_abort_render_request(false);
std::atomic<long> g_seek_target_frame(-1);
std::atomic<long> g_current_rendered_frame(0);

// --- 视频参数 ---
int g_video_width = 0;
int g_video_height = 0;
std::atomic<double> g_avg_frame_rate(25.0); // 默认值, 会从视频文件获取

// --- 视频渲染线程与资源 ---
std::thread g_video_render_thread;
FILE *g_yuv_file_ptr_for_render = nullptr;
std::mutex g_yuv_file_mutex_render;
ANativeWindow *g_native_window_render = nullptr;
std::string g_yuv_file_path_render_str;

// --- OpenSL ES 相关 ---
SLObjectItf engineObject = nullptr;
SLEngineItf engineEngine = nullptr;
SLObjectItf outputMixObject = nullptr;
SLObjectItf playerObject = nullptr;
SLPlayItf playerPlay = nullptr;
SLSeekItf playerSeek = nullptr;

std::atomic<long> g_audio_start_offset_ms(-1); // 记录停止状态下seek后，下次播放的音频起始点


// 视频渲染线程函数
void video_render_loop_internal() {
    LOGI("Video render thread started.");
    if (!g_native_window_render) {
        LOGE("RenderLoop: Native window is null!");
        g_is_video_playing_flag = false;
        return;
    }
    if (g_video_width <= 0 || g_video_height <= 0) {
        LOGE("RenderLoop: Video dimensions invalid (%dx%d)", g_video_width, g_video_height);
        g_is_video_playing_flag = false;
        return;
    }

    int yuv_frame_size = g_video_width * g_video_height * 3 / 2; // YUV420p
    std::vector<uint8_t> yuv_data_buffer(yuv_frame_size);

    ANativeWindow_Buffer window_buffer;
    long current_file_frame_pos = 0; // 文件指针对应的帧号

    {
        std::lock_guard<std::mutex> lock(g_yuv_file_mutex_render);
        if (g_yuv_file_ptr_for_render) {
            fclose(g_yuv_file_ptr_for_render);
            g_yuv_file_ptr_for_render = nullptr;
        }
        g_yuv_file_ptr_for_render = fopen(g_yuv_file_path_render_str.c_str(), "rb");
        if (!g_yuv_file_ptr_for_render) {
            LOGE("RenderLoop: Failed to open YUV file: %s", g_yuv_file_path_render_str.c_str());
            g_is_video_playing_flag = false;
            return;
        }
    }

    g_is_video_playing_flag = true;

    long initial_seek_frame = g_seek_target_frame.load();
    if (initial_seek_frame != -1) {
        std::lock_guard<std::mutex> lock(g_yuv_file_mutex_render);
        if (g_yuv_file_ptr_for_render) {
            long offset = initial_seek_frame * yuv_frame_size;
            if (fseek(g_yuv_file_ptr_for_render, offset, SEEK_SET) == 0) {
                current_file_frame_pos = initial_seek_frame;
                g_current_rendered_frame = initial_seek_frame;
                LOGI("RenderLoop: Initial seek to frame %ld successful", initial_seek_frame);
            } else {
                LOGE("RenderLoop: Initial fseek to frame %ld failed. Will start from beginning.", initial_seek_frame);
                fseek(g_yuv_file_ptr_for_render, 0, SEEK_SET);
                current_file_frame_pos = 0;
                g_current_rendered_frame = 0;
            }
        }
        // Consumed, reset unless Java logic re-sets it for a specific reason
        // g_seek_target_frame.store(-1);
    } else {
        g_current_rendered_frame = 0;
        current_file_frame_pos = 0;
        // Ensure file pointer is at the beginning if no initial seek
        std::lock_guard<std::mutex> lock(g_yuv_file_mutex_render);
        if (g_yuv_file_ptr_for_render) {
            fseek(g_yuv_file_ptr_for_render, 0, SEEK_SET);
        }
    }


    while (!g_abort_render_request.load()) {
        long seek_to_frame = g_seek_target_frame.exchange(-1);
        if (seek_to_frame != -1) {
            std::lock_guard<std::mutex> lock(g_yuv_file_mutex_render);
            if (g_yuv_file_ptr_for_render) {
                long offset = seek_to_frame * yuv_frame_size;
                if (fseek(g_yuv_file_ptr_for_render, offset, SEEK_SET) == 0) {
                    current_file_frame_pos = seek_to_frame;
                    g_current_rendered_frame = seek_to_frame;
                    LOGI("RenderLoop: Seek to frame %ld successful", seek_to_frame);
                } else {
                    LOGE("RenderLoop: fseek to frame %ld failed", seek_to_frame);
                }
            }
        }

        if (g_is_paused.load()) {
            usleep(50 * 1000);
            continue;
        }

        size_t bytes_read;
        {
            std::lock_guard<std::mutex> lock(g_yuv_file_mutex_render);
            if (!g_yuv_file_ptr_for_render) {
                LOGW("RenderLoop: YUV file pointer became null during read attempt.");
                break;
            }
            bytes_read = fread(yuv_data_buffer.data(), 1, yuv_frame_size, g_yuv_file_ptr_for_render);
        }

        if (bytes_read != yuv_frame_size) {
            if (feof(g_yuv_file_ptr_for_render)) {
                LOGI("RenderLoop: End of YUV file reached.");
            } else if (bytes_read == 0 && !ferror(g_yuv_file_ptr_for_render)) {
                // Sometimes fread returns 0 at EOF instead of a short read, check ferror too.
                LOGI("RenderLoop: End of YUV file reached (fread returned 0, no error).");
            }
            else {
                LOGE("RenderLoop: Read error from YUV file. Expected %d, got %zu. Error: %s", yuv_frame_size, bytes_read, strerror(errno));
            }
            break;
        }
        g_current_rendered_frame = current_file_frame_pos;
        current_file_frame_pos++;


        if (ANativeWindow_lock(g_native_window_render, &window_buffer, nullptr) < 0) {
            LOGE("RenderLoop: Cannot lock native window");
            break;
        }

        uint8_t *dst_bits = (uint8_t *) window_buffer.bits;
        int dst_stride_bytes = window_buffer.stride * 4; // Stride in bytes for RGBA

        uint8_t* src_y = yuv_data_buffer.data();
        uint8_t* src_u = src_y + g_video_width * g_video_height;
        uint8_t* src_v = src_u + g_video_width * g_video_height / 4;

        for (int y_coord = 0; y_coord < g_video_height; y_coord++) {
            uint8_t *dst_line = dst_bits + y_coord * dst_stride_bytes;
            for (int x_coord = 0; x_coord < g_video_width; x_coord++) {
                int y_idx = y_coord * g_video_width + x_coord;
                int uv_x = x_coord / 2;
                int uv_y = y_coord / 2;
                int u_idx = uv_y * (g_video_width / 2) + uv_x;
                // v_idx is same as u_idx for YUV420p separate planes U and V after Y plane.

                uint8_t Y_val = src_y[y_idx];
                uint8_t U_val = src_u[u_idx];
                uint8_t V_val = src_v[u_idx]; // Assuming V plane directly follows U plane and has same indexing logic.

                // Standard YUV to RGB conversion (BT.601 for SD, can be BT.709 for HD)
                // Using integer arithmetic for performance (common approximation)
                int C = Y_val - 16;
                int D = U_val - 128;
                int E = V_val - 128;

                int R_val = (298 * C + 409 * E + 128) >> 8;
                int G_val = (298 * C - 100 * D - 208 * E + 128) >> 8;
                int B_val = (298 * C + 516 * D + 128) >> 8;

                // Clipping to [0, 255]
                dst_line[x_coord * 4 + 0] = static_cast<uint8_t>(std::max(0, std::min(255, R_val))); // R
                dst_line[x_coord * 4 + 1] = static_cast<uint8_t>(std::max(0, std::min(255, G_val))); // G
                dst_line[x_coord * 4 + 2] = static_cast<uint8_t>(std::max(0, std::min(255, B_val))); // B
                dst_line[x_coord * 4 + 3] = 255; // Alpha (opaque)
            }
        }
        ANativeWindow_unlockAndPost(g_native_window_render);

        double frame_rate_val = g_avg_frame_rate.load();
        if (frame_rate_val > 0.01) {
            float current_speed = g_playback_speed.load();
            if (current_speed <= 0.01f) current_speed = 0.01f;
            long delay_us = (long) (1000000.0 / (frame_rate_val * current_speed));
            if (delay_us > 1000) { // Minimum sleep of 1ms
                usleep(delay_us);
            } else {
                usleep(1000);
            }
        } else {
            usleep(33000); // Default to ~30fps
        }
    }

    {
        std::lock_guard<std::mutex> lock(g_yuv_file_mutex_render);
        if (g_yuv_file_ptr_for_render) {
            fclose(g_yuv_file_ptr_for_render);
            g_yuv_file_ptr_for_render = nullptr;
        }
    }
    LOGI("Video render thread finished.");
    g_is_video_playing_flag = false;
}


extern "C" {

JNIEXPORT jint JNICALL
Java_com_example_androidplayer_MainActivity_decodeVideoToFile(JNIEnv *env, jobject /* this */,
                                                              jstring inputFilePath,
                                                              jstring outputFilePath) {
    const char *input_c = env->GetStringUTFChars(inputFilePath, nullptr);
    const char *output_c = env->GetStringUTFChars(outputFilePath, nullptr);
    LOGI("Decode: Input: %s, Output: %s", input_c, output_c);

    // Initialize all FFmpeg pointers to nullptr
    AVFormatContext *pFormatCtx = nullptr;
    AVCodecContext *pCodecCtxOrig = nullptr;
    AVCodec *pCodec = nullptr;
    AVFrame *pFrame = nullptr;
    AVPacket packet; // av_init_packet will initialize it later
    FILE *outFileYUV = nullptr;

    int videoStreamIdx = -1;
    int ret = 0; // Return code

    // avformat_network_init(); // Only if dealing with network streams

    if (avformat_open_input(&pFormatCtx, input_c, nullptr, nullptr) != 0) {
        LOGE("Cannot open input file: %s", input_c);
        ret = -1;return 0;
    }

    if (avformat_find_stream_info(pFormatCtx, nullptr) < 0) {
        LOGE("Cannot find stream information for %s", input_c);
        ret = -2; return 0;
    }

    for (unsigned int i = 0; i < pFormatCtx->nb_streams; i++) {
        if (pFormatCtx->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_VIDEO) {
            videoStreamIdx = i;
            g_video_width = pFormatCtx->streams[i]->codecpar->width;
            g_video_height = pFormatCtx->streams[i]->codecpar->height;

            AVRational avg_rate = pFormatCtx->streams[i]->avg_frame_rate;
            AVRational r_rate = pFormatCtx->streams[i]->r_frame_rate;
            if (avg_rate.num != 0 && avg_rate.den != 0) {
                g_avg_frame_rate = av_q2d(avg_rate);
            } else if (r_rate.num != 0 && r_rate.den != 0) {
                g_avg_frame_rate = av_q2d(r_rate);
                LOGI("Used r_frame_rate for avg_frame_rate: %f", g_avg_frame_rate.load());
            } else {
                g_avg_frame_rate = 25.0;
                LOGW("Could not determine frame rate, using default: %f", g_avg_frame_rate.load());
            }
            LOGI("Video stream found: %dx%d @ %f fps", g_video_width, g_video_height, g_avg_frame_rate.load());
            break;
        }
    }
    if (videoStreamIdx == -1) {
        LOGE("No video stream found in %s", input_c);
        ret = -3; return 0;
    }

    AVCodecParameters *pCodecPar = pFormatCtx->streams[videoStreamIdx]->codecpar;
    pCodec = avcodec_find_decoder(pCodecPar->codec_id);
    if (!pCodec) {
        LOGE("Unsupported codec ID: %d", pCodecPar->codec_id);
        ret = -4; goto cleanup_decode;
    }

    pCodecCtxOrig = avcodec_alloc_context3(pCodec);
    if (!pCodecCtxOrig) {
        LOGE("Could not allocate codec context");
        ret = -5; goto cleanup_decode;
    }
    if (avcodec_parameters_to_context(pCodecCtxOrig, pCodecPar) < 0) {
        LOGE("Could not copy codec parameters to context");
        ret = -6; goto cleanup_decode;
    }
    if (avcodec_open2(pCodecCtxOrig, pCodec, nullptr) < 0) {
        LOGE("Cannot open codec");
        ret = -7; goto cleanup_decode;
    }

    pFrame = av_frame_alloc();
    if (!pFrame) {
        LOGE("Cannot allocate frame");
        ret = -8; goto cleanup_decode;
    }

    outFileYUV = fopen(output_c, "wb");
    if (!outFileYUV) {
        LOGE("Cannot open output YUV file: %s", output_c);
        ret = -9; goto cleanup_decode;
    }

    if (pCodecCtxOrig->pix_fmt != AV_PIX_FMT_YUV420P) {
        LOGW("Video stream is not YUV420P, it's %s. Output YUV might not be standard YUV420P without conversion.", av_get_pix_fmt_name(pCodecCtxOrig->pix_fmt));
    }

    av_init_packet(&packet); // Initialize packet structure
    packet.data = nullptr;   // Recommended to null data and size before first av_read_frame
    packet.size = 0;

    while (av_read_frame(pFormatCtx, &packet) >= 0) {
        if (packet.stream_index == videoStreamIdx) {
            if (avcodec_send_packet(pCodecCtxOrig, &packet) == 0) {
                while (avcodec_receive_frame(pCodecCtxOrig, pFrame) == 0) {
                    // Write Y, U, V planes for YUV420P
                    // fwrite uses pFrame->linesize for correct row length
                    // Y plane
                    for(int i = 0; i < pFrame->height; i++) {
                        fwrite(pFrame->data[0] + i * pFrame->linesize[0], 1, pFrame->width, outFileYUV);
                    }
                    // U plane
                    for(int i = 0; i < pFrame->height / 2; i++) {
                        fwrite(pFrame->data[1] + i * pFrame->linesize[1], 1, pFrame->width / 2, outFileYUV);
                    }
                    // V plane
                    for(int i = 0; i < pFrame->height / 2; i++) {
                        fwrite(pFrame->data[2] + i * pFrame->linesize[2], 1, pFrame->width / 2, outFileYUV);
                    }
                    av_frame_unref(pFrame);
                }
            }
        }
        av_packet_unref(&packet); // Important: free the packet allocated by av_read_frame
    }

    // Flush decoder
    avcodec_send_packet(pCodecCtxOrig, nullptr); // Send null packet to flush
    while (avcodec_receive_frame(pCodecCtxOrig, pFrame) == 0) { // Check pFrame also to avoid issues if it was freed
        for(int i = 0; i < pFrame->height; i++) {
            fwrite(pFrame->data[0] + i * pFrame->linesize[0], 1, pFrame->width, outFileYUV);
        }
        for(int i = 0; i < pFrame->height / 2; i++) {
            fwrite(pFrame->data[1] + i * pFrame->linesize[1], 1, pFrame->width / 2, outFileYUV);
        }
        for(int i = 0; i < pFrame->height / 2; i++) {
            fwrite(pFrame->data[2] + i * pFrame->linesize[2], 1, pFrame->width / 2, outFileYUV);
        }
        av_frame_unref(pFrame);
    }

    LOGI("Decoding to YUV file completed.");
    ret = 0; // Success

    cleanup_decode: // Label for cleanup
    if (outFileYUV) fclose(outFileYUV);
    if (pFrame) av_frame_free(&pFrame); // Safe to call even if pFrame is nullptr after alloc
    if (pCodecCtxOrig) avcodec_free_context(&pCodecCtxOrig); // Safe to call even if pCodecCtxOrig is nullptr
    if (pFormatCtx) avformat_close_input(&pFormatCtx); // Safe to call even if pFormatCtx is nullptr
    // if (avformat_network_init was called) avformat_network_deinit();

    env->ReleaseStringUTFChars(inputFilePath, input_c);
    env->ReleaseStringUTFChars(outputFilePath, output_c);
    return ret;
}
JNIEXPORT void JNICALL
Java_com_example_androidplayer_MainActivity_nativeStopVideoPlayback(JNIEnv *env, jobject /* this */) {
    LOGI("Requesting native video playback stop.");
    g_abort_render_request = true;
    g_is_paused = false;

    if (g_video_render_thread.joinable()) {
        g_video_render_thread.join();
        LOGI("Video render thread joined successfully.");
    } else {
        LOGI("Video render thread was not joinable.");
    }
    g_is_video_playing_flag = false;

    if (g_native_window_render) {
        ANativeWindow_release(g_native_window_render);
        g_native_window_render = nullptr;
        LOGI("Native window released.");
    }
    g_current_rendered_frame = 0;
    g_seek_target_frame = -1;
    LOGI("Native video playback fully stopped.");
}


JNIEXPORT void JNICALL
Java_com_example_androidplayer_MainActivity_nativeStartVideoPlayback(JNIEnv *env, jobject /* this */,
                                                                     jstring yuv_file_path_java,
                                                                     jobject surface) {
    if (g_is_video_playing_flag.load()) {
        LOGW("Video playback thread is already running. Stopping previous one first.");
        Java_com_example_androidplayer_MainActivity_nativeStopVideoPlayback(env, nullptr);
    }

    const char* yuv_path_c_str = env->GetStringUTFChars(yuv_file_path_java, nullptr);
    g_yuv_file_path_render_str = yuv_path_c_str;
    env->ReleaseStringUTFChars(yuv_file_path_java, yuv_path_c_str);

    if (g_native_window_render) {
        ANativeWindow_release(g_native_window_render);
        g_native_window_render = nullptr;
    }
    g_native_window_render = ANativeWindow_fromSurface(env, surface);
    if (!g_native_window_render) {
        LOGE("Failed to get native window from surface for playback.");
        return;
    }

    if(g_video_width > 0 && g_video_height > 0) {
        int32_t result = ANativeWindow_setBuffersGeometry(g_native_window_render, g_video_width, g_video_height, WINDOW_FORMAT_RGBA_8888);
        if (result < 0) {
            LOGE("Failed to set native window buffer geometry, error: %d", result);
            ANativeWindow_release(g_native_window_render);
            g_native_window_render = nullptr;
            return;
        }
        LOGI("Native window buffers geometry set to %dx%d, format RGBA_8888", g_video_width, g_video_height);
    } else {
        LOGE("Video dimensions not set (or invalid: %dx%d). Cannot set buffer geometry.", g_video_width, g_video_height);
        ANativeWindow_release(g_native_window_render);
        g_native_window_render = nullptr;
        return;
    }

    g_abort_render_request = false;
    g_is_paused = false;

    if (g_video_render_thread.joinable()) {
        g_video_render_thread.join();
    }
    g_video_render_thread = std::thread(video_render_loop_internal);
    LOGI("Native video playback thread initiated.");
}


JNIEXPORT void JNICALL
Java_com_example_androidplayer_MainActivity_nativePauseVideo(JNIEnv *env, jobject /* this */) {
    g_is_paused = true;
    LOGI("Native video PAUSED (flag set).");
}

JNIEXPORT void JNICALL
Java_com_example_androidplayer_MainActivity_nativeResumeVideo(JNIEnv *env, jobject /* this */) {
    g_is_paused = false;
    LOGI("Native video RESUMED (flag cleared).");
}

JNIEXPORT void JNICALL
Java_com_example_androidplayer_MainActivity_nativeSetSpeed(JNIEnv *env, jobject /* this */, jfloat speed) {
    if (speed > 0.0f) {
        g_playback_speed = speed;
        LOGI("Native playback speed set to: %f", speed);
    } else {
        LOGW("Invalid playback speed: %f. Not changed.", speed);
    }
}

JNIEXPORT void JNICALL
Java_com_example_androidplayer_MainActivity_nativeSeekToFrame(JNIEnv *env, jobject /* this */, jint frame_num) {
    if (frame_num >= 0) {
        g_seek_target_frame = frame_num;
        LOGI("Native seek to frame requested: %d", frame_num);
    } else {
        LOGW("Invalid seek frame number: %d", frame_num);
    }
}

JNIEXPORT jint JNICALL
Java_com_example_androidplayer_MainActivity_nativeGetTotalFrames(JNIEnv *env, jobject /* this */, jstring yuv_file_path_java) {
    if (g_video_width <= 0 || g_video_height <= 0) {
        LOGE("Cannot get total frames: video dimensions not set or invalid (%dx%d).", g_video_width, g_video_height);
        return 0;
    }
    const char *yuv_c = env->GetStringUTFChars(yuv_file_path_java, nullptr);
    FILE *fp = fopen(yuv_c, "rb");
    if (!fp) {
        LOGE("Cannot open YUV file '%s' to get total frames.", yuv_c);
        env->ReleaseStringUTFChars(yuv_file_path_java, yuv_c);
        return 0;
    }
    fseek(fp, 0, SEEK_END);
    long file_size = ftell(fp);
    fclose(fp);
    env->ReleaseStringUTFChars(yuv_file_path_java, yuv_c);

    int single_frame_size = g_video_width * g_video_height * 3 / 2;
    if (single_frame_size == 0) {
        LOGE("Cannot get total frames: single_frame_size is zero.");
        return 0;
    }
    return (jint) (file_size / single_frame_size);
}

JNIEXPORT jint JNICALL
Java_com_example_androidplayer_MainActivity_nativeGetCurrentFrame(JNIEnv *env, jobject /* this */) {
    return (jint) g_current_rendered_frame.load();
}

JNIEXPORT jdouble JNICALL
Java_com_example_androidplayer_MainActivity_nativeGetFrameRate(JNIEnv *env, jobject /* this */) {
    return g_avg_frame_rate.load();
}


// --- Audio Section ---

JNIEXPORT jint JNICALL
Java_com_example_androidplayer_MainActivity_initAudio(JNIEnv *env, jobject /* this */, jstring inputFilePath) {
    SLresult result;

    if (outputMixObject != nullptr) {
        (*outputMixObject)->Destroy(outputMixObject);
        outputMixObject = nullptr;
    }
    if (engineObject != nullptr) {
        (*engineObject)->Destroy(engineObject);
        engineObject = nullptr;
        engineEngine = nullptr;
    }

    result = slCreateEngine(&engineObject, 0, nullptr, 0, nullptr, nullptr);
    if (result != SL_RESULT_SUCCESS) { LOGE("Failed to create OpenSL ES engine: %u", result); return -1; }
    result = (*engineObject)->Realize(engineObject, SL_BOOLEAN_FALSE);
    if (result != SL_RESULT_SUCCESS) { LOGE("Failed to realize engine: %u", result); (*engineObject)->Destroy(engineObject); engineObject = nullptr; return -1; }
    result = (*engineObject)->GetInterface(engineObject, SL_IID_ENGINE, &engineEngine);
    if (result != SL_RESULT_SUCCESS) { LOGE("Failed to get engine interface: %u", result); (*engineObject)->Destroy(engineObject); engineObject = nullptr; return -1; }

    result = (*engineEngine)->CreateOutputMix(engineEngine, &outputMixObject, 0, nullptr, nullptr);
    if (result != SL_RESULT_SUCCESS) { LOGE("Failed to create output mix: %u", result); (*engineObject)->Destroy(engineObject); engineObject = nullptr; return -1; }
    result = (*outputMixObject)->Realize(outputMixObject, SL_BOOLEAN_FALSE);
    if (result != SL_RESULT_SUCCESS) { LOGE("Failed to realize output mix: %u", result); (*outputMixObject)->Destroy(outputMixObject); outputMixObject = nullptr; (*engineObject)->Destroy(engineObject); engineObject = nullptr; return -1; }

    LOGI("OpenSL ES audio engine initialized successfully.");
    return 0;
}

JNIEXPORT void JNICALL
Java_com_example_androidplayer_MainActivity_startAudio(JNIEnv *env, jobject /* this */, jstring inputFilePath, jlong startOffsetMs) {
    if (!engineEngine) { LOGE("Audio engine not initialized! Cannot start audio."); return; }
    SLresult result;
    const char *input_c = env->GetStringUTFChars(inputFilePath, nullptr);

    if (playerObject != nullptr) {
        (*playerObject)->Destroy(playerObject);
        playerObject = nullptr;
        playerPlay = nullptr;
        playerSeek = nullptr;
    }

    SLDataLocator_URI loc_uri = {SL_DATALOCATOR_URI, (SLchar *) input_c};
    SLDataFormat_MIME format_mime = {SL_DATAFORMAT_MIME, nullptr, SL_CONTAINERTYPE_UNSPECIFIED};
    SLDataSource audioSrc = {&loc_uri, &format_mime};

    SLDataLocator_OutputMix loc_outmix = {SL_DATALOCATOR_OUTPUTMIX, outputMixObject};
    SLDataSink audioSnk = {&loc_outmix, nullptr};

    const SLInterfaceID ids[2] = {SL_IID_PLAY, SL_IID_SEEK};
    const SLboolean req[2] = {SL_BOOLEAN_TRUE, SL_BOOLEAN_TRUE};
    int numInterfaces = 2;

    result = (*engineEngine)->CreateAudioPlayer(engineEngine, &playerObject, &audioSrc, &audioSnk, numInterfaces, ids, req);
    if (result != SL_RESULT_SUCCESS) { LOGE("Failed to create audio player: %u", result); env->ReleaseStringUTFChars(inputFilePath, input_c); return; }

    result = (*playerObject)->Realize(playerObject, SL_BOOLEAN_FALSE);
    if (result != SL_RESULT_SUCCESS) { LOGE("Failed to realize audio player: %u", result); (*playerObject)->Destroy(playerObject); playerObject = nullptr; env->ReleaseStringUTFChars(inputFilePath, input_c); return; }

    result = (*playerObject)->GetInterface(playerObject, SL_IID_PLAY, &playerPlay);
    if (result != SL_RESULT_SUCCESS) { LOGE("Failed to get play interface: %u", result); (*playerObject)->Destroy(playerObject); playerObject = nullptr; env->ReleaseStringUTFChars(inputFilePath, input_c); return; }

    result = (*playerObject)->GetInterface(playerObject, SL_IID_SEEK, &playerSeek);
    if (result != SL_RESULT_SUCCESS) {
        LOGW("Failed to get seek interface: %u. Audio seeking will not be available.", result);
        playerSeek = nullptr;
    }

    long actualStartOffset = (startOffsetMs >= 0) ? startOffsetMs : g_audio_start_offset_ms.exchange(-1); // Use pending if startOffsetMs is not explicitly set (or negative)

    if (actualStartOffset >= 0 && playerSeek != nullptr) { // Only seek if offset is valid
        LOGI("Audio seeking to %ld ms before starting playback.", actualStartOffset);
        result = (*playerPlay)->SetPlayState(playerPlay, SL_PLAYSTATE_PAUSED);
        if (result == SL_RESULT_SUCCESS) {
            result = (*playerSeek)->SetLoop(playerSeek, SL_BOOLEAN_FALSE, 0, SL_TIME_UNKNOWN);
            if (result != SL_RESULT_SUCCESS) LOGW("Failed to set loop to false before seek: %u", result);

            result = (*playerSeek)->SetPosition(playerSeek, (SLmillisecond)actualStartOffset, SL_SEEKMODE_ACCURATE);
            if (result != SL_RESULT_SUCCESS) {
                LOGE("Failed to seek audio to %ld ms, error: %u. Will play from start.", actualStartOffset, result);
            } else {
                LOGI("Audio seek to %ld ms successful.", actualStartOffset);
            }
        } else {
            LOGE("Failed to set audio to PAUSED before seek, error: %u.", result);
        }
    } else if (actualStartOffset >= 0) {
        LOGW("Audio seek requested to %ld ms, but seek interface is not available or offset invalid.", actualStartOffset);
    }


    result = (*playerPlay)->SetPlayState(playerPlay, SL_PLAYSTATE_PLAYING);
    if (result != SL_RESULT_SUCCESS) {
        LOGE("Failed to set play state to playing: %u", result);
        (*playerObject)->Destroy(playerObject);
        playerObject = nullptr;
        playerPlay = nullptr;
        playerSeek = nullptr;
        env->ReleaseStringUTFChars(inputFilePath, input_c);
        return;
    }

    LOGI("Audio playback started from file: %s", input_c);
    env->ReleaseStringUTFChars(inputFilePath, input_c);
}


JNIEXPORT void JNICALL
Java_com_example_androidplayer_MainActivity_stopAudio(JNIEnv *env, jobject /* this */) {
    if (playerObject != nullptr) {
        if (playerPlay != nullptr) {
            SLuint32 state;
            (*playerPlay)->GetPlayState(playerPlay, &state);
            if (state != SL_PLAYSTATE_STOPPED) {
                (*playerPlay)->SetPlayState(playerPlay, SL_PLAYSTATE_STOPPED);
            }
        }
        (*playerObject)->Destroy(playerObject);
        playerObject = nullptr;
        playerPlay = nullptr;
        playerSeek = nullptr;
        LOGI("Audio player stopped and destroyed.");
    }
    g_audio_start_offset_ms = -1;
}

JNIEXPORT void JNICALL
Java_com_example_androidplayer_MainActivity_pauseAudio(JNIEnv *env, jobject /* this */, jboolean do_pause) {
    if (playerPlay != nullptr) {
        // Corrected variable name from tState to targetState
        SLuint32 targetState = do_pause ? SL_PLAYSTATE_PAUSED : SL_PLAYSTATE_PLAYING;
        SLuint32 currentState;
        (*playerPlay)->GetPlayState(playerPlay, &currentState);

        if (currentState != targetState) {
            SLresult result = (*playerPlay)->SetPlayState(playerPlay, targetState);
            if (result == SL_RESULT_SUCCESS) {
                LOGI("Audio playback state set to %s", do_pause ? "PAUSED" : "PLAYING");
            } else {
                LOGE("Failed to set audio play state to %s, error: %u", do_pause ? "PAUSED" : "PLAYING", result);
            }
        } else {
            LOGI("Audio already in desired state: %s", do_pause ? "PAUSED" : "PLAYING");
        }
    } else {
        LOGE("Audio player not initialized, cannot %s.", do_pause ? "pause" : "resume");
    }
}

JNIEXPORT void JNICALL
Java_com_example_androidplayer_MainActivity_nativeSeekAudioToTimestamp(JNIEnv *env, jobject /* this */, jlong timeMs) {
    if (timeMs < 0) {
        LOGW("Invalid audio seek timestamp: %lld ms. Ignoring.", timeMs);
        return;
    }

    if (playerPlay != nullptr && playerSeek != nullptr) {
        SLuint32 currentState;
        (*playerPlay)->GetPlayState(playerPlay,&currentState);
        bool was_playing = (currentState == SL_PLAYSTATE_PLAYING);
        SLresult result;

        if (was_playing) {
            result = (*playerPlay)->SetPlayState(playerPlay, SL_PLAYSTATE_PAUSED);
            if (result != SL_RESULT_SUCCESS) {
                LOGW("Failed to pause audio before seek: %u. Seeking might be less accurate.", result);
            }
        }

        result = (*playerSeek)->SetLoop(playerSeek, SL_BOOLEAN_FALSE, 0, SL_TIME_UNKNOWN);
        if (result != SL_RESULT_SUCCESS) LOGW("Failed to set loop to false during seek: %u", result);

        result = (*playerSeek)->SetPosition(playerSeek, (SLmillisecond)timeMs, SL_SEEKMODE_ACCURATE);
        if (result == SL_RESULT_SUCCESS) {
            LOGI("Audio seeked to %lld ms.", timeMs);
        } else {
            LOGE("Failed to seek audio to %lld ms, error: %u", timeMs, result);
        }

        if (was_playing) {
            result = (*playerPlay)->SetPlayState(playerPlay, SL_PLAYSTATE_PLAYING);
            if (result != SL_RESULT_SUCCESS) {
                LOGE("Failed to resume audio after seek: %u.", result);
            }
        }

        if (currentState == SL_PLAYSTATE_STOPPED) { // If it was stopped, player is now seeked but still stopped.
            g_audio_start_offset_ms = timeMs; // Store for next explicit play command
            LOGI("Audio was stopped. Player seeked to %lld ms, will play from there on next start.", timeMs);
        }

    } else {
        g_audio_start_offset_ms = timeMs;
        LOGW("Audio player not ready for seek (or seek interface unavailable). Storing target %lld ms for next playback.", timeMs);
    }
}

} // extern "C"