package com.acard.acard.ui;

import com.acard.acard.capture.DiskCaptureCache;
import com.acard.acard.events.UIUpdateEvent;
import com.acard.acard.events.UIUpdateEventManager;
import com.acard.acard.store.GridStore;
import com.acard.acard.tools.CaptureDataManager;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.GridPane;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;

/**
 * ⚡ Element1 控制器 V2 - 高效版本
 * 
 * 核心特性：
 * - 0延迟切换排列方向（横向/纵向）
 * - 增量操作，不清空重建
 * - 智能尺寸计算和适配
 * - 支持动态行列数调整
 * - 拖动实时刷新尺寸
 * - 删除自动适配
 * 
 * 重用现有组件：
 * - CaptureItemView
 * - DiskCaptureCache.DiskFrameItem
 * - FrameRingBuffer.FrameItem
 */
public class Element1ControllerV2 implements Initializable {
    
    @FXML private ScrollPane scroll;
    @FXML private GridPane grid;
    
    private int rows = 1;
    private int cols = 1;
    private boolean isHorizontalLayout = true;
    
    private final List<CaptureItemView> items = new ArrayList<>();
    private static final int MAX_CAPTURE_ITEMS = 100;
    
    // ⭐ 静态实例引用（用于偏移同步）
    private static Element1ControllerV2 instance;
    
    // ⚡ 高效布局管理器
    private GridLayoutManager layoutManager;
    
    // 事件管理
    private final String listenerId = "element1v2_" + System.currentTimeMillis();
    private final UIUpdateEventManager eventManager = UIUpdateEventManager.getInstance();
    
    // 初始化标志
    private boolean isFirstLayout = true;
    
    // 事件监听器
    private java.util.function.Consumer<UIUpdateEvent> deleteItemListener;
    
    @Override
    public void initialize(URL location, ResourceBundle resources) {
        // ⭐ 设置静态实例
        instance = this;
        
        // 从 GridStore 读取配置
        rows = GridStore.getInstance().getRows();
        cols = GridStore.getInstance().getCols();
        isHorizontalLayout = GridStore.getInstance().isHorizontalLayout();
        
        // ⚡ 初始化布局管理器
        layoutManager = new GridLayoutManager(grid, scroll, items);
        layoutManager.setGridSize(rows, cols);
        layoutManager.setLayoutDirection(isHorizontalLayout);
        
        // 配置 GridPane
        if (grid != null) {
            grid.setStyle("-fx-background-color: #1F1F1F; -fx-padding: 2;");
            grid.setSnapToPixel(false);
            grid.setHgap(2);
            grid.setVgap(2);
        }
        
        // 配置 ScrollPane
        if (scroll != null) {
            scroll.setFitToWidth(true);
            scroll.setFitToHeight(true);
            scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            scroll.setStyle("-fx-background-color: #1F1F1F; -fx-background: #1F1F1F; -fx-border-color: transparent; -fx-border-width: 0;");
            
            // ⭐ 设置 viewport 背景色（需要延迟设置，等待 viewport 初始化）
            javafx.application.Platform.runLater(() -> {
                javafx.scene.Node viewport = scroll.lookup(".viewport");
                if (viewport != null) {
                    viewport.setStyle("-fx-background-color: #1F1F1F;");
                }
            });
            
            // ⚡ 监听 ScrollPane 的实际宽高变化（用于初始化和拖动）
            scroll.widthProperty().addListener((obs, ov, nv) -> {
                if (nv.doubleValue() > 0) {
                    if (isFirstLayout && scroll.getHeight() > 0) {
                        isFirstLayout = false;
                        System.out.println("⚡ 首次布局完成，ScrollPane 尺寸: " + nv.doubleValue() + "x" + scroll.getHeight());
                    }
                    layoutManager.refreshItemSizes();
                }
            });
            
            scroll.heightProperty().addListener((obs, ov, nv) -> {
                if (nv.doubleValue() > 0 && scroll.getWidth() > 0) {
                    layoutManager.refreshItemSizes();
                }
            });
        }
        
        registerUIUpdateEvents();
        
        System.out.println("✅ Element1ControllerV2 初始化完成 - " + rows + "x" + cols + 
                          (isHorizontalLayout ? " 横向" : " 纵向"));
    }
    
