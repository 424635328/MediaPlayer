package com.example.androidplayer;

import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import android.os.Handler;
import android.os.Message;

public class MainActivity extends AppCompatActivity implements SurfaceHolder.Callback {

    private static final String TAG = "MainActivity";
    private static final String INPUT_FILE_NAME = "1.mp4";
    private static final String YUV_FILE_NAME = "output.yuv";
    private SurfaceView surfaceView;
    private SurfaceHolder surfaceHolder;
    private ExecutorService executorService = Executors.newSingleThreadExecutor();
    private String inputFilePath;
    private String yuvFilePath;
    private int videoWidth = 1024;
    private int videoHeight = 436;
    private AtomicBoolean isPlaying = new AtomicBoolean(false);
    private float playbackSpeed = 1.0f; // 默认播放速度
    private int videoDuration = 0; // 总时长

    private Button playButton, stopButton, speedButton;
    private SeekBar seekBar;

    private static final int MSG_UPDATE_PROGRESS = 1;

    //handler
    private Handler handler = new Handler(new Handler.Callback() {
        @Override
        public boolean handleMessage(Message msg) {
            if (msg.what == MSG_UPDATE_PROGRESS) {
                seekBar.setProgress(msg.arg1); // 更新 SeekBar 进度
                return true;
            }
            return false;
        }
    });

    static {
        try {
            Log.i(TAG, "正在加载 ffmpeg 库...");
            System.loadLibrary("ffmpeg");
            Log.i(TAG, "ffmpeg 库加载成功");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "加载 ffmpeg 库失败", e);
        }

        try {
            Log.i(TAG, "正在加载 androidplayer 库...");
            System.loadLibrary("androidplayer");
            Log.i(TAG, "androidplayer 库加载成功");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "加载 androidplayer 库失败", e);
        }
    }

    private native String stringFromJNI();
    private native int decodeVideoToFile(String inputFilePath, String outputFilePath);
    private native int renderYUVToSurface(String yuvFilePath, int width, int height, Surface surface, AtomicBoolean isPlaying);
    private native void nativeSetSpeed(float speed);
    private native void nativePause();
//    private native int getVideoDuration(String yuvFilePath);
//    private native int seekToPosition(String yuvFilePath, int position); // 添加 Seek 方法

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        TextView tv = findViewById(R.id.sample_text);
        surfaceView = findViewById(R.id.surfaceView);
        surfaceHolder = surfaceView.getHolder();
        surfaceHolder.addCallback(this);

        playButton = findViewById(R.id.button);
        stopButton = findViewById(R.id.button2);
        speedButton = findViewById(R.id.button3);
        seekBar = findViewById(R.id.seekBar);

        try {
            String jniString = stringFromJNI();
            tv.setText(jniString);
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "调用 stringFromJNI 失败", e);
            tv.setText("错误：无法加载 JNI 字符串。");
        }

        playButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startOrResumePlaying();
            }
        });

        stopButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopPlaying();
            }
        });

        speedButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleSpeed();
            }
        });

