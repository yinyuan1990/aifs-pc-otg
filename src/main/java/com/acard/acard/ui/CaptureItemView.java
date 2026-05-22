package com.acard.acard.ui;

import com.acard.acard.DiskFrameRingBuffer;
import com.acard.acard.FrameRingBuffer;
import com.acard.acard.capture.DiskCaptureCache;
import com.acard.acard.events.UIUpdateEvent;
import com.acard.acard.events.UIUpdateEventManager;
import com.acard.acard.storage.SlowmoStore;
import com.acard.acard.store.ShortcutStore;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.layout.StackPane;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.stage.Popup;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;

import java.util.List;

/**
 * 简化的抓拍条目视图：内部复用 SnapshotPlayerView 显示一组帧。
 * 后续可扩展标题、操作按钮等。
 */
public class CaptureItemView extends StackPane {
    private final SnapshotPlayerView inner = new SnapshotPlayerView();
    // ⭐ 删除回调接口
    private Runnable onDeleteCallback = null;
    // ⭐ 格子在列表中的索引（用于全屏查看时同步抓拍）
    private int itemIndex = -1;

    public List<DiskCaptureCache.DiskFrameItem> getDiskFramesV2() {
        return inner.getDiskFramesV2();
    }



    // ⭐ 新增：获取当前帧索引
    public int getCurrentFrameIndex() {
        return inner.getCurrentFrameIndex();
    }
    
    // ⭐ 设置格子在列表中的索引
    public void setItemIndex(int index) {
        this.itemIndex = index;
    }
    
    // ⭐ 获取格子在列表中的索引
    public int getItemIndex() {
        return this.itemIndex;
    }
    
    // ⭐ 偏移同步代理方法
    public int getEventFrameId() {
        return inner.getEventFrameId();
    }
    
    public boolean hasAppliedOffset() {
        return inner.hasAppliedOffset();
    }
    
    public void applyOffset(int offset) {
        inner.applyOffset(offset);
    }

// 确保在播放时更新 currentFrameIndex
// 如果 CaptureItemView 有播放逻辑，需要在切换帧时更新这个值


    public void OnMouseEntered(){
        if(inner!=null)
             inner.OnMouseEntered();

    }

    public void OnMouseExited(){
        if(inner!=null)
            inner.OnMouseExited();
    }

    private final UIUpdateEventManager eventManager = UIUpdateEventManager.getInstance();
    private final String listenerId = "dis_" + System.currentTimeMillis();

    private volatile boolean eventListenersRegistered = false;

    public void handleUIUpdateEvent(UIUpdateEvent event) {
          // ✅ 清空抓拍时，关闭全局全屏播放器
          if(globalFullscreenPopup != null && globalFullscreenPopup.isShowing()){
              globalFullscreenPopup.hide();
          }
    }

