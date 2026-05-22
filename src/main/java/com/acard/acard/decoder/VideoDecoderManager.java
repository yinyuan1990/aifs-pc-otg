package com.acard.acard.decoder;

import org.freedesktop.gstreamer.*;
import org.freedesktop.gstreamer.event.Event;
import org.freedesktop.gstreamer.event.EventType;
import org.freedesktop.gstreamer.lowlevel.GstVideoAPI;
import org.freedesktop.gstreamer.lowlevel.GstAPI.GstCallback;
import org.freedesktop.gstreamer.glib.Natives;
import com.sun.jna.Pointer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 视频解码器管理器
 * 专门处理H.264解码相关逻辑，包括解码器配置、caps协商、错误处理等
 */
public class VideoDecoderManager {
    
    private Element decoder;
    private Element parser;
    private final AtomicBoolean isDecoding = new AtomicBoolean(false);
    private final AtomicLong decodedFrames = new AtomicLong(0);
    private final AtomicLong decodingErrors = new AtomicLong(0);
    
    // 解码器配置
    private static final String[] DECODER_CANDIDATES = {
        "avdec_h264",      // FFmpeg软解码器
        "nvh264dec",       // NVIDIA硬解码器
        "vaapih264dec",    // VAAPI硬解码器
        "d3d11h264dec"     // Direct3D硬解码器
    };
    
    // 回调接口
    public interface DecoderCallback {
        void onDecodingStarted();
        void onDecodingStopped();
        void onDecodingError(String error);
        void onFrameDecoded();
        void onCapsNegotiated(Caps caps);
    }
    
    private DecoderCallback callback;
    
    public VideoDecoderManager() {
        System.err.println("🎬 初始化视频解码器管理器");
    }
    
    public void setCallback(DecoderCallback callback) {
        this.callback = callback;
    }
    
    /**
     * 创建最佳可用的H.264解码器
     */
    public Element createOptimalDecoder() {
        Element bestDecoder = null;
        String selectedDecoderName = null;
        
        // 尝试创建最佳解码器
        for (String decoderName : DECODER_CANDIDATES) {
            try {
                Element testDecoder = ElementFactory.make(decoderName, "dec");
                if (testDecoder != null) {
                    bestDecoder = testDecoder;
                    selectedDecoderName = decoderName;
                    System.err.println("✅ 选择解码器: " + decoderName);
                    break;
                }
            } catch (Exception e) {
                System.err.println("⚠️ 解码器 " + decoderName + " 不可用: " + e.getMessage());
            }
        }
        
        if (bestDecoder == null) {
            throw new RuntimeException("无法创建任何H.264解码器");
        }
        
        this.decoder = bestDecoder;
        configureDecoder(selectedDecoderName);
        setupDecoderSignals();
        
        return decoder;
    }
    
