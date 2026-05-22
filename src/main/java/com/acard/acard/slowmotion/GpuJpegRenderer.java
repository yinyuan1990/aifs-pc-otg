package com.acard.acard.slowmotion;

import com.acard.acard.tools.LogTools;
import com.sun.jna.Native;
import org.freedesktop.gstreamer.*;
import org.freedesktop.gstreamer.elements.AppSrc;
import org.freedesktop.gstreamer.interfaces.VideoOverlay;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;

/**
 * GPU JPEG 渲染器（测试版）
 * 
 * 自动弹出一个测试窗口，用GPU渲染JPEG图片
 * 
 * 用法：
 *   GpuJpegRenderer.getInstance().displayImage("D:/path/to/image.jpeg");
 */
public class GpuJpegRenderer {
    
    // 单例
    private static GpuJpegRenderer instance;
    
    private Pipeline pipeline;
    private AppSrc appsrc;  // ⭐ 使用 appsrc 推送数据，避免闪屏
    private Element jpegdec;
    private Element d3d11upload;
    private Element d3d11convert;
    private Element d3d11videosink;
    private VideoOverlay videoOverlay;
    
    private long hwnd;
    private volatile boolean initialized = false;  // 窗口是否已创建
    private volatile boolean pipelineReady = false;  // 管道是否已就绪
    private volatile String lastPath = null;
    
    // 测试窗口
    private JFrame testFrame;
    private Canvas canvas;
    
    private GpuJpegRenderer() {
    }
    
    public static synchronized GpuJpegRenderer getInstance() {
        if (instance == null) {
            instance = new GpuJpegRenderer();
        }
        return instance;
    }
    
