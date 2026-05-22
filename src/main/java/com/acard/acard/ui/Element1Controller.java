package com.acard.acard.ui;

import com.acard.acard.DiskFrameRingBuffer;
import com.acard.acard.FrameRingBuffer;
import com.acard.acard.storage.SlowmoStore;
import com.acard.acard.store.GridStore;
import com.acard.acard.tools.CaptureDataManager;
import com.acard.acard.tools.LogTools;
import com.acard.acard.events.UIUpdateEvent;
import com.acard.acard.events.UIUpdateEventManager;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.layout.GridPane;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.Region;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;

import javafx.animation.PauseTransition;
import javafx.util.Duration;

/**
 * 元素1控制器：用于容纳独立抓拍UI播放器的网格。
 * 支持动态行列（最多10x10），每次抓拍生成一个条目加入网格。
 */
public class Element1Controller implements Initializable {

    @FXML private ScrollPane scroll;
    @FXML private GridPane grid;


    public ScrollPane getScroll() {
        return scroll;
    }

    String normalStyle =
            "-fx-background-color: #292929;";

    String hoverStyle =
            "-fx-background-color: #292929;";


    private int rows = 1;
    private int cols = 1;

    private final List<CaptureItemView> items = new ArrayList<>();
    // ⭐ 添加全屏查看器
    //private CaptureItemViewer fullscreenViewer;


    // ✅ 抓拍项数量上限（防止内存爆炸）
    // 20个项 × 120帧 × 200KB(JPEG) ≈ 480MB + 显示的Image对象 ≈ 500-600MB
    private static final int MAX_CAPTURE_ITEMS = 100;

    // 防抖计时器与上次约束缓存（用于减少频繁重建与CPU抖动）
    private PauseTransition layoutDebounceTimer;
    private double[] lastColWidths;
    private double[] lastRowHeights;
    // 记录上次视口与GridPane偏好尺寸，避免形成反馈循环
    private double lastViewportW = -1, lastViewportH = -1;
    private double lastPrefW = -1, lastPrefH = -1;
    // 布局保护与items版本，用于避免反馈循环与不必要重绘
    private boolean layoutGuard = false;
    private int itemsVersion = 0;
    private int lastItemsVersion = -1;


    // 在 Element1Controller 类中添加
    private boolean isHorizontalLayout = true; // true=从左到右，false=从上到下

    // 添加设置排列方式的方法

    // 修改这个方法