    /**
     * 配置解码器参数
     */
    private void configureDecoder(String decoderName) {
        try {
            // 通用配置
            if (decoderName.equals("avdec_h264")) {
                // FFmpeg解码器配置
                decoder.set("max-threads", Math.min(4, Runtime.getRuntime().availableProcessors()));
                decoder.set("output-corrupt", false);
                decoder.set("skip-frame", 0); // 不跳帧
                System.err.println("🔧 FFmpeg解码器配置: max-threads=4, output-corrupt=false");
                
            } else if (decoderName.equals("nvh264dec")) {
                // NVIDIA硬解码器配置
                decoder.set("gpu-id", 0);
                System.err.println("🔧 NVIDIA解码器配置: gpu-id=0");
                
            } else if (decoderName.equals("vaapih264dec")) {
                // VAAPI硬解码器配置
                System.err.println("🔧 VAAPI解码器配置");
                
            } else if (decoderName.equals("d3d11h264dec")) {
                // Direct3D硬解码器配置
                System.err.println("🔧 Direct3D解码器配置");
            }
            
        } catch (Exception e) {
            System.err.println("❌ 配置解码器失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 设置解码器信号处理
     */
    private void setupDecoderSignals() {
        // 状态变化监听 - 简化实现，避免GObject.NOTIFY导入问题
        // decoder.connect("notify::state", (GObject.NOTIFY) (obj, param) -> {
        //     try {
        //         State state = decoder.getState();
        //         System.err.println("🎬 解码器状态: " + state);
        //         
        //         if (state == State.PLAYING) {
        //             isDecoding.set(true);
        //             if (callback != null) {
        //                 callback.onDecodingStarted();
        //             }
        //         } else if (state == State.NULL || state == State.READY) {
        //             isDecoding.set(false);
        //             if (callback != null) {
        //                 callback.onDecodingStopped();
        //             }
        //         }
        //     } catch (Exception e) {
        //         System.err.println("❌ 处理解码器状态变化失败: " + e.getMessage());
        //     }
        // });
        
        // 使用状态变化回调替代GObject.NOTIFY
        decoder.getBus().connect((Bus.STATE_CHANGED) (source, oldState, newState, pending) -> {
            try {
                System.err.println("🎬 解码器状态变化: " + oldState + " → " + newState);
                
                if (newState == State.PLAYING) {
                    isDecoding.set(true);
                    if (callback != null) {
                        callback.onDecodingStarted();
                    }
                } else if (newState == State.NULL || newState == State.READY) {
                    isDecoding.set(false);
                    if (callback != null) {
                        callback.onDecodingStopped();
                    }
                }
            } catch (Exception e) {
                System.err.println("❌ 处理解码器状态变化失败: " + e.getMessage());
            }
        });
        
        // 错误处理
        decoder.getBus().connect((Bus.ERROR) (source, code, message) -> {
            System.err.println("❌ 解码器错误: " + message);
            decodingErrors.incrementAndGet();
            if (callback != null) {
                callback.onDecodingError(message);
            }
        });
        
        // 警告处理
        decoder.getBus().connect((Bus.WARNING) (source, code, message) -> {
            System.err.println("⚠️ 解码器警告: " + message);
        });
    }
    
    /**
     * 创建H.264解析器
     */
    public Element createH264Parser() {
        try {
            parser = ElementFactory.make("h264parse", "parser");
            if (parser == null) {
                throw new RuntimeException("无法创建h264parse元素");
            }
            
            // 配置parser
            parser.set("config-interval", -1);
            
            setupParserSignals();
            
            System.err.println("✅ H.264解析器创建成功");
            return parser;
            
        } catch (Exception e) {
            System.err.println("❌ 创建H.264解析器失败: " + e.getMessage());
            throw new RuntimeException("创建H.264解析器失败", e);
        }
    }
    
    /**
     * 设置解析器信号处理
     */
    private void setupParserSignals() {
        // Caps协商监听
        parser.connect((Element.PAD_ADDED) (element, pad) -> {
            try {
                Caps caps = pad.getCurrentCaps();
                if (caps != null) {
                    System.err.println("📋 解析器输出caps: " + caps.toString());
                    if (callback != null) {
                        callback.onCapsNegotiated(caps);
                    }
                }
            } catch (Exception e) {
                System.err.println("❌ 处理解析器caps失败: " + e.getMessage());
            }
        });
    }
    
    /**
     * 动态协商解码器caps
     */
    public boolean negotiateDecoderCaps(Caps inputCaps, Pad sourcePad) {
        try {
            System.err.println("🔄 开始解码器caps协商...");
            System.err.println("📋 输入caps: " + inputCaps.toString());
            
            // 检查输入caps是否包含H.264
            String capsStr = inputCaps.toString().toLowerCase();
            if (!capsStr.contains("h264")) {
                System.err.println("❌ 输入caps不包含H.264编码");
                return false;
            }
            
            // 提取profile-level-id
            String profileLevelId = extractProfileLevelId(inputCaps);
            if (profileLevelId != null) {
                System.err.println("📋 检测到profile-level-id: " + profileLevelId);
                
                // 根据profile-level-id调整解码器配置
                adjustDecoderForProfile(profileLevelId);
            }
            
            // 尝试设置解码器输入caps
            Pad decoderSinkPad = decoder.getStaticPad("sink");
            if (decoderSinkPad != null) {
                // 创建兼容的caps
                Caps decoderCaps = createCompatibleDecoderCaps(inputCaps);
                
                // 使用link方法进行连接，让GStreamer自动处理caps协商
                try {
                    sourcePad.link(decoderSinkPad);
                    System.err.println("✅ 解码器连接成功，caps自动协商");
                    return true;
                } catch (Exception e) {
                    System.err.println("❌ 解码器连接失败: " + e.getMessage());
                }
            }
            
            return false;
            
        } catch (Exception e) {
            System.err.println("❌ 解码器caps协商异常: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 从caps中提取profile-level-id
     */
    private String extractProfileLevelId(Caps caps) {
        try {
            Structure structure = caps.getStructure(0);
            if (structure != null) {
                Object profileLevelId = structure.getValue("profile-level-id");
                if (profileLevelId != null) {
                    return profileLevelId.toString();
                }
            }
        } catch (Exception e) {
            System.err.println("⚠️ 提取profile-level-id失败: " + e.getMessage());
        }
        return null;
    }
    
    /**
     * 根据profile调整解码器配置
     */
    private void adjustDecoderForProfile(String profileLevelId) {
        try {
            System.err.println("🔧 根据profile调整解码器: " + profileLevelId);
            
            // 根据不同的profile-level-id调整配置
            if (profileLevelId.startsWith("42e0")) {
                // Baseline profile
                System.err.println("📋 检测到Baseline profile");
            } else if (profileLevelId.startsWith("4d40") || profileLevelId.startsWith("640c")) {
                // Main/High profile
                System.err.println("📋 检测到Main/High profile");
                
                // 对于高级profile，可能需要更多线程
                try {
                    decoder.set("max-threads", Math.min(8, Runtime.getRuntime().availableProcessors()));
                } catch (Exception e) {
                    System.err.println("⚠️ 设置max-threads失败: " + e.getMessage());
                }
            }
            
        } catch (Exception e) {
            System.err.println("⚠️ 调整解码器配置失败: " + e.getMessage());
        }
    }
    
    /**
     * 创建兼容的解码器caps
     */
    private Caps createCompatibleDecoderCaps(Caps inputCaps) {
        try {
            // 创建基本的H.264 caps
            String capsString = "video/x-h264,stream-format=byte-stream,alignment=au";
            
            // 尝试从输入caps中提取有用信息
            Structure inputStruct = inputCaps.getStructure(0);
            if (inputStruct != null) {
                // 提取profile-level-id
                Object profileLevelId = inputStruct.getValue("profile-level-id");
                if (profileLevelId != null) {
                    capsString += ",profile-level-id=" + profileLevelId.toString();
                }
                
                // 提取width和height
                Object width = inputStruct.getValue("width");
                Object height = inputStruct.getValue("height");
                if (width != null && height != null) {
                    capsString += ",width=" + width + ",height=" + height;
                }
                
                // 提取framerate
                Object framerate = inputStruct.getValue("framerate");
                if (framerate != null) {
                    capsString += ",framerate=" + framerate;
                }
            }
            
            Caps decoderCaps = Caps.fromString(capsString);
            System.err.println("📋 创建解码器caps: " + capsString);
            
            return decoderCaps;
            
        } catch (Exception e) {
            System.err.println("⚠️ 创建解码器caps失败: " + e.getMessage());
            // 返回基本caps作为fallback
            return Caps.fromString("video/x-h264");
        }
    }
    
    /**
     * 请求关键帧
     */
    public boolean requestKeyFrame() {
        try {
            System.err.println("🔑 请求关键帧...");
            
            // 使用GStreamer Video API创建force key unit事件
            Pointer eventPtr = GstVideoAPI.GSTVIDEO_API.ptr_gst_video_event_new_upstream_force_key_unit(
                -1L,  // running_time: -1 表示立即请求
                true, // all_headers: 包含所有头信息
                1     // count: 计数器
            );
            
            if (eventPtr != null) {
                Event keyFrameEvent = Natives.objectFor(eventPtr, Event.class, false, true);
                
                // 发送到解码器
                Pad decoderSinkPad = decoder.getStaticPad("sink");
                if (decoderSinkPad != null) {
                    boolean result = decoderSinkPad.sendEvent(keyFrameEvent);
                    System.err.println("🔑 关键帧请求结果: " + result);
                    return result;
                }
            }
        } catch (Exception e) {
            System.err.println("❌ 请求关键帧失败: " + e.getMessage());
        }
        
        return false;
    }
    
    /**
     * 重置解码器
     */
    public void resetDecoder() {
        try {
            if (decoder != null) {
                System.err.println("🔄 重置解码器...");
                
                // 停止解码器
                decoder.setState(State.READY);
                
                // 清除统计
                decodedFrames.set(0);
                decodingErrors.set(0);
                isDecoding.set(false);
                
                // 重新启动
                decoder.setState(State.PLAYING);
                
                System.err.println("✅ 解码器重置完成");
            }
        } catch (Exception e) {
            System.err.println("❌ 重置解码器失败: " + e.getMessage());
        }
    }
    
    /**
     * 获取解码统计信息
     */
    public DecoderStats getStats() {
        return new DecoderStats(
            isDecoding.get(),
            decodedFrames.get(),
            decodingErrors.get()
        );
    }
    
    /**
     * 解码统计信息类
     */
    public static class DecoderStats {
        public final boolean isDecoding;
        public final long decodedFrames;
        public final long decodingErrors;
        
        public DecoderStats(boolean isDecoding, long decodedFrames, long decodingErrors) {
            this.isDecoding = isDecoding;
            this.decodedFrames = decodedFrames;
            this.decodingErrors = decodingErrors;
        }
        
        @Override
        public String toString() {
            return String.format("DecoderStats{decoding=%s, frames=%d, errors=%d}", 
                isDecoding, decodedFrames, decodingErrors);
        }
    }
    
    /**
     * 更新帧计数
     */
    public void incrementFrameCount() {
        decodedFrames.incrementAndGet();
        if (callback != null) {
            callback.onFrameDecoded();
        }
    }
    
    /**
     * 检查解码器是否正在工作
     */
    public boolean isDecoding() {
        return isDecoding.get();
    }
    
    /**
     * 获取解码器元素
     */
    public Element getDecoder() {
        return decoder;
    }
    
    /**
     * 获取解析器元素
     */
    public Element getParser() {
        return parser;
    }
    
    /**
     * 关闭解码器管理器
     */
    public void shutdown() {
        try {
            isDecoding.set(false);
            
            if (decoder != null) {
                decoder.setState(State.NULL);
            }
            
            if (parser != null) {
                parser.setState(State.NULL);
            }
            
            System.err.println("视频解码器管理器已关闭");
            
        } catch (Exception e) {
            System.err.println("❌ 关闭解码器管理器失败: " + e.getMessage());
        }
    }
}