//        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
//            @Override
//            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
//                // 当用户拖动 SeekBar 时触发
//            }
//
//            @Override
//            public void onStartTrackingTouch(SeekBar seekBar) {
//                // 当用户开始触摸 SeekBar 时触发
//            }
//
//            @Override
//            public void onStopTrackingTouch(SeekBar seekBar) {
//                // 当用户停止触摸 SeekBar 时触发
//                Log.i(TAG, "Seeking to position: " + seekBar.getProgress());
//                seekToPosition(seekBar.getProgress());
//            }
//        });
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Log.i(TAG, "Surface 创建");
        generateYUVAndPlay();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.i(TAG, "Surface 改变: width=" + width + ", height=" + height);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.i(TAG, "Surface 销毁");
        stopPlaying();
    }

    private void generateYUVAndPlay() {
        executorService.execute(() -> {
            File inputFile = null;
            try {
                inputFile = copyAssetToFile(INPUT_FILE_NAME);
            } catch (IOException e) {
                Log.e(TAG, "复制 asset 文件失败", e);
                return;
            }

            if (inputFile == null) {
                Log.e(TAG, "获取输入文件失败");
                return;
            }

            inputFilePath = inputFile.getAbsolutePath();
            File outputDir = getApplicationContext().getFilesDir();
            File outputFile = new File(outputDir, YUV_FILE_NAME);
            yuvFilePath = outputFile.getAbsolutePath();

            if (!inputFile.exists()) {
                Log.e(TAG, "输入文件不存在: " + inputFilePath);
                return;
            }

            // 1. 解码生成 YUV 文件 (在后台线程中)
            try {
                Log.i(TAG, "调用 decodeVideoToFile, 输入文件: " + inputFilePath + ", 输出文件: " + yuvFilePath);
                decodeVideoToFile(inputFilePath, yuvFilePath);
                Log.i(TAG, "解码成功完成！YUV 文件: " + yuvFilePath);
            } catch (UnsatisfiedLinkError e) {
                Log.e(TAG, "调用 decodeVideoToFile 失败", e);
                Log.e(TAG, "输入路径: " + inputFilePath);
                Log.e(TAG, "输出路径: " + yuvFilePath);
                return;
            }

            //获取视频总时长
            File yuvFile = new File(yuvFilePath);
            if (!yuvFile.exists()) {
                Log.e(TAG, "YUV 文件不存在: " + yuvFilePath);
                return;
            }

//            videoDuration = getVideoDuration(yuvFilePath);

            // 设置 SeekBar 的最大值
            runOnUiThread(() -> seekBar.setMax(videoDuration));

            // 2. 渲染 YUV 文件到 SurfaceView (在后台线程中)
            Surface surface = surfaceHolder.getSurface();
            isPlaying.set(true);
            executorService.execute(() -> {
                try {
                    Log.i(TAG, "调用 renderYUVToSurface, YUV 文件: " + yuvFilePath);
                    int result = renderYUVToSurface(yuvFilePath, videoWidth, videoHeight, surface, isPlaying);
                    if (result < 0) {
                        Log.e(TAG, "渲染失败，错误代码: " + result);
                    }
                    Log.i(TAG, "渲染成功完成！");
                } catch (UnsatisfiedLinkError e) {
                    Log.e(TAG, "调用 renderYUVToSurface 失败", e);
                    Log.e(TAG, "YUV 路径: " + yuvFilePath);
                }
            });
        });
    }

    private void startOrResumePlaying() {
        if (isPlaying.get()) {
            nativePause(); // 暂停/恢复播放
        } else {
            Surface surface = surfaceHolder.getSurface();
            isPlaying.set(true); // 开始播放
            executorService.execute(() -> {
                try {
                    Log.i(TAG, "调用 renderYUVToSurface, YUV 文件: " + yuvFilePath);
                    int result = renderYUVToSurface(yuvFilePath, videoWidth, videoHeight, surface, isPlaying);
                    if (result < 0) {
                        Log.e(TAG, "渲染失败，错误代码: " + result);
                    }
                    Log.i(TAG, "渲染成功完成！");
                } catch (UnsatisfiedLinkError e) {
                    Log.e(TAG, "调用 renderYUVToSurface 失败", e);
                    Log.e(TAG, "YUV 路径: " + yuvFilePath);
                }
            });
        }
    }

//    private void seekToPosition(int position) {
//        executorService.execute(() -> {
//            // 调用 C++ 代码执行 Seek 操作
//            int result = seekToPosition(yuvFilePath, position);
//            if (result < 0) {
//                Log.e(TAG, "Seek 失败，错误代码: " + result);
//            } else {
//                Log.i(TAG, "Seek 成功完成，位置: " + position);
//                // Seek 完成后重新开始播放
//                startOrResumePlaying();
//            }
//        });
//    }

    private void stopPlaying() {
        Log.i(TAG, "停止按钮被按下");
        isPlaying.set(false);
    }

    private void toggleSpeed() {
        playbackSpeed = (playbackSpeed == 1.0f) ? 2.0f : 1.0f; // 切换速度
        Log.i(TAG, "设置播放速度为: " + playbackSpeed);
        nativeSetSpeed(playbackSpeed); // 设置播放速度
    }

    private File copyAssetToFile(String filename) throws IOException {
        InputStream in = null;
        OutputStream out = null;
        File outFile = null;

        try {
            in = getAssets().open(filename);
            File outputDir = getApplicationContext().getFilesDir();
            outFile = new File(outputDir, filename);

            out = new FileOutputStream(outFile);
            copyFile(in, out);
            Log.d(TAG, "Asset 成功复制到: " + outFile.getAbsolutePath());

        } catch (IOException e) {
            Log.e(TAG, "复制 asset 文件出错: " + filename, e);
            throw e;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                    Log.e(TAG, "关闭输入流出错", e);
                }
            }
            if (out != null) {
                try {
                    out.close();
                } catch (IOException e) {
                    Log.e(TAG, "关闭输出流出错", e);
                }
            }
        }
        return outFile;
    }

    private void copyFile(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executorService.shutdown();
    }
}