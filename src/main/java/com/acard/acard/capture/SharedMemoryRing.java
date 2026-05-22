package com.acard.acard.capture;

import com.acard.acard.tools.LogTools;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * ⚡ 共享内存环形缓冲（用于实时流抓拍）
 * 
 * 特点：
 * - 使用内存映射文件（Memory-Mapped File）实现共享内存
 * - GStreamer appsink 回调只做内存拷贝（快），不做格式转换
 * - 抓拍时才读取并转换（按需处理）
 * - 环形缓冲，自动覆盖旧帧
 * 
 * 内存布局：
 * [Header 4KB] [Frame0] [Frame1] ... [Frame59]
 * 
 * Header:
 * - offset 0: writeIndex (4 bytes) - 下一个写入位置
 * - offset 4: frameCount (4 bytes) - 当前帧数
 * - offset 8: frameWidth (4 bytes)
 * - offset 12: frameHeight (4 bytes)
 * - offset 16: frameFormat (4 bytes) - 0=NV12, 1=BGRA
 * - offset 20: totalPushed (8 bytes) - 总推送帧数
 */
public class SharedMemoryRing {
    
    // ⚡ 配置
    private static final int DEFAULT_CAPACITY = 60;  // 默认容量：前30+后30
    private static final int HEADER_SIZE = 4096;     // 头部大小（4KB 对齐）
    private static final int MAX_FRAME_WIDTH = 1920;
    private static final int MAX_FRAME_HEIGHT = 1440;
    private static final int FRAME_SLOT_PADDING = 4096;  // 每帧槽位额外预留（对齐+元数据）
    
    // ⚡ 帧格式
    public static final int FORMAT_NV12 = 0;
    public static final int FORMAT_BGRA = 1;
    
    // ⚡ 单例
    private static volatile SharedMemoryRing instance;
    
    // ⚡ 内存映射
    private final int capacity;
    private final int maxFrameSize;  // 单帧最大字节数（NV12: w*h*1.5, BGRA: w*h*4）
    private final long totalSize;    // 总内存大小
    
    private RandomAccessFile raf;
    private FileChannel channel;
    private MappedByteBuffer buffer;
    private File tempFile;
    
    // ⚡ 状态
    private final ReentrantLock writeLock = new ReentrantLock();
    private final AtomicInteger writeIndex = new AtomicInteger(0);
    private final AtomicInteger frameCount = new AtomicInteger(0);
    private final AtomicLong totalPushed = new AtomicLong(0);
    
    private volatile int currentWidth = 0;
    private volatile int currentHeight = 0;
    private volatile int currentFormat = FORMAT_NV12;
    
    private volatile boolean initialized = false;
    
    public static SharedMemoryRing getInstance() {
        if (instance == null) {
            synchronized (SharedMemoryRing.class) {
                if (instance == null) {
                    instance = new SharedMemoryRing(DEFAULT_CAPACITY);
                }
            }
        }
        return instance;
    }
    
    public SharedMemoryRing(int capacity) {
        this.capacity = capacity;
        // NV12 格式：w * h * 1.5 字节
        // BGRA 格式：w * h * 4 字节
        // 加上元数据和对齐空间
        this.maxFrameSize = MAX_FRAME_WIDTH * MAX_FRAME_HEIGHT * 4 + FRAME_SLOT_PADDING;
        this.totalSize = HEADER_SIZE + (long) capacity * maxFrameSize;
        
        initialize();
    }
    
