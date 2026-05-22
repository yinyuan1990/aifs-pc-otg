package com.acard.acard.tools;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.acard.acard.ai.AIDetectorClient;
import com.acard.acard.ai.AIDetectorClient.DetectionResult;

/**
 * JPEG 文件清理器
 * 根据 CaptureDataManager 的有效索引范围，删除无用的 JPEG 文件
 */
public class JpegFileCleaner {
    
    private static final String DEFAULT_DIRECTORY = "runtime/captures/ssl";
    
    // ⭐ AI 检测开关
    private static volatile boolean aiDetectionEnabled =false;
    
    // ⭐ AI 检测跳帧（每 N 帧检测一次，1=每帧都检测）
    private static int aiDetectionInterval = 1;
    
    // ⭐ AI 帧计数器
    private static final java.util.concurrent.atomic.AtomicInteger aiFrameCounter = 
        new java.util.concurrent.atomic.AtomicInteger(0);
    private static final Pattern FILE_NAME_PATTERN = Pattern.compile("s_(\\d{9})\\.jpeg");


    // ⭐ 线程安全的计数器
    private static final java.util.concurrent.atomic.AtomicInteger lookCounter = new java.util.concurrent.atomic.AtomicInteger(0);
    
    // ⭐ 安全边界：保留最新的 N 帧不删除（避免误删正在抓拍的文件）
    private static final int SAFETY_MARGIN = 1000;  // 保留最新1000帧
    
