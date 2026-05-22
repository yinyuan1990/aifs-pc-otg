package com.acard.acard.slowmotion;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.image.Image;
import javafx.util.Duration;

import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 边录边播慢动作播放器
 * 
 * 功能：
 * - 实时跟随录制进度播放
 * - 支持1-10倍慢放
 * - 支持进度跳转（只能跳到已录制的帧）
 */
public class LiveSlowMotionPlayer {
    
    private final SlowMotionRecorder recorder;
    private Timeline playbackTimeline;
    private volatile boolean playing = false;
    private AtomicInteger currentFrameIndex = new AtomicInteger(0);
    
    // 慢放倍数（1-10）
    private double slowMotionFactor = 1.0;
    
    // 实时流帧率（从录制器获取）
    private int baseFps = 30;
    
    // 帧回调（显示到UI）
    private Consumer<Image> frameCallback;
    
    // 进度回调（更新进度条）
    private Consumer<Double> progressCallback;
    
    public LiveSlowMotionPlayer(SlowMotionRecorder recorder) {
        this.recorder = recorder;
        System.out.println("🎬 边录边播播放器已创建");
    }
    
    /**
     * 设置基准帧率（从录制器获取）
     */
    public void setBaseFps(int fps) {
        this.baseFps = fps;
        
        // 如果正在播放，动态调整播放速度
        if (playing && Math.abs(this.baseFps - fps) > 2) {
            updatePlaybackSpeed();
        }
    }
    
    /**
     * 设置慢放倍数（1-10）
     */
    public void setSlowMotionFactor(double factor) {
        double oldFactor = this.slowMotionFactor;
        this.slowMotionFactor = Math.max(1.0, Math.min(10.0, factor));
        
        // 如果正在播放且倍数变化，重新调整播放速度
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
        
        playing = true;
        
        // 计算播放帧间隔
        double playbackFps = baseFps / slowMotionFactor;
        double frameInterval = 1000.0 / playbackFps;
        
        playbackTimeline = new Timeline(new KeyFrame(
            Duration.millis(frameInterval),
            event -> displayNextFrame()
        ));
        playbackTimeline.setCycleCount(Timeline.INDEFINITE);
        playbackTimeline.play();
        
        System.out.println("▶️ 开始边录边播: " + String.format("%.1f", playbackFps) + " fps");
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
    }
    
    /**
     * 停止
     */
    public void stop() {
        pause();
        currentFrameIndex.set(0);
    }
    
    /**
     * 显示下一帧
     */
    private void displayNextFrame() {
        int currentIndex = currentFrameIndex.get();
        List<FrameMetadata> frames = recorder.getFrames();
        
        // 如果当前索引超过已录制帧数，等待新帧
        if (currentIndex >= frames.size()) {
            // 录制已停止且没有新帧，停止播放
            if (!recorder.isRecording()) {
                pause();
                System.out.println("⏸️ 已播放完所有录制内容");
            }
            return;
        }
        
        // 加载当前帧
        Image frame = loadFrame(currentIndex, frames);
        if (frame != null && frameCallback != null) {
            frameCallback.accept(frame);
        }
        
        // 更新进度
        if (progressCallback != null && frames.size() > 0) {
            double progress = (double) currentIndex / Math.max(1, frames.size());
            progressCallback.accept(progress);
        }
        
        currentFrameIndex.incrementAndGet();
    }
    
    /**
     * 加载帧
     */
    private Image loadFrame(int index, List<FrameMetadata> frames) {
        if (index < 0 || index >= frames.size()) {
            return null;
        }
        
        try {
            FrameMetadata metadata = frames.get(index);
            File file = new File(metadata.filePath);
            
            if (!file.exists()) {
                return null;
            }
            
            return new Image(file.toURI().toString());
            
        } catch (Exception e) {
            System.err.println("⚠️ 加载帧失败: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 跳转到指定帧
     */
    public void seekToFrame(int frameIndex) {
        List<FrameMetadata> frames = recorder.getFrames();
        
        if (frameIndex >= 0 && frameIndex < frames.size()) {
            currentFrameIndex.set(frameIndex);
            
            // 如果正在播放，立即显示
            if (playing) {
                Image frame = loadFrame(frameIndex, frames);
                if (frame != null && frameCallback != null) {
                    frameCallback.accept(frame);
                }
            }
        }
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
     * 是否正在播放
     */
    public boolean isPlaying() {
        return playing;
    }
    
    /**
     * 获取当前帧索引
     */
    public int getCurrentFrameIndex() {
        return currentFrameIndex.get();
    }
    
    /**
     * 获取总帧数（已录制）
     */
    public int getTotalFrameCount() {
        return recorder.getFrameCount();
    }
    
    /**
     * 获取当前进度（0.0-1.0）
     */
    public double getProgress() {
        int total = recorder.getFrameCount();
        if (total == 0) {
            return 0.0;
        }
        return (double) currentFrameIndex.get() / total;
    }
    
    /**
     * 获取播放帧率
     */
    public double getPlaybackFps() {
        return baseFps / slowMotionFactor;
    }
    
    /**
     * 清理资源
     */
    public void cleanup() {
        stop();
    }
}

