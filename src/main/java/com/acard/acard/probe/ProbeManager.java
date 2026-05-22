package com.acard.acard.probe;

import org.freedesktop.gstreamer.*;
import org.freedesktop.gstreamer.event.Event;
import org.freedesktop.gstreamer.event.EventType;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map;

/**
 * Probe管理器
 * 统一管理所有的probe和调试信息，提供详细的数据流监控
 */
public class ProbeManager {
    
    private final Map<String, ProbeInfo> probes = new ConcurrentHashMap<>();
    private final AtomicLong totalBuffers = new AtomicLong(0);
    private final AtomicLong totalEvents = new AtomicLong(0);
    private boolean debugEnabled = true;
    
    // 回调接口
    public interface ProbeCallback {
        void onBufferProbe(String probeName, Buffer buffer, ProbeStats stats);
        void onEventProbe(String probeName, Event event, ProbeStats stats);
        void onProbeError(String probeName, String error);
    }
    
    private ProbeCallback callback;
    
    public ProbeManager() {
        System.err.println("🔍 初始化Probe管理器");
    }
    
    public void setCallback(ProbeCallback callback) {
        this.callback = callback;
    }
    
    public void setDebugEnabled(boolean enabled) {
        this.debugEnabled = enabled;
        System.err.println("🔍 Probe调试: " + (enabled ? "启用" : "禁用"));
    }
    
    /**
     * 在指定pad上安装buffer probe
     */
    public void installBufferProbe(Pad pad, String probeName) {
        try {
            ProbeInfo probeInfo = new ProbeInfo(probeName, ProbeType.BUFFER);
            
            Pad.PROBE probeCallback = (padProbe, info) -> {
                try {
                    Buffer buffer = info.getBuffer();
                    if (buffer != null) {
                        handleBufferProbe(probeName, buffer, probeInfo);
                    }
                    return PadProbeReturn.OK;
                } catch (Exception e) {
                    System.err.println("❌ Buffer probe错误 [" + probeName + "]: " + e.getMessage());
                    if (callback != null) {
                        callback.onProbeError(probeName, e.getMessage());
                    }
                    return PadProbeReturn.OK;
                }
            };
            
            probeInfo.setProbeCallback(probeCallback);
            pad.addProbe(PadProbeType.BUFFER, probeCallback);
            probes.put(probeName, probeInfo);
            
            System.err.println("✅ 安装buffer probe: " + probeName);
            
        } catch (Exception e) {
            System.err.println("❌ 安装buffer probe失败 [" + probeName + "]: " + e.getMessage());
        }
    }
    
    /**
     * 在指定pad上安装event probe
     */
    public void installEventProbe(Pad pad, String probeName) {
        try {
            ProbeInfo probeInfo = new ProbeInfo(probeName, ProbeType.EVENT);
            
            Pad.PROBE probeCallback = (p, info) -> {
                try {
                    if (info.getEvent() != null) {
                        handleEventProbe(probeName, info.getEvent(), probeInfo);
                    }
                    return PadProbeReturn.OK;
                } catch (Exception e) {
                    System.err.println("⚠️ Event probe回调失败: " + e.getMessage());
                    return PadProbeReturn.OK;
                }
            };
            
            probeInfo.setProbeCallback(probeCallback);
            pad.addProbe(PadProbeType.EVENT_BOTH, probeCallback);
            probes.put(probeName, probeInfo);
            
            System.err.println("📊 安装event probe: " + probeName);
        } catch (Exception e) {
            System.err.println("❌ 安装event probe失败 [" + probeName + "]: " + e.getMessage());
        }
    }
    
    /**
     * 在指定pad上安装综合probe（buffer + event）
     */
    public void installComprehensiveProbe(Pad pad, String probeName) {
        try {
            ProbeInfo probeInfo = new ProbeInfo(probeName, ProbeType.COMPREHENSIVE);
            
            Pad.PROBE probeCallback = (p, info) -> {
                try {
                    if (info.getBuffer() != null) {
                        handleBufferProbe(probeName, info.getBuffer(), probeInfo);
                    }
                    if (info.getEvent() != null) {
                        handleEventProbe(probeName, info.getEvent(), probeInfo);
                    }
                    return PadProbeReturn.OK;
                } catch (Exception e) {
                    System.err.println("⚠️ Comprehensive probe回调失败: " + e.getMessage());
                    return PadProbeReturn.OK;
                }
            };
            
            probeInfo.setProbeCallback(probeCallback);
            pad.addProbe(PadProbeType.BUFFER, probeCallback);
            pad.addProbe(PadProbeType.EVENT_BOTH, probeCallback);
            probes.put(probeName, probeInfo);
            
            System.err.println("📊 安装comprehensive probe: " + probeName);
        } catch (Exception e) {
            System.err.println("❌ 安装comprehensive probe失败 [" + probeName + "]: " + e.getMessage());
        }
    }
    
