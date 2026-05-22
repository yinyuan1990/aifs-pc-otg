package com.acard.acard.player;

import org.freedesktop.gstreamer.*;
import org.freedesktop.gstreamer.elements.AppSink;
import javafx.application.Platform;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * 简单的GPU播放器
 * 写死参数用于测试效果
 */
public class SimpleGpuPlayer {
    
    private Pipeline pipeline;
    private Element fileSrc;
    private Element jpegDec;
    private Element videoConvert;
    private Element d3d11Upload;
    private Element d3d11Convert;
    private AppSink appSink;
    
    private File[] imageFiles;
    private int currentIndex = 0;
    private boolean isPlaying = false;
    private double playbackSpeed = 1.0;
    
    // 回调接口
    public interface FrameCallback {
        void onFrame(byte[] frameData, int frameIndex, int totalFrames);
    }
    
    private FrameCallback frameCallback;
    
    public SimpleGpuPlayer(String imageDirectory) {
        // 初始化GStreamer
        Gst.init("SimpleGpuPlayer");
        
        // 获取图片文件列表
        loadImageFiles(imageDirectory);
        
        // 设置GPU管道
        setupGpuPipeline();
    }
    
    /**
     * 加载图片文件列表
     */
    private void loadImageFiles(String directory) {
        File dir = new File(directory);
        imageFiles = dir.listFiles((d, name) -> 
            name.toLowerCase().endsWith(".jpg") || 
            name.toLowerCase().endsWith(".jpeg") || 
            name.toLowerCase().endsWith(".png"));
        
        if (imageFiles != null) {
            Arrays.sort(imageFiles, (a, b) -> a.getName().compareTo(b.getName()));
        }
    }
    
    /**
     * 设置GPU管道
     */
    private void setupGpuPipeline() {
        try {
            // 创建管道
            pipeline = new Pipeline("gpu-player-pipeline");
            
            // 创建元素
            fileSrc = ElementFactory.make("filesrc", "file-source");
            jpegDec = ElementFactory.make("jpegdec", "jpeg-decoder");
            videoConvert = ElementFactory.make("videoconvert", "video-convert");
            d3d11Upload = ElementFactory.make("d3d11upload", "d3d11-upload");
            d3d11Convert = ElementFactory.make("d3d11convert", "d3d11-convert");
            appSink = (AppSink) ElementFactory.make("appsink", "app-sink");
            
            // 写死Caps参数 - 1280x720
            //Caps caps = Caps.fromString("video/x-raw,format=BGRA");
            Caps caps = Caps.fromString("video/x-raw,width=1280,height=720,format=BGRA");
            // 配置AppSink
            appSink.set("emit-signals", true);
            appSink.set("max-buffers", 1);
            appSink.set("drop", true);
            appSink.setCaps(caps);
            // 设置帧回调
            appSink.connect(new AppSink.NEW_SAMPLE() {
                @Override
                public FlowReturn newSample(AppSink appSink) {
                    Sample sample = appSink.pullSample();
                    if (sample != null) {
                        Buffer buffer = sample.getBuffer();
                        ByteBuffer byteBuffer = buffer.map(false);
                        
                        if (byteBuffer != null) {
                            byte[] frameData = new byte[byteBuffer.remaining()];
                            byteBuffer.get(frameData);
                            buffer.unmap();
                            
                            // 回调到UI线程
                            if (frameCallback != null) {
                                Platform.runLater(() -> {
                                    frameCallback.onFrame(frameData, currentIndex, 
                                                        imageFiles != null ? imageFiles.length : 0);
                                });
                            }
                        }
                        sample.dispose();
                    }
                    return FlowReturn.OK;
                }
            });
            
            // 添加元素到管道
            pipeline.addMany(fileSrc, jpegDec, videoConvert, d3d11Upload, d3d11Convert, appSink);
            
            // 链接元素
            Element.linkMany(fileSrc, jpegDec, videoConvert, d3d11Upload, d3d11Convert, appSink);
            
            System.out.println("GPU管道设置完成 - 使用1280x720分辨率");
            
        } catch (Exception e) {
            System.err.println("设置GPU管道失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 设置帧回调
     */
    public void setFrameCallback(FrameCallback callback) {
        this.frameCallback = callback;
    }
    
    /**
     * 加载指定帧
     */
    public void loadFrame(int frameIndex) {
        if (imageFiles == null || frameIndex < 0 || frameIndex >= imageFiles.length) {
            return;
        }
        
        try {
            currentIndex = frameIndex;
            
            // 停止当前播放
            pipeline.setState(State.NULL);
            
            // 设置新文件
            fileSrc.set("location", imageFiles[frameIndex].getAbsolutePath());
            
            // 开始播放这一帧
            pipeline.setState(State.PLAYING);
            
            // 短暂播放后暂停
            new Thread(() -> {
                try {
                    Thread.sleep(100);
                    pipeline.setState(State.PAUSED);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();
            
        } catch (Exception e) {
            System.err.println("加载帧失败: " + e.getMessage());
        }
    }
    
    /**
     * 播放
     */
    public void play() {
        isPlaying = true;
        startPlayback();
    }
    
    /**
     * 暂停
     */
    public void pause() {
        isPlaying = false;
        pipeline.setState(State.PAUSED);
    }
    
    /**
     * 停止
     */
    public void stop() {
        isPlaying = false;
        currentIndex = 0;
        pipeline.setState(State.NULL);
    }
    
    /**
     * 跳转到指定帧
     */
    public void seekToFrame(int frameIndex) {
        currentIndex = frameIndex;
        loadFrame(frameIndex);
    }
    
    /**
     * 设置播放速度
     */
    public void setPlaybackSpeed(double speed) {
        this.playbackSpeed = speed;
    }
    
    /**
     * 是否正在播放
     */
    public boolean isPlaying() {
        return isPlaying;
    }
    
    /**
     * 获取总帧数
     */
    public int getTotalFrames() {
        return imageFiles != null ? imageFiles.length : 0;
    }
    
    /**
     * 获取当前帧索引
     */
    public int getCurrentFrame() {
        return currentIndex;
    }
    
    /**
     * 开始播放
     */
    private void startPlayback() {
        new Thread(() -> {
            while (isPlaying && imageFiles != null) {
                try {
                    loadFrame(currentIndex);
                    
                    // 计算延迟时间
                    long delay = (long)(1000.0 / (30.0 * playbackSpeed));
                    Thread.sleep(delay);
                    
                    // 下一帧
                    currentIndex++;
                    if (currentIndex >= imageFiles.length) {
                        currentIndex = 0; // 循环播放
                    }
                    
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    System.err.println("播放错误: " + e.getMessage());
                    break;
                }
            }
        }).start();
    }
    
    /**
     * 释放资源
     */
    public void dispose() {
        if (pipeline != null) {
            pipeline.setState(State.NULL);
            pipeline.dispose();
        }
    }
}