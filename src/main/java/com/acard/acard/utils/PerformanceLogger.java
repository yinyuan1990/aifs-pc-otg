package com.acard.acard.utils;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 性能诊断日志工具
 * - 慢放开启链路追踪（CPU + 内存）
 * - 抓拍链路追踪（CPU + 内存）
 */
public class PerformanceLogger {
    
    // ✅ 生产环境关闭文件日志（只保留控制台输出）
    private static final boolean ENABLE_FILE_LOGGING = false;
    
    private static final Path LOG_DIR = Paths.get("runtime/logs");
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    
    private final String logType;  // "slowmo" 或 "capture"
    private final BufferedWriter writer;
    private final long startTime;
    private final Runtime runtime;
    
    // CPU监控（通过定时采样估算）
    private long lastCpuCheckTime = 0;
    private int sampleCount = 0;
    
    /**
     * 创建性能日志
     * @param logType "slowmo" 或 "capture"
     */
    public PerformanceLogger(String logType) throws IOException {
        this.logType = logType;
        this.startTime = System.currentTimeMillis();
        this.runtime = Runtime.getRuntime();
        
        // ✅ 生产环境不创建文件
        if (ENABLE_FILE_LOGGING) {
            // 确保日志目录存在
            Files.createDirectories(LOG_DIR);
            
            // 创建日志文件：runtime/logs/slowmo_20250120_143025.txt
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String fileName = logType + "_" + timestamp + ".txt";
            File logFile = LOG_DIR.resolve(fileName).toFile();
            
            this.writer = new BufferedWriter(new FileWriter(logFile, true));
        } else {
            this.writer = null;  // 生产环境不写文件
        }
        
        // 写入头部
        log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log("🔍 [" + logType.toUpperCase() + "] 性能诊断日志");
        log("   启动时间: " + LocalDateTime.now().format(FORMATTER));
        log("   JVM最大内存: " + (runtime.maxMemory() / 1024 / 1024) + "MB");
        log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        flush();
    }
    
    /**
     * 记录检查点（自动记录内存、耗时）
     */
    public void checkpoint(String stage) {
        try {
            long elapsed = System.currentTimeMillis() - startTime;
            long totalMB = runtime.totalMemory() / 1024 / 1024;
            long freeMB = runtime.freeMemory() / 1024 / 1024;
            long usedMB = totalMB - freeMB;
            long maxMB = runtime.maxMemory() / 1024 / 1024;
            double usagePercent = (usedMB * 100.0 / maxMB);
            
            log("");
            log("📍 [" + stage + "] (+" + elapsed + "ms)");
            log("   内存: " + usedMB + "MB / " + maxMB + "MB (" + String.format("%.1f%%", usagePercent) + ")");
            log("   JVM堆: total=" + totalMB + "MB, free=" + freeMB + "MB");
            
            // 内存警告
            if (usagePercent > 80) {
                log("   ⚠️ 内存使用超过80%！");
            }
            
            flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    /**
     * 记录详细信息
     */
    public void logDetail(String key, Object value) {
        try {
            log("   " + key + ": " + value);
            flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    /**
     * 记录TurboJPEG解码性能
     */
    public void logTurboJPEGDecode(String fileName, long decodeTimeMs, int width, int height, 
                                   long rgbSizeMB, long pixelsSizeMB, long memBefore, long memAfter) {
        try {
            log("");
            log("🖼️ [TurboJPEG解码] " + fileName);
            log("   耗时: " + decodeTimeMs + "ms");
            log("   分辨率: " + width + "x" + height);
            log("   临时数组: RGB=" + rgbSizeMB + "MB + pixels=" + pixelsSizeMB + "MB");
            log("   内存变化: " + memBefore + "MB → " + memAfter + "MB (Δ" + (memAfter - memBefore) + "MB)");
            flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    /**
     * 记录Image加载
     */
    public void logImageLoad(int index, int cacheSize, int width, int height) {
        try {
            log("");
            log("🖼️ [Image加载] index=" + index);
            log("   尺寸: " + width + "x" + height + " (" + (width * height * 4 / 1024 / 1024) + "MB)");
            log("   缓存: " + cacheSize + " 张");
            flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    /**
     * 记录帧缓存状态
     */
    public void logFrameCacheStatus(int cacheSize, int preloadQueueSize) {
        try {
            log("");
            log("💾 [帧缓存状态]");
            log("   frameCache.size: " + cacheSize + " 张");
            log("   preloadQueue.size: " + preloadQueueSize + " 个任务");
            flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    /**
     * 记录错误
     */
    public void logError(String stage, Throwable e) {
        try {
            log("");
            log("❌ [错误] " + stage);
            log("   " + e.getClass().getSimpleName() + ": " + e.getMessage());
            if (e.getStackTrace().length > 0) {
                log("   at " + e.getStackTrace()[0]);
            }
            flush();
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }
    
    /**
     * 记录完成（关闭日志）
     */
    public void complete(String summary) {
        try {
            long totalTime = System.currentTimeMillis() - startTime;
            log("");
            log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            log("✅ [完成] " + summary);
            log("   总耗时: " + totalTime + "ms");
            log("   结束时间: " + LocalDateTime.now().format(FORMATTER));
            
            // 最终内存状态
            long usedMB = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024;
            long maxMB = runtime.maxMemory() / 1024 / 1024;
            log("   最终内存: " + usedMB + "MB / " + maxMB + "MB");
            log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            
            close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    /**
     * 写入日志
     */
    private void log(String message) throws IOException {
        if (writer == null) return;  // ✅ 生产环境不写文件
        String timestamp = LocalDateTime.now().format(FORMATTER);
        writer.write("[" + timestamp + "] " + message);
        writer.newLine();
    }
    
    /**
     * 刷新缓冲
     */
    private void flush() throws IOException {
        if (writer == null) return;  // ✅ 生产环境不写文件
        writer.flush();
    }
    
    /**
     * 关闭日志
     */
    public void close() {
        if (writer == null) return;  // ✅ 生产环境不写文件
        try {
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