    /**
     * 创建测试窗口并初始化GPU渲染
     */
    public void initTestWindow() {
        System.out.println("🎬 GpuJpegRenderer.initTestWindow() 被调用, initialized=" + initialized);
        LogTools.getInstance().logRecord4("🎬 GpuJpegRenderer.initTestWindow() 被调用, initialized=" + initialized);
        
        if (initialized) {
            System.out.println("⚠️ 已初始化，跳过");
            return;
        }
        
        // 标记正在初始化，防止重复
        initialized = true;
        
        SwingUtilities.invokeLater(() -> {
            try {
                System.out.println("🖼️ 正在创建测试窗口...");
                
                // 创建测试窗口
                testFrame = new JFrame("GPU JPEG 渲染测试");
                testFrame.setSize(960, 540);  // 测试窗口大小
                testFrame.setLocationRelativeTo(null);  // 居中
                testFrame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
                testFrame.setAlwaysOnTop(true);  // 置顶显示
                
                // 创建Canvas用于获取HWND
                canvas = new Canvas();
                canvas.setBackground(Color.BLACK);
                testFrame.add(canvas);
                
                testFrame.setVisible(true);
                testFrame.toFront();  // 确保窗口在最前面
                
                System.out.println("🖼️ 测试窗口已创建并显示");
                
                // 获取Canvas的HWND
                long canvasHwnd = Native.getComponentID(canvas);
                System.out.println("🖼️ Canvas HWND=" + canvasHwnd);
                LogTools.getInstance().logRecord4("🖼️ 测试窗口创建成功, Canvas HWND=" + canvasHwnd);
                
                // 延迟初始化GStreamer管道，确保窗口完全显示
                new Thread(() -> {
                    try {
                        Thread.sleep(500);  // 等待窗口完全显示
                        initPipeline(canvasHwnd);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }).start();
                
            } catch (Exception e) {
                System.out.println("❌ 创建测试窗口失败: " + e.getMessage());
                LogTools.getInstance().logRecord4("❌ 创建测试窗口失败: " + e.getMessage());
                e.printStackTrace();
                initialized = false;
            }
        });
    }
    
    /**
     * 初始化GPU渲染管道（使用 appsrc 避免闪屏）
     */
    private void initPipeline(long hwnd) {
        this.hwnd = hwnd;
        System.out.println("🔧 initPipeline 开始, HWND=" + hwnd);
        
        try {
            // 确保GStreamer已初始化
            System.out.println("🔧 检查 GStreamer...");
            if (!Gst.isInitialized()) {
                System.out.println("🔧 GStreamer 未初始化，正在初始化...");
                Gst.init("GpuJpegRenderer");
            }
            System.out.println("🔧 GStreamer 已就绪");
            
            // 创建元素（使用 appsrc 推送数据，避免闪屏）
            System.out.println("🔧 创建 GStreamer 元素...");
            appsrc = (AppSrc) ElementFactory.make("appsrc", "gpu_appsrc");
            jpegdec = ElementFactory.make("jpegdec", "gpu_jpegdec");
            d3d11upload = ElementFactory.make("d3d11upload", "gpu_d3d11upload");
            d3d11convert = ElementFactory.make("d3d11convert", "gpu_d3d11convert");
            d3d11videosink = ElementFactory.make("d3d11videosink", "gpu_d3d11videosink");
            
            System.out.println("🔧 元素创建结果: appsrc=" + appsrc + ", jpegdec=" + jpegdec + 
                ", d3d11videosink=" + d3d11videosink);
            
            if (appsrc == null || jpegdec == null || d3d11videosink == null) {
                System.out.println("❌ GpuJpegRenderer: 创建GStreamer元素失败");
                LogTools.getInstance().logRecord4("❌ GpuJpegRenderer: 创建GStreamer元素失败");
                pipelineReady = false;
                return;
            }
            
            // 配置 appsrc
            System.out.println("🔧 配置 appsrc...");
            appsrc.setCaps(Caps.fromString("image/jpeg"));
            appsrc.set("is-live", true);
            appsrc.set("format", 3);  // GST_FORMAT_TIME
            appsrc.set("block", false);
            
            // 配置sink
            System.out.println("🔧 配置 d3d11videosink...");
            d3d11videosink.set("force-aspect-ratio", false);
            d3d11videosink.set("sync", false);
            
            // 绑定HWND
            System.out.println("🔧 绑定 HWND...");
            videoOverlay = VideoOverlay.wrap(d3d11videosink);
            if (videoOverlay != null) {
                videoOverlay.setWindowHandle(hwnd);
                System.out.println("✅ VideoOverlay 绑定成功");
            } else {
                System.out.println("⚠️ VideoOverlay.wrap 返回 null");
                LogTools.getInstance().logRecord4("⚠️ VideoOverlay.wrap 返回 null");
            }
            
            // 创建管道
            System.out.println("🔧 创建管道...");
            pipeline = new Pipeline("gpu-jpeg-pipeline");
            pipeline.addMany(appsrc, jpegdec, d3d11upload, d3d11convert, d3d11videosink);
            
            // 链接元素
            System.out.println("🔧 链接元素...");
            if (!Element.linkMany(appsrc, jpegdec, d3d11upload, d3d11convert, d3d11videosink)) {
                System.out.println("❌ GpuJpegRenderer: 链接元素失败");
                LogTools.getInstance().logRecord4("❌ GpuJpegRenderer: 链接元素失败");
                pipelineReady = false;
                return;
            }
            
            // 启动管道（只启动一次，后续只推送数据）
            System.out.println("🔧 启动管道...");
            pipeline.setState(State.PLAYING);
            
            pipelineReady = true;
            System.out.println("✅ GpuJpegRenderer 管道初始化成功!");
            LogTools.getInstance().logRecord4("✅ GpuJpegRenderer 初始化成功, HWND=" + hwnd);
            
        } catch (Exception e) {
            System.out.println("❌ GpuJpegRenderer 初始化异常: " + e.getMessage());
            LogTools.getInstance().logRecord4("❌ GpuJpegRenderer 初始化异常: " + e.getMessage());
            e.printStackTrace();
            pipelineReady = false;
        }
    }
    
    /**
     * 初始化GPU渲染管道（使用外部HWND）
     * @param hwnd Windows窗口句柄
     */
    public void init(long hwnd) {
        initPipeline(hwnd);
    }
    
    /**
     * 显示JPEG图片（GPU渲染，无闪屏）
     * @param filePath JPEG文件路径
     */
    public void displayImage(String filePath) {
        if (!pipelineReady || pipeline == null || appsrc == null) {
            return;
        }
        
        if (filePath == null || filePath.isEmpty()) {
            return;
        }
        
        File file = new File(filePath);
        if (!file.exists()) {
            return;
        }
        
        try {
            // 读取JPEG文件数据
            byte[] jpegData = Files.readAllBytes(file.toPath());
            
            // 创建 GStreamer Buffer
            Buffer buffer = new Buffer(jpegData.length);
            ByteBuffer bb = buffer.map(true);
            bb.put(jpegData);
            buffer.unmap();
            
            // 推送到 appsrc（不重启管道，无闪屏）
            appsrc.pushBuffer(buffer);
            
            lastPath = filePath;
            
        } catch (Exception e) {
            System.out.println("❌ displayImage 异常: " + e.getMessage());
            LogTools.getInstance().logRecord4("❌ displayImage 异常: " + e.getMessage());
        }
    }
    
    /**
     * 强制刷新显示（即使是同一张图片）
     */
    public void forceDisplay(String filePath) {
        lastPath = null;
        displayImage(filePath);
    }
    
    /**
     * 更新HWND（窗口大小变化时调用）
     */
    public void updateHwnd(long newHwnd) {
        this.hwnd = newHwnd;
        if (videoOverlay != null) {
            try {
                videoOverlay.setWindowHandle(newHwnd);
            } catch (Exception e) {
                LogTools.getInstance().logRecord4("⚠️ 更新HWND失败: " + e.getMessage());
            }
        }
    }
    
    /**
     * 暴露VideoOverlay接口（用于窗口位置调整）
     */
    public void expose() {
        if (videoOverlay != null) {
            try {
                videoOverlay.expose();
            } catch (Exception ignored) {}
        }
    }
    
    /**
     * 停止渲染
     */
    public void stop() {
        if (pipeline != null) {
            try {
                pipeline.setState(State.NULL);
            } catch (Exception ignored) {}
        }
        lastPath = null;
    }
    
    /**
     * 释放资源
     */
    public void dispose() {
        stop();
        
        if (pipeline != null) {
            try {
                pipeline.setState(State.NULL);
                pipeline.dispose();
            } catch (Exception ignored) {}
            pipeline = null;
        }
        
        initialized = false;
        LogTools.getInstance().logRecord4("🗑️ GpuJpegRenderer 已释放");
    }
    
    public boolean isInitialized() {
        return pipelineReady;
    }
    
    public boolean isWindowCreated() {
        return initialized;
    }
    
    public long getHwnd() {
        return hwnd;
    }
}

