package com.acard.acard.tools;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 图片上传队列管理器（单例模式）
 * 
 * 生产者-消费者模式：
 * - 生产者：抓拍、查看上下帧时将图片路径入队
 * - 消费者：后台线程异步上传图片到服务器
 * 
 * 特点：
 * - 不阻塞主线程，不影响抓拍功能
 * - 自动重试失败的上传
 * - 支持队列大小限制，防止内存溢出
 */
public class ImageUploadQueue {
    
    private static final String UPLOAD_URL = "http://171.80.4.72:9990/api/upload";
    private static final int MAX_QUEUE_SIZE = 1000;           // 队列最大容量
    private static final int MAX_RETRY_COUNT = 3;             // 最大重试次数
    private static final int UPLOAD_TIMEOUT_SECONDS = 30;     // 上传超时时间
    private static final int CONSUMER_THREAD_COUNT = 2;       // 消费者线程数
    
    private static volatile ImageUploadQueue instance;
    
    private final BlockingQueue<UploadTask> uploadQueue;
    private final ExecutorService consumerExecutor;
    private final HttpClient httpClient;
    private final AtomicBoolean isRunning;
    
    // ⚡ 去重集合：记录已入队或已上传的文件路径
    private final Set<String> processedFiles = ConcurrentHashMap.newKeySet();
    
    // 统计信息
    private volatile long totalEnqueued = 0;
    private volatile long totalUploaded = 0;
    private volatile long totalFailed = 0;
    private volatile long totalSkipped = 0;  // 跳过的重复文件数
    
    /**
     * 上传任务
     */
    public static class UploadTask {
        private final String filePath;
        private final String source;      // 来源：CAPTURE（抓拍）、FRAME_VIEW（查看上下帧）
        private final long timestamp;
        private int retryCount = 0;
        
        public UploadTask(String filePath, String source) {
            this.filePath = filePath;
            this.source = source;
            this.timestamp = System.currentTimeMillis();
        }
        
        public String getFilePath() { return filePath; }
        public String getSource() { return source; }
        public long getTimestamp() { return timestamp; }
        public int getRetryCount() { return retryCount; }
        public void incrementRetry() { retryCount++; }
    }
    
    private ImageUploadQueue() {
        this.uploadQueue = new LinkedBlockingQueue<>(MAX_QUEUE_SIZE);
        this.consumerExecutor = Executors.newFixedThreadPool(CONSUMER_THREAD_COUNT);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.isRunning = new AtomicBoolean(true);
        
        // 启动消费者线程
        for (int i = 0; i < CONSUMER_THREAD_COUNT; i++) {
            consumerExecutor.submit(this::consumeLoop);
        }
        
        LogTools.getInstance().logRecord3("📤 ImageUploadQueue 已启动，消费者线程数: " + CONSUMER_THREAD_COUNT);
    }
    
    public static ImageUploadQueue getInstance() {
        if (instance == null) {
            synchronized (ImageUploadQueue.class) {
                if (instance == null) {
                    instance = new ImageUploadQueue();
                }
            }
        }
        return instance;
    }
    
    /**
     * 将图片路径加入上传队列（抓拍时调用）
     * @param filePath 图片文件路径
     */
    public void enqueueCapture(String filePath) {
        enqueue(filePath, "CAPTURE");
    }
    
    /**
     * 将图片路径加入上传队列（查看上下帧时调用）
     * @param filePath 图片文件路径
     */
    public void enqueueFrameView(String filePath) {
        enqueue(filePath, "FRAME_VIEW");
    }
    
    /**
     * 将图片路径加入上传队列
     * @param filePath 图片文件路径
     * @param source 来源标识
     */
    public void enqueue(String filePath, String source) {
        if (filePath == null || filePath.isEmpty()) {
            return;
        }
        
        // ⚡ 去重检查：如果文件已经入队或上传过，跳过
        if (!processedFiles.add(filePath)) {
            totalSkipped++;
            LogTools.getInstance().logRecord3("⏭️ 文件已入队，跳过重复: " + filePath);
            return;
        }
        
        // 检查文件是否存在
        File file = new File(filePath);
        if (!file.exists() || !file.isFile()) {
            processedFiles.remove(filePath);  // 移除无效的记录
            LogTools.getInstance().logRecord3("⚠️ 文件不存在，跳过入队: " + filePath);
            return;
        }
        
        UploadTask task = new UploadTask(filePath, source);
        boolean offered = uploadQueue.offer(task);
        
        if (offered) {
            totalEnqueued++;
            LogTools.getInstance().logRecord3("📥 图片入队成功 [" + source + "]: " + filePath + " (队列大小: " + uploadQueue.size() + ")");
        } else {
            LogTools.getInstance().logRecord3("⚠️ 队列已满，图片入队失败: " + filePath);
        }
    }
    
