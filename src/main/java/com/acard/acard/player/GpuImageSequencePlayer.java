package com.acard.acard.player;

import org.freedesktop.gstreamer.*;
import org.freedesktop.gstreamer.elements.*;
import org.freedesktop.gstreamer.event.SeekEvent;
import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import org.freedesktop.gstreamer.event.SeekFlags;
import org.freedesktop.gstreamer.event.SeekType;

import java.io.File;
import java.util.EnumSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 基于 GStreamer 的 GPU 加速图片序列播放器
 * 支持慢放、拖动、倍数调节功能
 */
public class GpuImageSequencePlayer {
    
    // GStreamer 组件
    private Pipeline pipeline;
    private Element fileSrc;
    private Element jpegDec;
    private Element videoConvert;
    private Element d3d11Upload;
    private Element d3d11Convert;
    private AppSink appSink;
    
    // 播放控制
    private final AtomicBoolean isPlaying = new AtomicBoolean(false);
    private final AtomicBoolean isPaused = new AtomicBoolean(false);
    private final AtomicInteger currentFrame = new AtomicInteger(0);
    private final AtomicInteger totalFrames = new AtomicInteger(3000);
    
    // 播放参数
    private double playbackRate = 1.0;
    private String imageDirectory;
    private String imagePattern;
    
    // JavaFX 属性
    private final DoubleProperty progressProperty = new SimpleDoubleProperty(0.0);
    private final BooleanProperty playingProperty = new SimpleBooleanProperty(false);
    private final DoubleProperty rateProperty = new SimpleDoubleProperty(1.0);
    private final IntegerProperty frameProperty = new SimpleIntegerProperty(0);
    
    // 回调接口
    public interface FrameCallback {
        void onFrameUpdate(int frameNumber, double progress);
    }
    
    private FrameCallback frameCallback;
    
    // 性能优化器

    
    public GpuImageSequencePlayer(String imageDirectory) {
        this.imageDirectory = imageDirectory;
        this.imagePattern = imageDirectory + "/s_%05d.jpeg";
        

        
        initializeGStreamer();
        setupPipeline();
    }
    