    // ⭐ 清理任务执行器（单线程异步执行，避免卡顿）
    private static final java.util.concurrent.ExecutorService cleanupExecutor = 
        java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "JpegCleaner-Thread");
            t.setDaemon(true);  // 守护线程，不阻止程序退出
            return t;
        });
    
    // ⭐ 清理任务是否正在执行（防止重复触发）
    private static final java.util.concurrent.atomic.AtomicBoolean isCleaningInProgress = 
        new java.util.concurrent.atomic.AtomicBoolean(false);

    // ⭐ 是否暂停文件清理（用于 AI 训练时保留图片）
    private static volatile boolean cleaningPaused = false;  // 默认暂停，保留图片用于训练
    
    /**
     * ⭐ 设置是否暂停清理
     */
    public static void setCleaningPaused(boolean paused) {
        cleaningPaused = paused;
        System.out.println(paused ? "⏸️ 文件清理已暂停（保留图片用于 AI 训练）" : "▶️ 文件清理已恢复");
    }
    
    /**
     * ⭐ 添加一个观察点（每600次触发一次清理）
     * 
     * @param index 当前帧索引
     */
    public static void addLook(int index){
        // ⭐ AI 检测
        if (aiDetectionEnabled) {
            int aiCount = aiFrameCounter.incrementAndGet();
            if (aiCount >= aiDetectionInterval) {
                aiFrameCounter.set(0);
                detectCardAsync(index);
            }
        }

        // ⭐ 如果清理被暂停，跳过
        if (cleaningPaused) {
            return;
        }

        int count = lookCounter.incrementAndGet();  // ✅ 线程安全的自增
        
        if (count >= 600) {
            lookCounter.set(0);  // ✅ 重置计数器
            
            // ✅ 异步执行清理任务（不阻塞当前线程）
            if (isCleaningInProgress.compareAndSet(false, true)) {
                cleanupExecutor.submit(() -> {
                    try {
                        cleanDeleteFiles();
                    } finally {
                        isCleaningInProgress.set(false);
                    }
                });
            } else {
                System.out.println("⚠️ 清理任务正在执行中，跳过本次触发");
            }
        }
    }
    
    // ==================== AI 检测相关方法 ====================
    
    /**
     * ⭐ 启用 AI 检测
     * @param interval 检测间隔（每 N 帧检测一次，1=每帧都检测）
     */
    public static void enableAIDetection(int interval) {
        aiDetectionInterval = Math.max(1, interval);
        aiDetectionEnabled = true;
        aiFrameCounter.set(0);
        
        // 连接 AI 服务
        AIDetectorClient client = AIDetectorClient.getInstance();
        if (client.connect()) {
            System.out.println("✅ AI 检测已启用，间隔: " + aiDetectionInterval + " 帧");
        } else {
            System.err.println("❌ AI 服务连接失败，请确保 Python 服务已启动");
            aiDetectionEnabled = false;
        }
    }
    
    /**
     * ⭐ 禁用 AI 检测
     */
    public static void disableAIDetection() {
        aiDetectionEnabled = false;
        System.out.println("🛑 AI 检测已禁用");
    }
    
    /**
     * ⭐ AI 检测是否启用
     */
    public static boolean isAIDetectionEnabled() {
        return aiDetectionEnabled;
    }
    
    /**
     * ⭐ 异步检测扑克牌
     */
    private static void detectCardAsync(int index) {
        // 直接构建绝对路径，不做 I/O 检查，交给 Python 判断
        String fileName = String.format("s_%09d.jpeg", index);
        String absolutePath = new File(DEFAULT_DIRECTORY, fileName).getAbsolutePath();
        
        // 异步检测
        AIDetectorClient.getInstance().detectAsync(absolutePath, new AIDetectorClient.DetectionCallback() {
            @Override
            public void onResult(DetectionResult result) {
                // 打印检测结果
                int cardCount = result.getCards().size();
                if (cardCount > 0) {
                   LogTools.getInstance().logRecord3("[AI] 📸 帧 " + index + " | 检测到 " + cardCount + " 张牌: " +
                        result.getCardsString() + " | FPS: " + String.format("%.1f", result.getFps()));
                } else {
                    // 未检测到牌时可以选择不打印，减少日志量
                    // System.out.println("[AI] 📸 帧 " + index + " | 未检测到牌 | FPS: " + String.format("%.1f", result.getFps()));
                }
            }
            
            @Override
            public void onError(String error) {
                LogTools.getInstance().logRecord3("[AI] ❌ 帧 " + index + " 检测失败: " + error);
            }
        });
    }

    /**
     * ⭐ 清理无效文件（异步执行，线程安全）
     */
    private static void cleanDeleteFiles() {
        try {
            long startTime = System.currentTimeMillis();


            // ✅ 1. 直接使用实时索引，避免扫描目录
            long currentFrameId = FileToos.jpegIndex;

            if (currentFrameId <= SAFETY_MARGIN) {
                System.out.println("📁 文件数量较少，无需清理");
                return;
            }

            // ⭐ 2. 只检查最近 5000 帧（避免检查过多历史文件）
            long lowIndex = Math.max(1, currentFrameId - 5000);

            // ⭐ 3. 应用安全边界：不检查最新的 SAFETY_MARGIN 帧
            long safeEndIndex = currentFrameId - SAFETY_MARGIN;

            if (safeEndIndex <= lowIndex) {
                LogTools.getInstance().logRecord("✅ 所有文件都在安全边界内，无需清理");
                return;
            }

            LogTools.getInstance().logRecord("🧹 开始清理: 范围 [" + lowIndex + " - " + safeEndIndex + "], 保留最新 " + SAFETY_MARGIN + " 帧");
            // ⭐ 3. 批量清理（只检查安全范围内的文件）
            int deletedCount = 0;
            int checkedCount = 0;
            
            for (long i = lowIndex; i <= safeEndIndex; i++) {
                checkedCount++;
                boolean deleted = deleteIfInvalid((int) i);
                if (deleted) {
                    deletedCount++;
                }
                
                // ⭐ 每检查1000个索引，输出进度（避免刷屏）
                if (checkedCount % 1000 == 0) {
                    LogTools.getInstance().logRecord("📊 清理进度: " + checkedCount + " / " + (safeEndIndex - lowIndex + 1));
                }
            }
            
            long elapsed = System.currentTimeMillis() - startTime;
            LogTools.getInstance().logRecord("✅ 清理完成: 检查 " + checkedCount + " 个索引, 删除 " + deletedCount + " 个文件, 耗时 " + elapsed + "ms");
            
        } catch (Exception e) {
            LogTools.getInstance().logRecord("❌ 清理失败: " + e.getMessage());
            e.printStackTrace();
        }
    }


    /**
     * ⭐ 删除指定索引对应的 JPEG 文件（如果该索引无效）
     * 
     * @param index 帧索引
     * @return true=已删除，false=保留或删除失败
     */
    public static boolean deleteIfInvalid(int index) {
        return deleteIfInvalid(index, DEFAULT_DIRECTORY);
    }
    
    /**
     * ⭐ 删除指定索引对应的 JPEG 文件（如果该索引无效）
     * 
     * @param index 帧索引
     * @param directoryPath 目录路径
     * @return true=已删除，false=保留或删除失败
     */
    public static boolean deleteIfInvalid(int index, String directoryPath) {
        // 1. 判断索引是否有效
        boolean isValid = CaptureDataManager.getInstance().isIndexValid(index);
        
        if (isValid) {
            // ✅ 索引有效，不删除
            return false;
        }
        
        // 2. 索引无效，构造文件路径并删除
        String fileName = String.format("s_%09d.jpeg", index);
        Path filePath = Paths.get(directoryPath, fileName);
        
        try {
            // 3. 删除文件（如果不存在会抛出异常，捕获后返回false）
            Files.deleteIfExists(filePath);
            System.out.println("🗑️ 删除无效文件: " + fileName + " (索引: " + index + ")");
            return true;
        } catch (Exception e) {
            // 文件不存在或删除失败
            return false;
        }
    }


    public static boolean deleteNoUse(int index) {

        String fileName = String.format("s_%09d.jpeg", index);
        Path filePath = Paths.get(DEFAULT_DIRECTORY, fileName);
        try {
            // 3. 删除文件（如果不存在会抛出异常，捕获后返回false）
            Files.deleteIfExists(filePath);
            System.out.println("🗑️ 删除无效文件: " + fileName + " (索引: " + index + ")");
            return true;
        } catch (Exception e) {
            // 文件不存在或删除失败
            return false;
        }
    }
    /**
     * 清理默认目录下的无效 JPEG 文件
     * 
     * @return 清理结果统计
     */
    public static CleanupResult cleanupDefaultDirectory() {
        return cleanup(DEFAULT_DIRECTORY);
    }
    
    /**
     * 清理指定目录下的无效 JPEG 文件
     * 
     * @param directoryPath 目录路径（例如: "runtime/captures/ssl"）
     * @return 清理结果统计
     */
    public static CleanupResult cleanup(String directoryPath) {
        File directory = new File(directoryPath);
        
        if (!directory.exists()) {
            System.out.println("⚠️ 目录不存在: " + directoryPath);
            return new CleanupResult(0, 0, 0);
        }
        
        if (!directory.isDirectory()) {
            System.err.println("❌ 路径不是目录: " + directoryPath);
            return new CleanupResult(0, 0, 0);
        }
        
        File[] files = directory.listFiles();
        if (files == null || files.length == 0) {
            System.out.println("📁 目录为空: " + directoryPath);
            return new CleanupResult(0, 0, 0);
        }
        
        CaptureDataManager manager = CaptureDataManager.getInstance();
        
        int totalFiles = 0;
        int deletedFiles = 0;
        int keptFiles = 0;
        List<String> deletedFileNames = new ArrayList<>();
        
        System.out.println("🚀 开始清理 JPEG 文件: " + directoryPath);
        System.out.println("📊 总文件数: " + files.length);
        
        for (File file : files) {
            if (!file.isFile()) {
                continue;
            }
            
            String fileName = file.getName();
            
            // 匹配文件名格式: s_000000123.jpeg
            Matcher matcher = FILE_NAME_PATTERN.matcher(fileName);
            if (!matcher.matches()) {
                // 不匹配命名规则的文件跳过
                continue;
            }
            
            totalFiles++;
            
            try {
                // 提取索引（例如: s_000000123.jpeg → 123）
                String indexStr = matcher.group(1);
                int index = Integer.parseInt(indexStr);
                
                // 判断索引是否有效
                boolean isValid = manager.isIndexValid(index);
                
                if (isValid) {
                    // ✅ 有效索引，保留文件
                    keptFiles++;
                    // System.out.println("✅ 保留: " + fileName + " (索引: " + index + ")");
                } else {
                    // ❌ 无效索引，删除文件
                    boolean deleted = file.delete();
                    if (deleted) {
                        deletedFiles++;
                        deletedFileNames.add(fileName);
                        System.out.println("🗑️ 删除: " + fileName + " (索引: " + index + ")");
                    } else {
                        System.err.println("⚠️ 删除失败: " + fileName);
                    }
                }
                
            } catch (NumberFormatException e) {
                System.err.println("⚠️ 解析索引失败: " + fileName + " - " + e.getMessage());
            }
        }
        
        System.out.println("✅ 清理完成！");
        System.out.println("📊 统计:");
        System.out.println("   - 总计扫描: " + totalFiles + " 个文件");
        System.out.println("   - 保留文件: " + keptFiles + " 个");
        System.out.println("   - 删除文件: " + deletedFiles + " 个");
        if (deletedFiles > 0) {
            long freedSpace = deletedFiles * 50; // 假设平均每个文件 50KB
            System.out.println("   - 释放空间: ~" + freedSpace + " KB");
        }
        
        return new CleanupResult(totalFiles, keptFiles, deletedFiles, deletedFileNames);
    }
    
    /**
     * 清理结果统计
     */
    public static class CleanupResult {
        private final int totalFiles;
        private final int keptFiles;
        private final int deletedFiles;
        private final List<String> deletedFileNames;
        
        public CleanupResult(int totalFiles, int keptFiles, int deletedFiles) {
            this(totalFiles, keptFiles, deletedFiles, new ArrayList<>());
        }
        
        public CleanupResult(int totalFiles, int keptFiles, int deletedFiles, List<String> deletedFileNames) {
            this.totalFiles = totalFiles;
            this.keptFiles = keptFiles;
            this.deletedFiles = deletedFiles;
            this.deletedFileNames = deletedFileNames;
        }
        
        public int getTotalFiles() {
            return totalFiles;
        }
        
        public int getKeptFiles() {
            return keptFiles;
        }
        
        public int getDeletedFiles() {
            return deletedFiles;
        }
        
        public List<String> getDeletedFileNames() {
            return new ArrayList<>(deletedFileNames);
        }
        
        @Override
        public String toString() {
            return String.format("CleanupResult{total=%d, kept=%d, deleted=%d}", 
                totalFiles, keptFiles, deletedFiles);
        }
    }
    
    /**
     * 测试方法
     */
    public static void main(String[] args) {
        // 示例：添加一些测试数据到 CaptureDataManager
        CaptureDataManager manager = CaptureDataManager.getInstance();
        
        // 假设有2个抓拍事件：
        // 事件1: 索引 10-50
        com.acard.acard.model.CaptureData data1 = new com.acard.acard.model.CaptureData();
        data1.setStartIndex(10);
        data1.setEndIndex(50);
        manager.put("event1", data1);
        
        // 事件2: 索引 60-100
        com.acard.acard.model.CaptureData data2 = new com.acard.acard.model.CaptureData();
        data2.setStartIndex(60);
        data2.setEndIndex(100);
        manager.put("event2", data2);
        
        System.out.println("📋 有效区间: " + manager.getValidRanges());
        System.out.println();
        
        // ⭐ 方法1：单个索引删除测试
        System.out.println("=== 测试单个索引删除 ===");
        boolean deleted1 = JpegFileCleaner.deleteIfInvalid(5);    // 在有效区间外 → 应该删除
        boolean deleted2 = JpegFileCleaner.deleteIfInvalid(25);   // 在有效区间内 → 不删除
        boolean deleted3 = JpegFileCleaner.deleteIfInvalid(55);   // 在有效区间外 → 应该删除
        boolean deleted4 = JpegFileCleaner.deleteIfInvalid(70);   // 在有效区间内 → 不删除
        System.out.println("索引5删除: " + deleted1);
        System.out.println("索引25删除: " + deleted2);
        System.out.println("索引55删除: " + deleted3);
        System.out.println("索引70删除: " + deleted4);
        System.out.println();
        
        // ⭐ 方法2：批量清理
        System.out.println("=== 批量清理整个目录 ===");
        CleanupResult result = JpegFileCleaner.cleanupDefaultDirectory();
        System.out.println("📊 最终结果: " + result);
    }
}