    /**
     * 处理buffer probe
     */
    private void handleBufferProbe(String probeName, Buffer buffer, ProbeInfo probeInfo) {
        try {
            probeInfo.incrementBufferCount();
            totalBuffers.incrementAndGet();
            
            // 获取buffer大小
            java.nio.ByteBuffer bb = buffer.map(false);
            long size = 0;
            if (bb != null) {
                size = bb.remaining();
                buffer.unmap();
            }
            
            probeInfo.addBufferSize(size);
            
            // 分析buffer内容
            analyzeBufferContent(probeName, buffer, probeInfo);
            
            // 回调通知
            if (callback != null) {
                ProbeStats stats = probeInfo.getStats();
                callback.onBufferProbe(probeName, buffer, stats);
            }
            
        } catch (Exception e) {
            System.err.println("⚠️ Buffer probe处理失败 [" + probeName + "]: " + e.getMessage());
            if (callback != null) {
                callback.onProbeError(probeName, "Buffer probe处理失败: " + e.getMessage());
            }
        }
    }
    
    /**
     * 处理event probe
     */
    private void handleEventProbe(String probeName, Event event, ProbeInfo probeInfo) {
        try {
            probeInfo.incrementEventCount();
            totalEvents.incrementAndGet();
            
            // 通过事件类名获取类型信息
            String eventType = event.getClass().getSimpleName();
            
            if (debugEnabled) {
                System.err.println(String.format(
                    "🔍 PROBE: %s ← event (%s)", 
                    probeName, eventType
                ));
            }
            
            // 分析特殊事件
            analyzeEventContent(probeName, event, probeInfo);
            
            // 回调通知
            if (callback != null) {
                callback.onEventProbe(probeName, event, probeInfo.getStats());
            }
            
        } catch (Exception e) {
            System.err.println("❌ 处理event probe失败 [" + probeName + "]: " + e.getMessage());
        }
    }
    
    /**
     * 分析buffer内容
     */
    private void analyzeBufferContent(String probeName, Buffer buffer, ProbeInfo probeInfo) {
        try {
            // 检查H.264 NAL单元
            if (probeName.contains("h264") || probeName.contains("parse") || probeName.contains("dec")) {
                analyzeH264Buffer(probeName, buffer, probeInfo);
            }
            
            // 检查RTP包
            if (probeName.contains("rtp") || probeName.contains("jb") || probeName.contains("depay")) {
                analyzeRtpBuffer(probeName, buffer, probeInfo);
            }
            
        } catch (Exception e) {
            System.err.println("⚠️ 分析buffer内容失败 [" + probeName + "]: " + e.getMessage());
        }
    }
    
    /**
     * 分析H.264 buffer - 在probe回调中安全处理
     */
    private void analyzeH264Buffer(String probeName, Buffer buffer, ProbeInfo probeInfo) {
        java.nio.ByteBuffer bb = null;
        try {
            // 在probe回调中立即映射和复制数据
            bb = buffer.map(false);
            if (bb == null || bb.remaining() <= 4) {
                return;
            }
            
            // 立即复制数据到本地数组，避免持有Buffer引用
            int dataSize = Math.min(32, bb.remaining());
            byte[] data = new byte[dataSize];
            bb.get(data);
            
            // 立即释放映射
            buffer.unmap();
            bb = null; // 标记为已释放
            
            // 分析复制的数据（不再依赖Buffer）
            analyzeH264Data(data, probeName, probeInfo);
            
        } catch (Exception e) {
            System.err.println("⚠️ 分析H.264 buffer失败: " + e.getMessage());
        } finally {
            // 确保在异常情况下也能释放映射
            if (bb != null) {
                try {
                    buffer.unmap();
                } catch (Exception e) {
                    System.err.println("⚠️ Buffer unmap错误: " + e.getMessage());
                }
            }
        }
    }
    