    /**
     * 初始化 GStreamer
     */
    private void initializeGStreamer() {
        try {
            // 设置 GStreamer 路径
            String gstPath = System.getProperty("user.dir") + "/runtime/gstreamer/win64/bin";
            System.setProperty("jna.library.path", gstPath);
            
            // 初始化 GStreamer
            Gst.init("GpuImageSequencePlayer");
            System.out.println("GStreamer 初始化成功");
        } catch (Exception e) {
            System.err.println("GStreamer 初始化失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 设置 GStreamer 管道
     */
    private void setupPipeline() {
        try {
            // 创建管道
            pipeline = new Pipeline("image-sequence-player");
            
            // 创建元素
            fileSrc = ElementFactory.make("filesrc", "filesrc");
            jpegDec = ElementFactory.make("jpegdec", "jpegdec");
            videoConvert = ElementFactory.make("videoconvert", "videoconvert");
            
            // GPU 加速元素
            d3d11Upload = ElementFactory.make("d3d11upload", "d3d11upload");
            d3d11Convert = ElementFactory.make("d3d11convert", "d3d11convert");
            
            // 输出元素
            appSink = new AppSink("appsink");
            
            // 配置 AppSink
            appSink.set("emit-signals", true);
            appSink.set("sync", true);
            appSink.set("max-buffers", 1);
            appSink.set("drop", true);
            
            // 设置 Caps
            Caps caps = Caps.fromString("video/x-raw,width=1280,height=720,format=BGRA");
            appSink.setCaps(caps);
            
            // 性能优化

            
            // 添加元素到管道
            pipeline.addMany(fileSrc, jpegDec, videoConvert, d3d11Upload, d3d11Convert, appSink);
            
            // 链接元素
            Element.linkMany(fileSrc, jpegDec, videoConvert, d3d11Upload, d3d11Convert, appSink);
            
            // 设置新样本回调
            appSink.connect(new AppSink.NEW_SAMPLE() {
                @Override
                public FlowReturn newSample(AppSink appSink) {
                    return onNewSample(appSink);
                }
            });
            
            // 设置总帧数
            updateTotalFrames();
            

            
            System.out.println("GStreamer 管道设置完成");
            
        } catch (Exception e) {
            System.err.println("管道设置失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 处理新样本
     */
    private FlowReturn onNewSample(AppSink appSink) {
        try {
            Sample sample = appSink.pullSample();
            if (sample != null) {
                // 更新当前帧
                int frame = currentFrame.incrementAndGet();
                double progress = (double) frame / totalFrames.get();
                
                // 更新 JavaFX 属性
                Platform.runLater(() -> {
                    progressProperty.set(progress);
                    frameProperty.set(frame);
                    
                    if (frameCallback != null) {
                        frameCallback.onFrameUpdate(frame, progress);
                    }
                });
                
                sample.dispose();
            }
            return FlowReturn.OK;
        } catch (Exception e) {
            System.err.println("处理样本失败: " + e.getMessage());
            return FlowReturn.ERROR;
        }
    }
    
    /**
     * 开始播放
     */
    public void play() {
        if (pipeline != null && !isPlaying.get()) {
            try {
                // 设置播放速率
                pipeline.seek(playbackRate, Format.TIME, 

                        EnumSet.of(SeekFlags.FLUSH, SeekFlags.ACCURATE),
                    SeekType.SET, 0,
                    SeekType.NONE, -1);
                
                pipeline.setState(State.PLAYING);
                isPlaying.set(true);
                isPaused.set(false);
                
                Platform.runLater(() -> playingProperty.set(true));
                
                System.out.println("开始播放，速率: " + playbackRate);
            } catch (Exception e) {
                System.err.println("播放失败: " + e.getMessage());
            }
        }
    }
    
    /**
     * 暂停播放
     */
    public void pause() {
        if (pipeline != null && isPlaying.get()) {
            try {
                pipeline.setState(State.PAUSED);
                isPaused.set(true);
                
                Platform.runLater(() -> playingProperty.set(false));
                
                System.out.println("暂停播放");
            } catch (Exception e) {
                System.err.println("暂停失败: " + e.getMessage());
            }
        }
    }
    
    /**
     * 停止播放
     */
    public void stop() {
        if (pipeline != null) {
            try {
                pipeline.setState(State.NULL);
                isPlaying.set(false);
                isPaused.set(false);
                currentFrame.set(0);
                
                Platform.runLater(() -> {
                    playingProperty.set(false);
                    progressProperty.set(0.0);
                    frameProperty.set(0);
                });
                
                System.out.println("停止播放");
            } catch (Exception e) {
                System.err.println("停止失败: " + e.getMessage());
            }
        }
    }
    
    /**
     * 跳转到指定帧
     */
    public void seekToFrame(int frameNumber) {
        if (pipeline != null && frameNumber >= 0 && frameNumber < totalFrames.get()) {
            try {
                // 计算时间位置
                long timeNs = (long) ((double) frameNumber / totalFrames.get() * getDurationNs());
                
                // 执行跳转
                pipeline.seek(playbackRate, Format.TIME,

                        EnumSet.of(SeekFlags.FLUSH, SeekFlags.ACCURATE),
                    SeekType.SET, timeNs,
                    SeekType.NONE, -1);
                
                currentFrame.set(frameNumber);
                
                Platform.runLater(() -> {
                    double progress = (double) frameNumber / totalFrames.get();
                    progressProperty.set(progress);
                    frameProperty.set(frameNumber);
                });
                
                System.out.println("跳转到帧: " + frameNumber);
            } catch (Exception e) {
                System.err.println("跳转失败: " + e.getMessage());
            }
        }
    }
    
    /**
     * 跳转到指定进度
     */
    public void seekToProgress(double progress) {
        if (progress >= 0.0 && progress <= 1.0) {
            int frameNumber = (int) (progress * totalFrames.get());
            seekToFrame(frameNumber);
        }
    }
    
    /**
     * 设置播放速率
     */
    public void setPlaybackRate(double rate) {
        if (rate > 0.0 && rate <= 10.0) {
            this.playbackRate = rate;
            
            Platform.runLater(() -> rateProperty.set(rate));
            
            // 如果正在播放，重新设置速率
            if (isPlaying.get()) {
                try {
                    long currentTime = pipeline.queryPosition(Format.TIME);
                    pipeline.seek(rate, Format.TIME,
                            EnumSet.of(SeekFlags.FLUSH, SeekFlags.ACCURATE),
                        SeekType.SET, currentTime,
                        SeekType.NONE, -1);
                    
                    System.out.println("设置播放速率: " + rate);
                } catch (Exception e) {
                    System.err.println("设置速率失败: " + e.getMessage());
                }
            }
        }
    }
    
    /**
     * 获取视频总时长（纳秒）
     */
    private long getDurationNs() {
        try {
            return pipeline.queryDuration(Format.TIME);
        } catch (Exception e) {
            // 估算时长：假设30fps
            return (long) (totalFrames.get() / 30.0 * TimeUnit.SECONDS.toNanos(1));
        }
    }
    
    /**
     * 更新总帧数
     */
    private void updateTotalFrames() {
        try {
            File dir = new File(imageDirectory);
            if (dir.exists() && dir.isDirectory()) {
                File[] files = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".jpeg") || name.toLowerCase().endsWith(".jpg"));
                if (files != null) {
                    totalFrames.set(files.length);
                    System.out.println("检测到图片数量: " + files.length);
                }
            }
        } catch (Exception e) {
            System.err.println("更新帧数失败: " + e.getMessage());
        }
    }
    
    /**
     * 设置当前显示的图片文件
     */
    public void setCurrentImage(int frameNumber) {
        if (fileSrc != null && frameNumber >= 0) {
            try {
                // 计算文件名（从339开始）
                int imageNumber = 339 + frameNumber;
                String filename = String.format("%s/s_%05d.jpeg", imageDirectory, imageNumber);
                
                File file = new File(filename);
                if (file.exists()) {
                    fileSrc.set("location", filename);
                    System.out.println("设置图片: " + filename);
                } else {
                    System.err.println("图片文件不存在: " + filename);
                }
            } catch (Exception e) {
                System.err.println("设置图片失败: " + e.getMessage());
            }
        }
    }
    
    // Getter 方法
    public boolean isPlaying() { return isPlaying.get(); }
    public boolean isPaused() { return isPaused.get(); }
    public int getCurrentFrame() { return currentFrame.get(); }
    public int getTotalFrames() { return totalFrames.get(); }
    public double getPlaybackRate() { return playbackRate; }
    
    // JavaFX 属性
    public DoubleProperty progressProperty() { return progressProperty; }
    public BooleanProperty playingProperty() { return playingProperty; }
    public DoubleProperty rateProperty() { return rateProperty; }
    public IntegerProperty frameProperty() { return frameProperty; }
    
    // 设置回调
    public void setFrameCallback(FrameCallback callback) {
        this.frameCallback = callback;
    }
    
    /**
     * 释放资源
     */
    public void dispose() {
        try {
            if (pipeline != null) {
                pipeline.setState(State.NULL);
                pipeline.dispose();
            }
            System.out.println("播放器资源已释放");
        } catch (Exception e) {
            System.err.println("释放资源失败: " + e.getMessage());
        }
    }
}