package com.acard.acard.ui;

import com.acard.acard.DiskFrameRingBuffer;
import com.acard.acard.FrameRingBuffer;
import com.acard.acard.store.ShortcutStore;
import com.acard.acard.tools.CaptureDataManager;
import com.acard.acard.tools.FileToos;
import com.acard.acard.tools.LogTools;
import javafx.application.Platform;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.image.PixelWriter;
import javafx.scene.input.KeyCode;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.HBox;
import javafx.scene.control.Label;
import javafx.scene.control.Button;
import javafx.geometry.Pos;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 抓拍播放独立UI：
 * - 默认显示事件帧（窗口中间）
 * - 鼠标在该UI上滚轮：上一帧/下一帧
 * - 左右箭头键：上一帧/下一帧（键盘快捷方式）
 * - 按住 Ctrl 键时，滚轮改为以鼠标位置局部放大/缩小
 * - 边界保护：帧索引与缩放均有上下限
 * - 自适应尺寸：Canvas 跟随父容器大小变化
 */
public class SnapshotPlayerView extends StackPane {

    private final Canvas canvas = new Canvas(800, 600);
    private final Label statusLabel = new Label("0 / 0");

    private final Button deleteButton = new Button("\uD83D\uDCA1");
    private final HBox topLeftBox = new HBox(8); // ⭐ 左上角容器（帧数标签 + 删除按钮）
    
    // ⭐ 删除回调接口
    private Runnable onDeleteCallback = null;
    private boolean useDisk = true;
    //private List<DiskFrameRingBuffer.FrameItem> diskFrames;
    //private List<FrameRingBuffer.FrameItem> memFrames;
    private List<com.acard.acard.capture.DiskCaptureCache.DiskFrameItem> diskFramesV2;  // ✅ 新格式（零内存）

    // ⭐ Getter 方法（新增）
    public List<com.acard.acard.capture.DiskCaptureCache.DiskFrameItem> getDiskFramesV2() {
        return diskFramesV2;
    }
    
    // ⭐ 偏移同步方法
    /**
     * 获取当前显示帧的 frameId（文件名里的数字）
     */
    public long getCurrentDisplayFrameId() {
        if (diskFramesV2 != null && !diskFramesV2.isEmpty() && index >= 0 && index < diskFramesV2.size()) {
            return diskFramesV2.get(index).frameId;
        }
        return -1;
    }
    
    /**
     * 获取事件帧的 frameId
     */
    public int getEventFrameId() {
        return eventFrameId;
    }
    
    /**
     * 是否已应用偏移
     */
    public boolean hasAppliedOffset() {
        return hasAppliedOffset;
    }
    
    /**
     * 应用偏移跳转
     */
    public void applyOffset(int offset) {
        if (diskFramesV2 != null && !diskFramesV2.isEmpty() && !hasAppliedOffset) {
            int newIndex = clamp(index + offset, 0, diskFramesV2.size() - 1);
            if (newIndex != index) {
                index = newIndex;
                render();
                System.out.println("📐 应用偏移: offset=" + offset + ", newIndex=" + index);
            }
            hasAppliedOffset = true;
        }
    }
    
    /**
     * 重置偏移标记（清空时调用）
     */
    public void resetOffsetFlag() {
        hasAppliedOffset = false;
        eventFrameId = -1;
    }
    private int index = 0;
    private double scale = 1.0;
    private static final double MIN_SCALE = 1.0;  // ⭐ 改为常量，最小缩放为1.0x（不能缩小）
    private double minScale = MIN_SCALE;
    private double maxScale = 8.0;
    private double offsetX = 0.0;
    private double offsetY = 0.0;
    // qPressed变量已移除，Q键不再用于缩放功能
    // 自动等比适配标志：默认开启。用户按Ctrl进行缩放后关闭；设置新帧或未按Ctrl滚动时恢复。
    private boolean autoFit = true;
    // 视口锁定：一旦设置，渲染按该尺寸适配，避免随容器增大而放大。
    private boolean viewportLocked = false;
    private double lockedW = -1;
    private double lockedH = -1;
    // 记录最近一次渲染的图像尺寸，用于滚轮缩放锚点计算
    private double lastImageW = -1;
    private double lastImageH = -1;
    // ✅ 按需加载策略：不缓存Image，只保留当前显示的那张
    // - JPEG才50KB，解码很快，无需预缓存
    // ✅ 零内存策略：不缓存图片，每次从磁盘按需加载
    // - JPEG解码很快（50KB → 10ms），不会卡UI
    // - 100个item × 0缓存 = 0MB 内存 ✅✅✅
    // private volatile Image currentImage = null;  // ❌ 已移除：不再缓存
    private volatile int currentImageIndex = -1;  // 当前图像索引（用于判断是否需要重新加载）
    
    // ⭐ 偏移同步相关
    private boolean hasAppliedOffset = false;  // 标记是否已应用偏移
    private int eventFrameId = -1;  // 事件帧的 frameId（文件名里的数字）

