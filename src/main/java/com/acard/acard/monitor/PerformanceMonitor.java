package com.acard.acard.monitor;

import javafx.application.Platform;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.StringProperty;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * 性能监控器
 * 监控视频流的性能指标，包括FPS、比特率、延迟等
 */
public class PerformanceMonitor {
    
    // JavaFX属性，用于UI绑定
    private final DoubleProperty fpsProperty = new SimpleDoubleProperty(0.0);
    private final DoubleProperty bitrateProperty = new SimpleDoubleProperty(0.0);
    private final IntegerProperty latencyProperty = new SimpleIntegerProperty(0);
    private final StringProperty statusProperty = new SimpleStringProperty("未连接");
    private final IntegerProperty droppedFramesProperty = new SimpleIntegerProperty(0);
    private final DoubleProperty cpuUsageProperty = new SimpleDoubleProperty(0.0);
    private final DoubleProperty memoryUsageProperty = new SimpleDoubleProperty(0.0);
    
    // 统计数据
    private final AtomicLong frameCount = new AtomicLong(0);
    private final AtomicLong byteCount = new AtomicLong(0);
    private final AtomicLong droppedFrames = new AtomicLong(0);
    private final AtomicLong lastFrameTime = new AtomicLong(0);
    private final AtomicLong connectionStartTime = new AtomicLong(0);
    
    // 性能计算
    private long lastStatsTime = 0;
    private long lastFrameCountForFps = 0;
    private long lastByteCountForBitrate = 0;
    
    // 历史数据存储
    private final Map<String, PerformanceHistory> historyData = new ConcurrentHashMap<>();
    
    // 定时器
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private boolean monitoring = false;
    
    // 回调接口
    public interface PerformanceCallback {
        void onPerformanceUpdate(PerformanceStats stats);
        void onPerformanceAlert(String alertType, String message);
    }
    
    private PerformanceCallback callback;
    
    public PerformanceMonitor() {
        System.err.println("📊 初始化性能监控器");
        initializeHistoryTracking();
    }
    
    public void setCallback(PerformanceCallback callback) {
        this.callback = callback;
    }
    
    /**
     * 开始监控
     */
    public void startMonitoring() {
        if (monitoring) {
            return;
        }
        
        monitoring = true;
        connectionStartTime.set(System.currentTimeMillis());
        lastStatsTime = System.currentTimeMillis();
        
        // 启动性能统计定时器（每秒更新一次）
        scheduler.scheduleAtFixedRate(this::updatePerformanceStats, 1, 1, TimeUnit.SECONDS);
        
        // 启动系统资源监控定时器（每5秒更新一次）
        scheduler.scheduleAtFixedRate(this::updateSystemStats, 5, 5, TimeUnit.SECONDS);
        
        Platform.runLater(() -> statusProperty.set("监控中"));
        System.err.println("📊 性能监控已启动");
    }
    
    /**
     * 停止监控
     */
    public void stopMonitoring() {
        if (!monitoring) {
            return;
        }
        
        monitoring = false;
        
        Platform.runLater(() -> {
            statusProperty.set("已停止");
            fpsProperty.set(0.0);
            bitrateProperty.set(0.0);
            latencyProperty.set(0);
        });
        
        System.err.println("📊 性能监控已停止");
    }
    
    /**
     * 记录新帧
     */
    public void recordFrame() {
        frameCount.incrementAndGet();
        lastFrameTime.set(System.currentTimeMillis());
    }
    
    /**
     * 记录数据传输
     */
    public void recordDataTransfer(long bytes) {
        byteCount.addAndGet(bytes);
    }
    
    /**
     * 记录丢帧
     */
    public void recordDroppedFrame() {
        long dropped = droppedFrames.incrementAndGet();
        Platform.runLater(() -> droppedFramesProperty.set((int) dropped));
        
        // 丢帧警告
        if (dropped % 10 == 0 && callback != null) {
            callback.onPerformanceAlert("DROPPED_FRAMES", "已丢帧 " + dropped + " 帧");
        }
    }
    