    // ⭐ 新增：事件监听器引用
    private final String listenerId = "deleteItem_" + System.currentTimeMillis();
    private final UIUpdateEventManager eventManager = UIUpdateEventManager.getInstance();
    public void registerUIUpdateEvents() {

        try {
            // 注册强制刷新事件
            eventManager.registerListener(UIUpdateEvent.EventType.DeleteItemEvent,
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

        } catch (Exception e) {
            System.err.println("❌ 注销UI更新事件监听器失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
    public void handleUIUpdateEvent(UIUpdateEvent event) {

        removeLastItem();
        System.gc();

    }

    // ⭐ 清理资源
    public void cleanup() {
        unregisterUIUpdateEvents();
        clearAllItems();
        System.out.println("✅ Element1Controller资源已清理");
    }

    public void setLayoutDirection(boolean horizontal) {
        if (this.isHorizontalLayout == horizontal) {
            return; // 如果状态相同，直接返回
        }

        this.isHorizontalLayout = horizontal;

        // 添加淡出效果
        if (grid != null) {
            javafx.animation.FadeTransition fadeOut = new javafx.animation.FadeTransition(
                    Duration.millis(150), grid);
            fadeOut.setFromValue(1.0);
            fadeOut.setToValue(0.3);

            fadeOut.setOnFinished(e -> {
                // 强制刷新布局
                itemsVersion++;
                lastItemsVersion = -1;
                lastColWidths = null;
                lastRowHeights = null;

                Platform.runLater(() -> {
                    layoutGuard = false;
                    layoutItems();

                    // 淡入效果
                    javafx.animation.FadeTransition fadeIn = new javafx.animation.FadeTransition(
                            Duration.millis(150), grid);
                    fadeIn.setFromValue(0.3);
                    fadeIn.setToValue(1.0);
                    fadeIn.play();
                });
            });

            fadeOut.play();
        }
    }

    @Override
    public void initialize(URL location, ResourceBundle resources) {

        rows = GridStore.getInstance().getRows();
        cols = GridStore.getInstance().getCols();
        isHorizontalLayout = GridStore.getInstance().isHorizontalLayout();

        if (grid != null) {
            grid.getColumnConstraints().clear();
            grid.getRowConstraints().clear();
            // 允许内容随视口拉伸，避免产生额外边距
            grid.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
            grid.setMinSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        }
        if (scroll != null) {
            scroll.setFitToWidth(true);
            scroll.setFitToHeight(true);
            // 视口尺寸变化时重新布局，加入阈值判断避免微小抖动导致持续重绘
            scroll.viewportBoundsProperty().addListener((obs, ov, nv) -> {
                double w = nv.getWidth();
                double h = nv.getHeight();
                if (layoutGuard) return; // 避免布局中的反馈触发
                // ⭐ 优化：降低阈值从8.0到1.0，提高拖动响应速度
                if (Math.abs(w - lastViewportW) > 1.0 || Math.abs(h - lastViewportH) > 1.0) {
                    lastViewportW = w;
                    lastViewportH = h;
                    layoutItemsAsync();
                }
            });
        }
        applyGridSize(rows, cols);

        // ⭐⭐⭐ 初始化全屏查看器
        //fullscreenViewer = new CaptureItemViewer();

        // ⭐⭐⭐ 将查看器添加到容器（延迟到布局完成后）
        // ⭐ 注册DeleteItemEvent监听器
        // ⭐ 设置GridPane背景为蓝色
        if (grid != null) { //#0055FF
            grid.setStyle("-fx-background-color: #1F1F1F; " +  // 深色背景
                    "-fx-padding: 2;");
            grid.setSnapToPixel(false);
            grid.setHgap(2);
            grid.setVgap(2);
        }
        registerUIUpdateEvents();
    }


    /**
     * ⭐ 注册DeleteItemEvent事件监听器
     */



    /** 设置网格行列，范围1-10 */
    public void setGridSize(int r, int c) {
        int nr = Math.max(1, Math.min(10, r));
        int nc = Math.max(1, Math.min(10, c));
        System.out.println("📐 Element1: setGridSize(" + nr + ", " + nc + "), 已有item数: " + items.size());
        rows = nr; cols = nc;

        applyGridSize(nr, nc);
        // 改为异步合并，避免频繁同步重绘
        layoutItemsAsync();
    }

    /** 添加一个抓拍条目（磁盘帧列表，旧格式） */
    public void addDiskCapture(List<DiskFrameRingBuffer.FrameItem> frames, int eventIndex) {
        // 在 FX 线程执行所有 UI 更新
        Platform.runLater(() -> {
            if (!items.isEmpty()) {
                CaptureItemView last = items.get(items.size() - 1);
                last.setDiskFrames(frames, eventIndex);
                // 更新最后项不改变网格结构，不触发布局
            } else {
                CaptureItemView view = new CaptureItemView();
                view.setDiskFrames(frames, eventIndex);
                items.add(view);
                view.setItemIndex(items.size() - 1);  // ⭐ 设置格子索引
                itemsVersion++;
                // 新增项触发布局（使用去抖合并）
                layoutItemsAsync();
            }
        });
    }

    /** ✅ 添加一个抓拍条目（磁盘帧列表，新格式：DiskCaptureCache.DiskFrameItem，零内存） */
    public void addDiskCaptureV2(List<com.acard.acard.capture.DiskCaptureCache.DiskFrameItem> frames, int eventIndex) {
        // 在 FX 线程执行所有 UI 更新
        Platform.runLater(() -> {
            if (!items.isEmpty()) {
                CaptureItemView last = items.get(items.size() - 1);
                last.setDiskFramesV2(frames, eventIndex);
                // 更新最后项不改变网格结构，不触发布局
            } else {
                CaptureItemView view = new CaptureItemView();
                view.setDiskFramesV2(frames, eventIndex);
                items.add(view);
                view.setItemIndex(items.size() - 1);  // ⭐ 设置格子索引
                itemsVersion++;
                // 新增项触发布局（使用去抖合并）
                layoutItemsAsync();
            }
        });
    }

    /** ✅ 通过指定索引更新抓拍项（零内存，线程安全） */
    public void updateItemByIndex(int itemIndex, List<com.acard.acard.capture.DiskCaptureCache.DiskFrameItem> frames, int eventIndex) {
        Platform.runLater(() -> {
            if (itemIndex >= 0 && itemIndex < items.size()) {
                CaptureItemView item = items.get(itemIndex);
                item.setDiskFramesV2(frames, eventIndex);
            } else {
                System.err.println("⚠️ 无效的item索引: " + itemIndex + ", 当前items数量: " + items.size());
            }
        });
    }

    public void addMemoryCapture(List<FrameRingBuffer.FrameItem> frames, int eventIndex) {
        Platform.runLater(() -> {
            if (!items.isEmpty()) {
                CaptureItemView last = items.get(items.size() - 1);
                last.setMemoryFrames(frames, eventIndex);
                // 更新最后项不改变网格结构，不触发布局
            } else {
                CaptureItemView view = new CaptureItemView();
                view.setMemoryFrames(frames, eventIndex);
                items.add(view);
                view.setItemIndex(items.size() - 1);  // ⭐ 设置格子索引
                itemsVersion++;
                // 新增项触发布局（使用去抖合并）
                layoutItemsAsync();
            }
        });
    }

    /**
     * ✅ 追加单个帧到指定的抓拍项（用于事件驱动的后续帧追加）
     */
    public void appendFrameToItem(int index, com.acard.acard.capture.DiskCaptureCache.DiskFrameItem newFrame) {
        Platform.runLater(() -> {
            if (index >= 0 && index < items.size()) {
                CaptureItemView item = items.get(index);
                item.appendDiskFrameV2(newFrame);
            }
        });
    }

    /**
     * ✅ 主控制器可调用：显式新增一个空抓拍项
     * @return 新创建的item索引（线程安全）
     */
    public int addEmptyItem() {
        // ✅ 防止内存爆炸：超过上限时，自动删除最旧的抓拍项
        if (items.size() >= MAX_CAPTURE_ITEMS) {
            items.remove(0);  // 删除最旧的（FIFO），GC会自动释放资源
            updateAllItemIndices();  // ⭐ 删除后更新索引
            System.out.println("⚠️ 抓拍项达到上限(" + MAX_CAPTURE_ITEMS + ")，自动删除最旧项（防止内存爆炸）");
        }

        CaptureItemView view = new CaptureItemView();

        items.add(view);
        int idx = items.size() - 1;
        view.setItemIndex(idx);  // ⭐ 设置格子索引

        // ⭐ 设置删除回调（使用对象引用，避免索引变化问题）
        view.setOnDelete(() -> {
            removeItem(view);
        });

        itemsVersion++;
        layoutItemsAsync();

        // ✅ 返回新创建的item索引（线程安全）
        return idx;
    }
    
    /**
     * ⭐ 更新所有 item 的索引（删除后需要重新编号）
     */
    private void updateAllItemIndices() {
        for (int i = 0; i < items.size(); i++) {
            items.get(i).setItemIndex(i);
        }
    }

    /** 更新最后一个抓拍项（若不存在则忽略）-磁盘 */
    public void updateLastDiskCapture(List<DiskFrameRingBuffer.FrameItem> frames, int eventIndex) {
        if (items.isEmpty()) return;
        Platform.runLater(() -> {
            CaptureItemView last = items.get(items.size() - 1);
            last.setDiskFrames(frames, eventIndex);
            // 更新内容不触发布局
        });
    }

    /** 更新最后一个抓拍项（若不存在则忽略）-内存 */
    public void updateLastMemoryCapture(List<FrameRingBuffer.FrameItem> frames, int eventIndex) {
        if (items.isEmpty()) return;
        Platform.runLater(() -> {
            CaptureItemView last = items.get(items.size() - 1);
            last.setMemoryFrames(frames, eventIndex);
            // 更新内容不触发布局
        });
    }

    /** 向最后一个抓拍项动态添加内存帧（异步推送用） */
    public void appendFramesToLastCapture(List<FrameRingBuffer.FrameItem> newFrames) {
        if (items.isEmpty() || newFrames == null || newFrames.isEmpty()) return;
        Platform.runLater(() -> {
            CaptureItemView last = items.get(items.size() - 1);
            last.appendMemoryFrames(newFrames);
        });
    }

    /** ✅ 向指定索引的抓拍项动态添加内存帧（支持多次快速抓拍） */
    public void appendFramesToCapture(int index, List<FrameRingBuffer.FrameItem> newFrames) {
        if (newFrames == null || newFrames.isEmpty()) {
            System.err.println("⚠️ appendFramesToCapture: newFrames为空");
            return;
        }
        System.out.println("🔧 Element1Controller.appendFramesToCapture: index=" + index + ", newFrames=" + newFrames.size() + "帧, items.size=" + items.size());
        Platform.runLater(() -> {
            if (index >= 0 && index < items.size()) {
                CaptureItemView item = items.get(index);
                System.out.println("  → 调用item[" + index + "].appendMemoryFrames(" + newFrames.size() + "帧)");
                item.appendMemoryFrames(newFrames);
            } else {
                System.err.println("⚠️ 追加帧失败: 索引 " + index + " 超出范围 (总数=" + items.size() + ")");
            }
        });
    }

    /** 向最后一个抓拍项动态添加磁盘帧（慢放异步推送用） */
    public void appendDiskFramesToLastCapture(List<DiskFrameRingBuffer.FrameItem> newFrames) {
        if (items.isEmpty() || newFrames == null || newFrames.isEmpty()) return;
        Platform.runLater(() -> {
            CaptureItemView last = items.get(items.size() - 1);
            last.appendDiskFrames(newFrames);
        });
    }

    /** ✅ 向指定索引的抓拍项动态添加磁盘帧（支持多次快速抓拍） */
    public void appendDiskFramesToCapture(int index, List<DiskFrameRingBuffer.FrameItem> newFrames) {
        if (newFrames == null || newFrames.isEmpty()) return;
        Platform.runLater(() -> {
            if (index >= 0 && index < items.size()) {
                CaptureItemView item = items.get(index);
                item.appendDiskFrames(newFrames);
            } else {
                System.err.println("⚠️ 追加磁盘帧失败: 索引 " + index + " 超出范围 (总数=" + items.size() + ")");
            }
        });
    }

    /** ✅ 获取当前抓拍项总数 */
    public int getCaptureItemCount() {
        return items.size();
    }

    private void layoutItemsAsync() {
        // 合并重绘请求：FX线程内使用 PauseTransition 去抖
        if (Platform.isFxApplicationThread()) {
            requestLayoutDebounced();
        } else {
            Platform.runLater(this::requestLayoutDebounced);
        }
    }

    // 去抖后的统一布局入口
    private void requestLayoutDebounced() {
        if (layoutDebounceTimer == null) {
            // ⭐ 优化：减少延迟从160ms到30ms，提高拖动响应速度
            layoutDebounceTimer = new PauseTransition(Duration.millis(30));
            layoutDebounceTimer.setOnFinished(e -> layoutItems());
        }
        layoutDebounceTimer.stop();
        layoutDebounceTimer.playFromStart();
    }

    /**
     * 锁定最后一个抓拍项的视口大小为当前网格单元大小。
     * 优先使用 ScrollPane 的 viewportBounds；否则使用 GridPane 当前宽高。
     */
    public void lockLastItemViewportWithCurrentCellSize() {
        if (items.isEmpty()) return;
        CaptureItemView last = items.get(items.size() - 1);
        Runnable locker = new Runnable() {
            @Override public void run() {
                double lw = last.getWidth();
                double lh = last.getHeight();
                if (lw > 0 && lh > 0) {
                    // 直接按条目实际大小锁定，并同步prefSize，避免二次膨胀
                    last.setPrefSize(lw, lh);
                    last.lockViewportSize(lw, lh);
                    return;
                }
                double gw = 0, gh = 0;
                if (scroll != null && scroll.getViewportBounds() != null) {
                    gw = scroll.getViewportBounds().getWidth();
                    gh = scroll.getViewportBounds().getHeight();
                } else if (grid != null) {
                    gw = grid.getWidth();
                    gh = grid.getHeight();
                }
                if (gw > 0 && gh > 0) {
                    // 扣除GridPane内边距与间距，计算精确单元尺寸
                    double padH = 0, padV = 0;
                    Insets insets = grid.getInsets();
                    if (insets != null) {
                        padH = insets.getLeft() + insets.getRight();
                        padV = insets.getTop() + insets.getBottom();
                    }
                    double hgap = grid.getHgap();
                    double vgap = grid.getVgap();
                    double contentW = gw - padH - Math.max(0, cols - 1) * hgap;
                    double contentH = gh - padV - Math.max(0, rows - 1) * vgap;
                    double cellW = Math.floor(contentW / Math.max(1, cols));
                    double cellH = Math.floor(contentH / Math.max(1, rows));
                    double safeW = Math.max(0, cellW - 10);
                    double safeH = Math.max(0, cellH - 10);
                    last.setPrefSize(safeW, safeH);
                    last.setMaxSize(safeW, safeH);
                    last.lockViewportSize(safeW, safeH);
                } else {
                    // 等下一帧布局完成后再尝试（通过去抖后的下一次布局）
                    Platform.runLater(this);
                }
            }
        };
        Platform.runLater(locker);
    }




    private void layoutItems() {
        if (grid == null) return;
        if (layoutGuard) return;
        layoutGuard = true;
        grid.getChildren().clear();
        // 计算当前单元格尺寸（优先用ScrollPane视口）
        double gw = 0, gh = 0;
        if (scroll != null && scroll.getViewportBounds() != null) {
            gw = scroll.getViewportBounds().getWidth();
            gh = scroll.getViewportBounds().getHeight();
        } else {
            gw = grid.getWidth();
            gh = grid.getHeight();
        }
        // 扣除GridPane内边距与间距，得到真实可用内容区域
        double padH = 0, padV = 0;
        Insets insets = grid.getInsets();
        if (insets != null) {
            padH = insets.getLeft() + insets.getRight();
            padV = insets.getTop() + insets.getBottom();
        }
        double hgap = grid.getHgap();
        double vgap = grid.getVgap();
        double contentW = gw > 0 ? gw - padH - Math.max(0, cols - 1) * hgap : -1;
        double contentH = gh > 0 ? gh - padV - Math.max(0, rows - 1) * vgap : -1;
        double cellW = contentW > 0 ? (contentW / Math.max(1, cols)) : -1;
        double cellH = contentH > 0 ? (contentH / Math.max(1, rows)) : -1;
        // 像素精确分配：最后一列/行吸收余数，保证总宽高精确匹配
        double[] colWidths = null;
        double[] rowHeights = null;
        if (cellW > 0 && cellH > 0) {
            colWidths = new double[cols];
            rowHeights = new double[rows];
            double baseCol = Math.floor(contentW / cols);
            double baseRow = Math.floor(contentH / rows);
            for (int i = 0; i < cols - 1; i++) colWidths[i] = baseCol;
            for (int i = 0; i < rows - 1; i++) rowHeights[i] = baseRow;
            double usedW = baseCol * Math.max(0, cols - 1);
            double usedH = baseRow * Math.max(0, rows - 1);
            double lastCol = Math.max(0, contentW - usedW);
            double lastRow = Math.max(0, contentH - usedH);
            if (cols > 0) colWidths[cols - 1] = lastCol;
            if (rows > 0) rowHeights[rows - 1] = lastRow;

            // 仅在约束变化时才重建，避免频繁清空/创建导致的CPU抖动
            boolean constraintsChanged = (lastColWidths == null || lastRowHeights == null
                    || !approxEquals(lastColWidths, colWidths, 0.5)
                    || !approxEquals(lastRowHeights, rowHeights, 0.5));
            if (constraintsChanged) {
                grid.getColumnConstraints().clear();
                grid.getRowConstraints().clear();
                for (int i = 0; i < cols; i++) {
                    javafx.scene.layout.ColumnConstraints cc = new javafx.scene.layout.ColumnConstraints();
                    cc.setMinWidth(colWidths[i]);
                    cc.setPrefWidth(colWidths[i]);
                    cc.setMaxWidth(colWidths[i]);
                    cc.setFillWidth(true);
                    grid.getColumnConstraints().add(cc);
                }
                for (int i = 0; i < rows; i++) {
                    javafx.scene.layout.RowConstraints rc = new javafx.scene.layout.RowConstraints();
                    rc.setMinHeight(rowHeights[i]);
                    rc.setPrefHeight(rowHeights[i]);
                    rc.setMaxHeight(rowHeights[i]);
                    rc.setFillHeight(true);
                    grid.getRowConstraints().add(rc);
                }
                lastColWidths = colWidths;
                lastRowHeights = rowHeights;
            }
            double totalW = padH + (cols > 0 ? colWidths[0] * (cols - 1) + colWidths[cols - 1] : 0) + Math.max(0, cols - 1) * hgap;
            double totalH = padV + (rows > 0 ? rowHeights[0] * (rows - 1) + rowHeights[rows - 1] : 0) + Math.max(0, rows - 1) * vgap;
            // 仅在与上次不同（超阈值）时才设置，避免触发ScrollPane视口变化形成反馈回路
            if (Math.abs(totalW - lastPrefW) > 8.0 || Math.abs(totalH - lastPrefH) > 8.0) {
                if (scroll == null) {
                    grid.setPrefSize(totalW, totalH);
                }
                lastPrefW = totalW;
                lastPrefH = totalH;
            }
            // 如果约束未变化且items版本未变化，则跳过后续重建，防止空转重绘
            if (!constraintsChanged && lastItemsVersion == itemsVersion) {
                layoutGuard = false;
                return;
            }
        }
        int idx = 0;
        for (CaptureItemView item : items) {
            int r, c;
            if (isHorizontalLayout) {
                // 行优先排列（从左到右填满一行，再填下一行）
                r = idx / cols;
                c = idx % cols;
            } else {
                // 列优先排列（从上到下填满一列，再填下一列）
                c = idx / rows;
                r = idx % rows;
            }
            if (r >= rows || c >= cols) break;


            // ⭐⭐⭐ 添加鼠标悬停高亮效果
            final int currentIdx = idx;  // 用于日志输出
            //StackPane.setMargin(item, new Insets(1));
            item.setStyle(normalStyle);
            // 鼠标进入时
            item.setOnMouseEntered(event -> {
                item.OnMouseEntered();

                System.out.println("🖱️ 鼠标进入 Item[" + currentIdx + "]");
            });

            // 鼠标离开时
            item.setOnMouseExited(event -> {

                item.OnMouseExited();
            });

            grid.add(item, c, r);
            GridPane.setFillWidth(item, true);
            GridPane.setFillHeight(item, true);
            GridPane.setHgrow(item, javafx.scene.layout.Priority.NEVER);
            GridPane.setVgrow(item, javafx.scene.layout.Priority.NEVER);
            if (colWidths != null && rowHeights != null) {
                double finalCellW = colWidths[c];
                double finalCellH = rowHeights[r];

                // ✅ 设置容器尺寸
                item.setPrefSize(finalCellW, finalCellH);
                item.setMaxSize(finalCellW, finalCellH);

                // ✅ 更新锁定的视口尺寸（不解锁，避免监听器循环）
                item.updateLockedViewportSize(finalCellW, finalCellH);

                System.out.println("   📦 Item[" + idx + "] 设置尺寸: " +
                        String.format("%.0fx%.0f", finalCellW, finalCellH));

                // 合并锁定：后置一次批量锁定，减少 Platform.runLater 任务数量
            } else {
                item.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
            }
            idx++;
        }
        int capacity = Math.max(1, rows * cols);
        while (idx < capacity) {
            int r, c;
            if (isHorizontalLayout) {
                // 行优先排列
                r = idx / cols;
                c = idx % cols;
            } else {
                // 列优先排列
                c = idx / rows;
                r = idx % rows;
            }
            if (r >= rows || c >= cols) break;

            // 🔥 关键修复：根据排列方式计算正确的显示数字
            int displayNumber;
            if (isHorizontalLayout) {
                // 行优先：按原来的顺序
                displayNumber = idx + 1;
            } else {
                // 列优先：按列优先顺序计算显示数字
                // 在列排列中，第一列是1,2,3...，第二列是rows+1, rows+2...
                displayNumber = c * rows + r + 1;
            }

            StackPane placeholder = createPlaceholder(displayNumber);
            grid.add(placeholder, c, r);
            GridPane.setFillWidth(placeholder, true);
            GridPane.setFillHeight(placeholder, true);
            GridPane.setHgrow(placeholder, javafx.scene.layout.Priority.NEVER);
            GridPane.setVgrow(placeholder, javafx.scene.layout.Priority.NEVER);
            if (colWidths != null && rowHeights != null) {
                double pw = colWidths[c];
                double ph = rowHeights[r];
                placeholder.setPrefSize(pw, ph);
                placeholder.setMaxSize(pw, ph);
            } else {
                placeholder.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
            }
            idx++;
        }

        // 已移除重复的批量锁定块，保留下方按约束变化触发的锁定逻辑
        if (colWidths != null && rowHeights != null &&
                (lastColWidths == null || lastRowHeights == null ||
                        !approxEquals(lastColWidths, colWidths, 0.5) || !approxEquals(lastRowHeights, rowHeights, 0.5))) {
            Platform.runLater(() -> {
                int count = Math.min(items.size(), Math.max(1, rows * cols));
                for (int i = 0; i < count; i++) {
                    CaptureItemView item = items.get(i);
                    double w = item.getPrefWidth();
                    double h = item.getPrefHeight();
                    if (w > 0 && h > 0) {
                        item.lockViewportSize(w, h);
                    }
                }
            });
        }

        // 移除二次锁定，避免测量微差导致逐次放大
        lastItemsVersion = itemsVersion;
        layoutGuard = false;
    }


    /** 应用网格大小：清空约束，按子项prefSize驱动 */
    private void applyGridSize(int r, int c) {
        if (grid == null) return;
        grid.getColumnConstraints().clear();
        grid.getRowConstraints().clear();
        grid.setGridLinesVisible(false);
        layoutItemsAsync();
    }

    private StackPane createPlaceholder(int index) {
        StackPane box = new StackPane();
        // ⭐ 深色主题占位符
        box.setStyle("-fx-background-color: #292929; " +
                "-fx-border-color: #292929; " +
                "-fx-border-width: 2px; " +
                "-fx-border-radius: 2; " +
                "-fx-background-radius: 2;");
        javafx.scene.control.Label label = new javafx.scene.control.Label(String.valueOf(index));
        label.setStyle("-fx-text-fill: #666666; -fx-font-size: 18px;");
        box.getChildren().add(label);
        return box;
    }

    /** 清理所有抓拍项并释放内存 */
    public void clearAllItems() {
        Platform.runLater(() -> {


            System.out.println("🧹 Element1Controller.clearAllItems: 清理 " + items.size() + " 个抓拍项");
            // 先清理每个item的内存
            for (CaptureItemView item : items) {

                if(item.getDiskFramesV2()!=null&&item.getDiskFramesV2().size()>0) {

                    CaptureDataManager.getInstance().remove(item.getDiskFramesV2().get(0).getEventId());
                }
                item.cleanup();
            }
            items.clear();
            grid.getChildren().clear();

            // ⭐ 重置网格约束，避免行列选择失效
            grid.getColumnConstraints().clear();
            grid.getRowConstraints().clear();
            // ⭐ 重置缓存的约束数据
            lastColWidths = null;
            lastRowHeights = null;
            lastPrefW = 0;
            lastPrefH = 0;
            itemsVersion++;

            // ⭐ 发送删除Item事件（清空所有项）
            layoutItemsAsync();

            // 强制GC，释放已清理的Image对象
            System.gc();
            System.out.println("✅ 已清理所有抓拍项、网格约束并触发GC");
        });
    }

    /**
     * ⭐ 删除指定索引的抓拍项
     * @param index 要删除的项索引
     */
    public void removeItemByIndex(final int index) {
        Platform.runLater(() -> {
            // ⭐ 查找实际的item（因为索引可能已经变化）
            CaptureItemView itemToRemove = null;
            int actualIndex = -1;

            // 遍历查找当前items列表中对应的item
            for (int i = 0; i < items.size(); i++) {
                // 这里需要通过某种方式识别item，暂时用简单的索引匹配
                // 如果items列表的顺序没有变化，可以直接用index
                if (i == index && i < items.size()) {
                    itemToRemove = items.get(i);
                    actualIndex = i;
                    break;
                }
            }

            if (itemToRemove == null || actualIndex == -1) {
                System.out.println("⚠️ 无法删除item：索引 " + index + " 超出范围（当前共 " + items.size() + " 项）");
                return;
            }

            System.out.println("🗑️ 删除抓拍项: 索引 " + actualIndex + "（共 " + items.size() + " 项）");

            // 清理该item的内存
            itemToRemove.cleanup();

            // 从列表中移除
            items.remove(actualIndex);

            // 从网格中移除
            grid.getChildren().remove(itemToRemove);
            
            // ⭐ 更新所有 item 的索引
            updateAllItemIndices();

            // 更新版本并重新布局
            itemsVersion++;
            layoutItemsAsync();
            System.gc();
            LogTools.getInstance().logRecord("✅ 已删除抓拍项 [" + actualIndex + "]，剩余 " + items.size() + " 项");
            // ⭐ 发送删除Item事件
            /*int remainingCount = items.size();
            try {
                UIUpdateEvent.DeleteItemData deleteData = new UIUpdateEvent.DeleteItemData(
                    actualIndex,
                    remainingCount
                );
                UIUpdateEvent event = new UIUpdateEvent(
                    UIUpdateEvent.EventType.DeleteItemEvent,
                    "Element1Controller",
                    deleteData
                );
                UIUpdateEventManager.getInstance().fireEvent(event);
                System.out.println("📢 发送DeleteItemEvent: 索引=" + actualIndex + ", 剩余=" + remainingCount);
            } catch (Exception e) {
                System.err.println("❌ 发送DeleteItemEvent失败: " + e.getMessage());
            }

            System.gc();
            LogTools.getInstance().logRecord("✅ 已删除抓拍项 [" + actualIndex + "]，剩余 " + items.size() + " 项");*/
        });
    }

    /**
     * ⭐ 删除指定的抓拍项（通过对象引用）
     * @param item 要删除的项
     */
    public void removeItem(CaptureItemView item) {
        Platform.runLater(() -> {
            int index = items.indexOf(item);
            if (index >= 0) {
                removeItemByIndex(index);
            } else {
                System.out.println("⚠️ 无法删除item：未在列表中找到该项");
            }
        });
    }

    /**
     * ⭐ 删除最后一项抓拍项
     * @return true 删除成功，false 列表为空无法删除
     */
    public boolean removeLastItem() {
        if (items.isEmpty()) {
            System.out.println("⚠️ 无法删除最后一项：列表为空");
            return false;
        }

        int lastIndex = items.size() - 1;
        System.out.println("🗑️ 删除最后一项: 索引 " + lastIndex);
        removeItemByIndex(lastIndex);
        return true;
    }

    /**
     * 获取Element1的根节点，用于快捷键管理器检测鼠标位置
     */
    public Node getRoot() {
        return scroll; // 返回ScrollPane作为根节点
    }

    // Helper for approximate equality of arrays to avoid unnecessary grid constraint rebuilds
    private static boolean approxEquals(double[] a, double[] b, double epsilon) {
        if (a == null || b == null) return false;
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (Math.abs(a[i] - b[i]) > epsilon) {
                return false;
            }
        }
        return true;
    }
}