    String normalDeleteStyle =
            "-fx-background-color: rgba(220, 38, 38, 0.01); " +  // ⭐ 红色背景，20%不透明
                    "-fx-text-fill: #ffffff00; " +
                    "-fx-font-size: 12px; " +
                    "-fx-padding: 0; " +
                    "-fx-background-radius: 4; " +
                    "-fx-cursor: hand; " +
                    "-fx-border-color: transparent;";


    String normalTextStyle ="-fx-background-color: rgba(0,0,0,0.2); -fx-text-fill: #ffffff; -fx-padding: 4 8; -fx-background-radius: 6; -fx-font-weight: bold;";


    // ⭐ 悬停样式：85%透明度（高亮显示）
    String hoverDeleteStyle =
            "-fx-background-color: rgba(220, 38, 38, 0.25); " +  // ⭐ 红色背景，85%不透明
                    "-fx-text-fill: #ffffffa0; " +
                    "-fx-font-size: 12px; " +
                    "-fx-padding: 0; " +
                    "-fx-background-radius: 4; " +
                    "-fx-cursor: hand; " +
                    "-fx-border-color: transparent;";

    String hoverTextStyle ="-fx-background-color: rgba(0,0,0,0.2); -fx-text-fill: #ffffff; -fx-padding: 4 8; -fx-background-radius: 6; -fx-font-weight: bold;";



    //待实现
    public int getCurrentFrameIndex() {
        return  index;
    }
    
    /**
     * ⭐ 设置当前帧索引并刷新显示（用于全屏查看同步）
     */
    public void setCurrentFrameIndex(int newIndex) {
        if (diskFramesV2 != null && newIndex >= 0 && newIndex < diskFramesV2.size()) {
            this.index = newIndex;
            render();  // 刷新显示
            System.out.println("📌 设置帧索引: " + newIndex);
        }
    }
    
