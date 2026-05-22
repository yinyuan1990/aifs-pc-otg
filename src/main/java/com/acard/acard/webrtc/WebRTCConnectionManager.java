package com.acard.acard.webrtc;

import org.freedesktop.gstreamer.*;
import org.freedesktop.gstreamer.webrtc.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WebRTC连接管理器
 * 负责WebRTC连接建立、SDP协商、ICE处理等
 */
public class WebRTCConnectionManager {
    
    private final WebRTCBin webrtcBin;
    private final HttpClient httpClient;
    private final String host;
    private final int apiPort;
    private final String app;
    private final String stream;
    
    private final AtomicBoolean isConnected = new AtomicBoolean(false);
    private final AtomicBoolean isNegotiating = new AtomicBoolean(false);
    
    // 回调接口
    public interface ConnectionCallback {
        void onConnected();
        void onDisconnected();
        void onError(String error);
        void onNewTransceiver(Element element, Object transceiver);
    }
    
    private ConnectionCallback callback;
    
    public WebRTCConnectionManager(String host, int apiPort, String app, String stream) {
        this.host = host;
        this.apiPort = apiPort;
        this.app = app;
        this.stream = stream;
        
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        
        // 创建WebRTCBin
        this.webrtcBin = (WebRTCBin) ElementFactory.make("webrtcbin", "webrtc");
        configureWebRTCBin();
    }
    
    public void configureWebRTCBin() {
        try {
            // 设置延迟
            webrtcBin.set("latency", 100);
            
            // 设置STUN服务器
            webrtcBin.set("stun-server", "stun://stun.l.google.com:19302");
            
            // 设置bundle策略 - 使用GValue设置枚举值
            try {
                webrtcBin.set("bundle-policy", "max-bundle");
            } catch (Exception e) {
                System.err.println("设置bundle-policy失败，使用默认值: " + e.getMessage());
            }
            
            // 设置ICE传输策略
            try {
                webrtcBin.set("ice-transport-policy", "all");
            } catch (Exception e) {
                System.err.println("设置ice-transport-policy失败，使用默认值: " + e.getMessage());
            }
            
            System.out.println("WebRTCBin配置完成: 延迟=100ms, STUN服务器已设置");
        } catch (Exception e) {
            System.err.println("配置WebRTCBin失败: " + e.getMessage());
            e.printStackTrace();
        }
        
        // 设置信号处理器
        setupSignalHandlers();
    }
    