    /**
     * 更新性能统计
     */
    private void updatePerformanceStats() {
        try {
            long currentTime = System.currentTimeMillis();
            long timeDiff = currentTime - lastStatsTime;
            
            if (timeDiff < 1000) {
                return; // 时间间隔太短，跳过
            }
            
            // 计算FPS
            long currentFrameCount = frameCount.get();
            long frameDiff = currentFrameCount - lastFrameCountForFps;
            double fps = (double) frameDiff / (timeDiff / 1000.0);
            
            // 计算比特率 (bps)
            long currentByteCount = byteCount.get();
            long byteDiff = currentByteCount - lastByteCountForBitrate;
            double bitrate = (double) byteDiff * 8 / (timeDiff / 1000.0); // 转换为bits per second
            
            // 计算延迟
            long lastFrame = lastFrameTime.get();
            int latency = lastFrame > 0 ? (int) (currentTime - lastFrame) : 0;
            
            // 更新UI属性
            Platform.runLater(() -> {
                fpsProperty.set(fps);
                bitrateProperty.set(bitrate);
                latencyProperty.set(latency);
            });
            
            // 记录历史数据
            recordHistoryData("fps", fps);
            recordHistoryData("bitrate", bitrate);
            recordHistoryData("latency", latency);
            
            // 更新统计基准
            lastStatsTime = currentTime;
            lastFrameCountForFps = currentFrameCount;
            lastByteCountForBitrate = currentByteCount;
            
            // 性能警告检查
            checkPerformanceAlerts(fps, bitrate, latency);
            
            // 回调通知
            if (callback != null) {
                PerformanceStats stats = new PerformanceStats(
                    fps, bitrate, latency, (int) droppedFrames.get(),
                    currentFrameCount, currentByteCount
                );
                callback.onPerformanceUpdate(stats);
            }
            
            // 调试输出
            if (fps > 0 || bitrate > 0) {
                System.err.println(String.format(
                    "📊 性能: FPS=%.1f, 比特率=%.1fkbps, 延迟=%dms, 丢帧=%d", 
                    fps, bitrate / 1000, latency, droppedFrames.get()
                ));
            }
            
        } catch (Exception e) {
            System.err.println("❌ 更新性能统计失败: " + e.getMessage());
        }
    }
    
    /**
     * 更新系统资源统计
     */
    private void updateSystemStats() {
        try {
            // 获取内存使用情况
            Runtime runtime = Runtime.getRuntime();
            long totalMemory = runtime.totalMemory();
            long freeMemory = runtime.freeMemory();
            long usedMemory = totalMemory - freeMemory;
            double memoryUsage = (double) usedMemory / totalMemory * 100;
            
            // 获取CPU使用情况（简化版本）
            double cpuUsage = getCpuUsage();
            
            Platform.runLater(() -> {
                memoryUsageProperty.set(memoryUsage);
                cpuUsageProperty.set(cpuUsage);
            });
            
            // 记录历史数据
            recordHistoryData("memory", memoryUsage);
            recordHistoryData("cpu", cpuUsage);
            
            // 资源警告检查
            if (memoryUsage > 80) {
                if (callback != null) {
                    callback.onPerformanceAlert("HIGH_MEMORY", "内存使用率过高: " + String.format("%.1f%%", memoryUsage));
                }
            }
            
            if (cpuUsage > 80) {
                if (callback != null) {
                    callback.onPerformanceAlert("HIGH_CPU", "CPU使用率过高: " + String.format("%.1f%%", cpuUsage));
                }
            }
            
        } catch (Exception e) {
            System.err.println("❌ 更新系统统计失败: " + e.getMessage());
        }
    }
    
