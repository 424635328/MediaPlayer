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

public class MainActivity extends AppCompatActivity implements SurfaceHolder.Callback {

    private static final String TAG = "MainActivity";
    private static final String INPUT_FILE_NAME = "1.mp4"; // 将你的测试视频放在 app/src/main/assets
    private static final String YUV_FILE_NAME = "output.yuv";

    private SurfaceView surfaceView;
    private SurfaceHolder surfaceHolder;
    private Button playPauseButton, stopButton, speedButton;
    private SeekBar seekBar;
    private TextView speedTextView;

    private ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();
    private Handler mainUIHandler = new Handler(Looper.getMainLooper());

    private String mp4FilePath;
    private String yuvFilePath;

    private enum PlayerState { IDLE, PREPARING, PLAYING, PAUSED, STOPPED, ERROR }
    private PlayerState currentPlayerState = PlayerState.IDLE;
    private float currentSpeed = 1.0f;
    private boolean isSurfaceReady = false;
    private boolean isYuvDecoded = false;
    private AtomicBoolean isSeekingFromUser = new AtomicBoolean(false);

    private double videoFrameRate = 25.0; // 解码后会更新
    private long pendingAudioSeekMs = -1; // 用于停止状态下seek后，记录音频应开始的时间

    private static final int PROGRESS_UPDATE_INTERVAL_MS = 200; // 进度条更新间隔

    static {
        try {
            System.loadLibrary("androidplayer");
            Log.i(TAG, "Native library 'androidplayer' loaded successfully.");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native library 'androidplayer'", e);
        }
    }

    // Native methods
    private native int decodeVideoToFile(String inputFilePath, String outputFilePath);
    private native void nativeStartVideoPlayback(String yuvFilePath, Surface surface);
    private native void nativeStopVideoPlayback();
    private native void nativePauseVideo();
    private native void nativeResumeVideo();
    private native void nativeSetSpeed(float speed);
    private native void nativeSeekToFrame(int frameNum);
    private native int nativeGetTotalFrames(String yuvFilePath);
    private native int nativeGetCurrentFrame();
    private native double nativeGetFrameRate(); // 新增：获取帧率

    private native int initAudio(String inputFilePath);
    private native void startAudio(String inputFilePath, long startOffsetMs); // 修改：增加startOffsetMs参数
    private native void stopAudio();
    private native void pauseAudio(boolean pause);
    private native void nativeSeekAudioToTimestamp(long timeMs); // 新增：音频Seek