    /**
     * 注册UI事件监听器
     */

    public void handleUIUpdateEvent(UIUpdateEvent event) {

        removeLastItem();


    }


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
     * 注销UI事件监听器
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
    
    // ============ 布局控制方法 ============
    
    /**
     * ⚡ 设置网格行列数（触发布局刷新）
     */
    public void setGridSize(int r, int c) {
        int nr = Math.max(1, Math.min(10, r));
        int nc = Math.max(1, Math.min(10, c));
        
        if (rows == nr && cols == nc) {
            return; // 没有变化
        }
        
        rows = nr;
        cols = nc;
        
        System.out.println("📐 设置网格大小: " + rows + "x" + cols);
        layoutManager.setGridSize(rows, cols);
    }
    
    /**
     * ⚡ 切换排列方向（0延迟）
     */
    public void setLayoutDirection(boolean horizontal) {
        if (this.isHorizontalLayout == horizontal) {
            return;
        }
        
        this.isHorizontalLayout = horizontal;
        System.out.println("🔄 切换排列方向: " + (horizontal ? "横向" : "纵向"));
        
        layoutManager.setLayoutDirection(horizontal);
        
        // 同步保存到 GridStore
        GridStore.getInstance().setHorizontalLayout(horizontal);
    }
    
    /**
     * ⚡ 刷新布局尺寸（供外部调用，窗口切换后刷新）
     */
    public void refreshLayoutSizes() {
        if (layoutManager != null) {
            Platform.runLater(() -> layoutManager.refreshItemSizes());
        }
    }
    
    // ============ Item 添加方法 ============
    
    /**
     * ⚡ 直接添加新的抓拍项（像Android adapter）
     * - 自动管理100个上限
     * - 增量添加，不触发完整布局
     * - 自动计算并设置item尺寸
     */
    public void addNewCaptureItemDirect(List<DiskCaptureCache.DiskFrameItem> frames, int eventIndex) {
        Platform.runLater(() -> {
            // 防止内存爆炸
            if (items.size() >= MAX_CAPTURE_ITEMS) {
                CaptureItemView oldest = items.remove(0);
                
                // ⚡ 清理最旧项的资源
                if (oldest.getDiskFramesV2() != null && oldest.getDiskFramesV2().size() > 0) {
                    String eventId = oldest.getDiskFramesV2().get(0).getEventId();
                    CaptureDataManager.getInstance().remove(eventId);
                    System.out.println("🗑️ 已从 CaptureDataManager 移除最旧项 eventId: " + eventId);
                }
                oldest.cleanup();
                
                layoutManager.removeItem(oldest, 0);
                System.out.println("⚠️ 抓拍项达到上限(100)，删除最旧项");
            }
            
            // 创建新item
            CaptureItemView view = new CaptureItemView();
            view.setDiskFramesV2(frames, eventIndex);
            
            // 设置删除回调
            view.setOnDelete(() -> removeItem(view));
            
            // 添加到列表
            items.add(view);
            int idx = items.size() - 1;
            view.setItemIndex(idx);  // ⭐ 设置格子索引
            
            // ⚡ 使用布局管理器添加（自动计算尺寸并适配）
            layoutManager.addItem(view, idx);
            
            System.out.println("✅ 新增抓拍项[" + idx + "], 当前共 " + items.size() + " 个");
        });
    }
    