    /**
     * 消费者循环
     */
    private void consumeLoop() {
        while (isRunning.get()) {
            try {
                // 阻塞等待任务
                UploadTask task = uploadQueue.take();
                
                // 执行上传
                boolean success = uploadFile(task);
                
                if (success) {
                    totalUploaded++;
                    LogTools.getInstance().logRecord3("✅ 上传成功 [" + task.getSource() + "]: " + task.getFilePath());
                } else {
                    // 重试逻辑
                    if (task.getRetryCount() < MAX_RETRY_COUNT) {
                        task.incrementRetry();
                        uploadQueue.offer(task);  // 重新入队
                        LogTools.getInstance().logRecord3("🔄 上传失败，重新入队 (重试 " + task.getRetryCount() + "/" + MAX_RETRY_COUNT + "): " + task.getFilePath());
                    } else {
                        totalFailed++;
                        LogTools.getInstance().logRecord3("❌ 上传失败，已达最大重试次数: " + task.getFilePath());
                    }
                }
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LogTools.getInstance().logRecord3("❌ 消费者异常: " + e.getMessage());
            }
        }
    }
    
    /**
     * 上传文件到服务器
     * @param task 上传任务
     * @return 是否成功
     */
    private boolean uploadFile(UploadTask task) {
        try {
            File file = new File(task.getFilePath());
            if (!file.exists()) {
                LogTools.getInstance().logRecord3("⚠️ 文件已不存在: " + task.getFilePath());
                return true;  // 文件不存在视为"成功"，不再重试
            }
            
            // 构建 multipart/form-data 请求
            String boundary = UUID.randomUUID().toString();
            byte[] fileBytes = Files.readAllBytes(Path.of(task.getFilePath()));
            
            // 构建请求体
            String fileName = file.getName();
            byte[] requestBody = buildMultipartBody(boundary, "file", fileName, fileBytes);
            
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(UPLOAD_URL))
                    .timeout(Duration.ofSeconds(UPLOAD_TIMEOUT_SECONDS))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody))
                    .build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                return true;
            } else {
                LogTools.getInstance().logRecord3("⚠️ 上传响应异常: " + response.statusCode() + " - " + response.body());
                return false;
            }
            
        } catch (IOException | InterruptedException e) {
            LogTools.getInstance().logRecord3("❌ 上传异常: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 构建 multipart/form-data 请求体
     */
    private byte[] buildMultipartBody(String boundary, String fieldName, String fileName, byte[] fileBytes) throws IOException {
        String lineSeparator = "\r\n";
        String contentDisposition = "Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\"" + fileName + "\"";
        String contentType = "Content-Type: " + getMimeType(fileName);
        
        StringBuilder header = new StringBuilder();
        header.append("--").append(boundary).append(lineSeparator);
        header.append(contentDisposition).append(lineSeparator);
        header.append(contentType).append(lineSeparator);
        header.append(lineSeparator);
        
        String footer = lineSeparator + "--" + boundary + "--" + lineSeparator;
        
        byte[] headerBytes = header.toString().getBytes();
        byte[] footerBytes = footer.getBytes();
        
        byte[] result = new byte[headerBytes.length + fileBytes.length + footerBytes.length];
        System.arraycopy(headerBytes, 0, result, 0, headerBytes.length);
        System.arraycopy(fileBytes, 0, result, headerBytes.length, fileBytes.length);
        System.arraycopy(footerBytes, 0, result, headerBytes.length + fileBytes.length, footerBytes.length);
        
        return result;
    }
    
    /**
     * 获取文件的 MIME 类型
     */
    private String getMimeType(String fileName) {
        if (fileName.toLowerCase().endsWith(".jpg") || fileName.toLowerCase().endsWith(".jpeg")) {
            return "image/jpeg";
        } else if (fileName.toLowerCase().endsWith(".png")) {
            return "image/png";
        } else if (fileName.toLowerCase().endsWith(".gif")) {
            return "image/gif";
        }
        return "application/octet-stream";
    }
    
    /**
     * 获取队列当前大小
     */
    public int getQueueSize() {
        return uploadQueue.size();
    }
    
    /**
     * 获取统计信息
     */
    public String getStats() {
        return String.format("入队: %d, 已上传: %d, 失败: %d, 跳过重复: %d, 待上传: %d", 
                totalEnqueued, totalUploaded, totalFailed, totalSkipped, uploadQueue.size());
    }
    
    /**
     * 关闭上传队列
     */
    public void shutdown() {
        isRunning.set(false);
        consumerExecutor.shutdownNow();
        LogTools.getInstance().logRecord3("📤 ImageUploadQueue 已关闭，最终统计: " + getStats());
    }
}

