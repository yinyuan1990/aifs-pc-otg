package com.acard.acard.slowmotion;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.image.Image;
import javafx.util.Duration;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 慢动作播放器
 * 
 * 功能：
 * - 支持1-10倍慢放
 * - 进度跳转
 * - 预加载优化（1倍速）
 * - 内存优化（只缓存5帧）
 */
public class SlowMotionPlayer {
    
    private final String sessionId;
    private final List<FrameMetadata> frames;
    private Timeline playbackTimeline;
    private volatile boolean playing = false;
    private int currentFrameIndex = 0;
    
    // 慢放倍数（1-10）
    private double slowMotionFactor = 1.0;
    
    // 实时流帧率（从录制器获取）
    private int baseFps = 30;
    
    // 帧回调（显示到UI）
    private Consumer<Image> frameCallback;
    
    // 进度回调（更新进度条）
    private Consumer<Double> progressCallback;
    
    // 预加载缓存（优化1倍速，最多5帧）
    private final LinkedHashMap<Integer, WeakReference<Image>> frameCache = 
        new LinkedHashMap<Integer, WeakReference<Image>>(5, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry eldest) {
                return size() > 5;
            }
        };
    
    // 循环播放
    private boolean loop = true;
    
    public SlowMotionPlayer(String sessionId, List<FrameMetadata> frames) {
        this.sessionId = sessionId;
        this.frames = new ArrayList<>(frames);
        System.out.println("🎬 慢动作播放器已创建 (Session: " + sessionId + ", 帧数: " + frames.size() + ")");
    }
    
    /**
     * 设置基准帧率（从录制器获取）
     */
    public void setBaseFps(int fps) {
        this.baseFps = fps;
        System.out.println("📊 设置基准帧率: " + fps + " fps");
    }
    
    /**
     * 设置慢放倍数（1-10）
     */
    public void setSlowMotionFactor(double factor) {
        double oldFactor = this.slowMotionFactor;
        this.slowMotionFactor = Math.max(1.0, Math.min(10.0, factor));
        
        System.out.println("🎬 慢放倍数: " + String.format("%.1fx", this.slowMotionFactor) + 
            " (播放帧率: " + String.format("%.1f", baseFps / this.slowMotionFactor) + " fps)");
        
        // 如果正在播放且倍数变化，重新启动播放
        if (playing && Math.abs(oldFactor - this.slowMotionFactor) > 0.01) {
            updatePlaybackSpeed();
        }
    }
    
    /**
     * 播放
     */
    public void play() {
        if (playing) {
            return;
        }
        
        if (frames.isEmpty()) {
            System.err.println("⚠️ 无帧可播放");
            return;
        }
        
        playing = true;
        
        // 计算播放帧间隔
        // 例如：30fps，慢放2倍 → 播放15fps → 间隔66ms
        double playbackFps = baseFps / slowMotionFactor;
        double frameInterval = 1000.0 / playbackFps;
        
        playbackTimeline = new Timeline(new KeyFrame(
            Duration.millis(frameInterval),
            event -> displayNextFrame()
        ));
        playbackTimeline.setCycleCount(Timeline.INDEFINITE);
        playbackTimeline.play();
        
        System.out.println("▶️ 开始播放: " + String.format("%.1f", playbackFps) + " fps (间隔 " + 
            String.format("%.1f", frameInterval) + " ms)");
    }
    
    /**
     * 暂停
     */
    public void pause() {
        if (!playing) {
            return;
        }
        
        playing = false;
        if (playbackTimeline != null) {
            playbackTimeline.stop();
        }
        
        System.out.println("⏸️ 暂停播放 (当前帧: " + currentFrameIndex + "/" + frames.size() + ")");
    }
    
    /**
     * 停止
     */
    public void stop() {
        pause();
        currentFrameIndex = 0;
        
        // 清理缓存
        frameCache.clear();
        System.out.println("⏹️ 停止播放");
    }
    
    /**
     * 显示下一帧
     */
    private void displayNextFrame() {
        if (currentFrameIndex >= frames.size()) {
            if (loop) {
                // 循环播放
                currentFrameIndex = 0;
            } else {
                // 停止播放
                pause();
                return;
            }
        }
        
        // 预加载下几帧（优化播放流畅性）
        if (slowMotionFactor <= 2.0) {
            // 1-2倍速时预加载3帧
            preloadNextFrames(currentFrameIndex, 3);
        }
        
        // 加载当前帧
        Image frame = loadFrame(currentFrameIndex);
        if (frame != null && frameCallback != null) {
            frameCallback.accept(frame);
        }
        
        // 更新进度
        if (progressCallback != null) {
            double progress = (double) currentFrameIndex / frames.size();
            progressCallback.accept(progress);
        }
        
        currentFrameIndex++;
    }
    
    /**
     * 加载帧（带缓存）
     */
    private Image loadFrame(int index) {
        if (index < 0 || index >= frames.size()) {
            return null;
        }
        
        // 检查缓存
        WeakReference<Image> cached = frameCache.get(index);
        if (cached != null) {
            Image img = cached.get();
            if (img != null) {
                return img;
            }
        }
        
        // 从磁盘加载
        try {
            FrameMetadata metadata = frames.get(index);
            File file = new File(metadata.filePath);
            
            if (!file.exists()) {
                System.err.println("⚠️ 帧文件不存在: " + file);
                return null;
            }
            
            Image image = new Image(file.toURI().toString());
            frameCache.put(index, new WeakReference<>(image));
            
            return image;
            
        } catch (Exception e) {
            System.err.println("⚠️ 加载帧失败: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 预加载下N帧（异步）
     */
    private void preloadNextFrames(int currentIndex, int count) {
        for (int i = 1; i <= count; i++) {
            int nextIndex = currentIndex + i;
            if (nextIndex < frames.size()) {
                final int idx = nextIndex;
                CompletableFuture.runAsync(() -> {
                    loadFrame(idx);
                });
            }
        }
    }
    
    /**
     * 跳转到指定帧
     */
    public void seekToFrame(int frameIndex) {
        if (frameIndex >= 0 && frameIndex < frames.size()) {
            currentFrameIndex = frameIndex;
            
            // 如果正在播放，立即显示
            if (playing) {
                Image frame = loadFrame(currentFrameIndex);
                if (frame != null && frameCallback != null) {
                    frameCallback.accept(frame);
                }
            }
            
            System.out.println("⏩ 跳转到帧: " + frameIndex + "/" + frames.size());
        }
    }
    
    /**
     * 跳转到指定进度（0.0-1.0）
     */
    public void seekToProgress(double progress) {
        int frameIndex = (int) (progress * frames.size());
        seekToFrame(frameIndex);
    }
    
    /**
     * 更新播放速度（动态调整）
     */
    private void updatePlaybackSpeed() {
        if (playbackTimeline != null) {
            playbackTimeline.stop();
        }
        
        if (playing) {
            playing = false;
            play();
        }
    }
    
    /**
     * 设置帧回调（显示到UI）
     */
    public void setFrameCallback(Consumer<Image> callback) {
        this.frameCallback = callback;
    }
    
    /**
     * 设置进度回调（更新进度条）
     */
    public void setProgressCallback(Consumer<Double> callback) {
        this.progressCallback = callback;
    }
    
    /**
     * 设置循环播放
     */
    public void setLoop(boolean loop) {
        this.loop = loop;
    }
    
    /**
     * 是否正在播放
     */
    public boolean isPlaying() {
        return playing;
    }
    
    /**
     * 获取当前帧索引
     */
    public int getCurrentFrameIndex() {
        return currentFrameIndex;
    }
    
    /**
     * 获取总帧数
     */
    public int getTotalFrameCount() {
        return frames.size();
    }
    
    /**
     * 获取当前进度（0.0-1.0）
     */
    public double getProgress() {
        if (frames.isEmpty()) {
            return 0.0;
        }
        return (double) currentFrameIndex / frames.size();
    }
    
    /**
     * 获取慢放倍数
     */
    public double getSlowMotionFactor() {
        return slowMotionFactor;
    }
    
    /**
     * 获取当前播放帧率
     */
    public double getPlaybackFps() {
        return baseFps / slowMotionFactor;
    }
    
    /**
     * 清理资源
     */
    public void cleanup() {
        stop();
        frameCache.clear();
        System.out.println("🗑️ 播放器已清理");
    }
}