    /**
     * ⚡ 添加磁盘抓拍（推荐使用 addNewCaptureItemDirect）
     */
    public void addDiskCaptureV2(List<DiskCaptureCache.DiskFrameItem> frames, int eventIndex) {
        Platform.runLater(() -> {
            if (!items.isEmpty()) {
                CaptureItemView last = items.get(items.size() - 1);
                last.setDiskFramesV2(frames, eventIndex);
            } else {
                CaptureItemView view = new CaptureItemView();
                view.setDiskFramesV2(frames, eventIndex);
                view.setOnDelete(() -> removeItem(view));
                
                items.add(view);
                int idx = items.size() - 1;
                view.setItemIndex(idx);  // ⭐ 设置格子索引
                layoutManager.addItem(view, idx);
            }
        });
    }
    
    /**
     * 创建空的抓拍项
     */
    public int addEmptyItem() {
        if (items.size() >= MAX_CAPTURE_ITEMS) {
            CaptureItemView oldest = items.remove(0);
            layoutManager.removeItem(oldest, 0);
            // ⭐ 删除后需要更新所有 item 的索引
            updateAllItemIndices();
        }
        
        CaptureItemView view = new CaptureItemView();
        view.setOnDelete(() -> removeItem(view));
        
        items.add(view);
        int idx = items.size() - 1;
        view.setItemIndex(idx);  // ⭐ 设置格子索引
        
        layoutManager.addItem(view, idx);
        
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
    
    // ============ Item 更新方法 ============
    
    /**
     * 通过索引更新抓拍项
     */
    public void updateItemByIndex(int itemIndex, List<DiskCaptureCache.DiskFrameItem> frames, int eventIndex) {
        Platform.runLater(() -> {
            if (itemIndex >= 0 && itemIndex < items.size()) {
                CaptureItemView item = items.get(itemIndex);
                item.setDiskFramesV2(frames, eventIndex);
            } else {
                System.err.println("⚠️ 无效的item索引: " + itemIndex);
            }
        });
    }
    
    /**
     * 追加帧到指定item
     */
    public void appendFrameToItem(int index, DiskCaptureCache.DiskFrameItem newFrame) {
        Platform.runLater(() -> {
            if (index >= 0 && index < items.size()) {
                items.get(index).appendDiskFrameV2(newFrame);
            }
        });
    }
    
    // ============ Item 删除方法 ============
    
    /**
     * ⚡ 删除指定的item（触发尺寸适配）
     */
    public void removeItem(CaptureItemView item) {
        Platform.runLater(() -> {
            int index = items.indexOf(item);
            if (index >= 0) {
                // ⚡ 清理 CaptureDataManager
                if (item.getDiskFramesV2() != null && item.getDiskFramesV2().size() > 0) {
                    String eventId = item.getDiskFramesV2().get(0).getEventId();
                    CaptureDataManager.getInstance().remove(eventId);
                    System.out.println("🗑️ 已从 CaptureDataManager 移除 eventId: " + eventId);
                }
                
                // 清理 item 资源
                item.cleanup();
                
                // 从列表移除
                items.remove(index);
                layoutManager.removeItem(item, index);
                
                // ⭐ 更新所有 item 的索引
                updateAllItemIndices();
                
                System.out.println("✅ 删除抓拍项[" + index + "], 剩余 " + items.size() + " 个");
            }
        });
    }
    
    /**
     * 通过索引删除item
     */
    public void removeItemByIndex(int index) {
        Platform.runLater(() -> {
            if (index >= 0 && index < items.size()) {
                CaptureItemView item = items.get(index);
                
                // ⚡ 清理 CaptureDataManager
                if (item.getDiskFramesV2() != null && item.getDiskFramesV2().size() > 0) {
                    String eventId = item.getDiskFramesV2().get(0).getEventId();
                    CaptureDataManager.getInstance().remove(eventId);
                    System.out.println("🗑️ 已从 CaptureDataManager 移除 eventId: " + eventId);
                }
                
                // 清理 item 资源
                item.cleanup();
                
                // 从列表移除
                items.remove(index);
                layoutManager.removeItem(item, index);
                
                // ⭐ 更新所有 item 的索引
                updateAllItemIndices();
                
                System.out.println("✅ 删除抓拍项[" + index + "], 剩余 " + items.size() + " 个");
            }
        });
    }
    
    /**
     * 删除最后一个item
     */
    public boolean removeLastItem() {
        if (items.isEmpty()) {
            return false;
        }
        removeItemByIndex(items.size() - 1);
        return true;
    }
    
    /**
     * 清空所有item
     */
    public void clearAllItems() {
        Platform.runLater(() -> {
            for (CaptureItemView item : items) {
                if(item.getDiskFramesV2()!=null&&item.getDiskFramesV2().size()>0) {
                    CaptureDataManager.getInstance().remove(item.getDiskFramesV2().get(0).getEventId());
                }
                item.cleanup();
            }
            items.clear();
            grid.getChildren().clear();
            layoutManager.setGridSize(rows, cols); // 重新初始化占位符
            System.gc();
            System.out.println("✅ 已清空所有抓拍项");
        });
    }
    
    // ============ 视口锁定方法 ============
    
    /**
     * 锁定最后一个item的视口大小
     */
    public void lockLastCaptureViewport() {
        if (items.isEmpty()) return;
        // 布局管理器已经自动处理了尺寸
    }
    
    // ============ 获取信息方法 ============
    
    /**
     * 获取当前抓拍项总数
     */
    public int getCaptureItemCount() {
        return items.size();
    }
    
    /**
     * 获取根节点
     */
    public Node getRoot() {
        return scroll;
    }
    
    /**
     * 获取 ScrollPane（兼容旧版API）
     */
    public ScrollPane getScroll() {
        return scroll;
    }
    
    /**
     * 锁定最后一个抓拍项的视口尺寸（兼容旧版API）
     * V2 版本中已在 addItem 时自动锁定，此方法仅用于兼容
     */
    public void lockLastItemViewport() {
        if (items.isEmpty()) return;
        
        Platform.runLater(() -> {
            CaptureItemView last = items.get(items.size() - 1);
            if (layoutManager != null) {
                // 确保最后一个 item 的尺寸已锁定
                double w = last.getWidth();
                double h = last.getHeight();
                if (w > 0 && h > 0) {
                    last.setPrefSize(w, h);
                    last.lockViewportSize(w, h);
                }
            }
        });
    }
    
    // ============ 清理方法 ============
    
    /**
     * 清理资源
     */
    public void cleanup() {
        unregisterUIUpdateEvents();
        clearAllItems();
        instance = null;
        System.out.println("✅ Element1ControllerV2 资源已清理");
    }
    
    // ============ 偏移同步方法 ============
    
    /**
     * ⭐ 静态方法：检查并应用偏移（从 SimpleWebRTCPlayer 调用）
     * 当 jpegIndex 变化时，遍历所有 item，对未应用偏移的 item 进行匹配检查
     * 
     * @param jpegIndex 当前最新的 JPEG 索引
     */
    public static void checkAndApplyOffset(int jpegIndex) {
        // ⭐ 先读取偏移值，0 或 1 不需要遍历
        int offset = com.acard.acard.store.CaptureStore.getInstance().getOffset();
        if (offset <= 1) {
            return;  // 偏移 0 或 1，不需要处理
        }
        
        if (instance == null || instance.items.isEmpty()) {
            return;
        }
        
        // 遍历所有 item
        for (CaptureItemView item : instance.items) {
            if (item == null || item.hasAppliedOffset()) {
                continue;  // 跳过已处理的
            }
            
            int eventFrameId = item.getEventFrameId();
            if (eventFrameId <= 0) {
                continue;  // 无效 frameId
            }
            
            // 匹配条件：eventFrameId + offset == jpegIndex
            if (eventFrameId + offset == jpegIndex) {
                // 匹配！在 UI 线程应用偏移
                Platform.runLater(() -> {
                    item.applyOffset(offset);
                    System.out.println("📐 [偏移同步] item[" + item.getItemIndex() + 
                        "] eventFrameId=" + eventFrameId + " + offset=" + offset + 
                        " == jpegIndex=" + jpegIndex);
                });
            }
        }
    }
}

