package com.example.androidplayer;

import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

// 主活动类，实现SurfaceHolder.Callback接口以处理SurfaceView的生命周期事件
public class MainActivity extends AppCompatActivity implements SurfaceHolder.Callback {

    private static final String TAG = "MainActivity"; // 日志标签
    private static final String INPUT_FILE_NAME = "1.mp4"; // 输入视频文件名 (assets目录)
    private static final String YUV_FILE_NAME = "output.yuv"; // 解码后的YUV文件名

    private SurfaceView surfaceView; // 用于显示视频的视图
    private SurfaceHolder surfaceHolder; // SurfaceView的控制器
    private Button playPauseButton, stopButton, speedButton; // 控制按钮
    private SeekBar seekBar; // 播放进度条
    private TextView speedTextView; // 显示播放速度的文本

    private ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor(); // 用于后台任务的线程池
    private Handler mainUIHandler = new Handler(Looper.getMainLooper()); // 用于在主线程更新UI

    private String mp4FilePath; // MP4文件在缓存中的绝对路径
    private String yuvFilePath; // YUV文件在缓存中的绝对路径

    // 播放器状态枚举
    private enum PlayerState { IDLE, PREPARING, PLAYING, PAUSED, STOPPED, ERROR }
    private PlayerState currentPlayerState = PlayerState.IDLE; // 当前播放器状态
    private float currentSpeed = 1.0f; // 当前播放速度
    private boolean isSurfaceReady = false; // Surface是否已准备好
    private boolean isYuvDecoded = false; // YUV文件是否已解码完成
    private AtomicBoolean isSeekingFromUser = new AtomicBoolean(false); // 用户是否正在拖动进度条

    private double videoFrameRate = 25.0; // 视频帧率
    private long pendingAudioSeekMs = -1; // 待处理的音频跳转时间点 (毫秒)

    private static final int PROGRESS_UPDATE_INTERVAL_MS = 200; // 进度条更新间隔 (毫秒)

    // 静态代码块，加载本地C++库
    static {
        try {
            System.loadLibrary("androidplayer"); // 加载名为 "androidplayer" 的库
            Log.i(TAG, "Native library 'androidplayer' loaded successfully.");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native library 'androidplayer'", e);
        }
    }

    // --- JNI本地方法声明 ---
    private native int decodeVideoToFile(String inputFilePath, String outputFilePath); // 解码视频到YUV文件
    private native void nativeStartVideoPlayback(String yuvFilePath, Surface surface); // 开始本地视频播放
    private native void nativeStopVideoPlayback(); // 停止本地视频播放
    private native void nativePauseVideo(); // 暂停本地视频
    private native void nativeResumeVideo(); // 恢复本地视频
    private native void nativeSetSpeed(float speed); // 设置视频播放速度 (影响帧延迟)
    private native void nativeSeekToFrame(int frameNum); // 视频跳转到指定帧
    private native int nativeGetTotalFrames(String yuvFilePath); // 获取视频总帧数
    private native int nativeGetCurrentFrame(); // 获取当前视频帧
    private native double nativeGetFrameRate(); // 获取视频帧率

    private native int initAudio(String inputFilePath); // 初始化音频
    private native void startAudio(String inputFilePath, long startOffsetMs); // 开始播放音频
    private native void stopAudio(); // 停止播放音频
    private native void pauseAudio(boolean pause); // 暂停/恢复音频
    private native void nativeSeekAudioToTimestamp(long timeMs); // 音频跳转到指定时间戳
    private native void nativeSetAudioPlaybackRate(float rate); // 设置音频播放速率

