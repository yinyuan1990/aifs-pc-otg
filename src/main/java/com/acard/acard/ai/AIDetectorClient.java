package com.acard.acard.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * AI 扑克牌检测客户端
 * 通过 TCP Socket 与 Python 服务通信
 */
public class AIDetectorClient {
    
    private static volatile AIDetectorClient instance;
    
    private final String host;
    private final int port;
    private final Gson gson;
    private final ExecutorService executor;
    
    private Socket socket;
    private PrintWriter writer;
    private BufferedReader reader;
    private volatile boolean connected = false;
    
    // 回调接口
    public interface DetectionCallback {
        void onResult(DetectionResult result);
        void onError(String error);
    }
    
    private AIDetectorClient() {
        this.host = "127.0.0.1";
        this.port = 5555;
        this.gson = new Gson();
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "AI-Detector");
            t.setDaemon(true);
            return t;
        });
    }
    
    public static AIDetectorClient getInstance() {
        if (instance == null) {
            synchronized (AIDetectorClient.class) {
                if (instance == null) {
                    instance = new AIDetectorClient();
                }
            }
        }
        return instance;
    }
    
    /**
     * 连接到 AI 服务
     */
    public boolean connect() {
        if (connected) return true;
        
        try {
            System.out.println("🔗 正在连接 AI 服务: " + host + ":" + port);
            socket = new Socket(host, port);
            socket.setSoTimeout(10000); // 10秒超时
            socket.setTcpNoDelay(true); // 禁用 Nagle 算法，减少延迟
            
            writer = new PrintWriter(
                new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)
                ), 
                true
            );
            reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)
            );
            
            connected = true;
            System.out.println("✅ AI 服务连接成功!");
            return true;
            
        } catch (IOException e) {
            System.err.println("❌ 连接 AI 服务失败: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 断开连接
     */
    public void disconnect() {
        connected = false;
        try {
            if (writer != null) writer.close();
            if (reader != null) reader.close();
            if (socket != null) socket.close();
        } catch (IOException e) {
            // ignore
        }
        System.out.println("🔌 AI 服务已断开");
    }
    
    /**
     * 检测图片中的扑克牌
     * 
     * @param imagePath 图片路径（绝对路径）
     * @return 检测结果
     */
    public DetectionResult detect(String imagePath) {
        if (!connected && !connect()) {
            DetectionResult result = new DetectionResult();
            result.setSuccess(false);
            result.setError("未连接到 AI 服务");
            return result;
        }
        
        try {
            // 构建请求
            JsonObject request = new JsonObject();
            request.addProperty("type", "detect");
            request.addProperty("image_path", imagePath);
            request.addProperty("timestamp", System.currentTimeMillis());
            
            // 发送请求（以换行符结尾）
            String requestStr = gson.toJson(request);
            writer.println(requestStr);
            writer.flush();
            
            // 接收响应
            String response = reader.readLine();
            if (response == null) {
                connected = false;
                DetectionResult result = new DetectionResult();
                result.setSuccess(false);
                result.setError("服务无响应");
                return result;
            }
            
            return parseResponse(response);
            
        } catch (IOException e) {
            connected = false;
            DetectionResult result = new DetectionResult();
            result.setSuccess(false);
            result.setError("通信错误: " + e.getMessage());
            return result;
        }
    }
    
    /**
     * 异步检测
     */
    public void detectAsync(String imagePath, DetectionCallback callback) {
        executor.submit(() -> {
            try {
                DetectionResult result = detect(imagePath);
                if (callback != null) {
                    if (result.isSuccess()) {
                        callback.onResult(result);
                    } else {
                        callback.onError(result.getError());
                    }
                }
            } catch (Exception e) {
                if (callback != null) {
                    callback.onError(e.getMessage());
                }
            }
        });
    }
    
    /**
     * 发送 ping 测试连接
     */
    public boolean ping() {
        if (!connected && !connect()) {
            return false;
        }
        
        try {
            JsonObject request = new JsonObject();
            request.addProperty("type", "ping");
            
            writer.println(gson.toJson(request));
            writer.flush();
            
            String response = reader.readLine();
            if (response != null) {
                JsonObject json = gson.fromJson(response, JsonObject.class);
                return json.has("success") && json.get("success").getAsBoolean();
            }
            return false;
            
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * 解析响应
     */
    private DetectionResult parseResponse(String response) {
        try {
            JsonObject json = gson.fromJson(response, JsonObject.class);
            
            DetectionResult result = new DetectionResult();
            result.setSuccess(json.has("success") && json.get("success").getAsBoolean());
            result.setTimestamp(json.has("timestamp") ? json.get("timestamp").getAsLong() : 0);
            result.setInferenceTimeMs(json.has("inference_time_ms") ? json.get("inference_time_ms").getAsDouble() : 0);
            result.setFps(json.has("fps") ? json.get("fps").getAsDouble() : 0);
            result.setFrameIndex(json.has("frame_index") ? json.get("frame_index").getAsInt() : 0);
            
            if (json.has("error")) {
                result.setError(json.get("error").getAsString());
            }
            
            // 解析检测到的牌
            List<CardInfo> cards = new ArrayList<>();
            if (json.has("cards")) {
                JsonArray cardsArray = json.getAsJsonArray("cards");
                for (int i = 0; i < cardsArray.size(); i++) {
                    JsonObject cardJson = cardsArray.get(i).getAsJsonObject();
                    CardInfo card = new CardInfo();
                    card.setLabel(cardJson.get("label").getAsString());
                    card.setConfidence(cardJson.get("confidence").getAsDouble());
                    
                    JsonArray bbox = cardJson.getAsJsonArray("bbox");
                    card.setBbox(new int[]{
                        bbox.get(0).getAsInt(),
                        bbox.get(1).getAsInt(),
                        bbox.get(2).getAsInt(),
                        bbox.get(3).getAsInt()
                    });
                    
                    cards.add(card);
                }
            }
            result.setCards(cards);
            
            return result;
            
        } catch (Exception e) {
            DetectionResult result = new DetectionResult();
            result.setSuccess(false);
            result.setError("解析响应失败: " + e.getMessage());
            return result;
        }
    }
    
    /**
     * 检查是否已连接
     */
    public boolean isConnected() {
        return connected && socket != null && socket.isConnected() && !socket.isClosed();
    }
    
    /**
     * 关闭客户端
     */
    public void shutdown() {
        disconnect();
        executor.shutdownNow();
    }
    
    // ==================== 数据类 ====================
    
    /**
     * 检测结果
     */
    public static class DetectionResult {
        private boolean success;
        private String error;
        private long timestamp;
        private double inferenceTimeMs;
        private double fps;
        private int frameIndex;
        private List<CardInfo> cards = new ArrayList<>();
        private String handType; // 牌型（如：顺子、同花等）
        
        // Getters and Setters
        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
        public String getError() { return error; }
        public void setError(String error) { this.error = error; }
        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
        public double getInferenceTimeMs() { return inferenceTimeMs; }
        public void setInferenceTimeMs(double inferenceTimeMs) { this.inferenceTimeMs = inferenceTimeMs; }
        public double getFps() { return fps; }
        public void setFps(double fps) { this.fps = fps; }
        public int getFrameIndex() { return frameIndex; }
        public void setFrameIndex(int frameIndex) { this.frameIndex = frameIndex; }
        public List<CardInfo> getCards() { return cards; }
        public void setCards(List<CardInfo> cards) { this.cards = cards; }
        public String getHandType() { return handType; }
        public void setHandType(String handType) { this.handType = handType; }
        
        /**
         * 获取检测到的牌的字符串表示
         */
        public String getCardsString() {
            if (cards == null || cards.isEmpty()) {
                return "无";
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < cards.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(cards.get(i).getLabel());
            }
            return sb.toString();
        }
        
        @Override
        public String toString() {
            return "DetectionResult{" +
                    "success=" + success +
                    ", cards=" + cards.size() +
                    ", fps=" + fps +
                    ", frameIndex=" + frameIndex +
                    '}';
        }
    }
    
    /**
     * 单张牌信息
     */
    public static class CardInfo {
        private String label;      // 牌面标签，如 "AS"(黑桃A), "KH"(红桃K)
        private double confidence; // 置信度 0-1
        private int[] bbox;        // 边界框 [x1, y1, x2, y2]
        
        // Getters and Setters
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public double getConfidence() { return confidence; }
        public void setConfidence(double confidence) { this.confidence = confidence; }
        public int[] getBbox() { return bbox; }
        public void setBbox(int[] bbox) { this.bbox = bbox; }
        
        /**
         * 获取中文牌面名称
         */
        public String getChineseName() {
            if (label == null) return "未知";
            
            // 解析花色和点数
            String suit = "";
            String rank = "";
            
            if (label.endsWith("S")) {
                suit = "黑桃";
                rank = label.substring(0, label.length() - 1);
            } else if (label.endsWith("H")) {
                suit = "红桃";
                rank = label.substring(0, label.length() - 1);
            } else if (label.endsWith("D")) {
                suit = "方块";
                rank = label.substring(0, label.length() - 1);
            } else if (label.endsWith("C")) {
                suit = "梅花";
                rank = label.substring(0, label.length() - 1);
            } else if (label.equals("JOKER_RED")) {
                return "大王";
            } else if (label.equals("JOKER_BLACK")) {
                return "小王";
            }
            
            // 转换点数
            switch (rank) {
                case "A": rank = "A"; break;
                case "J": rank = "J"; break;
                case "Q": rank = "Q"; break;
                case "K": rank = "K"; break;
            }
            
            return suit + rank;
        }
        
        @Override
        public String toString() {
            return label + "(" + String.format("%.1f%%", confidence * 100) + ")";
        }
    }
}
