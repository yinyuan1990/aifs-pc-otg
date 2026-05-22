package com.acard.acard.utils;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 抓拍日志类 - 记录抓拍全过程
 * 用于诊断前置帧和后续帧的收集情况
 */
public class CaptureLogger {
    
    // ✅ 生产环境关闭文件日志（只保留控制台输出）
    private static final boolean ENABLE_FILE_LOGGING = false;
    
    private static final Path LOG_DIR = Paths.get("runtime/logs");
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    
    private final String sessionId;
    private Path logFile;  // 移除 final，允许在 catch 中赋值
    private BufferedWriter writer;
    
    /**
     * 创建抓拍日志
     * 
     * @param sessionId 会话ID（例如：时时流-001）
     */
    public CaptureLogger(String sessionId) {
        this.sessionId = sessionId;
        
        // ✅ 生产环境不创建文件
        if (!ENABLE_FILE_LOGGING) {
            this.writer = null;
            return;
        }
        
        try {
            // 创建日志目录
            Files.createDirectories(LOG_DIR);
            
            // 日志文件名：capture_时时流-001_20250120_143022.log
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String fileName = String.format("capture_%s_%s.log", sessionId, timestamp);
            this.logFile = LOG_DIR.resolve(fileName);
            
            // 创建writer
            this.writer = new BufferedWriter(new FileWriter(logFile.toFile(), true));
            
            log("============================================");
            log("抓拍日志开始");
            log("会话ID: " + sessionId);
            log("============================================");
            
        } catch (IOException e) {
            System.err.println("⚠️ 创建抓拍日志失败: " + e.getMessage());
            this.logFile = null;
        }
    }
    
    /**
     * 记录日志
     */
    public void log(String message) {
        String timestamp = LocalDateTime.now().format(FORMATTER);
        String logLine = String.format("[%s] %s", timestamp, message);
        
        // 打印到控制台
        System.out.println(logLine);
        
        // 写入文件
        if (writer != null) {
            try {
                writer.write(logLine);
                writer.newLine();
                writer.flush();  // 立即刷新
            } catch (IOException e) {
                System.err.println("⚠️ 写入日志失败: " + e.getMessage());
            }
        }
    }
    
    /**
     * 记录抓拍开始
     */
    public void logCaptureStart(int preCount, int postCount) {
        log("============================================");
        log("📸 抓拍开始");
        log("   请求前置帧数: " + preCount);
        log("   请求后续帧数: " + postCount);
        log("   预期总帧数: " + (preCount + postCount));
        log("============================================");
    }
    
    /**
     * 记录前置帧收集
     */
    public void logPreFrames(int requested, int actual) {
        log("📊 前置帧收集:");
        log("   请求数: " + requested);
        log("   实际数: " + actual);
        if (actual < requested) {
            log("   ⚠️ 警告: 少了 " + (requested - actual) + " 帧！");
        } else {
            log("   ✅ 前置帧收集完整");
        }
    }
    
    /**
     * 记录事件注册
     */
    public void logEventRegistered(long eventFrameId, int postCount) {
        log("📌 事件注册:");
        log("   事件帧ID: " + eventFrameId);
        log("   需要后续帧数: " + postCount);
        log("   等待后续帧到来...");
    }
    
    /**
     * 记录后续帧收集
     */
    public void logPostFrame(int index, int total, long frameId) {
        log(String.format("✅ 后续帧 [%d/%d]: frameId=%d", index, total, frameId));
    }
    
    /**
     * 记录抓拍完成
     */
    public void logCaptureComplete(int totalFrames, long durationMs) {
        log("============================================");
        log("🎉 抓拍完成");
        log("   总帧数: " + totalFrames);
        log("   耗时: " + durationMs + "ms");
        log("============================================");
    }
    
    /**
     * 记录错误
     */
    public void logError(String error, Throwable e) {
        log("❌ 错误: " + error);
        if (e != null) {
            log("   异常: " + e.getClass().getName() + ": " + e.getMessage());
            for (StackTraceElement element : e.getStackTrace()) {
                log("      at " + element.toString());
            }
        }
    }
    
    /**
     * 关闭日志
     */
    public void close() {
        if (writer != null) {
            try {
                log("============================================");
                log("抓拍日志结束");
                log("日志文件: " + logFile.toAbsolutePath());
                log("============================================");
                writer.close();
            } catch (IOException e) {
                System.err.println("⚠️ 关闭日志失败: " + e.getMessage());
            }
        }
    }
    
    /**
     * 获取日志文件路径
     */
    public Path getLogFile() {
        return logFile;
    }
}