    private void setupSignalHandlers() {
        try {
            // ICE连接状态变化 - 简化实现，避免GObject.NOTIFY导入问题
            // webrtcBin.connect("notify::ice-connection-state", (GObject.NOTIFY) (obj, param) -> {
            //     try {
            //         Object state = webrtcBin.get("ice-connection-state");
            //         System.err.println("ICE连接状态: " + state);
            //         
            //         if ("connected".equals(state.toString()) || "completed".equals(state.toString())) {
            //             isConnected.set(true);
            //             if (callback != null) {
            //                 callback.onConnected();
            //             }
            //         } else if ("disconnected".equals(state.toString()) || "failed".equals(state.toString())) {
            //             isConnected.set(false);
            //             if (callback != null) {
            //                 callback.onDisconnected();
            //             }
            //         }
            //     } catch (Exception e) {
            //         System.err.println("ICE状态处理异常: " + e.getMessage());
            //     }
            // });
            
            // 协商需要信号
            webrtcBin.connect(new WebRTCBin.ON_NEGOTIATION_NEEDED() {
                @Override
                public void onNegotiationNeeded(Element elem) {
                    System.err.println("🔄 需要重新协商");
                    if (!isNegotiating.compareAndSet(false, true)) {
                        System.err.println("⚠️ 协商已在进行中，跳过");
                        return;
                    }
                    
                    try {
                        createOfferAsync();
                    } catch (Throwable e) {
                        System.err.println("❌ 创建Offer失败: " + e.getMessage());
                        isNegotiating.set(false);
                    }
                }
            });
            
            // 新收发器信号 - 简化实现，避免复杂的回调接口
            // webrtcBin.connect("on-new-transceiver", (Element element, Object transceiver) -> {
            //     System.err.println("📡 新收发器: " + transceiver);
            //     if (callback != null) {
            //         callback.onNewTransceiver(element, transceiver);
            //     }
            // });
            
            System.out.println("WebRTCBin信号处理器设置完成");
        } catch (Exception e) {
            System.err.println("设置信号处理器失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    public void setCallback(ConnectionCallback callback) {
        this.callback = callback;
    }
    
    public WebRTCBin getWebRTCBin() {
        return webrtcBin;
    }
    
    public boolean isConnected() {
        return isConnected.get();
    }
    
    /**
     * 公开的创建Offer方法，供外部调用
     */
    public void createOffer(OfferCallback callback) {
        this.offerCallback = callback;
        createOfferAsync();
    }
    
    /**
     * Offer回调接口
     */
    public interface OfferCallback {
        void onOfferCreated(WebRTCSessionDescription offer);
    }
    
    private OfferCallback offerCallback;
    
    /**
     * 添加视频收发器
     */
    public void addVideoTransceiver() {
        try {
            System.err.println("📡 添加视频收发器...");
            
            // 🔧 创建增强的H264 caps，包含SPS/PPS参数集
            // 修改为支持640c34 profile以匹配SRS服务器返回的profile
            String capsString = "application/x-rtp,media=video,encoding-name=H264,clock-rate=90000," +
                    "profile-level-id=640c34,packetization-mode=1,level-asymmetry-allowed=1";
            
            // 🔍 尝试添加通用的H264 baseline profile SPS/PPS参数
            // 这些是标准的baseline profile参数，可以帮助h264parse正确初始化
            try {
                // 添加sprop-parameter-sets（如果服务器支持的话）
                capsString += ",sprop-parameter-sets=\"Z2QAH6zZQFAFuwFsgAAAAwCAAAAeB4wYyw==,aOvjyyLA\"";
                System.err.println("🔧 添加sprop-parameter-sets到WebRTC caps");
            } catch (Exception e) {
                System.err.println("⚠️ sprop-parameter-sets添加失败: " + e.getMessage());
            }
            
            Caps videoCaps = Caps.fromString(capsString);
            webrtcBin.emit("add-transceiver", 3, videoCaps);
            
            System.err.println("✅ 视频收发器添加成功，caps: " + capsString);
        } catch (Exception e) {
            System.err.println("❌ 添加视频收发器失败: " + e.getMessage());
            if (callback != null) {
                callback.onError("添加视频收发器失败: " + e.getMessage());
            }
        }
    }
    
    public void addTransceiver() {
        try {
            // 添加视频接收收发器
            webrtcBin.emit("add-transceiver", "video", "recvonly");
            System.out.println("✅ 视频收发器添加成功 (RECVONLY)");
        } catch (Exception e) {
            System.err.println("❌ 添加收发器失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 异步创建Offer
     */
    private void createOfferAsync() {
        CompletableFuture.runAsync(() -> {
            try {
                System.err.println("🔄 开始创建SDP Offer...");
                
                webrtcBin.createOffer(new WebRTCBin.CREATE_OFFER() {
                    @Override
                    public void onOfferCreated(WebRTCSessionDescription offer) {
                        try {
                            System.err.println("✅ SDP Offer创建成功");
                            handleOfferCreated(offer);
                        } catch (Exception e) {
                            System.err.println("❌ Offer创建回调失败: " + e.getMessage());
                            isNegotiating.set(false);
                            if (callback != null) {
                                callback.onError("Offer创建失败: " + e.getMessage());
                            }
                        }
                    }
                });
                
            } catch (Exception e) {
                System.err.println("❌ 创建Offer异常: " + e.getMessage());
                isNegotiating.set(false);
                if (callback != null) {
                    callback.onError("创建Offer异常: " + e.getMessage());
                }
            }
        });
    }
    
    /**
     * 处理Offer创建完成
     */
    private void handleOfferCreated(WebRTCSessionDescription offer) {
        try {
            System.err.println("✅ SDP Offer创建成功");
            
            // 设置本地描述
            setLocalDescription(offer);
            
            // 如果有外部回调，调用它
            if (offerCallback != null) {
                offerCallback.onOfferCreated(offer);
                return;
            }
            
            // 否则使用默认行为：发送到SRS
            String sdpText = getSdpText(offer);
            if (sdpText != null) {
                sendOfferToSRS(sdpText);
            } else {
                throw new RuntimeException("无法获取SDP文本");
            }
            
        } catch (Exception e) {
            System.err.println("❌ 处理Offer失败: " + e.getMessage());
            isNegotiating.set(false);
            if (callback != null) {
                callback.onError("处理Offer失败: " + e.getMessage());
            }
        }
    }
    
    /**
     * 设置本地SDP描述
     */
    private void setLocalDescription(WebRTCSessionDescription desc) {
        try {
            System.err.println("▶️ 设置本地SDP描述开始");
            desc.disown(); // 交给底层接管
            webrtcBin.emit("set-local-description", desc, null); // 第三参 GstPromise* = NULL
            System.err.println("✅ 本地SDP描述设置成功");
        } catch (Exception e) {
            System.err.println("❌ 设置本地SDP描述失败: " + e.getMessage());
            throw new RuntimeException("设置本地SDP描述失败", e);
        }
    }
    
    /**
     * 公开的设置远程描述方法，支持字符串SDP
     */
    public void setRemoteDescription(String sdpAnswer) {
        try {
            // 创建SDP消息
            org.freedesktop.gstreamer.SDPMessage sdpMessage = new org.freedesktop.gstreamer.SDPMessage();
            sdpMessage.parseBuffer(sdpAnswer);
            
            // 创建WebRTC会话描述
            WebRTCSessionDescription answer = new WebRTCSessionDescription(
                WebRTCSDPType.ANSWER, 
                sdpMessage
            );
            
            // 调用私有方法
            setRemoteDescription(answer);
            
        } catch (Exception e) {
            System.err.println("❌ 设置远程SDP描述失败: " + e.getMessage());
            throw new RuntimeException("设置远程SDP描述失败", e);
        }
    }
    
    /**
     * 设置远程SDP描述（私有方法）
     */
    private void setRemoteDescription(WebRTCSessionDescription desc) {
        try {
            System.err.println("▶️ 设置远程SDP描述开始");
            desc.disown(); // 交给底层接管
            webrtcBin.emit("set-remote-description", desc, null); // 第三参 GstPromise* = NULL
            System.err.println("✅ 远程SDP描述设置成功");
            isNegotiating.set(false);
        } catch (Exception e) {
            System.err.println("❌ 设置远程SDP描述失败: " + e.getMessage());
            isNegotiating.set(false);
            throw new RuntimeException("设置远程SDP描述失败", e);
        }
    }
    
    /**
     * 获取SDP文本
     */
    private String getSdpText(WebRTCSessionDescription desc) {
        try {
            org.freedesktop.gstreamer.SDPMessage sdpMsg = desc.getSDPMessage();
            if (sdpMsg != null) {
                return sdpMsg.toString();
            }
        } catch (Exception e) {
            System.err.println("❌ 获取SDP文本失败: " + e.getMessage());
        }
        return null;
    }
    
    /**
     * 发送Offer到SRS服务器
     */
    private void sendOfferToSRS(String sdpOffer) {
        CompletableFuture.runAsync(() -> {
            try {
                System.err.println("📤 发送SDP Offer到SRS服务器...");
                
                String answerSdp = postOfferToSRS(sdpOffer);
                if (answerSdp != null && !answerSdp.isEmpty()) {
                    // 创建远程描述并设置
                    org.freedesktop.gstreamer.SDPMessage sdpMessage = new org.freedesktop.gstreamer.SDPMessage();
                    sdpMessage.parseBuffer(answerSdp);
                    
                    WebRTCSessionDescription answer = new WebRTCSessionDescription(
                        WebRTCSDPType.ANSWER, 
                        sdpMessage
                    );
                    setRemoteDescription(answer);
                    
                    System.err.println("✅ SDP协商完成");
                } else {
                    throw new RuntimeException("SRS返回空的Answer SDP");
                }
                
            } catch (Exception e) {
                System.err.println("❌ SDP协商失败: " + e.getMessage());
                isNegotiating.set(false);
                if (callback != null) {
                    callback.onError("SDP协商失败: " + e.getMessage());
                }
            }
        });
    }
    
    /**
     * 向SRS服务器POST Offer并获取Answer
     */
    private String postOfferToSRS(String sdpOffer) throws Exception {
        String url = String.format("http://%s:%d/rtc/v1/play/", host, apiPort);
        
        String jsonBody = String.format(
            "{\"api\":\"%s\",\"streamurl\":\"webrtc://%s/%s/%s\",\"sdp\":\"%s\"}",
            url, host, app, stream, jsonEscape(sdpOffer)
        );
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .timeout(Duration.ofSeconds(10))
                .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() != 200) {
            throw new RuntimeException("SRS响应错误: " + response.statusCode() + " " + response.body());
        }
        
        // 解析响应JSON获取SDP
        String responseBody = response.body();
        System.err.println("📥 SRS响应: " + responseBody);
        
        // 简单的JSON解析获取sdp字段
        int sdpStart = responseBody.indexOf("\"sdp\":\"") + 7;
        if (sdpStart < 7) {
            throw new RuntimeException("SRS响应中未找到SDP字段");
        }
        
        int sdpEnd = responseBody.indexOf("\"", sdpStart);
        if (sdpEnd < 0) {
            throw new RuntimeException("SRS响应中SDP字段格式错误");
        }
        
        String answerSdp = responseBody.substring(sdpStart, sdpEnd);
        answerSdp = jsonUnescape(answerSdp);
        
        System.err.println("✅ 获取到Answer SDP，长度: " + answerSdp.length());
        return answerSdp;
    }
    
    /**
     * JSON字符串转义
     */
    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
    }
    
    /**
     * JSON字符串反转义
     */
    private static String jsonUnescape(String s) {
        return s.replace("\\\"", "\"")
                .replace("\\r", "\r")
                .replace("\\n", "\n")
                .replace("\\t", "\t")
                .replace("\\\\", "\\");
    }
    
    /**
     * 关闭连接
     */
    public void shutdown() {
        isConnected.set(false);
        isNegotiating.set(false);
        
        if (webrtcBin != null) {
            webrtcBin.setState(State.NULL);
        }
        
        System.err.println("WebRTC连接管理器已关闭");
    }
}