    /**
     * 分析H264数据（从复制的字节数组）
     */
    private void analyzeH264Data(byte[] data, String probeName, ProbeInfo probeInfo) {
        try {
            // 检查NAL单元类型
            for (int i = 0; i < data.length - 4; i++) {
                if (data[i] == 0 && data[i+1] == 0 && data[i+2] == 0 && data[i+3] == 1) {
                    if (i + 4 < data.length) {
                        int nalType = data[i+4] & 0x1F;
                        String nalTypeStr = getNalTypeString(nalType);
                        
                        probeInfo.addNalType(nalType);
                        
                        if (debugEnabled && (nalType == 5 || nalType == 7 || nalType == 8)) {
                            System.err.println(String.format(
                                "🎬 PROBE: %s ← H.264 NAL %s (type=%d)", 
                                probeName, nalTypeStr, nalType
                            ));
                        }
                    }
                    break;
                }
            }
        } catch (Exception e) {
            System.err.println("⚠️ 分析H264数据失败: " + e.getMessage());
        }
    }
    
    /**
     * 分析RTP buffer - 在probe回调中安全处理
     */
    private void analyzeRtpBuffer(String probeName, Buffer buffer, ProbeInfo probeInfo) {
        java.nio.ByteBuffer bb = null;
        try {
            // 在probe回调中立即映射数据
            bb = buffer.map(false);
            if (bb == null || bb.remaining() < 12) { // RTP头最小12字节
                return;
            }
            
            // 立即获取数据大小并记录
            int packetSize = bb.remaining();
            probeInfo.addRtpPacket(packetSize);
            
            // 立即释放映射
            buffer.unmap();
            bb = null;
            
            if (debugEnabled) {
                System.err.println(String.format(
                    "📦 PROBE: %s ← RTP packet (size=%d)", 
                    probeName, packetSize
                ));
            }
            
        } catch (Exception e) {
            System.err.println("⚠️ 分析RTP buffer失败: " + e.getMessage());
        } finally {
            // 确保在异常情况下也能释放映射
            if (bb != null) {
                try {
                    buffer.unmap();
                } catch (Exception e) {
                    System.err.println("⚠️ Buffer unmap错误: " + e.getMessage());
                }
            }
        }
    }
    
    /**
     * 分析event内容
     */
    private void analyzeEventContent(String probeName, Event event, ProbeInfo probeInfo) {
        try {
            // 通过事件类名获取类型信息
            String eventType = event.getClass().getSimpleName();
            
            // 记录重要事件
            if (eventType.equals("CapsEvent")) {
                System.err.println("📋 PROBE: " + probeName + " ← CAPS事件");
            } else if (eventType.equals("StreamStartEvent")) {
                System.err.println("▶️ PROBE: " + probeName + " ← 流开始事件");
            } else if (eventType.equals("EOSEvent")) {
                System.err.println("⏹️ PROBE: " + probeName + " ← 流结束事件");
            } else if (eventType.equals("FlushStartEvent")) {
                System.err.println("🔄 PROBE: " + probeName + " ← 刷新开始事件");
            } else if (eventType.equals("FlushStopEvent")) {
                System.err.println("✅ PROBE: " + probeName + " ← 刷新停止事件");
            }
            
        } catch (Exception e) {
            System.err.println("⚠️ 分析event内容失败: " + e.getMessage());
        }
    }
    
    /**
     * 获取NAL单元类型字符串
     */
    private String getNalTypeString(int nalType) {
        switch (nalType) {
            case 1: return "非IDR片";
            case 5: return "IDR片";
            case 6: return "SEI";
            case 7: return "SPS";
            case 8: return "PPS";
            case 9: return "访问单元分隔符";
            default: return "未知(" + nalType + ")";
        }
    }
    
    /**
     * 移除probe
     */
    public void removeProbe(Pad pad, String probeName) {
        try {
            ProbeInfo probeInfo = probes.get(probeName);
            if (probeInfo != null && probeInfo.getProbeCallback() != null) {
                pad.removeProbe(probeInfo.getProbeCallback());
                probes.remove(probeName);
                System.err.println("🗑️ 移除probe: " + probeName);
            }
        } catch (Exception e) {
            System.err.println("❌ 移除probe失败 [" + probeName + "]: " + e.getMessage());
        }
    }
    
    /**
     * 获取probe统计信息
     */
    public ProbeStats getProbeStats(String probeName) {
        ProbeInfo probeInfo = probes.get(probeName);
        return probeInfo != null ? probeInfo.getStats() : null;
    }
    