    /**
     * 获取CPU使用率（简化实现）
     */
    private double getCpuUsage() {
        try {
            com.sun.management.OperatingSystemMXBean osBean = 
                (com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            return osBean.getProcessCpuLoad() * 100;
        } catch (Exception e) {
            return 0.0;
        }
    }
    
    /**
     * 性能警告检查
     */
    private void checkPerformanceAlerts(double fps, double bitrate, int latency) {
        try {
            // FPS过低警告
            if (fps > 0 && fps < 15) {
                if (callback != null) {
                    callback.onPerformanceAlert("LOW_FPS", "帧率过低: " + String.format("%.1f FPS", fps));
                }
            }
            
            // 延迟过高警告
            if (latency > 1000) {
                if (callback != null) {
                    callback.onPerformanceAlert("HIGH_LATENCY", "延迟过高: " + latency + "ms");
                }
            }
            
            // 比特率异常警告
            if (bitrate > 0 && bitrate < 100000) { // 低于100kbps
                if (callback != null) {
                    callback.onPerformanceAlert("LOW_BITRATE", "比特率过低: " + String.format("%.1fkbps", bitrate / 1000));
                }
            }
            
        } catch (Exception e) {
            System.err.println("❌ 性能警告检查失败: " + e.getMessage());
        }
    }
    
    /**
     * 初始化历史数据跟踪
     */
    private void initializeHistoryTracking() {
        historyData.put("fps", new PerformanceHistory("FPS", 60)); // 保存60个数据点
        historyData.put("bitrate", new PerformanceHistory("比特率", 60));
        historyData.put("latency", new PerformanceHistory("延迟", 60));
        historyData.put("memory", new PerformanceHistory("内存使用率", 12)); // 保存12个数据点（1小时）
        historyData.put("cpu", new PerformanceHistory("CPU使用率", 12));
    }
    
    /**
     * 记录历史数据
     */
    private void recordHistoryData(String metric, double value) {
        PerformanceHistory history = historyData.get(metric);
        if (history != null) {
            history.addValue(value);
        }
    }
    
    /**
     * 获取历史数据
     */
    public PerformanceHistory getHistoryData(String metric) {
        return historyData.get(metric);
    }
    
    /**
     * 获取当前性能统计
     */
    public PerformanceStats getCurrentStats() {
        return new PerformanceStats(
            fpsProperty.get(),
            bitrateProperty.get(),
            latencyProperty.get(),
            droppedFramesProperty.get(),
            frameCount.get(),
            byteCount.get()
        );
    }
    
    /**
     * 重置统计数据
     */
    public void resetStats() {
        frameCount.set(0);
        byteCount.set(0);
        droppedFrames.set(0);
        lastFrameTime.set(0);
        connectionStartTime.set(System.currentTimeMillis());
        
        Platform.runLater(() -> {
            fpsProperty.set(0.0);
            bitrateProperty.set(0.0);
            latencyProperty.set(0);
            droppedFramesProperty.set(0);
        });
        
        // 清除历史数据
        historyData.values().forEach(PerformanceHistory::clear);
        
        System.err.println("📊 性能统计已重置");
    }
    
    /**
     * 获取运行时间
     */
    public long getUptime() {
        long startTime = connectionStartTime.get();
        return startTime > 0 ? System.currentTimeMillis() - startTime : 0;
    }
    
    /**
     * 打印性能报告
     */
    public void printPerformanceReport() {
        System.err.println("📊 ===== 性能报告 =====");
        System.err.println("运行时间: " + formatDuration(getUptime()));
        System.err.println("当前FPS: " + String.format("%.1f", fpsProperty.get()));
        System.err.println("当前比特率: " + String.format("%.1f kbps", bitrateProperty.get() / 1000));
        System.err.println("当前延迟: " + latencyProperty.get() + "ms");
        System.err.println("总帧数: " + frameCount.get());
        System.err.println("丢帧数: " + droppedFrames.get());
        System.err.println("总数据量: " + formatBytes(byteCount.get()));
        System.err.println("内存使用: " + String.format("%.1f%%", memoryUsageProperty.get()));
        System.err.println("CPU使用: " + String.format("%.1f%%", cpuUsageProperty.get()));
        System.err.println("📊 ==================");
    }
    
    /**
     * 格式化持续时间
     */
    private String formatDuration(long millis) {
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        
        if (hours > 0) {
            return String.format("%d小时%d分钟", hours, minutes % 60);
        } else if (minutes > 0) {
            return String.format("%d分钟%d秒", minutes, seconds % 60);
        } else {
            return String.format("%d秒", seconds);
        }
    }
    
    /**
     * 格式化字节数
     */
    private String formatBytes(long bytes) {
        if (bytes >= 1024 * 1024 * 1024) {
            return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        } else if (bytes >= 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        } else if (bytes >= 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        } else {
            return bytes + " B";
        }
    }
    
    // JavaFX属性的getter方法
    public DoubleProperty fpsProperty() { return fpsProperty; }
    public DoubleProperty bitrateProperty() { return bitrateProperty; }
    public IntegerProperty latencyProperty() { return latencyProperty; }
    public StringProperty statusProperty() { return statusProperty; }
    public IntegerProperty droppedFramesProperty() { return droppedFramesProperty; }
    public DoubleProperty cpuUsageProperty() { return cpuUsageProperty; }
    public DoubleProperty memoryUsageProperty() { return memoryUsageProperty; }
    
    /**
     * 关闭性能监控器
     */
    public void shutdown() {
        stopMonitoring();
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
        }
        System.err.println("📊 性能监控器已关闭");
    }
    
    /**
     * 性能统计数据类
     */
    public static class PerformanceStats {
        public final double fps;
        public final double bitrate;
        public final int latency;
        public final int droppedFrames;
        public final long totalFrames;
        public final long totalBytes;
        
        public PerformanceStats(double fps, double bitrate, int latency, int droppedFrames, 
                               long totalFrames, long totalBytes) {
            this.fps = fps;
            this.bitrate = bitrate;
            this.latency = latency;
            this.droppedFrames = droppedFrames;
            this.totalFrames = totalFrames;
            this.totalBytes = totalBytes;
        }
    }
    
    /**
     * 性能历史数据类
     */
    public static class PerformanceHistory {
        private final String name;
        private final int maxSize;
        private final java.util.List<Double> values = new java.util.ArrayList<>();
        private final java.util.List<Long> timestamps = new java.util.ArrayList<>();
        
        public PerformanceHistory(String name, int maxSize) {
            this.name = name;
            this.maxSize = maxSize;
        }
        
        public synchronized void addValue(double value) {
            values.add(value);
            timestamps.add(System.currentTimeMillis());
            
            // 保持最大大小
            while (values.size() > maxSize) {
                values.remove(0);
                timestamps.remove(0);
            }
        }
        
        public synchronized java.util.List<Double> getValues() {
            return new java.util.ArrayList<>(values);
        }
        
        public synchronized java.util.List<Long> getTimestamps() {
            return new java.util.ArrayList<>(timestamps);
        }
        
        public synchronized void clear() {
            values.clear();
            timestamps.clear();
        }
        
        public String getName() { return name; }
        public int getSize() { return values.size(); }
        
        public synchronized double getAverage() {
            return values.isEmpty() ? 0.0 : values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        }
        
        public synchronized double getMax() {
            return values.isEmpty() ? 0.0 : values.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        }
        
        public synchronized double getMin() {
            return values.isEmpty() ? 0.0 : values.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
        }
    }
}