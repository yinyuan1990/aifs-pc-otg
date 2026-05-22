package com.acard.acard.config;

/**
 * WebRTC缓冲配置管理（动态调整）
 * 根据网络质量和机器性能动态调整缓冲大小，防止马赛克
 */
public class WebRTCBufferConfig {
    
    /**
     * 缓冲模式
     */
    public enum BufferMode {
        LOW_LATENCY("低延迟", 200, 300, 50, 30, 3, 40),      // webrtc延迟：100→200, jitter：200→300
        BALANCED("平衡", 400, 500, 100, 60, 3, 40),         // webrtc延迟：200→400, jitter：300→500
        STABLE("稳定", 600, 700, 150, 100, 5, 50),          // webrtc延迟：300→600, jitter：400→700
        ULTRA_STABLE("超稳定", 800, 1000, 200, 150, 7, 60); // webrtc延迟：400→800, jitter：500→1000 ✅防马赛克
        
        private final String displayName;
        private final int webrtcLatency;        // webrtcbin延迟(ms)
        private final int jitterLatency;        // jitterbuffer延迟(ms)
        private final int queueDepayBuffers;    // queueDepay缓冲数
        private final int queueDecodeBuffers;   // queueDecode缓冲数
        private final int retryCount;           // 重传次数
        private final int retryTimeout;         // 重传超时(ms)
        
        BufferMode(String displayName, int webrtcLatency, int jitterLatency, 
                   int queueDepayBuffers, int queueDecodeBuffers,
                   int retryCount, int retryTimeout) {
            this.displayName = displayName;
            this.webrtcLatency = webrtcLatency;
            this.jitterLatency = jitterLatency;
            this.queueDepayBuffers = queueDepayBuffers;
            this.queueDecodeBuffers = queueDecodeBuffers;
            this.retryCount = retryCount;
            this.retryTimeout = retryTimeout;
        }
        
        public String getDisplayName() { return displayName; }
        public int getWebrtcLatency() { return webrtcLatency; }
        public int getJitterLatency() { return jitterLatency; }
        public int getQueueDepayBuffers() { return queueDepayBuffers; }
        public int getQueueDecodeBuffers() { return queueDecodeBuffers; }
        public int getRetryCount() { return retryCount; }
        public int getRetryTimeout() { return retryTimeout; }
        
        public String getDescription() {
            return String.format("%s (延迟:%dms, 缓冲:%d/%d)", 
                displayName, webrtcLatency + jitterLatency, 
                queueDepayBuffers, queueDecodeBuffers);
        }
    }
    
    // 单例
    private static final WebRTCBufferConfig INSTANCE = new WebRTCBufferConfig();
    
    // 当前模式（默认：超稳定模式，防止马赛克，适合低端机型）
    private volatile BufferMode currentMode = BufferMode.ULTRA_STABLE;
    
    private WebRTCBufferConfig() {
        // 从System Property加载初始配置
        String modeName = System.getProperty("webrtc.buffer.mode", "ULTRA_STABLE");
        try {
            currentMode = BufferMode.valueOf(modeName.toUpperCase());
            System.out.println("✅ WebRTC缓冲模式: " + currentMode.getDescription());
        } catch (Exception e) {
            System.err.println("⚠️ 无效的缓冲模式: " + modeName + "，使用默认: ULTRA_STABLE");
        }
    }
    
    public static WebRTCBufferConfig getInstance() {
        return INSTANCE;
    }
    
    /**
     * 获取当前模式
     */
    public BufferMode getCurrentMode() {
        return currentMode;
    }
    
    /**
     * 切换缓冲模式（需要重启播放器生效）
     */
    public void setMode(BufferMode mode) {
        if (mode != currentMode) {
            System.out.println("📡 缓冲模式切换: " + currentMode.displayName + " → " + mode.displayName);
            currentMode = mode;
            
            // 更新System Property（影响新创建的播放器）
            System.setProperty("webrtc.rx.latency.ms", String.valueOf(mode.webrtcLatency));
            System.setProperty("webrtc.jitter.latency", String.valueOf(mode.jitterLatency));
            System.setProperty("queue.depay.max.buffers", String.valueOf(mode.queueDepayBuffers));
            System.setProperty("queue.decode.max.buffers", String.valueOf(mode.queueDecodeBuffers));
            
            System.out.println("⚠️ 配置已更新，需要重新连接才能生效");
        }
    }
    
    /**
     * 获取webrtcbin延迟
     */
    public int getWebrtcLatency() {
        return currentMode.webrtcLatency;
    }
    
    /**
     * 获取jitterbuffer延迟
     */
    public int getJitterLatency() {
        return currentMode.jitterLatency;
    }
    
    /**
     * 获取queueDepay缓冲数
     */
    public int getQueueDepayBuffers() {
        return currentMode.queueDepayBuffers;
    }
    
    /**
     * 获取queueDecode缓冲数
     */
    public int getQueueDecodeBuffers() {
        return currentMode.queueDecodeBuffers;
    }
    
    /**
     * 获取重传次数
     */
    public int getRetryCount() {
        return currentMode.retryCount;
    }
    
    /**
     * 获取重传超时
     */
    public int getRetryTimeout() {
        return currentMode.retryTimeout;
    }
    
    /**
     * 自动检测并推荐模式（根据CPU/GPU）
     */
    public static BufferMode detectRecommendedMode() {
        // 检测CPU核心数
        int cpuCores = Runtime.getRuntime().availableProcessors();
        
        // 检测可用内存
        long maxMemory = Runtime.getRuntime().maxMemory() / (1024 * 1024); // MB
        
        if (cpuCores >= 8 && maxMemory >= 4096) {
            return BufferMode.LOW_LATENCY;  // 高端机型
        } else if (cpuCores >= 6 && maxMemory >= 2048) {
            return BufferMode.BALANCED;     // 中端机型
        } else if (cpuCores >= 4 && maxMemory >= 1024) {
            return BufferMode.STABLE;       // 低端机型
        } else {
            return BufferMode.ULTRA_STABLE; // 超低端机型
        }
    }
    
    /**
     * 打印当前配置
     */
    public void printCurrentConfig() {
        System.out.println("==========================================");
        System.out.println("WebRTC缓冲配置（当前）");
        System.out.println("==========================================");
        System.out.println("模式: " + currentMode.displayName);
        System.out.println("webrtcbin延迟: " + currentMode.webrtcLatency + "ms");
        System.out.println("jitterbuffer延迟: " + currentMode.jitterLatency + "ms");
        System.out.println("queueDepay缓冲: " + currentMode.queueDepayBuffers + "帧");
        System.out.println("queueDecode缓冲: " + currentMode.queueDecodeBuffers + "帧");
        System.out.println("重传次数: " + currentMode.retryCount + "次");
        System.out.println("重传超时: " + currentMode.retryTimeout + "ms");
        System.out.println("总延迟: " + (currentMode.webrtcLatency + currentMode.jitterLatency) + "ms");
        System.out.println("==========================================");
    }
}