    // 更新播放进度的Runnable
    private final Runnable progressUpdater = new Runnable() {
        @Override
        public void run() {
            // 仅在播放或暂停状态且用户未拖动进度条时更新
            if ((currentPlayerState == PlayerState.PLAYING || currentPlayerState == PlayerState.PAUSED) && !isSeekingFromUser.get()) {
                int currentFrame = nativeGetCurrentFrame(); // 获取当前帧
                if (seekBar != null) {
                    int totalFrames = seekBar.getMax(); // 获取总帧数
                    if (totalFrames > 0) {
                        if (!isSeekingFromUser.get()) { // 再次检查用户是否在拖动
                            seekBar.setProgress(currentFrame); // 更新进度条
                        }
                        // 如果接近视频末尾，则认为播放结束
                        if (currentFrame >= totalFrames - 2 && currentPlayerState == PlayerState.PLAYING && totalFrames > 1) {
                            Log.i(TAG, "Video playback likely finished based on frame count.");
                            handleStop(); // 处理停止逻辑
                            return;
                        }
                    }
                }
            }
            // 如果仍在播放或暂停，则延迟一段时间后再次执行
            if (currentPlayerState == PlayerState.PLAYING || currentPlayerState == PlayerState.PAUSED) {
                mainUIHandler.postDelayed(this, PROGRESS_UPDATE_INTERVAL_MS);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String packageName = getPackageName(); // 获取包名
        // 通过资源名动态获取布局ID
        int layoutId = getResources().getIdentifier("activity_main", "layout", packageName);
        if (layoutId == 0) {
            Log.e(TAG, "Failed to find layout: activity_main.");
            Toast.makeText(this, "Error: Layout resource not found!", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        setContentView(layoutId); // 设置布局

        // 通过资源名动态获取UI组件ID并初始化
        surfaceView = findViewById(getResources().getIdentifier("surfaceView", "id", packageName));
        playPauseButton = findViewById(getResources().getIdentifier("playPauseButton", "id", packageName));
        stopButton = findViewById(getResources().getIdentifier("stopButton", "id", packageName));
        speedButton = findViewById(getResources().getIdentifier("speedButton", "id", packageName));
        seekBar = findViewById(getResources().getIdentifier("seekBar", "id", packageName));
        speedTextView = findViewById(getResources().getIdentifier("speedText", "id", packageName));

        // 检查关键UI组件是否成功获取
        if (surfaceView == null || playPauseButton == null || stopButton == null ||
                speedButton == null || seekBar == null || speedTextView == null) {
            Log.e(TAG, "Critical UI element(s) not found. Check IDs.");
            Toast.makeText(this, "Error: UI elements not found.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        surfaceHolder = surfaceView.getHolder(); // 获取SurfaceHolder
        surfaceHolder.addCallback(this); // 添加Surface生命周期回调

        updateUIForState(PlayerState.IDLE); // 初始化UI状态

        // 设置按钮点击监听器
        playPauseButton.setOnClickListener(v -> handlePlayPause());
        stopButton.setOnClickListener(v -> handleStop());
        speedButton.setOnClickListener(v -> handleSpeedToggle());

        // 设置SeekBar监听器
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            int targetFrameOnSeek = 0; // 用户拖动进度条时的目标帧
            @Override
            public void onProgressChanged(SeekBar seekBarParam, int progress, boolean fromUser) {
                if (fromUser) { // 如果是用户改变的进度
                    targetFrameOnSeek = progress;
                }
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBarParam) { // 开始拖动进度条
                // 仅当YUV解码完成且播放器处于可跳转状态时
                if (isYuvDecoded && (currentPlayerState == PlayerState.PLAYING || currentPlayerState == PlayerState.PAUSED || currentPlayerState == PlayerState.IDLE || currentPlayerState == PlayerState.STOPPED)) {
                    isSeekingFromUser.set(true); // 标记用户正在拖动
                    if (currentPlayerState == PlayerState.PLAYING || currentPlayerState == PlayerState.PAUSED) {
                        mainUIHandler.removeCallbacks(progressUpdater); // 暂停进度更新
                    }
                }
            }
            @Override
            public void onStopTrackingTouch(SeekBar seekBarParam) { // 停止拖动进度条
                if (!isYuvDecoded) { // 如果YUV未解码，则不处理
                    isSeekingFromUser.set(false);
                    return;
                }
                if (!isSeekingFromUser.get()) { // 如果不是用户拖动结束，则不处理
                    return;
                }

                Log.i(TAG, "SeekBar seeking to frame: " + targetFrameOnSeek);
                long targetTimeMs = 0; // 计算音频跳转的目标时间
                if (videoFrameRate > 0.001) {
                    targetTimeMs = (long) (((double) targetFrameOnSeek / videoFrameRate) * 1000.0);
                } else {
                    Log.w(TAG, "Cannot calculate target time for audio seek: frame rate is invalid ("+ videoFrameRate +").");
                    targetTimeMs = -1; // 帧率无效则不进行音频跳转
                }

                // 根据当前播放状态处理跳转逻辑
                if (currentPlayerState == PlayerState.PLAYING || currentPlayerState == PlayerState.PAUSED) {
                    boolean wasPlaying = (currentPlayerState == PlayerState.PLAYING); // 记录跳转前是否在播放

                    if (wasPlaying) { // 如果在播放，先暂停
                        nativePauseVideo();
                        pauseAudio(true);
                    }

                    nativeSeekToFrame(targetFrameOnSeek); // 跳转视频帧
                    if (targetTimeMs >= 0) {
                        nativeSeekAudioToTimestamp(targetTimeMs); // 跳转音频时间
                    } else {
                        Log.w(TAG, "Skipping audio seek due to invalid targetTimeMs.");
                    }

                    if (MainActivity.this.seekBar != null) { // 更新进度条显示
                        MainActivity.this.seekBar.setProgress(targetFrameOnSeek);
                    }

                    if (wasPlaying) { // 如果之前在播放，则恢复播放
                        nativeResumeVideo();
                        pauseAudio(false);
                    }
                } else { // 如果是IDLE或STOPPED状态，则只记录跳转位置供下次播放使用
                    nativeSeekToFrame(targetFrameOnSeek);
                    pendingAudioSeekMs = targetTimeMs; // 记录音频待跳转时间
                    if (MainActivity.this.seekBar != null) {
                        MainActivity.this.seekBar.setProgress(targetFrameOnSeek);
                    }
                }

                isSeekingFromUser.set(false); // 清除用户拖动标记
                if (currentPlayerState == PlayerState.PLAYING || currentPlayerState == PlayerState.PAUSED) {
                    mainUIHandler.removeCallbacks(progressUpdater); // 重新开始进度更新
                    mainUIHandler.post(progressUpdater);
                }
            }
        });

        prepareMediaInBackground(); // 在后台准备媒体文件 (拷贝和解码)
    }

    // 在后台准备媒体文件 (拷贝assets中的MP4到缓存，然后解码为YUV)
    private void prepareMediaInBackground() {
        updateUIForState(PlayerState.PREPARING); // 更新UI为准备状态
        backgroundExecutor.submit(() -> { // 提交到后台线程执行
            try {
                File copiedMp4File = copyAssetToCacheDir(INPUT_FILE_NAME); // 拷贝MP4
                mp4FilePath = copiedMp4File.getAbsolutePath();

                File yuvOutputFile = new File(getCacheDir(), YUV_FILE_NAME); // 创建YUV输出文件对象
                yuvFilePath = yuvOutputFile.getAbsolutePath();

                Log.i(TAG, "Starting YUV decoding from " + mp4FilePath + " to " + yuvFilePath);
                int decodeResult = decodeVideoToFile(mp4FilePath, yuvFilePath); // 调用JNI解码

                if (decodeResult == 0) { // 解码成功
                    isYuvDecoded = true;
                    videoFrameRate = nativeGetFrameRate(); // 获取视频帧率
                    if (videoFrameRate <= 0.001) { // 帧率无效则使用默认值
                        Log.w(TAG, "Invalid frame rate from native: " + videoFrameRate + ", using default 25.0");
                        videoFrameRate = 25.0;
                    }
                    Log.i(TAG, "YUV decoding successful. Video Frame Rate: " + videoFrameRate);
                    mainUIHandler.post(() -> { // 在主线程更新UI
                        if (seekBar != null) {
                            int totalFrames = nativeGetTotalFrames(yuvFilePath); // 获取总帧数
                            seekBar.setMax(totalFrames > 0 ? totalFrames : 1); // 设置进度条最大值
                            seekBar.setProgress(0); // 设置进度条初始值为0
                        }
                        if (isSurfaceReady) { // 如果Surface已准备好
                            updateUIForState(PlayerState.IDLE); // 更新UI为IDLE状态
                        } else {
                            Toast.makeText(this, "Video decoded, waiting for surface...", Toast.LENGTH_SHORT).show();
                        }
                    });
                } else { // 解码失败
                    Log.e(TAG, "YUV decoding failed, code: " + decodeResult);
                    mainUIHandler.post(() -> updateUIForState(PlayerState.ERROR)); // 更新UI为错误状态
                }
            } catch (IOException e) { // 文件操作异常
                Log.e(TAG, "Error preparing media files", e);
                mainUIHandler.post(() -> updateUIForState(PlayerState.ERROR));
            }
        });
    }

    // 处理播放/暂停按钮点击事件
    private void handlePlayPause() {
        if (!isYuvDecoded) { // 如果YUV未解码
            Toast.makeText(this, "Video not yet decoded.", Toast.LENGTH_SHORT).show();
            if(currentPlayerState != PlayerState.PREPARING) prepareMediaInBackground(); // 重新准备
            return;
        }
        if (!isSurfaceReady) { // 如果Surface未准备好
            Toast.makeText(this, "Surface not ready.", Toast.LENGTH_SHORT).show();
            return;
        }
        // 检查Surface是否有效
        if (surfaceHolder == null || surfaceHolder.getSurface() == null || !surfaceHolder.getSurface().isValid()) {
            Log.e(TAG, "Surface is not valid for playback.");
            Toast.makeText(this, "Surface invalid, cannot play.", Toast.LENGTH_SHORT).show();
            return;
        }

        // 根据当前状态执行相应操作
        switch (currentPlayerState) {
            case IDLE:
            case STOPPED:
            case ERROR: // 从空闲、停止或错误状态开始新的播放
                startNewPlayback();
                break;
            case PLAYING: // 从播放状态暂停
                pauseCurrentPlayback();
                break;
            case PAUSED: // 从暂停状态恢复播放
                resumeCurrentPlayback();
                break;
            default:
                break;
        }
    }

    // 开始新的播放
    private void startNewPlayback() {
        Log.i(TAG, "Starting new playback...");
        if (initAudio(mp4FilePath) == 0) { // 初始化音频
            startAudio(mp4FilePath, pendingAudioSeekMs); // 开始播放音频 (如有待处理的跳转)
            pendingAudioSeekMs = -1; // 清除待处理的音频跳转
        } else {
            Log.e(TAG, "Failed to initialize audio.");
            Toast.makeText(this, "Audio init failed", Toast.LENGTH_SHORT).show();
        }

        // 确保Surface有效
        if (surfaceHolder != null && surfaceHolder.getSurface() != null && surfaceHolder.getSurface().isValid()) {
            nativeStartVideoPlayback(yuvFilePath, surfaceHolder.getSurface()); // 开始视频播放
            nativeSetSpeed(currentSpeed); // 应用当前速度到视频
            nativeSetAudioPlaybackRate(currentSpeed); // 应用当前速度到音频

            updateUIForState(PlayerState.PLAYING); // 更新UI为播放状态
            mainUIHandler.removeCallbacks(progressUpdater); // 开始进度更新
            mainUIHandler.post(progressUpdater);
        } else {
            Log.e(TAG, "Cannot start playback, surface is not valid.");
            Toast.makeText(this, "Cannot start: Surface not ready.", Toast.LENGTH_SHORT).show();
            updateUIForState(PlayerState.ERROR);
        }
    }

    // 暂停当前播放
    private void pauseCurrentPlayback() {
        if (currentPlayerState == PlayerState.PLAYING) {
            nativePauseVideo(); // 暂停视频
            pauseAudio(true);  // 暂停音频
            updateUIForState(PlayerState.PAUSED); // 更新UI为暂停状态
            mainUIHandler.removeCallbacks(progressUpdater); // 停止进度更新
        }
    }

    // 恢复当前播放
    private void resumeCurrentPlayback() {
        if (currentPlayerState == PlayerState.PAUSED) {
            // 确保Surface有效
            if (isSurfaceReady && surfaceHolder != null && surfaceHolder.getSurface() != null && surfaceHolder.getSurface().isValid()) {
                nativeResumeVideo(); // 恢复视频
                pauseAudio(false); // 恢复音频
                updateUIForState(PlayerState.PLAYING); // 更新UI为播放状态
                mainUIHandler.removeCallbacks(progressUpdater); // 重新开始进度更新
                mainUIHandler.post(progressUpdater);
            } else {
                Log.w(TAG, "Cannot resume: Surface not ready. Forcing stop.");
                Toast.makeText(this, "Cannot resume: Surface not ready.", Toast.LENGTH_SHORT).show();
                handleStop(); // 如果Surface无效，则停止播放
            }
        }
    }

    // 处理停止按钮点击事件
    private void handleStop() {
        Log.i(TAG, "Stopping playback...");
        nativeStopVideoPlayback(); // 停止视频
        stopAudio(); // 停止音频
        currentSpeed = 1.0f; // 停止时重置速度为1.0x

        updateUIForState(PlayerState.STOPPED); // 更新UI为停止状态
        mainUIHandler.removeCallbacks(progressUpdater); // 停止进度更新
        if (seekBar != null) {
            seekBar.setProgress(0); // 重置进度条
        }
        pendingAudioSeekMs = -1; // 清除待处理的音频跳转
        nativeSeekToFrame(0); // 视频跳转回第0帧
    }

    // 处理速度切换按钮点击事件
    private void handleSpeedToggle() {
        // 仅在播放或暂停状态下允许改变速度
        if (!isYuvDecoded || (currentPlayerState != PlayerState.PLAYING && currentPlayerState != PlayerState.PAUSED)) {
            Toast.makeText(this, "Please start playback first to change speed.", Toast.LENGTH_SHORT).show();
            return;
        }
        float newSpeed = currentSpeed; // 速度循环切换: 1.0 -> 1.5 -> 2.0 -> 0.5 -> 1.0
        if (currentSpeed == 1.0f) newSpeed = 1.5f;
        else if (currentSpeed == 1.5f) newSpeed = 2.0f;
        else if (currentSpeed == 2.0f) newSpeed = 0.5f;
        else newSpeed = 1.0f;

        currentSpeed = newSpeed;

        nativeSetSpeed(currentSpeed); // 设置视频速度
        nativeSetAudioPlaybackRate(currentSpeed); // 设置音频速度

        if (speedTextView != null) { // 更新速度显示文本
            mainUIHandler.post(() -> speedTextView.setText(String.format(Locale.US, "Speed: %.1fx", currentSpeed)));
        }
    }

    // 根据播放器状态更新UI
    private void updateUIForState(PlayerState state) {
        currentPlayerState = state;
        Log.d(TAG, "Updating UI for state: " + state);

        // 检查UI组件是否为空
        if (playPauseButton == null || stopButton == null || speedButton == null || seekBar == null || speedTextView == null) {
            Log.e(TAG, "Cannot update UI, view components are null.");
            if (state == PlayerState.ERROR && playPauseButton != null) { // 错误状态特殊处理
                playPauseButton.setText("Retry");
                playPauseButton.setEnabled(true);
            }
            return;
        }

        // 根据不同状态设置按钮文本和可用性
        switch (state) {
            case IDLE:
                playPauseButton.setText("Play");
                playPauseButton.setEnabled(isYuvDecoded && isSurfaceReady); // 仅当YUV解码且Surface准备好时可用
                stopButton.setEnabled(false);
                speedButton.setEnabled(false);
                seekBar.setEnabled(isYuvDecoded);
                break;
            case PREPARING:
                playPauseButton.setText("Preparing...");
                playPauseButton.setEnabled(false);
                stopButton.setEnabled(false);
                speedButton.setEnabled(false);
                seekBar.setEnabled(false);
                break;
            case PLAYING:
                playPauseButton.setText("Pause");
                playPauseButton.setEnabled(true);
                stopButton.setEnabled(true);
                speedButton.setEnabled(true);
                seekBar.setEnabled(true);
                break;
            case PAUSED:
                playPauseButton.setText("Resume");
                playPauseButton.setEnabled(true);
                stopButton.setEnabled(true);
                speedButton.setEnabled(true);
                seekBar.setEnabled(true);
                break;
            case STOPPED:
                playPauseButton.setText("Play");
                playPauseButton.setEnabled(isYuvDecoded && isSurfaceReady);
                stopButton.setEnabled(false);
                speedButton.setEnabled(false);
                seekBar.setEnabled(isYuvDecoded);
                if (seekBar != null) seekBar.setProgress(0); // 重置进度条
                break;
            case ERROR:
                playPauseButton.setText("Retry");
                playPauseButton.setEnabled(true);
                stopButton.setEnabled(false);
                speedButton.setEnabled(false);
                seekBar.setEnabled(false);
                Toast.makeText(this, "An error occurred. Please retry.", Toast.LENGTH_LONG).show();
                break;
        }
        if (speedTextView != null) { // 更新速度显示
            speedTextView.setText(String.format(Locale.US, "Speed: %.1fx", currentSpeed));
        }
    }

    // --- SurfaceHolder.Callback 实现 ---
    @Override
    public void surfaceCreated(SurfaceHolder holder) { // Surface创建时调用
        Log.i(TAG, "Surface created.");
        this.surfaceHolder = holder;
        isSurfaceReady = true; // 标记Surface已准备好
        // 如果YUV已解码且播放器处于可播放状态，则更新UI
        if (isYuvDecoded && (currentPlayerState == PlayerState.IDLE || currentPlayerState == PlayerState.STOPPED || currentPlayerState == PlayerState.ERROR || currentPlayerState == PlayerState.PREPARING ) ) {
            if (currentPlayerState == PlayerState.PREPARING && isYuvDecoded) { // 如果在准备中且YUV解码完成
                updateUIForState(PlayerState.IDLE);
            } else {
                updateUIForState(currentPlayerState);
            }
        } else if (currentPlayerState == PlayerState.PAUSED) { // 如果在暂停时Surface重建
            Log.w(TAG,"Surface created while player was paused. User might need to press resume.");
            updateUIForState(PlayerState.PAUSED); // 保持暂停状态
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { // Surface尺寸或格式改变时调用
        Log.i(TAG, "Surface changed: " + width + "x" + height);
        this.surfaceHolder = holder; // 更新SurfaceHolder引用
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) { // Surface销毁时调用
        Log.i(TAG, "Surface destroyed.");
        isSurfaceReady = false; // 标记Surface未准备好
        if (currentPlayerState == PlayerState.PLAYING || currentPlayerState == PlayerState.PAUSED) { // 如果在播放或暂停时销毁
            Log.w(TAG, "Surface destroyed during playback/pause. Pausing playback.");
            nativePauseVideo(); // 暂停视频
            pauseAudio(true);  // 暂停音频
            updateUIForState(PlayerState.PAUSED); // 更新UI为暂停状态
            mainUIHandler.removeCallbacks(progressUpdater); // 停止进度更新
        }
        this.surfaceHolder = null; // 清除SurfaceHolder引用
    }

    // 将assets目录下的文件拷贝到应用的缓存目录
    private File copyAssetToCacheDir(String filename) throws IOException {
        File cacheDir = getCacheDir(); // 获取缓存目录
        File outFile = new File(cacheDir, filename); // 创建输出文件对象
        try (InputStream in = getAssets().open(filename); // 打开assets输入流
             OutputStream out = new FileOutputStream(outFile)) { // 打开文件输出流
            byte[] buffer = new byte[4096]; // 缓冲区
            int read;
            while ((read = in.read(buffer)) != -1) { // 读取并写入
                out.write(buffer, 0, read);
            }
            Log.i(TAG, "Asset '" + filename + "' copied to cache: " + outFile.getAbsolutePath());
        }
        return outFile; // 返回拷贝后的文件对象
    }

    @Override
    protected void onPause() { // Activity暂停时调用
        super.onPause();
        if (currentPlayerState == PlayerState.PLAYING) { // 如果正在播放，则暂停
            pauseCurrentPlayback();
        }
    }

    @Override
    protected void onDestroy() { // Activity销毁时调用
        super.onDestroy();
        Log.i(TAG, "onDestroy: Cleaning up resources.");
        handleStop(); // 停止播放并释放资源
        if (backgroundExecutor != null && !backgroundExecutor.isShutdown()) { // 关闭后台线程池
            backgroundExecutor.shutdownNow();
        }
        if (mainUIHandler != null) { // 移除Handler中的所有回调和消息
            mainUIHandler.removeCallbacksAndMessages(null);
        }
    }
}