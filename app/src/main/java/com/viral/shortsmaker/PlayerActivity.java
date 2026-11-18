package com.viral.shortsmaker;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.VideoView;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.util.Locale;

/** Full-screen player for one short (v1 kept five VideoViews alive in the list, which lagged low-end phones). */
public class PlayerActivity extends AppCompatActivity {
    /** v17: same compact UI scale on every screen */
    @Override protected void attachBaseContext(android.content.Context base) { super.attachBaseContext(UiScale.wrap(base)); }

    public static final String EXTRA_PATH = "path";
    private VideoView video;
    private SeekBar seek;
    private TextView cur, tot;
    private ImageView play;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (video != null && video.isPlaying()) {
                seek.setProgress(video.getCurrentPosition());
                cur.setText(fmt(video.getCurrentPosition()));
            }
            h.postDelayed(this, 250);
        }
    };

    static String fmt(int ms) { int s = ms / 1000; return String.format(Locale.US, "%02d:%02d", s / 60, s % 60); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_player);
        Ui.fitSystemBars(this);
        String path = getIntent().getStringExtra(EXTRA_PATH);
        if (path == null || !new File(path).exists()) { finish(); return; }
        video = findViewById(R.id.videoView);
        seek = findViewById(R.id.seekBar);
        cur = findViewById(R.id.tvCurrentTime);
        tot = findViewById(R.id.tvTotalTime);
        play = findViewById(R.id.btnPlayPause);
        findViewById(R.id.btnClose).setOnClickListener(v -> finish());
        video.setVideoPath(path);
        video.setOnPreparedListener(mp -> {
            seek.setMax(video.getDuration());
            tot.setText(fmt(video.getDuration()));
            // code 32: not when the user already left while it was loading (4.9: sound played in the background)
            if (!isFinishing() && getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) video.start();
            setPlayIcon();
        });
        video.setOnCompletionListener(mp -> setPlayIcon());
        video.setOnErrorListener((mp, what, extra) -> { finish(); return true; });
        play.setOnClickListener(v -> { if (video.isPlaying()) video.pause(); else video.start(); setPlayIcon(); });
        video.setOnClickListener(v -> { if (video.isPlaying()) video.pause(); else video.start(); setPlayIcon(); });
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) { if (user) { video.seekTo(p); cur.setText(fmt(p)); } }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        h.post(tick);
    }

    private void setPlayIcon() {
        play.setImageResource(video.isPlaying() ? R.drawable.ic_v_pause : R.drawable.ic_v_play);
    }

    @Override protected void onPause() { super.onPause(); if (video != null) video.pause(); setPlayIconSafe(); }
    private void setPlayIconSafe() { if (play != null && video != null) setPlayIcon(); }
    @Override protected void onDestroy() { h.removeCallbacksAndMessages(null); if (video != null) video.stopPlayback(); super.onDestroy(); }
}