    /**
     * ⚡ 初始化共享内存
     */
    private void initialize() {
        try {
            // 创建临时文件（实际上是内存映射，不会真正写磁盘）
            tempFile = File.createTempFile("acard_shm_ring_", ".tmp");
            tempFile.deleteOnExit();
            
            raf = new RandomAccessFile(tempFile, "rw");
            channel = raf.getChannel();
            
            // 映射整个文件到内存
            buffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, totalSize);
            
            // 初始化头部
            buffer.putInt(0, 0);  // writeIndex
            buffer.putInt(4, 0);  // frameCount
            buffer.putInt(8, 0);  // frameWidth
            buffer.putInt(12, 0); // frameHeight
            buffer.putInt(16, FORMAT_NV12); // frameFormat
            buffer.putLong(20, 0); // totalPushed
            
            initialized = true;
            LogTools.getInstance().logRecord5("✅ SharedMemoryRing 初始化成功: 容量=" + capacity + 
                "帧, 单帧最大=" + (maxFrameSize / 1024 / 1024) + "MB, 总内存=" + (totalSize / 1024 / 1024) + "MB");
            
        } catch (Exception e) {
            LogTools.getInstance().logRecord2("❌ SharedMemoryRing 初始化失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    // 帧元数据布局（每帧开头 32 字节）
    private static final int FRAME_META_SIZE = 32;
    // offset 0: frameSize (4 bytes)
    // offset 4: frameWidth (4 bytes)
    // offset 8: frameHeight (4 bytes)
    // offset 12: frameFormat (4 bytes)
    // offset 16-31: reserved
    
    /**
     * ⚡ 推送帧到共享内存（由 GStreamer appsink 回调调用）
     * 只做内存拷贝，不做格式转换
     * 
     * @param data 帧数据（NV12 或 BGRA）
     * @param width 宽度
     * @param height 高度
     * @param format 格式（FORMAT_NV12 或 FORMAT_BGRA）
     */
    public void push(ByteBuffer data, int width, int height, int format) {
        if (!initialized || data == null || !data.hasRemaining()) return;
        
        int dataSize = data.remaining();
        if (dataSize + FRAME_META_SIZE > maxFrameSize) {
            LogTools.getInstance().logRecord5("⚠️ 帧数据过大: " + dataSize + " > " + (maxFrameSize - FRAME_META_SIZE));
            return;
        }
        
        writeLock.lock();
        try {
            // 更新全局分辨率信息
            if (width != currentWidth || height != currentHeight || format != currentFormat) {
                currentWidth = width;
                currentHeight = height;
                currentFormat = format;
                buffer.putInt(8, width);
                buffer.putInt(12, height);
                buffer.putInt(16, format);
            }
            
            // 计算写入位置
            int idx = writeIndex.get();
            int frameOffset = HEADER_SIZE + idx * maxFrameSize;
            
            // 写入帧元数据
            buffer.putInt(frameOffset, dataSize);           // 帧数据大小
            buffer.putInt(frameOffset + 4, width);          // 帧宽度
            buffer.putInt(frameOffset + 8, height);         // 帧高度
            buffer.putInt(frameOffset + 12, format);        // 帧格式
            
            // 复制帧数据（保持原 ByteBuffer 的 position 不变）
            int srcPos = data.position();
            buffer.position(frameOffset + FRAME_META_SIZE);
            
            // 使用 slice 避免修改原 buffer
            ByteBuffer slice = data.slice();
            slice.limit(dataSize);
            buffer.put(slice);
            
            // 更新索引
            int newIdx = (idx + 1) % capacity;
            writeIndex.set(newIdx);
            buffer.putInt(0, newIdx);
            
            // 更新帧数
            int count = frameCount.get();
            if (count < capacity) {
                frameCount.incrementAndGet();
                buffer.putInt(4, count + 1);
            }
            
            // 更新总推送数
            long pushed = totalPushed.incrementAndGet();
            buffer.putLong(20, pushed);
            
        } finally {
            writeLock.unlock();
        }
    }
    
    /**
     * ⚡ 推送帧（byte[] 版本）
     */
    public void push(byte[] data, int width, int height, int format) {
        if (data == null) return;
        push(ByteBuffer.wrap(data), width, height, format);
    }
    
    /**
     * ⚡ 获取最近 N 帧（用于抓拍）
     * 在这里才做格式转换（按需）
     * 
     * @param count 需要的帧数
     * @return 帧列表（从旧到新）
     */
    public java.util.List<Image> getRecentFrames(int count) {
        java.util.List<Image> result = new java.util.ArrayList<>();
        
        if (!initialized) return result;
        
        int actualCount = Math.min(count, frameCount.get());
        if (actualCount == 0) return result;
        
        int width = buffer.getInt(8);
        int height = buffer.getInt(12);
        int format = buffer.getInt(16);
        
        if (width <= 0 || height <= 0) return result;
        
        // 计算起始索引
        int currentIdx = buffer.getInt(0);
        int startIdx = (currentIdx - actualCount + capacity) % capacity;
        
        for (int i = 0; i < actualCount; i++) {
            int idx = (startIdx + i) % capacity;
            Image img = readFrame(idx, width, height, format);
            if (img != null) {
                result.add(img);
            }
        }
        
        return result;
    }
    
    /**
     * ⚡ 获取当前帧（最新一帧）
     */
    public Image getCurrentFrame() {
        if (!initialized || frameCount.get() == 0) return null;
        
        int width = buffer.getInt(8);
        int height = buffer.getInt(12);
        int format = buffer.getInt(16);
        
        if (width <= 0 || height <= 0) return null;
        
        // 当前写入位置的前一个就是最新帧
        int currentIdx = buffer.getInt(0);
        int latestIdx = (currentIdx - 1 + capacity) % capacity;
        
        return readFrame(latestIdx, width, height, format);
    }
    
    /**
     * ⚡ 读取指定索引的帧并转换为 Image
     */
    private Image readFrame(int idx, int width, int height, int format) {
        try {
            int frameOffset = HEADER_SIZE + idx * maxFrameSize;
            
            // 读取帧元数据
            int dataSize = buffer.getInt(frameOffset);
            int frameWidth = buffer.getInt(frameOffset + 4);
            int frameHeight = buffer.getInt(frameOffset + 8);
            int frameFormat = buffer.getInt(frameOffset + 12);
            
            // 使用帧自身的元数据（更准确）
            if (frameWidth > 0 && frameHeight > 0) {
                width = frameWidth;
                height = frameHeight;
                format = frameFormat;
            }
            
            if (dataSize <= 0 || dataSize > maxFrameSize - FRAME_META_SIZE) {
                return null;  // 无效数据
            }
            
            int dataOffset = frameOffset + FRAME_META_SIZE;
            
            if (format == FORMAT_BGRA) {
                // BGRA 格式：直接读取
                int expectedSize = width * height * 4;
                byte[] pixels = new byte[Math.min(dataSize, expectedSize)];
                
                buffer.position(dataOffset);
                buffer.get(pixels);
                
                WritableImage img = new WritableImage(width, height);
                img.getPixelWriter().setPixels(0, 0, width, height,
                    PixelFormat.getByteBgraInstance(), pixels, 0, width * 4);
                return img;
                
            } else if (format == FORMAT_NV12) {
                // NV12 格式：需要转换
                return readNV12Frame(dataOffset, width, height);
            }
            
        } catch (Exception e) {
            LogTools.getInstance().logRecord5("⚠️ 读取帧失败: " + e.getMessage());
        }
        
        return null;
    }
    
    /**
     * ⚡ 读取 NV12 帧并转换为 RGB Image
     */
    private Image readNV12Frame(int dataOffset, int width, int height) {
        try {
            int ySize = width * height;
            int uvSize = ySize / 2;
            
            byte[] yPlane = new byte[ySize];
            byte[] uvPlane = new byte[uvSize];
            
            buffer.position(dataOffset);
            buffer.get(yPlane);
            buffer.get(uvPlane);
            
            // NV12 to RGB 转换（优化版）
            int[] pixels = new int[width * height];
            for (int j = 0; j < height; j++) {
                int uvRowOffset = (j / 2) * width;
                for (int i = 0; i < width; i++) {
                    int yIndex = j * width + i;
                    int uvIndex = uvRowOffset + (i & ~1);  // (i / 2) * 2
                    
                    int y = (yPlane[yIndex] & 0xFF) - 16;
                    int u = (uvPlane[uvIndex] & 0xFF) - 128;
                    int v = (uvPlane[Math.min(uvIndex + 1, uvPlane.length - 1)] & 0xFF) - 128;
                    
                    // YUV to RGB (BT.601)
                    int r = Math.max(0, Math.min(255, (int)(1.164 * y + 1.596 * v)));
                    int g = Math.max(0, Math.min(255, (int)(1.164 * y - 0.392 * u - 0.813 * v)));
                    int b = Math.max(0, Math.min(255, (int)(1.164 * y + 2.017 * u)));
                    
                    pixels[yIndex] = 0xFF000000 | (r << 16) | (g << 8) | b;
                }
            }
            
            WritableImage img = new WritableImage(width, height);
            img.getPixelWriter().setPixels(0, 0, width, height,
                PixelFormat.getIntArgbInstance(), pixels, 0, width);
            return img;
            
        } catch (Exception e) {
            LogTools.getInstance().logRecord5("⚠️ NV12 转换失败: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * ⚡ 获取状态信息
     */
    public String getStatus() {
        if (!initialized) return "未初始化";
        return String.format("帧数=%d/%d, 分辨率=%dx%d, 格式=%s, 总推送=%d",
            frameCount.get(), capacity,
            currentWidth, currentHeight,
            currentFormat == FORMAT_BGRA ? "BGRA" : "NV12",
            totalPushed.get());
    }
    
    /**
     * ⚡ 获取帧数
     */
    public int getFrameCount() {
        return frameCount.get();
    }
    
    /**
     * ⚡ 获取总推送数
     */
    public long getTotalPushed() {
        return totalPushed.get();
    }
    
    /**
     * ⚡ 清空缓冲
     */
    public void clear() {
        writeLock.lock();
        try {
            writeIndex.set(0);
            frameCount.set(0);
            buffer.putInt(0, 0);
            buffer.putInt(4, 0);
            LogTools.getInstance().logRecord5("🗑️ SharedMemoryRing 已清空");
        } finally {
            writeLock.unlock();
        }
    }
    
    /**
     * ⚡ 释放资源
     */
    public void dispose() {
        try {
            if (channel != null) channel.close();
            if (raf != null) raf.close();
            if (tempFile != null) tempFile.delete();
            initialized = false;
            LogTools.getInstance().logRecord5("🗑️ SharedMemoryRing 资源已释放");
        } catch (Exception e) {
            LogTools.getInstance().logRecord2("⚠️ SharedMemoryRing 释放失败: " + e.getMessage());
        }
    }
}

