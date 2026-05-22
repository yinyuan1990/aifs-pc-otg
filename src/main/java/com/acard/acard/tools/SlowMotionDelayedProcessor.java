package com.acard.acard.tools;

import com.acard.acard.capture.CaptureEvent;
import com.acard.acard.capture.CaptureSession;
import com.acard.acard.capture.DiskCaptureCache;
import javafx.application.Platform;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;

/**
 * 慢放后续帧延迟处理器
 * 使用延迟队列机制，1秒后批量处理所有后续帧，不卡UI
 */
public class SlowMotionDelayedProcessor {

    // 线程安全的事件列表
    private final CopyOnWriteArrayList<CaptureEvent> slowList = new CopyOnWriteArrayList<>();

    // 延迟任务执行器
    private final ScheduledExecutorService delayedExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "SlowMotion-Delayed-Processor");
        t.setDaemon(true);
        return t;
    });

    // 后台处理线程池
    private final ScheduledExecutorService backgroundExecutor = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "SlowMotion-Background-Worker");
        t.setDaemon(true);
        return t;
    });

    // 单例
    private static final SlowMotionDelayedProcessor INSTANCE = new SlowMotionDelayedProcessor();

    private SlowMotionDelayedProcessor() {
    }

    public static SlowMotionDelayedProcessor getInstance() {
        return INSTANCE;
    }

    /**
     * 添加慢放事件到延迟处理队列
     * 1秒后自动处理
     */
    public void addSlowEvent(CaptureEvent event) {
        if (event == null || event.getType() != CaptureEvent.Type.SLOWMOTION) {
            return;
        }

        slowList.add(event);
        System.out.println("📌 添加慢放事件到延迟队列: " + event.getEventId().substring(0, 8) +
                ", 需要后续帧: " + event.getPostFrameCount() + ", 1秒后开始处理");
        // 安排1秒后处理这个事件
        scheduleDelayedProcessing(event);
    }

    /**
     * 安排延迟处理任务
     */
    private void scheduleDelayedProcessing(CaptureEvent event) {
        delayedExecutor.schedule(() -> {
            // 检查事件是否还在列表中（可能已被取消）
            if (!slowList.contains(event)) {
                System.out.println("⏭️ 事件已被取消，跳过处理: " + event.getEventId().substring(0, 8));
                return;
            }

            System.out.println("⏰ 开始延迟处理慢放事件: " + event.getEventId().substring(0, 8));

            // 在后台线程中处理，避免阻塞延迟调度器
            CompletableFuture.runAsync(() -> {
                processSlowEventBatch(event);
            }, backgroundExecutor);

        }, 1, TimeUnit.SECONDS); // 1秒延迟
    }


    /**
     * 批量处理慢放事件的所有后续帧
     * @param event 慢放事件
     */
    private void processSlowEventBatch(CaptureEvent event) {
        try {
            System.out.println("🚀 开始批量处理后续帧: " + event.getEventId().substring(0, 8));

            // 检查事件状态
            if (event.isCompleted()) {
                removeEventFromList(event);
                return;
            }

            // 批量获取所有后续帧（最大60帧）
            List<DiskCaptureCache.DiskFrameItem> allPostFrames = batchGetAllPostFrames(event);

            if (allPostFrames.isEmpty()) {
                System.out.println("⚠️ 未找到任何后续帧: " + event.getEventId().substring(0, 8));
                removeEventFromList(event);
                return;
            }

            System.out.println("✅ 批量获取到 " + allPostFrames.size() + " 个后续帧");

            // 批量推送到UI（在JavaFX线程中）
            batchPushFramesToUI(event, allPostFrames);

            // 标记事件完成并移除
            removeEventFromList(event);

            System.out.println("🎉 慢放事件批量处理完成: " + event.getEventId().substring(0, 8) +
                    ", 处理了 " + allPostFrames.size() + " 帧");

        } catch (Throwable e) {
            System.err.println("❌ 批量处理慢放事件失败: " + event.getEventId().substring(0, 8) +
                    ", 错误: " + e.getMessage());
            e.printStackTrace();
            removeEventFromList(event);
        }
    }

    /**
     * 批量获取所有后续帧（最大60帧）
     */
    private List<DiskCaptureCache.DiskFrameItem> batchGetAllPostFrames(CaptureEvent event) {
        List<DiskCaptureCache.DiskFrameItem> sourceFrames = new ArrayList<>();

        try {
            File slowDir = new File("runtime/captures/slow");
            if (!slowDir.exists()) {
                System.out.println("⚠️ 慢放目录不存在: " + slowDir.getAbsolutePath());
                return sourceFrames;
            }

            CaptureSession session = event.getSession();
            if (session == null) {
                System.err.println("❌ 事件会话为null");
                return sourceFrames;
            }

            // 计算需要的帧范围
            long startFrameId = event.getEventFrameId() + 1; // 从事件帧的下一帧开始
            int maxFrames = Math.min(event.getPostFrameCount(), 60); // 最大60帧
            long endFrameId = startFrameId + maxFrames - 1;

            System.out.println("📋 批量获取帧范围: " + startFrameId + " ~ " + endFrameId +
                    " (最大" + maxFrames + "帧)");

            // 批量检查和处理文件，先收集源帧信息
            for (long frameId = startFrameId; frameId <= endFrameId; frameId++) {
                String fileName = String.format("s_%09d.jpeg", frameId);
                File sourceFile = new File(slowDir, fileName);

                if (!sourceFile.exists()) {
                    System.out.println("⏭️ 帧文件不存在，跳过: " + fileName);
                    continue;
                }

                try {
                    // 读取图片尺寸
                    int width = FileToos.slowWidth;
                    int height = FileToos.slowHight;

                    // 创建源帧信息
                    DiskCaptureCache.DiskFrameItem sourceFrame = new DiskCaptureCache.DiskFrameItem(
                            sourceFile.getAbsolutePath(),
                            frameId,
                            width,
                            height,
                            "jpeg",
                            frameId
                    );

                    sourceFrames.add(sourceFrame);
                    System.out.println("✅ 收集源帧: " + fileName + " [" +
                            sourceFrames.size() + "/" + maxFrames + "]");

                } catch (Exception e) {
                    System.err.println("❌ 处理帧文件失败: " + fileName + ", 错误: " + e.getMessage());
                }
            }

            // 使用copyFramesFromCache方法复制文件到会话目录
            if (!sourceFrames.isEmpty()) {
                return session.copyFramesFromCache2(sourceFrames, 1, "post_");
            }

        } catch (Throwable e) {
            System.err.println("❌ 批量获取后续帧异常: " + e.getMessage());
            e.printStackTrace();
        }

        return sourceFrames;
    }

    /**
     * 推送帧到UI（在JavaFX线程中执行）
     */
    private void batchPushFramesToUI(CaptureEvent event, List<DiskCaptureCache.DiskFrameItem> frames) {
        if (frames.isEmpty()) {
            return;
        }

        // 获取事件的专属回调
        java.util.function.BiConsumer<DiskCaptureCache.DiskFrameItem, String> callback = event.getCallback();
        if (callback == null) {
            System.err.println("⚠️ 事件回调为null: " + event.getEventId().substring(0, 8));
            return;
        }

        String sessionId = event.getSessionId();

        // 直接在JavaFX线程中推送所有帧
        Platform.runLater(() -> {
            try {
                System.out.println("📞 推送所有帧到UI (" + frames.size() + "帧) → " + sessionId);

                // 方法1：使用索引循环（推荐）
                for (int i = 0; i < frames.size(); i++) {
                    DiskCaptureCache.DiskFrameItem frame = frames.get(i);
                    boolean isLastFrame = (i == frames.size() - 1);
                    if (isLastFrame) {
                        frame.isEnd=true;
                    }
                    callback.accept(frame, sessionId);
                    

                }

                System.out.println("🎉 所有帧已推送到UI完成: " + sessionId + " (总计" + frames.size() + "帧)");

            } catch (Throwable e) {
                System.err.println("❌ UI回调失败: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    /**
     * 从列表中移除事件
     */
    private void removeEventFromList(CaptureEvent event) {
        boolean removed = slowList.remove(event);
        if (removed) {
            System.out.println("🗑️ 事件已从延迟队列移除: " + event.getEventId().substring(0, 8) +
                    ", 剩余事件: " + slowList.size());
        }
    }
}
