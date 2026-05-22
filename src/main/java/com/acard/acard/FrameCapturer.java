package com.acard.acard;

import org.freedesktop.gstreamer.*;
import org.freedesktop.gstreamer.elements.AppSink;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 视频帧捕获器 - 用于从WebRTC流中捕获并保存视频帧
 */
public class FrameCapturer {
    private final String outputDir;
    private final AtomicInteger frameCount = new AtomicInteger(0);
    private final long captureStartTime;
    private final long captureDurationMs;
    private volatile boolean isCapturing = false;
    
    public FrameCapturer(String outputDir, long captureDurationMs) {
        this.outputDir = outputDir;
        this.captureDurationMs = captureDurationMs;
        this.captureStartTime = System.currentTimeMillis();
        
        // 确保输出目录存在
        File dir = new File(outputDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }
    
    /**
     * 创建用于帧捕获的AppSink
     */
    public AppSink createFrameCaptureSink() {
        AppSink captureSink = (AppSink) ElementFactory.make("appsink", "frame-capture-sink");
        captureSink.setCaps(Caps.fromString("video/x-raw,format=BGRA,colorimetry=bt709,color-range=full"));
        captureSink.set("emit-signals", true);
        captureSink.set("max-buffers", 1);
        captureSink.set("drop", true);
        captureSink.set("qos", false); // 禁用QoS以确保捕获所有帧
        
        captureSink.connect((AppSink.NEW_SAMPLE) appSink -> {
            if (!isCapturing) {
                return FlowReturn.OK;
            }
            
            // 检查是否超过捕获时间
            long elapsed = System.currentTimeMillis() - captureStartTime;
            if (elapsed > captureDurationMs) {
                stopCapture();
                return FlowReturn.OK;
            }
            
            Sample sample = appSink.pullSample();
            if (sample != null) {
                try {
                    saveFrame(sample);
                } catch (Exception e) {
                    System.err.println("保存帧失败: " + e.getMessage());
                    e.printStackTrace();
                } finally {
                    sample.dispose();
                }
            }
            return FlowReturn.OK;
        });
        
        return captureSink;
    }
    
    /**
     * 开始捕获帧
     */
    public void startCapture() {
        isCapturing = true;
        frameCount.set(0);
        System.out.println("开始捕获视频帧，持续时间: " + captureDurationMs + "ms");
        System.out.println("输出目录: " + outputDir);
    }
    
    /**
     * 停止捕获帧
     */
    public void stopCapture() {
        if (isCapturing) {
            isCapturing = false;
            System.out.println("帧捕获完成，总共保存了 " + frameCount.get() + " 帧");
        }
    }
    
    /**
     * 保存单个帧到文件
     */
    private void saveFrame(Sample sample) throws IOException {
        Buffer buffer = sample.getBuffer();
        Caps caps = sample.getCaps();
        
        // 安全校验，避免在 caps 为 null 或结构不可用时触发原生断言
        if (caps == null) {
            System.err.println("CAPTURE: ⚠️ sample.caps 为 null，跳过该帧");
            return;
        }
        Structure struct = null;
        try {
            struct = caps.getStructure(0);
        } catch (Throwable t) {
            System.err.println("CAPTURE: ⚠️ 获取 caps 结构失败: " + t.getMessage());
        }
        if (struct == null) {
            System.err.println("CAPTURE: ⚠️ caps 结构为空，跳过该帧");
            return;
        }
        int width = struct.getInteger("width");
        int height = struct.getInteger("height");
        
        // 读取缓冲区数据
        ByteBuffer byteBuffer = buffer != null ? buffer.map(false) : null;
        if (byteBuffer == null) {
            return;
        }
        
        try {
            byte[] data = new byte[byteBuffer.remaining()];
            byteBuffer.get(data);

            // 计算实际每行字节数（row stride），避免在某些分辨率下由于行对齐产生马赛克
            int totalBytes = data.length;
            int computedStride = (height > 0) ? (totalBytes / height) : (width * 4);
            if (computedStride < width * 4 || (computedStride % 4) != 0) {
                computedStride = width * 4;
            }
            final int rowStride = computedStride;
            final int effectiveW = Math.min(width, rowStride / 4);
            if (effectiveW != width) {
                System.err.println("CAPTURE: ⚠️ width mismatch, capsW=" + width + ", effW=" + effectiveW + ", rowStride=" + rowStride + ", totalBytes=" + totalBytes);
            }

            // 创建BufferedImage (按有效宽度)
            BufferedImage image = new BufferedImage(effectiveW, height, BufferedImage.TYPE_INT_RGB);

            // 转换BGRx到RGB，按实际行步长读取
            for (int y = 0; y < height; y++) {
                int row = y * rowStride;
                for (int x = 0; x < effectiveW; x++) {
                    int offset = row + x * 4; // BGRx每像素4字节
                    if (offset + 2 < data.length) {
                        int b = data[offset] & 0xFF;
                        int g = data[offset + 1] & 0xFF;
                        int r = data[offset + 2] & 0xFF;
                        int rgb = (r << 16) | (g << 8) | b;
                        image.setRGB(x, y, rgb);
                    }
                }
            }
            
            // 生成文件名
            int currentFrame = frameCount.incrementAndGet();
            long timestamp = System.currentTimeMillis() - captureStartTime;
            String filename = String.format("frame_%04d_%06dms.png", currentFrame, timestamp);
            File outputFile = new File(outputDir, filename);
            
            // 保存图片
            ImageIO.write(image, "PNG", outputFile);
            
            if (currentFrame % 10 == 0) { // 每10帧打印一次进度
                System.out.println("已保存第 " + currentFrame + " 帧: " + filename);
            }
            
        } finally {
            buffer.unmap();
        }
    }
    
    /**
     * 获取当前捕获的帧数
     */
    public int getFrameCount() {
        return frameCount.get();
    }
    
    /**
     * 检查是否正在捕获
     */
    public boolean isCapturing() {
        return isCapturing;
    }
    
    /**
     * 创建带时间戳的输出目录
     */
    public static String createTimestampedOutputDir(String baseDir) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        return baseDir + File.separator + "capture_" + timestamp;
    }
}