    /**
     * 获取全局统计信息
     */
    public GlobalStats getGlobalStats() {
        return new GlobalStats(
            probes.size(),
            totalBuffers.get(),
            totalEvents.get()
        );
    }
    
    /**
     * 打印所有probe统计信息
     */
    public void printAllStats() {
        System.err.println("📊 =====  Probe统计信息  =====");
        System.err.println("总probe数: " + probes.size());
        System.err.println("总buffer数: " + totalBuffers.get());
        System.err.println("总event数: " + totalEvents.get());
        
        for (Map.Entry<String, ProbeInfo> entry : probes.entrySet()) {
            ProbeStats stats = entry.getValue().getStats();
            System.err.println(String.format(
                "📍 %s: buffers=%d, events=%d, avgSize=%.1f", 
                entry.getKey(), stats.bufferCount, stats.eventCount, stats.averageBufferSize
            ));
        }
        System.err.println("📊 ========================");
    }
    
    /**
     * 清除所有probe
     */
    public void clearAllProbes() {
        probes.clear();
        totalBuffers.set(0);
        totalEvents.set(0);
        System.err.println("🗑️ 清除所有probe");
    }
    
    /**
     * Probe类型枚举
     */
    public enum ProbeType {
        BUFFER, EVENT, COMPREHENSIVE
    }
    
    /**
     * Probe信息类
     */
    private static class ProbeInfo {
        private final String name;
        private final ProbeType type;
        private long probeId;
        private Pad.PROBE probeCallback;
        private final AtomicLong bufferCount = new AtomicLong(0);
        private final AtomicLong eventCount = new AtomicLong(0);
        private final AtomicLong totalBufferSize = new AtomicLong(0);
        private final Map<Integer, Integer> nalTypes = new ConcurrentHashMap<>();
        private final AtomicLong rtpPackets = new AtomicLong(0);
        
        public ProbeInfo(String name, ProbeType type) {
            this.name = name;
            this.type = type;
        }
        
        public void setProbeId(long probeId) { this.probeId = probeId; }
        public long getProbeId() { return probeId; }
        public void setProbeCallback(Pad.PROBE callback) { this.probeCallback = callback; }
        public Pad.PROBE getProbeCallback() { return probeCallback; }
        
        public void incrementBufferCount() { bufferCount.incrementAndGet(); }
        public void incrementEventCount() { eventCount.incrementAndGet(); }
        public void addBufferSize(long size) { totalBufferSize.addAndGet(size); }
        public void addNalType(int nalType) { nalTypes.merge(nalType, 1, Integer::sum); }
        public void addRtpPacket(long size) { rtpPackets.incrementAndGet(); }
        
        public ProbeStats getStats() {
            long buffers = bufferCount.get();
            double avgSize = buffers > 0 ? (double) totalBufferSize.get() / buffers : 0.0;
            
            return new ProbeStats(
                name, type, buffers, eventCount.get(), 
                avgSize, nalTypes, rtpPackets.get()
            );
        }
    }
    
    /**
     * Probe统计信息类
     */
    public static class ProbeStats {
        public final String name;
        public final ProbeType type;
        public final long bufferCount;
        public final long eventCount;
        public final double averageBufferSize;
        public final Map<Integer, Integer> nalTypes;
        public final long rtpPackets;
        
        public ProbeStats(String name, ProbeType type, long bufferCount, long eventCount, 
                         double averageBufferSize, Map<Integer, Integer> nalTypes, long rtpPackets) {
            this.name = name;
            this.type = type;
            this.bufferCount = bufferCount;
            this.eventCount = eventCount;
            this.averageBufferSize = averageBufferSize;
            this.nalTypes = new ConcurrentHashMap<>(nalTypes);
            this.rtpPackets = rtpPackets;
        }
    }
    
    /**
     * 全局统计信息类
     */
    public static class GlobalStats {
        public final int totalProbes;
        public final long totalBuffers;
        public final long totalEvents;
        
        public GlobalStats(int totalProbes, long totalBuffers, long totalEvents) {
            this.totalProbes = totalProbes;
            this.totalBuffers = totalBuffers;
            this.totalEvents = totalEvents;
        }
    }
    
    /**
     * 关闭probe管理器
     */
    public void shutdown() {
        clearAllProbes();
        System.err.println("Probe管理器已关闭");
    }
}