    private final Runnable progressUpdater = new Runnable() {
        @Override
        public void run() {
            if ((currentPlayerState == PlayerState.PLAYING || currentPlayerState == PlayerState.PAUSED) && !isSeekingFromUser.get()) {
                int currentFrame = nativeGetCurrentFrame();
                if (seekBar != null) {
                    int totalFrames = seekBar.getMax();
                    if (totalFrames > 0) {
                        // 只有当用户没有拖动SeekBar时才更新，避免冲突
                        if (!isSeekingFromUser.get()) {
                            seekBar.setProgress(currentFrame);
                        }
                        // 检查是否播放到接近末尾
                        if (currentFrame >= totalFrames - 2 && currentPlayerState == PlayerState.PLAYING && totalFrames > 1) {
                            Log.i(TAG, "Video playback likely finished based on frame count.");
                            handleStop();
                            return;
                        }
                    }
                }
            }
            // 只有在播放或暂停状态下才继续调度
            if (currentPlayerState == PlayerState.PLAYING || currentPlayerState == PlayerState.PAUSED) {
                mainUIHandler.postDelayed(this, PROGRESS_UPDATE_INTERVAL_MS);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String packageName = getPackageName();
        int layoutId = getResources().getIdentifier("activity_main", "layout", packageName);
        if (layoutId == 0) {
            Log.e(TAG, "Failed to find layout: activity_main. Check XML file name and build.");
            Toast.makeText(this, "Error: Layout resource not found!", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        setContentView(layoutId);

        surfaceView = findViewById(getResources().getIdentifier("surfaceView", "id", packageName));
        playPauseButton = findViewById(getResources().getIdentifier("playPauseButton", "id", packageName));
        stopButton = findViewById(getResources().getIdentifier("stopButton", "id", packageName));
        speedButton = findViewById(getResources().getIdentifier("speedButton", "id", packageName));
        seekBar = findViewById(getResources().getIdentifier("seekBar", "id", packageName));
        speedTextView = findViewById(getResources().getIdentifier("speedText", "id", packageName));

        if (surfaceView == null || playPauseButton == null || stopButton == null ||
                speedButton == null || seekBar == null || speedTextView == null) {
            String missingViews = "";
            if (surfaceView == null) missingViews += "surfaceView ";
            if (playPauseButton == null) missingViews += "playPauseButton ";
            if (stopButton == null) missingViews += "stopButton ";
            if (speedButton == null) missingViews += "speedButton ";
            if (seekBar == null) missingViews += "seekBar ";
            if (speedTextView == null) missingViews += "speedTextView ";
            Log.e(TAG, "Critical UI element(s) not found: " + missingViews.trim() +
                    ". Ensure IDs in XML match strings used in getIdentifier().");
            Toast.makeText(this, "Error: UI elements not found: " + missingViews.trim(), Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        surfaceHolder = surfaceView.getHolder();
        surfaceHolder.addCallback(this);

        updateUIForState(PlayerState.IDLE);

        playPauseButton.setOnClickListener(v -> handlePlayPause());
        stopButton.setOnClickListener(v -> handleStop());
        speedButton.setOnClickListener(v -> handleSpeedToggle());

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            int targetFrameOnSeek = 0;
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    targetFrameOnSeek = progress;
                }
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                // 允许在IDLE, STOPPED, PLAYING, PAUSED 状态下开始拖动 (前提是已解码)
                if (isYuvDecoded && (currentPlayerState == PlayerState.PLAYING || currentPlayerState == PlayerState.PAUSED || currentPlayerState == PlayerState.IDLE || currentPlayerState == PlayerState.STOPPED)) {
                    isSeekingFromUser.set(true);
                    // 如果正在播放或暂停，暂时移除进度更新回调，避免冲突
                    if (currentPlayerState == PlayerState.PLAYING || currentPlayerState == PlayerState.PAUSED) {
                        mainUIHandler.removeCallbacks(progressUpdater);
                    }
                }
            }
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (!isYuvDecoded) { // 如果还没解码完，则不响应seek
                    isSeekingFromUser.set(false);
                    return;
                }
                if (!isSeekingFromUser.get()) { // 如果不是用户主动seek（例如代码设置进度），则忽略
                    return;
                }

                Log.i(TAG, "SeekBar seeking to frame: " + targetFrameOnSeek);
                long targetTimeMs = 0;
                if (videoFrameRate > 0.001) { // 确保帧率有效
                    targetTimeMs = (long) (((double) targetFrameOnSeek / videoFrameRate) * 1000.0);
                } else {
                    Log.w(TAG, "Cannot calculate target time for audio seek: frame rate is invalid ("+ videoFrameRate +"). Defaulting audio seek to 0 or no seek.");
                    targetTimeMs = -1; // 表示不进行有效的音频seek，或者native层处理为从头
                }

                if (currentPlayerState == PlayerState.PLAYING || currentPlayerState == PlayerState.PAUSED) {
                    boolean wasPlaying = (currentPlayerState == PlayerState.PLAYING);

                    if (wasPlaying) { // 如果正在播放，先暂停内部逻辑（不更新UI为PAUSED）
                        nativePauseVideo(); // 仅设置C++层暂停标志
                        pauseAudio(true);   // OpenSL ES 暂停
                    }

                    // Seek 视频
                    nativeSeekToFrame(targetFrameOnSeek);
                    // Seek 音频
                    if (targetTimeMs >= 0) { // 确保时间有效
                        nativeSeekAudioToTimestamp(targetTimeMs);
                    } else {
                        Log.w(TAG, "Skipping audio seek due to invalid targetTimeMs.");
                    }

                    // 更新当前帧的UI显示 (进度条)
                    if (seekBar != null) seekBar.setProgress(targetFrameOnSeek); // 立刻更新UI上的进度条


                    if (wasPlaying) { // 如果之前是播放状态，则恢复播放
                        nativeResumeVideo(); // C++层恢复
                        pauseAudio(false);  // OpenSL ES 恢复
                    }
                    // 如果是暂停时seek，不需要做额外操作，因为nativeSeekAudioToTimestamp会处理，并且保持暂停状态
                } else { // STOPPED, IDLE, ERROR (这些状态下，下次播放时会从新位置开始)
                    nativeSeekToFrame(targetFrameOnSeek); // 视频会在下次start时seek到目标帧（通过render loop的seek机制）
                    pendingAudioSeekMs = targetTimeMs;    // 音频会在下次startAudio时从这个时间点开始
                    if (seekBar != null) seekBar.setProgress(targetFrameOnSeek); // 更新UI显示
                }

                isSeekingFromUser.set(false);
                // 无论如何，在seek结束后，如果播放器处于活动状态，重新启动进度更新器
                if (currentPlayerState == PlayerState.PLAYING || currentPlayerState == PlayerState.PAUSED) {
                    mainUIHandler.removeCallbacks(progressUpdater); // 先移除，确保只有一个实例
                    mainUIHandler.post(progressUpdater); // 重新启动进度更新器
                }
            }
        });

        prepareMediaInBackground();
    }

    private void prepareMediaInBackground() {
        updateUIForState(PlayerState.PREPARING);
        backgroundExecutor.submit(() -> {
            try {
                File copiedMp4File = copyAssetToCacheDir(INPUT_FILE_NAME);
                mp4FilePath = copiedMp4File.getAbsolutePath();

                File yuvOutputFile = new File(getCacheDir(), YUV_FILE_NAME);
                yuvFilePath = yuvOutputFile.getAbsolutePath();

                Log.i(TAG, "Starting YUV decoding from " + mp4FilePath + " to " + yuvFilePath);
                int decodeResult = decodeVideoToFile(mp4FilePath, yuvFilePath);

                if (decodeResult == 0) {
                    isYuvDecoded = true;
                    videoFrameRate = nativeGetFrameRate(); // 获取帧率
                    if (videoFrameRate <= 0.001) { // 做个保护
                        Log.w(TAG, "Invalid frame rate from native: " + videoFrameRate + ", using default 25.0");
                        videoFrameRate = 25.0;
                    }
                    Log.i(TAG, "YUV decoding successful. Video Frame Rate: " + videoFrameRate);
                    mainUIHandler.post(() -> {
                        if (seekBar != null) {
                            int totalFrames = nativeGetTotalFrames(yuvFilePath);
                            seekBar.setMax(totalFrames > 0 ? totalFrames : 1); // 避免除以0
                            seekBar.setProgress(0);
                        }
                        if (isSurfaceReady) {
                            updateUIForState(PlayerState.IDLE);
                        } else {
                            Toast.makeText(this, "Video decoded, waiting for surface...", Toast.LENGTH_SHORT).show();
                        }
                    });
                } else {
                    Log.e(TAG, "YUV decoding failed, code: " + decodeResult);
                    mainUIHandler.post(() -> updateUIForState(PlayerState.ERROR));
                }
            } catch (IOException e) {
                Log.e(TAG, "Error preparing media files", e);
                mainUIHandler.post(() -> updateUIForState(PlayerState.ERROR));
            }
        });
    }

    private void handlePlayPause() {
        if (!isYuvDecoded) {
            Toast.makeText(this, "Video not yet decoded.", Toast.LENGTH_SHORT).show();
            if(currentPlayerState != PlayerState.PREPARING) prepareMediaInBackground();
            return;
        }
        if (!isSurfaceReady) {
            Toast.makeText(this, "Surface not ready.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (surfaceHolder == null || surfaceHolder.getSurface() == null || !surfaceHolder.getSurface().isValid()) {
            Log.e(TAG, "Surface is not valid for playback.");
            Toast.makeText(this, "Surface invalid, cannot play.", Toast.LENGTH_SHORT).show();
            return;
        }

        switch (currentPlayerState) {
            case IDLE:
            case STOPPED:
            case ERROR: // Retry can also start new playback
                startNewPlayback();
                break;
            case PLAYING:
                pauseCurrentPlayback();
                break;
            case PAUSED:
                resumeCurrentPlayback();
                break;
            default:
                break;
        }
    }

    private void startNewPlayback() {
        Log.i(TAG, "Starting new playback...");
        if (initAudio(mp4FilePath) == 0) {
            // 使用 pendingAudioSeekMs，如果没有则为-1 (或0)，native层会处理
            startAudio(mp4FilePath, pendingAudioSeekMs);
            pendingAudioSeekMs = -1; // 用完后重置
        } else {
            Log.e(TAG, "Failed to initialize audio. Proceeding without audio.");
            Toast.makeText(this, "Audio init failed", Toast.LENGTH_SHORT).show();
        }

        if (surfaceHolder != null && surfaceHolder.getSurface() != null && surfaceHolder.getSurface().isValid()) {
            // nativeSeekToFrame 应该在 onStopTrackingTouch 或 handleStop 中处理过了
            // nativeStartVideoPlayback 会启动渲染线程，该线程会检查g_seek_target_frame
            nativeStartVideoPlayback(yuvFilePath, surfaceHolder.getSurface());
            updateUIForState(PlayerState.PLAYING);
            mainUIHandler.removeCallbacks(progressUpdater); // 先移除，确保只有一个实例
            mainUIHandler.post(progressUpdater);
        } else {
            Log.e(TAG, "Cannot start playback, surface is not valid.");
            Toast.makeText(this, "Cannot start: Surface not ready.", Toast.LENGTH_SHORT).show();
            updateUIForState(PlayerState.ERROR);
        }
    }

    private void pauseCurrentPlayback() {
        if (currentPlayerState == PlayerState.PLAYING) {
            nativePauseVideo();
            pauseAudio(true);
            updateUIForState(PlayerState.PAUSED);
            mainUIHandler.removeCallbacks(progressUpdater); // 暂停时停止进度更新器
        }
    }

    private void resumeCurrentPlayback() {
        if (currentPlayerState == PlayerState.PAUSED) {
            if (isSurfaceReady && surfaceHolder != null && surfaceHolder.getSurface() != null && surfaceHolder.getSurface().isValid()) {
                nativeResumeVideo();
                pauseAudio(false);
                updateUIForState(PlayerState.PLAYING);
                mainUIHandler.removeCallbacks(progressUpdater); // 先移除
                mainUIHandler.post(progressUpdater); // 恢复播放后，重新启动进度更新
            } else {
                Log.w(TAG, "Cannot resume: Surface not ready. Forcing stop.");
                Toast.makeText(this, "Cannot resume: Surface not ready.", Toast.LENGTH_SHORT).show();
                handleStop();
            }
        }
    }

    private void handleStop() {
        Log.i(TAG, "Stopping playback...");
        nativeStopVideoPlayback();
        stopAudio();
        updateUIForState(PlayerState.STOPPED);
        mainUIHandler.removeCallbacks(progressUpdater);
        if (seekBar != null) {
            seekBar.setProgress(0); // 停止时进度条归零
        }
        pendingAudioSeekMs = -1; // 清除任何待处理的音频seek
        nativeSeekToFrame(0); // 请求视频也从头开始（如果下次播放）
    }

    private void handleSpeedToggle() {
        // 允许在播放或暂停时调整速度
        if (!isYuvDecoded || (currentPlayerState != PlayerState.PLAYING && currentPlayerState != PlayerState.PAUSED)) {
            Toast.makeText(this, "Please start playback first to change speed.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (currentSpeed == 1.0f) currentSpeed = 1.5f;
        else if (currentSpeed == 1.5f) currentSpeed = 2.0f;
        else if (currentSpeed == 2.0f) currentSpeed = 0.5f;
        else currentSpeed = 1.0f;

        nativeSetSpeed(currentSpeed);
        if (speedTextView != null) {
            mainUIHandler.post(() -> speedTextView.setText(String.format(Locale.US, "Speed: %.1fx", currentSpeed)));
        }
    }

    private void updateUIForState(PlayerState state) {
        currentPlayerState = state;
        Log.d(TAG, "Updating UI for state: " + state);

        if (playPauseButton == null || stopButton == null || speedButton == null || seekBar == null || speedTextView == null) {
            Log.e(TAG, "Cannot update UI, one or more view components are null.");
            if (state == PlayerState.ERROR && playPauseButton != null) {
                playPauseButton.setText("Retry");
                playPauseButton.setEnabled(true);
            }
            return;
        }

        switch (state) {
            case IDLE:
                playPauseButton.setText("Play");
                playPauseButton.setEnabled(isYuvDecoded && isSurfaceReady);
                stopButton.setEnabled(false);
                speedButton.setEnabled(false);
                seekBar.setEnabled(isYuvDecoded); // IDLE时允许拖动以选择起始点
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
                seekBar.setEnabled(isYuvDecoded); // STOPPED时允许拖动以选择起始点
                if (seekBar != null) seekBar.setProgress(0); // 停止时进度条归零
                break;
            case ERROR:
                playPauseButton.setText("Retry");
                playPauseButton.setEnabled(true); // 允许重试
                stopButton.setEnabled(false);
                speedButton.setEnabled(false);
                seekBar.setEnabled(false); // 出错时不允许操作进度条
                Toast.makeText(this, "An error occurred. Please retry.", Toast.LENGTH_LONG).show();
                break;
        }
        if (speedTextView != null) { // 确保 speedTextView 在任何时候都更新
            speedTextView.setText(String.format(Locale.US, "Speed: %.1fx", currentSpeed));
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Log.i(TAG, "Surface created.");
        this.surfaceHolder = holder;
        isSurfaceReady = true;
        // 如果YUV已解码且播放器处于可以播放的状态，更新UI
        if (isYuvDecoded && (currentPlayerState == PlayerState.IDLE || currentPlayerState == PlayerState.STOPPED || currentPlayerState == PlayerState.ERROR || currentPlayerState == PlayerState.PREPARING /*解码后可能还在preparing UI*/) ) {
            if (currentPlayerState == PlayerState.PREPARING && isYuvDecoded) { // 特殊情况：解码完成，但surface后创建
                updateUIForState(PlayerState.IDLE);
            } else {
                updateUIForState(currentPlayerState); // 保持当前状态的UI，但启用播放按钮 (如果适用)
            }
        } else if (currentPlayerState == PlayerState.PAUSED) {
            Log.w(TAG,"Surface created while player was paused. User might need to press resume.");
            // 此时可以考虑是否自动恢复，但通常让用户手动恢复更好
            updateUIForState(PlayerState.PAUSED); // 确保按钮状态正确
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.i(TAG, "Surface changed: " + width + "x" + height);
        this.surfaceHolder = holder;
        // 如果视频尺寸与Surface不匹配，可能需要在这里重新设置NativeWindow的buffer geometry
        // 但当前的实现是在 nativeStartVideoPlayback 中设置
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.i(TAG, "Surface destroyed.");
        isSurfaceReady = false;
        // 当Surface销毁时，如果正在播放或暂停，我们选择暂停而不是完全停止
        // 这样当Surface重建后，可以从暂停的位置恢复
        if (currentPlayerState == PlayerState.PLAYING || currentPlayerState == PlayerState.PAUSED) {
            Log.w(TAG, "Surface destroyed during playback/pause. Pausing playback.");
            nativePauseVideo(); // C++层暂停视频渲染
            pauseAudio(true);   // OpenSL ES 暂停音频
            updateUIForState(PlayerState.PAUSED); // 更新UI为暂停状态
            mainUIHandler.removeCallbacks(progressUpdater); // 停止进度更新
        }
        this.surfaceHolder = null;
    }

    private File copyAssetToCacheDir(String filename) throws IOException {
        File cacheDir = getCacheDir();
        File outFile = new File(cacheDir, filename);
        // Optional: if you don't want to re-copy every time, uncomment below
        // if (outFile.exists() && outFile.length() > 0) {
        //    Log.i(TAG, "Asset '" + filename + "' already exists in cache: " + outFile.getAbsolutePath());
        //    return outFile;
        // }
        try (InputStream in = getAssets().open(filename);
             OutputStream out = new FileOutputStream(outFile)) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            Log.i(TAG, "Asset '" + filename + "' copied to cache: " + outFile.getAbsolutePath());
        }
        return outFile;
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 当Activity进入后台时，如果正在播放，则暂停
        if (currentPlayerState == PlayerState.PLAYING) {
            pauseCurrentPlayback();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "onDestroy: Cleaning up resources.");
        handleStop(); // 停止播放并释放native资源
        if (backgroundExecutor != null && !backgroundExecutor.isShutdown()) {
            backgroundExecutor.shutdownNow();
        }
        if (mainUIHandler != null) {
            mainUIHandler.removeCallbacksAndMessages(null);
        }
        // 理论上 OpenSL ES 的 engine 和 outputMix 应该在这里销毁
        // 但当前stopAudio只销毁player。如果需要彻底清理，应增加nativeDestroyAudioEngine()等方法
        // 例如：nativeDestroyAudioEngine(); (需要在C++中实现)
    }
}