    // ✅ 简单缓存：只缓存当前Image对象（约8MB）
    private final java.util.LinkedHashMap<Integer, Image> imageCache = new java.util.LinkedHashMap<Integer, Image>(5, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<Integer, Image> eldest) {
            // 100个item场景下，进一步减少缓存，确保内存可控
            return size() > 1;  // ✅ 只保留1个Image
        }
    };
    
    // ✅ 内存监控配置（针对100个item优化）
    private static final long MEMORY_THRESHOLD_MB = 500; // 内存阈值500MB（100个item × 8MB = 800MB，留余量）
    private long lastMemoryCheck = 0;
    private static final long MEMORY_CHECK_INTERVAL = 2000; // 2秒检查一次（更频繁监控）
    
    // ✅ 单线程加载器：避免并发加载冲突
    private static final ExecutorService imageLoader = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SnapshotImageLoader");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY);
        return t;
    });
    // 合并渲染请求，避免频繁重复 render 造成 CPU 飙升
    private volatile boolean renderRequested = false;
    private void requestRender() {
        if (renderRequested) return;
        renderRequested = true;
        Platform.runLater(() -> {
            renderRequested = false;
            render();
        });
    }

    public SnapshotPlayerView() {
        // ⭐ 配置删除按钮（20x20，紧凑样式）
        deleteButton.setMinSize(15, 15);
        deleteButton.setMaxSize(15, 15);
        deleteButton.setPrefSize(15, 15);
        
        // ⭐ 默认样式：20%透明度（低调显示）

        
        deleteButton.setStyle(normalDeleteStyle);
        
        // ⭐ 使用事件过滤器优先处理删除按钮点击（最高优先级）
        deleteButton.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_CLICKED, e -> {
            System.out.println("🗑️ 删除按钮被点击！");
            e.consume();  // ⭐ 立即消费事件，阻止传播
            if (onDeleteCallback != null) {
                CaptureDataManager.getInstance().remove(diskFramesV2.get(0).getEventId());
                onDeleteCallback.run();
            }
        });
        

        
        // ⭐ 配置帧数标签
        //statusLabel.setMouseTransparent(true);
        //statusLabel.setStyle("-fx-background-color: rgba(0,0,0,0.45); -fx-text-fill: #ffffff; -fx-padding: 4 8; -fx-background-radius: 6; -fx-font-weight: bold;");

        // ⭐ 将标签和按钮放入左上角容器
        topLeftBox.getChildren().addAll(deleteButton, statusLabel);
       // topLeftBox.getChildren().addAll(deleteButton);
        topLeftBox.setAlignment(Pos.CENTER_LEFT);  // ⭐ 垂直居中对齐
        topLeftBox.setMaxHeight(25);               // ✅ 限制容器高度，防止它占据整个父容器高度
        topLeftBox.setPrefHeight(25);              // ✅ 首选高度
        // ⭐ 修复：HBox 不能设置为 mouseTransparent，否则子元素无法接收事件
        topLeftBox.setMouseTransparent(false);  // ⭐ 允许容器接收事件
        topLeftBox.setPickOnBounds(false);  // ⭐ 只在实际内容上响应鼠标事件
        deleteButton.setMouseTransparent(false); // 按钮需要接收鼠标事件
        statusLabel.setMouseTransparent(true);   // 标签不需要接收事件
        StackPane.setAlignment(topLeftBox, Pos.TOP_LEFT);
        topLeftBox.setTranslateX(5);
        topLeftBox.setTranslateY(0);
        
        // 右上角显示文件名/来源标签

        // ⭐ 添加所有子节点（用 topLeftBox 替换 statusLabel）
        getChildren().addAll(canvas, topLeftBox);
        // 绑定尺寸，保持自适应
        widthProperty().addListener((obs, ov, nv) -> {
            double w = nv.doubleValue();
            canvas.setWidth(viewportLocked ? lockedW : w);
            requestRender();
        });
        heightProperty().addListener((obs, ov, nv) -> {
            double h = nv.doubleValue();
            canvas.setHeight(viewportLocked ? lockedH : h);
            requestRender();
        });
        setFocusTraversable(true);
        // 点击后请求焦点，确保能接收 Q 键事件
        // ⭐ 修复：检查点击目标，避免拦截删除按钮的点击
        setOnMouseClicked(e -> {
            // 如果点击的是删除按钮或其容器，不处理（让按钮自己处理）
            if (e.getTarget() == deleteButton || e.getTarget() == topLeftBox || e.isConsumed()) {
                System.out.println("⚠️ 点击了删除按钮区域，跳过焦点请求");
                return;
            }
            requestFocus();
        });
        // 鼠标进入时自动获取焦点，避免Q键不生效
        setOnMouseEntered(e -> {
            if (e.getTarget() == deleteButton || e.getTarget() == topLeftBox) {
                return;
            }
            requestFocus();
        });
        // 鼠标按下/移动也尝试获取焦点，进一步提升可靠性
        setOnMousePressed(e -> {
            if (e.getTarget() == deleteButton || e.getTarget() == topLeftBox) {
                return;
            }
            requestFocus();
        });
        setOnMouseMoved(e -> {
            if (e.getTarget() == deleteButton || e.getTarget() == topLeftBox) {
                return;
            }
            requestFocus();
        });

        // ⭐ 只使用一个事件过滤器处理滚轮事件，避免重复处理
        // 使用事件过滤器优先处理滚轮事件，避免被父级ScrollPane拦截
        addEventFilter(ScrollEvent.SCROLL, this::handleScroll);

        setOnKeyPressed(e -> {
            // Q键事件处理已移除，Q键现在可用于其他功能
            
            // ⭐ 左右箭头键切换帧（配合滚轮帧率设置）
            if (e.getCode() == KeyCode.LEFT) {
                // 左箭头：上一帧
                int total = getTotal();
                if (total > 0) {
                    // ⭐ 获取滚轮帧率设置（0表示跳1帧，n表示跳n帧）
                    int scrollFrameRate = ShortcutStore.getInstance().getScrollFrameRate();
                    int step = scrollFrameRate == 0 ? 1 : scrollFrameRate;
                    index = clamp(index - step, 0, total - 1);
                    render();
                    // ⚡ 将查看的帧图片路径加入上传队列
                    enqueueCurrentFrameForUpload();
                }
                e.consume();
            } else if (e.getCode() == KeyCode.RIGHT) {
                // 右箭头：下一帧
                int total = getTotal();
                if (total > 0) {
                    int scrollFrameRate = ShortcutStore.getInstance().getScrollFrameRate();
                    int step = scrollFrameRate == 0 ? 1 : scrollFrameRate;
                    index = clamp(index + step, 0, total - 1);
                    render();
                    // ⚡ 将查看的帧图片路径加入上传队列
                    enqueueCurrentFrameForUpload();
                }
                e.consume();
            }
        });
        setOnKeyReleased(e -> {
            // Q键事件处理已移除
        });
        // ⭐ 移除重复的 setOnScroll，只用上面的 addEventFilter 处理滚轮事件
    }




    public void OnMouseEntered(){

        deleteButton.setStyle(hoverDeleteStyle);
        statusLabel.setStyle(hoverTextStyle);
    }

    public void OnMouseExited(){
        deleteButton.setStyle(normalDeleteStyle);
        statusLabel.setStyle(normalTextStyle);

    }
    public void setDiskFrames(List<DiskFrameRingBuffer.FrameItem> frames, int eventIndex) {
        useDisk = true;
       // this.diskFrames = frames;
       // this.memFrames = null;
        this.diskFramesV2 = null;  // 清空V2
        this.index = clamp(eventIndex, 0, frames != null ? Math.max(0, frames.size() - 1) : 0);
        // 初始缩放与偏移复位
        this.scale = 1.0; this.offsetX = 0.0; this.offsetY = 0.0;
        this.autoFit = true; // 新内容时恢复自适配
        
        // ✅ 清空当前缓存，释放内存（按需加载策略）
        currentImageIndex = -1;
        System.out.println("✅ 切换磁盘帧列表，零内存模式");
        
        // ✅ 按需加载策略：render时同步加载当前帧，不预加载
        // 在 FX 线程进行渲染与获取焦点，避免跨线程更新 UI
        if (Platform.isFxApplicationThread()) {
            requestRender();
            requestFocus();
        } else {
            Platform.runLater(() -> {
                requestRender();
                requestFocus();
            });
        }
    }
    
    /** ✅ 设置磁盘帧（新格式：DiskCaptureCache.DiskFrameItem，零内存） */
    public void setDiskFramesV2(List<com.acard.acard.capture.DiskCaptureCache.DiskFrameItem> frames, int eventIndex) {
        useDisk = true;
        this.diskFramesV2 = frames;
        //this.diskFrames = null;  // 清空旧格式
        //this.memFrames = null;
        this.index = eventIndex;// clamp(eventIndex, 0, frames != null ? Math.max(0, frames.size() - 1) : 0);
        // 初始缩放与偏移复位
        this.scale = 1.0; this.offsetX = 0.0; this.offsetY = 0.0;
        this.autoFit = true; // 新内容时恢复自适配
        
        // ⭐ 重置偏移标记，记录事件帧 frameId
        this.hasAppliedOffset = false;
        if (frames != null && !frames.isEmpty() && eventIndex >= 0 && eventIndex < frames.size()) {
            this.eventFrameId = (int) frames.get(eventIndex).frameId;
        }
        
        // ✅ 清空LRU缓存和当前索引
        imageCache.clear();
        currentImageIndex = -1;
        System.out.println("✅ 切换磁盘帧列表V2，eventFrameId=" + eventFrameId);
        
        // ✅ 按需加载策略：render时同步加载当前帧，不预加载
        // 在 FX 线程进行渲染与获取焦点，避免跨线程更新 UI
        if (Platform.isFxApplicationThread()) {
            requestRender();
            requestFocus();
        } else {
            Platform.runLater(() -> {
                requestRender();
                requestFocus();
            });
        }
    }

    public void setMemoryFrames(List<FrameRingBuffer.FrameItem> frames, int eventIndex) {
        useDisk = false;
        //this.memFrames = frames;
        //this.diskFrames = null;
        this.index = clamp(eventIndex, 0, frames != null ? Math.max(0, frames.size() - 1) : 0);
        // 初始缩放与偏移复位
        this.scale = 1.0; this.offsetX = 0.0; this.offsetY = 0.0;
        this.autoFit = true; // 新内容时恢复自适配
        // ✅ 清空当前缓存，释放内存
        currentImageIndex = -1;
        if (Platform.isFxApplicationThread()) {
            requestRender();
            requestFocus();
        } else {
            Platform.runLater(() -> {
                requestRender();
                requestFocus();
            });
        }
    }
    
    /**
     * 动态添加内存帧到现有帧列表（异步推送用）
     */
    public void appendMemoryFrames(List<FrameRingBuffer.FrameItem> newFrames) {
        if (newFrames == null || newFrames.isEmpty()) return;
        
        if (Platform.isFxApplicationThread()) {
            doAppendMemoryFrames(newFrames);
        } else {
            Platform.runLater(() -> doAppendMemoryFrames(newFrames));
        }
    }

    /**
     * 动态添加磁盘帧到现有帧列表（慢放异步推送用）
     */
    public void appendDiskFrames(List<DiskFrameRingBuffer.FrameItem> newFrames) {
        if (newFrames == null || newFrames.isEmpty()) return;
        
        if (Platform.isFxApplicationThread()) {
            doAppendDiskFrames(newFrames);
        } else {
            Platform.runLater(() -> doAppendDiskFrames(newFrames));
        }
    }
    
    private void doAppendDiskFrames(List<DiskFrameRingBuffer.FrameItem> newFrames) {

    }
    
    /**
     * ✅ 追加单个帧到磁盘帧列表（用于事件驱动的后续帧追加）
     */
    public void appendDiskFrameV2(com.acard.acard.capture.DiskCaptureCache.DiskFrameItem newFrame) {
        if (diskFramesV2 != null && newFrame != null) {
            // ✅ 直接追加新帧，但不自动切换显示
            // 用户需要手动切换才能看到新帧（保持当前画面）
            diskFramesV2.add(newFrame);
            
            // ⚠️ 不再自动切换到新帧，停留在当前显示的帧
            // 用户可以通过滚轮或点击切换到新帧
        }
    }
    
    private void doAppendMemoryFrames(List<FrameRingBuffer.FrameItem> newFrames) {

    }

    private void handleScroll(ScrollEvent e) {
        // ⭐ 立即消费事件，防止跨 item 时事件被多次处理
        e.consume();
        
        // 滚轮交互开始时强制获取焦点，确保键盘状态能被捕获
        requestFocus();
        boolean zoomMode = e.isControlDown(); // 只允许 Ctrl+滚轮 进行缩放
        if (zoomMode) {
            // ⭐ 日志：记录缩放开始状态
            LogTools.getInstance().logRecord4(String.format(
                "[缩放开始] index=%d, autoFit=%b, scale=%.4f, minScale=%.4f, hashCode=%d",
                index, autoFit, scale, minScale, System.identityHashCode(this)));
            
            // ✅ 从autoFit切换到手动缩放时，保持当前铺满状态
            if (autoFit) {
                autoFit = false;
                initManualZoomFromAutoFit();  // 计算并设置初始scale
                LogTools.getInstance().logRecord4(String.format(
                    "[initManualZoom后] index=%d, scale=%.4f", index, scale));
            }
            
            // ⭐ 强制确保当前 scale 不小于 MIN_SCALE（防止跨 item 时的累积误差）
            if (scale < MIN_SCALE) {
                LogTools.getInstance().logRecord4(String.format(
                    "⚠️ [异常检测] index=%d, scale=%.4f < MIN_SCALE=%.4f, 强制修正!", 
                    index, scale, MIN_SCALE));
                scale = MIN_SCALE;
            }
            
            // 局部缩放：以鼠标位置为锚点调整偏移使该点尽量保持
            double oldScale = scale;
            double delta = e.getDeltaY() > 0 ? FileToos.ImageScale : -FileToos.ImageScale;
            double newScale = clamp(oldScale + delta, MIN_SCALE, maxScale);
            
            // ⭐ 日志：记录缩放计算过程
            LogTools.getInstance().logRecord4(String.format(
                "[缩放计算] index=%d, oldScale=%.4f, delta=%.4f, newScale(clamp后)=%.4f",
                index, oldScale, delta, newScale));
            
            // ⭐ 再次确保 newScale 不小于 MIN_SCALE（绝对不允许缩小到1.0x以下）
            if (newScale < MIN_SCALE) {
                LogTools.getInstance().logRecord4(String.format(
                    "⚠️ [newScale异常] index=%d, newScale=%.4f < MIN_SCALE, 强制修正!", 
                    index, newScale));
                newScale = MIN_SCALE;
            }
            double ratio = newScale / oldScale;

            // 以当前绘制状态计算居中基准位移（旧scale下）
            double cw = viewportLocked ? lockedW : canvas.getWidth();
            double ch = viewportLocked ? lockedH : canvas.getHeight();
            // ⭐ 修复：使用容器尺寸作为基准（与 render() 保持一致）
            // render() 中 drawW = cw * scale, drawH = ch * scale
            double drawWOld = cw * oldScale;
            double drawHOld = ch * oldScale;
            double drawWNew = cw * newScale;
            double drawHNew = ch * newScale;
            double baseXOld = (cw - drawWOld) / 2.0;
            double baseYOld = (ch - drawHOld) / 2.0;
            double baseXNew = (cw - drawWNew) / 2.0;
            double baseYNew = (ch - drawHNew) / 2.0;

            double sx = e.getX();
            double sy = e.getY();
            // 保持鼠标点不动：offset' = ratio*offset + (1 - ratio)*mouse + (ratio*baseOld - baseNew)
            offsetX = ratio * offsetX + (1 - ratio) * sx + (ratio * baseXOld - baseXNew);
            offsetY = ratio * offsetY + (1 - ratio) * sy + (ratio * baseYOld - baseYNew);

            scale = newScale;
            
            // ⭐ 修复：当 scale = 1.0 时，必须重置 offset 为 0，否则图像会偏移看起来"缩小"
            if (scale <= MIN_SCALE) {
                scale = MIN_SCALE;
                offsetX = 0.0;
                offsetY = 0.0;
            }
            
            // ⭐ 日志：记录最终缩放结果和偏移量
            LogTools.getInstance().logRecord4(String.format(
                "[缩放完成] index=%d, 最终scale=%.4f, offsetX=%.2f, offsetY=%.2f, hashCode=%d",
                index, scale, offsetX, offsetY, System.identityHashCode(this)));
            
            render();
        } else {
            // 切换帧：上一/下一（配合滚轮帧率设置）
            int total = getTotal();
            if (total <= 0) return;
            
            boolean isNext = e.getDeltaY() < 0;  // 向下滚 = 下一帧
            int scrollFrameRate = ShortcutStore.getInstance().getScrollFrameRate();
            int frameStep = scrollFrameRate == 0 ? 1 : scrollFrameRate;
            int step = isNext ? frameStep : -frameStep;
            
            index = clamp(index + step, 0, total - 1);
            // 若未处于手动缩放，保持自适配；若用户此前手动缩放，则保留其缩放状态
            render();
            // ⚡ 将查看的帧图片路径加入上传队列
            enqueueCurrentFrameForUpload();
        }
        // ⭐ 事件已在函数开头消费，无需再次调用 e.consume()
    }
    
    /**
     * ⚡ 将当前帧的图片路径加入上传队列（查看上下帧时调用）
     */
    private void enqueueCurrentFrameForUpload() {
        if (diskFramesV2 != null && !diskFramesV2.isEmpty()) {
            int idx = clamp(index, 0, diskFramesV2.size() - 1);
            com.acard.acard.capture.DiskCaptureCache.DiskFrameItem frame = diskFramesV2.get(idx);
            if (frame != null && frame.filePath != null && !frame.filePath.isEmpty()) {
                com.acard.acard.tools.ImageUploadQueue.getInstance().enqueueFrameView(frame.filePath);
            }
        }
    }

    private int getTotal() {
        if (useDisk) {
            // ✅ 优先使用新格式
            if (diskFramesV2 != null) return diskFramesV2.size();

            return 0;
        }
        return 0;
    }

    /**
     * ✅ 从autoFit模式切换到手动缩放模式时，初始化scale为铺满状态
     * 避免图片突然变小
     */
    private void initManualZoomFromAutoFit() {
        // ✅ 从autoFit切换到手动缩放：初始scale=1.0（拉伸铺满，和autoFit一致）
        scale = MIN_SCALE;  // ⭐ 使用常量确保最小值
        minScale = MIN_SCALE;
        offsetX = 0.0;
        offsetY = 0.0;
        // ⭐ 不在这里调用 requestRender()，让 handleScroll 统一调用 render()
        // 避免异步渲染导致的状态不一致
    }

    private void render() {
        // 更新状态标签（当前帧/总帧）
        int total = getTotal();
        int curr = total > 0 ? clamp(index, 0, total - 1) : 0;
        //statusLabel.setText((total > 0 ? (curr + 1) : 0) + " / " + total);
        statusLabel.setText((total > 0 ? (curr + 1) : 0)+"");
        // 更新右上角的名称标签
        updateNameLabel(curr);

        GraphicsContext gc = canvas.getGraphicsContext2D();
        double cw = viewportLocked ? lockedW : canvas.getWidth();
        double ch = viewportLocked ? lockedH : canvas.getHeight();
        gc.clearRect(0, 0, cw, ch);

        // ✅ 同步加载当前帧（JPEG才50KB，解码很快，不卡UI）
        Image img = loadCurrentImage();
        if (img == null) {
            return;  // 加载失败，不渲染
        }
        double iw = img.getWidth();
        double ih = img.getHeight();
        // 记录图像尺寸以供滚轮缩放锚点计算
        lastImageW = iw;
        lastImageH = ih;

        // ✅ 和实时流、慢放一致：直接拉伸铺满（stretch模式，force-aspect-ratio=false）
        if (autoFit) {
            // ✅ 直接拉伸到容器大小，不保持宽高比

            gc.drawImage(img, 0, 0, cw, ch);

           /* double padding = 2.0;
            double drawW = Math.max(0, cw - padding * 2);
            double drawH = Math.max(0, ch - padding * 2);
            gc.drawImage(img, padding, padding, drawW, drawH);*/
        } else {
            // ✅ 手动缩放模式：以"拉伸铺满"为基准（MIN_SCALE=1.0）
            // 1.0x = 直接拉伸到容器大小（和autoFit一致）
            // >1.0x = 放大（保持宽高比）
            minScale = MIN_SCALE;  // ✅ 使用常量确保最小缩放为1.0x
            double oldScaleInRender = scale;  // 记录修正前的值
            scale = clamp(scale, MIN_SCALE, maxScale);
            // ⭐ 强制确保 scale 不小于 MIN_SCALE（防止任何情况下的下溢）
            if (scale < MIN_SCALE) {
                LogTools.getInstance().logRecord4(String.format(
                    "⚠️ [render异常] index=%d, scale=%.4f被修正为%.4f, hashCode=%d",
                    index, oldScaleInRender, MIN_SCALE, System.identityHashCode(this)));
                scale = MIN_SCALE;
            }
            
            // ✅ 先拉伸到容器大小，再应用缩放
            // scale=1.0时显示效果和autoFit完全一致（拉伸铺满）
            double drawW = cw * scale;
            double drawH = ch * scale;
            double baseX = (cw - drawW) / 2.0;
            double baseY = (ch - drawH) / 2.0;
            gc.save();
            gc.translate(baseX + offsetX, baseY + offsetY);
            // 以容器尺寸为基准缩放
            gc.drawImage(img, 0, 0, drawW, drawH);



            gc.restore();
        }
    }

    /** 更新右上角名称标签：显示会话ID（时时流-001/慢放-001）+ 文件名 */
    private void updateNameLabel(int idx) {
        /*try {
            if (useDisk) {
                // ✅ 优先使用新格式
                if (diskFramesV2 != null && !diskFramesV2.isEmpty()) {
                    idx = clamp(idx, 0, diskFramesV2.size() - 1);
                    com.acard.acard.capture.DiskCaptureCache.DiskFrameItem item = diskFramesV2.get(idx);
                    if (item == null) { nameLabel.setText(""); return; }
                    
                    // 从路径提取会话ID：runtime/captures/时时流-001/frame_001.jpeg → 时时流-001
                    String sessionId = extractSessionId(item.filePath);
                    String fn = (item.filePath != null) ? new File(item.filePath).getName() : "";
                    nameLabel.setText(sessionId + ": " + fn);
                    
                }  else {
                    nameLabel.setText("");
                    return;
                }
            }
        } catch (Throwable ignore) {
            nameLabel.setText("");
        }*/
    }


    // 在 loadCurrentImage() 方法中，完全移除缓存逻辑：

    private Image loadCurrentImage() {
        try {
            if (useDisk && diskFramesV2 != null && !diskFramesV2.isEmpty()) {
                int idx = clamp(index, 0, diskFramesV2.size() - 1);

                // ❌ 删除这些缓存相关代码：
                // Image cached = imageCache.get(idx);
                // if (cached != null) return cached;
                // imageCache.put(idx, img);

                // ✅ 直接从磁盘加载，不缓存
                com.acard.acard.capture.DiskCaptureCache.DiskFrameItem item = diskFramesV2.get(idx);
                if (item == null || item.filePath == null) return null;

                File file = new File(item.filePath);
                if (!file.exists()) return null;

                // 每次都重新加载（50KB→10ms，可接受）
                return new Image(file.toURI().toString(), false);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }
    /*private Image loadCurrentImage() {
        try {
            // ✅ 内存监控检查
            checkAndCleanupMemory();
            
            if (useDisk) {
                // ✅ 优先使用新格式（DiskCaptureCache.DiskFrameItem）
                if (diskFramesV2 != null && !diskFramesV2.isEmpty()) {
                    int idx = clamp(index, 0, diskFramesV2.size() - 1);
                    
                    // ✅ 智能缓存检查（避免重复加载）
                    Image cached = imageCache.get(idx);
                    if (cached != null) {
                        currentImageIndex = idx;
                        return cached;
                    }
                    
                    // ✅ 从磁盘加载原图
                    com.acard.acard.capture.DiskCaptureCache.DiskFrameItem item = diskFramesV2.get(idx);
                    if (item == null || item.filePath == null) return null;
                    
                    File file = new File(item.filePath);
                    if (!file.exists()) return null;
                    
                    // ✅ 加载原图（满足客户需求：必须显示原图）
                    Image img = new Image(file.toURI().toString(), false);
                    if (img.isError()) return null;
                    
                    // ✅ 加入智能缓存（最多3帧）
                    int oldSize = imageCache.size();
                    imageCache.put(idx, img);
                    
                    // 🔍 诊断日志（每10次记录一次）
                    if (idx % 10 == 0) {
                        double memoryMB = estimateImageMemory(img);
                        System.out.println("🖼️ [SnapshotPlayerView] 原图加载: idx=" + idx + 
                            ", 缓存: " + oldSize + "→" + imageCache.size() + 
                            ", 图像尺寸: " + (int)img.getWidth() + "x" + (int)img.getHeight() +
                            ", 内存: " + String.format("%.1f", memoryMB) + "MB");
                    }
                    
                    currentImageIndex = idx;
                    return img;
                    
                } else {
                    return null;
                }
                
            }
            return null;
        } catch (Exception e) {
            System.err.println("⚠️ 加载图像失败: " + e.getMessage());
            return null;
        }
    }*/



    

    


    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));  // ✅ 限制在 [lo, hi] 范围内
    }

    // 重载：用于缩放计算的 double 版本，避免自动转换为 int 导致编译错误
    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /**
     * 显式设置视口（容器）大小，触发画布和缩放的重新计算。
     */
    public void setViewportSize(double w, double h) {
        if (w <= 0 || h <= 0) return;
        canvas.setWidth(viewportLocked ? lockedW : w);
        canvas.setHeight(viewportLocked ? lockedH : h);
        requestRender();
    }

    /** 显式锁定视口大小（后续所有帧按该尺寸适配）。 */
    public void lockViewportSize(double w, double h) {
        if (w <= 0 || h <= 0) return;
        
        // ✅ 如果已经锁定且尺寸相同，忽略（避免快速抓拍时重复调用）
        if (viewportLocked && Math.abs(lockedW - w) < 1.0 && Math.abs(lockedH - h) < 1.0) {
            return;
        }
        
        Runnable r = () -> {
            viewportLocked = true;
            lockedW = w;
            lockedH = h;
            canvas.setWidth(w);
            canvas.setHeight(h);
            // ✅ 只在首次锁定时设置autoFit，避免覆盖用户手动缩放状态
            if (scale == 1.0 && offsetX == 0 && offsetY == 0) {
                // 初始状态，设置自适配
                autoFit = true;
            }
            // ✅ 否则保留用户的缩放状态（scale, offsetX, offsetY）
            requestRender();
        };
        if (Platform.isFxApplicationThread()) r.run(); else Platform.runLater(r);
    }
    
    /** 更新锁定的视口尺寸（不解锁，避免触发监听器循环） */
    public void updateLockedViewportSize(double w, double h) {
        if (w <= 0 || h <= 0) return;
        Runnable r = () -> {
            System.out.println("   🔄 更新锁定视口尺寸: " + String.format("%.0fx%.0f", w, h));
            // 保持锁定状态，只更新尺寸
            lockedW = w;
            lockedH = h;
            canvas.setWidth(w);
            canvas.setHeight(h);
            // ✅ 只在初始状态时恢复自适应，避免覆盖用户手动缩放状态
            if (scale == 1.0 && offsetX == 0 && offsetY == 0) {
                autoFit = true;
            }
            requestRender();
        };
        if (Platform.isFxApplicationThread()) r.run(); else Platform.runLater(r);
    }
    
    /**
     * ✅ 使用TurboJPEG解码JPEG文件为Image（零额外内存开销）
     */
    private Image loadImageViaTurboJPEG(File jpegFile) {
        try {
            // 1. 读取JPEG bytes
            byte[] jpegBytes = Files.readAllBytes(jpegFile.toPath());
            
            // 2. 使用TurboJPEG解码到RGB
            com.acard.acard.utils.TurboJpegEncoder.DecodedImage decoded = 
                com.acard.acard.utils.TurboJpegEncoder.decodeJPEGToRGB(jpegBytes);
            
            if (decoded == null || decoded.rgbData == null) {
                return null;
            }
            
            // 3. 创建WritableImage并写入RGB数据
            WritableImage image = new WritableImage(decoded.width, decoded.height);
            PixelWriter pw = image.getPixelWriter();
            
            // ✅ RGB → ARGB（添加Alpha通道）
            byte[] argbData = new byte[decoded.width * decoded.height * 4];
            for (int i = 0, j = 0; i < decoded.rgbData.length; i += 3, j += 4) {
                argbData[j] = (byte) 255;  // A
                argbData[j + 1] = decoded.rgbData[i];      // R
                argbData[j + 2] = decoded.rgbData[i + 1];  // G
                argbData[j + 3] = decoded.rgbData[i + 2];  // B
            }
            
            // 4. 写入PixelBuffer
            pw.setPixels(0, 0, decoded.width, decoded.height, 
                PixelFormat.getByteBgraPreInstance(), argbData, 0, decoded.width * 4);
            
            // 5. 返回Image（临时RGB数据会被GC自动回收）
            return image;
            
        } catch (Throwable e) {
            // 降级到JavaFX Image
            return null;
        }
    }
    
    /**
     * ✅ 清理所有缓存，释放内存（在移除CaptureItemView时调用）
     */
    public void cleanup() {
        // ✅ 清理Image缓存（只清理 UI 缓存，不删除 CaptureDataManager 中的数据）
        imageCache.clear();
        currentImageIndex = -1;
        // ❌ 不要在这里删除 CaptureDataManager 中的数据！
        // 原因：cleanup() 会在全屏关闭时调用，不应该删除抓拍数据
        // 只有用户明确点击"删除按钮"时才应该删除
        // if(diskFramesV2!=null){
        //     if(diskFramesV2.size()>0) {
        //         LogTools.getInstance().logRecord2("clean;---> "+diskFramesV2.get(0).getEventId());
        //         CaptureDataManager.getInstance().remove(diskFramesV2.get(0).getEventId());
        //     }
        // }
        diskFramesV2 = null;

        System.out.println("🧹 SnapshotPlayerView缓存已清理（包括" + imageCache.size() + "个Image对象）");
    }
    
    /**
     * ✅ 内存监控：当内存使用超过阈值时强制清理
     */
    private void checkAndCleanupMemory() {
        long now = System.currentTimeMillis();
        if (now - lastMemoryCheck < MEMORY_CHECK_INTERVAL) return;
        
        lastMemoryCheck = now;
        
        Runtime runtime = Runtime.getRuntime();
        long usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024;
        
        if (usedMemory > MEMORY_THRESHOLD_MB) {
            System.out.println("⚠️ 内存使用过高: " + usedMemory + "MB, 开始清理缓存");
            
            // 清理缓存（只保留当前图片）
            int currentIdx = this.index;
            imageCache.entrySet().removeIf(entry -> entry.getKey() != currentIdx);
            
            // 建议GC
            System.gc();
            
            long newUsedMemory = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024;
            System.out.println("🧹 内存清理完成: " + usedMemory + "MB → " + newUsedMemory + "MB");
        }
    }
    

    
    /**
     * ✅ 估算图片内存占用
     */
    private double estimateImageMemory(Image img) {
        if (img == null) return 0;
        // RGBA格式：width × height × 4字节
        return (img.getWidth() * img.getHeight() * 4) / 1024 / 1024;
    }
    
    /**
     * ✅ 获取缓存状态信息
     */
    public String getCacheStatus() {
        int cacheSize = imageCache.size();
        double estimatedMemory = imageCache.values().stream()
            .mapToDouble(this::estimateImageMemory)
            .sum();
        
        return String.format("缓存状态 - 图片数: %d张, 预估内存: %.1fMB", 
            cacheSize, estimatedMemory);
    }
    
    /**
     * 从文件路径提取会话ID
     * 例如：runtime/captures/时时流-001/frame_001.jpeg → 时时流-001
     */
    private String extractSessionId(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            return "未知";
        }
        
        try {
            // 标准化路径分隔符
            filePath = filePath.replace("\\", "/");
            
            // 查找 captures/ 后面的部分
            int capturesIndex = filePath.indexOf("captures/");
            if (capturesIndex >= 0) {
                String afterCaptures = filePath.substring(capturesIndex + "captures/".length());
                // 提取第一个路径段（会话ID）
                int slashIndex = afterCaptures.indexOf("/");
                if (slashIndex > 0) {
                    return afterCaptures.substring(0, slashIndex);
                }
            }
            
            // 回退：从路径中提取倒数第二个路径段
            String[] parts = filePath.split("/");
            if (parts.length >= 2) {
                return parts[parts.length - 2];  // 倒数第二个（文件夹名）
            }
            
            return "未知";
        } catch (Exception e) {
            return "未知";
        }
    }
    
    /**
     * ⭐ 设置删除按钮的回调（当用户点击删除按钮时调用）
     * @param callback 删除回调函数
     */
    public void setOnDelete(Runnable callback) {
        this.onDeleteCallback = callback;
    }
}
