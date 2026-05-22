package com.acard.acard;

import javafx.application.Platform;
import javafx.scene.image.Image;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 慢放播放器：从 FrameRingBuffer 的快照列表播放，支持 1-10x 慢放倍数。
 * 倍数含义：factor=1 正常速度，factor=10 表示 10 倍慢（速度为 1/10）。
 */
public class SlowMoPlayer implements PlayerControl {

    private final List<FrameRingBuffer.FrameItem> frames;
    private final Consumer<Image> imageConsumer;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "SlowMoPlayer");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> future;
    private int index = 0;
    private int factor = 1; // 1..10，越大越慢
    private long baseIntervalMs = 33; // 默认 30FPS

    public SlowMoPlayer(List<FrameRingBuffer.FrameItem> frames, int factor, Consumer<Image> imageConsumer) {
        this.frames = frames;
        this.imageConsumer = imageConsumer;
        setFactor(factor);
        computeBaseInterval();
    }

    private void computeBaseInterval() {
        if (frames == null || frames.size() < 2) {
            baseIntervalMs = 33;
            return;
        }
        long sum = 0;
        int cnt = 0;
        for (int i = 1; i < frames.size(); i++) {
            long dt = frames.get(i).timestamp - frames.get(i - 1).timestamp;
            if (dt > 0 && dt < 1000) { // 简单容错
                sum += dt;
                cnt++;
            }
        }
        baseIntervalMs = (cnt > 0) ? Math.max(10, Math.min(1000, sum / cnt)) : 33;
    }

    public void start() {
        if (frames == null || frames.isEmpty()) return;
        schedule();
    }

    private void schedule() {
        cancel();
        long period = Math.max(10, baseIntervalMs * factor);
        future = scheduler.scheduleAtFixedRate(this::tick, 0, period, TimeUnit.MILLISECONDS);
    }

    private void tick() {
        if (index >= frames.size()) {
            cancel();
            return;
        }
        FrameRingBuffer.FrameItem item = frames.get(index++);
        if (item.jpegBytes == null || item.jpegBytes.length == 0) return;
        Image img = new Image(new ByteArrayInputStream(item.jpegBytes));
        Platform.runLater(() -> imageConsumer.accept(img));
    }

    public void setFactor(int factor) {
        this.factor = Math.max(1, Math.min(10, factor));
        if (future != null && !future.isCancelled()) {
            schedule();
        }
    }

    public void stop() {
        cancel();
        scheduler.shutdown();
    }

    private void cancel() {
        if (future != null) {
            future.cancel(false);
            future = null;
        }
    }
}