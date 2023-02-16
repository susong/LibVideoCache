package com.dream.video.cache.demo;

import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.dream.video.cache.HttpProxyCacheServer;

import java.io.File;
import java.io.IOException;

import tv.danmaku.ijk.media.player.IjkMediaPlayer;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    String url = "http://vfx.mtime.cn/Video/2019/03/09/mp4/190309153658147087.mp4";
//    String url = "https://media.w3.org/2010/05/sintel/trailer.mp4";
    private SurfaceView mSurfaceView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        IjkMediaPlayer player = new IjkMediaPlayer();
        String dir = Environment.getExternalStorageDirectory().getPath() + "/video_cache";
        File file = new File(dir);
        if (!file.exists()) {
            file.mkdirs();
        }
        HttpProxyCacheServer httpProxyCacheServer = new HttpProxyCacheServer.Builder(this)
                .cacheDirectory(file)
                .maxCacheSize(1024 * 1024 * 1024)
                .build();

        mSurfaceView = findViewById(R.id.surface_view);
        mSurfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
                player.setDisplay(holder);
                Log.d(TAG, "surfaceCreated: ");
            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
                Log.d(TAG, "surfaceChanged: ");
            }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                Log.d(TAG, "surfaceDestroyed: ");
            }
        });

        try {
            String proxyUrl = httpProxyCacheServer.getProxyUrl(url);
            player.setDataSource(proxyUrl);
            player.prepareAsync();
            player.start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}