    public void registerUIUpdateEvents() {

        try {
            // 注册强制刷新事件
            eventManager.registerListener(UIUpdateEvent.EventType.CleanAllEvent,
                    this::handleUIUpdateEvent, listenerId);

        } catch (Exception e) {
            System.err.println("❌ 注册UI更新事件监听器失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 注销UI更新事件监听器
     */
    public void unregisterUIUpdateEvents() {

        try {
            // 注销所有该监听器ID的事件
            eventManager.unregisterAllListeners(listenerId);

            eventListenersRegistered = false;

        } catch (Exception e) {
            System.err.println("❌ 注销UI更新事件监听器失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
    public CaptureItemView() {


         // ⭐ 设置深色背景
        setStyle("-fx-background-color: #292929;");
        
        // ⭐ 不设置内边距（CaptureItemView的2px边框与Placeholder的2px边框一致）
        Insets padding = new Insets(-3);
        StackPane.setMargin(inner, padding);
        
        // 使内部播放器填充父容器并随父容器尺寸变化
        // ⭐ 注意：setMargin 已经会自动缩小 inner，不需要再 subtract
        inner.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        inner.prefWidthProperty().bind(widthProperty());
        inner.prefHeightProperty().bind(heightProperty());
        
        // 监听自身尺寸变化，通知内部播放器视口大小（setMargin 后 inner 的实际大小已经自动减去了内边距）
        widthProperty().addListener((o, ov, nv) -> inner.setViewportSize(getWidth(), getHeight()));
        heightProperty().addListener((o, ov, nv) -> inner.setViewportSize(getWidth(), getHeight()));
        
        // ⭐ 设置内部播放器的删除回调（向上传递给外部）
        inner.setOnDelete(() -> {
            if (onDeleteCallback != null) {
                onDeleteCallback.run();
            }
        });

        getChildren().add(inner);
        
        // ⭐ 设置可获得焦点（用于接收键盘事件）
        this.setFocusTraversable(true);
        
        // ⭐ 添加键盘事件：按快捷键打开全屏查看
        this.setOnKeyPressed(event -> {
            // 读取配置的全屏查看快捷键
            KeyCode fullscreenViewerKey = ShortcutStore.getInstance().getFullscreenViewerKey();
            if (event.getCode() == fullscreenViewerKey) {
                // 按下全屏查看快捷键（默认 K）
                openFullscreenViewer();
                event.consume();
            }
        });
        
        // ⭐ 点击时获得焦点（确保能接收键盘事件）
        this.setOnMousePressed(event -> {
            this.requestFocus();
        });

        registerUIUpdateEvents();


    }

    /**
     * 锁定该条目的视口大小（后续所有帧按此尺寸适配）。
     */
    public void lockViewportSize(double w, double h) {
        if (Platform.isFxApplicationThread()) {
            inner.lockViewportSize(w, h);
        } else {
            Platform.runLater(() -> inner.lockViewportSize(w, h));
        }
    }
    
    /**
     * 更新锁定的视口尺寸（不解锁，避免触发监听器循环）
     */
    public void updateLockedViewportSize(double w, double h) {
        if (Platform.isFxApplicationThread()) {
            inner.updateLockedViewportSize(w, h);
        } else {
            Platform.runLater(() -> inner.updateLockedViewportSize(w, h));
        }
    }

    public void setDiskFrames(List<DiskFrameRingBuffer.FrameItem> frames, int eventIndex) {
        if (Platform.isFxApplicationThread()) {
            inner.setDiskFrames(frames, eventIndex);
        } else {
            Platform.runLater(() -> inner.setDiskFrames(frames, eventIndex));
        }
    }
    
    /** ✅ 设置磁盘帧（新格式：DiskCaptureCache.DiskFrameItem，零内存） */
    public void setDiskFramesV2(List<DiskCaptureCache.DiskFrameItem> frames, int eventIndex) {
        if (Platform.isFxApplicationThread()) {
            inner.setDiskFramesV2(frames, eventIndex);
        } else {
            Platform.runLater(() -> inner.setDiskFramesV2(frames, eventIndex));
        }
    }
    
    /**
     * ✅ 追加单个帧到磁盘帧列表（用于事件驱动的后续帧追加）
     */
    public void appendDiskFrameV2(DiskCaptureCache.DiskFrameItem newFrame) {
        if (Platform.isFxApplicationThread()) {
            inner.appendDiskFrameV2(newFrame);
        } else {
            Platform.runLater(() -> inner.appendDiskFrameV2(newFrame));
        }
    }

    public void setMemoryFrames(List<FrameRingBuffer.FrameItem> frames, int eventIndex) {
        if (Platform.isFxApplicationThread()) {
            inner.setMemoryFrames(frames, eventIndex);
        } else {
            Platform.runLater(() -> inner.setMemoryFrames(frames, eventIndex));
        }
    }
    
    /**
     * 动态添加内存帧到现有抓拍项（异步推送用）
     */
    public void appendMemoryFrames(List<FrameRingBuffer.FrameItem> newFrames) {
        if (Platform.isFxApplicationThread()) {
            inner.appendMemoryFrames(newFrames);
        } else {
            Platform.runLater(() -> inner.appendMemoryFrames(newFrames));
        }
    }
    
    /**
     * 动态添加磁盘帧到现有抓拍项（慢放异步推送用）
     */
    public void appendDiskFrames(List<DiskFrameRingBuffer.FrameItem> newFrames) {
        if (Platform.isFxApplicationThread()) {
            inner.appendDiskFrames(newFrames);
        } else {
            Platform.runLater(() -> inner.appendDiskFrames(newFrames));
        }
    }
    
    /**
     * ✅ 清理所有缓存，释放内存（在移除CaptureItemView时调用）
     */
    public void cleanup() {

        inner.cleanup();
        unregisterUIUpdateEvents();
    }
    
    /**
     * ⭐ 设置删除按钮的回调（当用户点击删除按钮时调用）
     * @param callback 删除回调函数
     */
    public void setOnDelete(Runnable callback) {
        this.onDeleteCallback = callback;
    }
    


    // ✅ 全局单例 Popup（所有 CaptureItemView 共享，避免创建多个导致内存爆炸）
    private static Popup globalFullscreenPopup = null;
    private static SnapshotPlayerView globalFullscreenPlayer = null;
    
    // ⭐ 记录当前全屏查看的格子（用于同步帧索引）
    private static CaptureItemView currentFullscreenItem = null;
    
    /**
     * ⭐ 检查抓拍全屏查看器是否正在显示
     */
    public static boolean isFullscreenViewerShowing() {
        return globalFullscreenPopup != null && globalFullscreenPopup.isShowing();
    }
    
    /**
     * ⭐ 获取当前全屏查看的格子索引（-1 表示无全屏查看）
     */
    public static int getFullscreenItemIndex() {
        if (isFullscreenViewerShowing() && currentFullscreenItem != null) {
            return currentFullscreenItem.getItemIndex();
        }
        return -1;
    }
    
    /**
     * ⭐ 关闭抓拍全屏查看器（供外部调用）
     * 注：帧索引同步在 setOnHidden 回调中自动处理
     */
    public static void closeFullscreenViewer() {
        if (globalFullscreenPopup != null && globalFullscreenPopup.isShowing()) {
            globalFullscreenPopup.hide();  // hide 触发 setOnHidden 回调，自动同步
            System.out.println("✅ 已关闭抓拍全屏查看器");
        }
    }
    
    /**
     * ⭐ 同步全屏查看的帧索引回原抓拍格子
     */
    private static void syncFrameIndexToItem() {
        if (currentFullscreenItem != null && globalFullscreenPlayer != null) {
            int fullscreenIndex = globalFullscreenPlayer.getCurrentFrameIndex();
            currentFullscreenItem.inner.setCurrentFrameIndex(fullscreenIndex);
            System.out.println("📌 同步帧索引: 全屏=" + fullscreenIndex + " → item");
        }
    }
    
    // ✅ 全局遮罩层引用（用于动态更新大小）
    private static StackPane globalOverlayRoot = null;
    
    private void openFullscreenViewer() {
        // 获取主舞台，作为 Popup 的 owner
        Stage ownerStage = null;
        if (getScene() != null && getScene().getWindow() instanceof Stage) {
            ownerStage = (Stage) getScene().getWindow();
        }
        
        // ⭐ 使用屏幕可视区域（自动排除任务栏）
        Rectangle2D screenBounds = Screen.getPrimary().getVisualBounds();
        double popupWidth = screenBounds.getWidth();
        double popupHeight = screenBounds.getHeight();
        double popupX = screenBounds.getMinX();
        double popupY = screenBounds.getMinY();

        // ✅ 全局只创建一次 Popup（所有抓拍项共享）
        if (globalFullscreenPopup == null) {
            globalFullscreenPopup = new Popup();
            globalFullscreenPopup.setAutoHide(false);      // 不自动关闭
            globalFullscreenPopup.setAutoFix(true);        // 自动校正位置

            // 1. 创建遮罩层
            globalOverlayRoot = new StackPane();
            globalOverlayRoot.setStyle("-fx-background-color: rgba(0,0,0,0.8);");
            globalOverlayRoot.setPrefSize(popupWidth, popupHeight);
            globalOverlayRoot.setFocusTraversable(true);

            // 2. 全屏播放器（全局单例）
            globalFullscreenPlayer = new SnapshotPlayerView();
            globalFullscreenPlayer.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
            globalFullscreenPlayer.setViewportSize(popupWidth, popupHeight);
            globalFullscreenPlayer.setStyle("-fx-background-color: transparent;");

            globalOverlayRoot.getChildren().add(globalFullscreenPlayer);

            // 3. 键盘／鼠标关闭
            globalOverlayRoot.setOnKeyPressed(event -> {
                KeyCode shortcut = ShortcutStore.getInstance().getFullscreenViewerKey();
                if (event.getCode() == shortcut || event.getCode() == KeyCode.ESCAPE) {
                    globalFullscreenPopup.hide();
                    event.consume();
                }
            });

            globalFullscreenPopup.getContent().add(globalOverlayRoot);

            // Popup 显示后请求焦点，以便接收按键
            globalFullscreenPopup.setOnShown(e -> {
                globalOverlayRoot.requestFocus();
            });
            
            // ✅ 关键：关闭时同步帧索引并清理播放器缓存
            globalFullscreenPopup.setOnHidden(e -> {
                // ⭐ 先同步帧索引回原 item（在 cleanup 之前！）
                syncFrameIndexToItem();
                currentFullscreenItem = null;
                
                Platform.runLater(() -> {
                    if (globalFullscreenPlayer != null) {
                        // ✅ 清理播放器的 Image 缓存、Canvas 缓冲区、监听器
                        globalFullscreenPlayer.cleanup();
                        System.out.println("✅ 全屏播放器已清理缓存");
                    }
                });
            });
        } else {
            // ✅ Popup 已存在，动态更新大小为当前主窗口大小
            if (globalOverlayRoot != null) {
                globalOverlayRoot.setPrefSize(popupWidth, popupHeight);
            }
            if (globalFullscreenPlayer != null) {
                globalFullscreenPlayer.setViewportSize(popupWidth, popupHeight);
            }
        }
        
        // ✅ 更新播放器内容（复用全局播放器，只更新数据）
        if (globalFullscreenPlayer != null && inner.getDiskFramesV2() != null && !inner.getDiskFramesV2().isEmpty()) {
            globalFullscreenPlayer.setDiskFramesV2(inner.getDiskFramesV2(), inner.getCurrentFrameIndex());
        }
        
        // ⭐ 记录当前全屏查看的格子（用于关闭时同步帧索引）
        currentFullscreenItem = this;
        System.out.println("📌 全屏查看格子: index=" + this.itemIndex + ", 帧=" + inner.getCurrentFrameIndex());

        // ✅ 显示 Popup，位置和大小跟主窗口一致
        if (ownerStage != null) {
            globalFullscreenPopup.show(ownerStage, popupX, popupY);
        } else {
            globalFullscreenPopup.show(getScene().getWindow(), popupX, popupY);
        }
    }
}