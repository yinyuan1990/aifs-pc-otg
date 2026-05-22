package com.acard.acard.ui;

import com.acard.acard.store.GridStore;
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
 * ⚡ AI 网格视图控制器
 * 
 * 复用 Element1ControllerV2 的布局逻辑，但使用 AiCardItem 作为 item
 * 
 * 功能：
 * - 行列网格布局
 * - 行、列数量调整
 * - 横向/纵向排列切换
 * - 显示扑克牌识别结果
 */
public class AiGridView implements Initializable {
    
    @FXML private ScrollPane scroll;
    @FXML private GridPane grid;
    
    private int rows = 2;
    private int cols = 3;
    private boolean isHorizontalLayout = true;
    
    private final List<AiCardItem> items = new ArrayList<>();
    private static final int MAX_CARD_ITEMS = 36;  // 最大 6x6 = 36 个
    
    // ⚡ 高效布局管理器
    private AiGridLayoutManager layoutManager;
    
    // 初始化标志
    private boolean isFirstLayout = true;
    
    @Override
    public void initialize(URL location, ResourceBundle resources) {
        // 从 GridStore 读取配置（与抓拍区域共享配置）
        rows = GridStore.getInstance().getRows();
        cols = GridStore.getInstance().getCols();
        isHorizontalLayout = GridStore.getInstance().isHorizontalLayout();
        
        // ⚡ 初始化布局管理器
        layoutManager = new AiGridLayoutManager(grid, scroll, items);
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
            
            // 设置 viewport 背景色
            Platform.runLater(() -> {
                Node viewport = scroll.lookup(".viewport");
                if (viewport != null) {
                    viewport.setStyle("-fx-background-color: #1F1F1F;");
                }
            });
            
            // ⚡ 监听 ScrollPane 的实际宽高变化
            scroll.widthProperty().addListener((obs, ov, nv) -> {
                if (nv.doubleValue() > 0) {
                    if (isFirstLayout && scroll.getHeight() > 0) {
                        isFirstLayout = false;
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
        
        System.out.println("✅ AiGridView 初始化完成 - " + rows + "x" + cols + 
                          (isHorizontalLayout ? " 横向" : " 纵向"));
    }
    
    // ============ 布局控制方法 ============
    
    /**
     * ⚡ 设置网格行列数
     */
    public void setGridSize(int r, int c) {
        int nr = Math.max(1, Math.min(6, r));
        int nc = Math.max(1, Math.min(6, c));
        
        if (rows == nr && cols == nc) {
            return;
        }
        
        rows = nr;
        cols = nc;
        
        // 同步保存到 GridStore
        GridStore.getInstance().setRows(rows);
        GridStore.getInstance().setCols(cols);
        
        System.out.println("📐 AI设置网格大小: " + rows + "x" + cols);
        layoutManager.setGridSize(rows, cols);
        
        // 重新初始化卡片数量
        initializeCards();
    }
    
    /**
     * ⚡ 切换排列方向
     */
    public void setLayoutDirection(boolean horizontal) {
        if (this.isHorizontalLayout == horizontal) {
            return;
        }
        
        this.isHorizontalLayout = horizontal;
        System.out.println("🔄 AI切换排列方向: " + (horizontal ? "横向" : "纵向"));
        
        layoutManager.setLayoutDirection(horizontal);
        
        // 同步保存到 GridStore
        GridStore.getInstance().setHorizontalLayout(horizontal);
    }
    
    /**
     * 初始化卡片（根据行列数创建空卡片）
     */
    public void initializeCards() {
        Platform.runLater(() -> {
            // 清空现有
            clearAllCards();
            
            int totalItems = rows * cols;
            for (int i = 0; i < totalItems && i < MAX_CARD_ITEMS; i++) {
                AiCardItem item = new AiCardItem();
                items.add(item);
                layoutManager.addItem(item, i);
            }
            
            System.out.println("✅ AI初始化 " + items.size() + " 个卡片");
        });
    }
    
    // ============ 卡片数据操作 ============
    
    /**
     * 设置指定索引的卡片数据
     */
    public void setCardData(int index, String rank, AiCardItem.Suit suit) {
        Platform.runLater(() -> {
            if (index >= 0 && index < items.size()) {
                items.get(index).setCard(rank, suit);
            }
        });
    }
    
    /**
     * 清空所有卡片数据（但保留卡片组件）
     */
    public void clearAllCardData() {
        Platform.runLater(() -> {
            for (AiCardItem item : items) {
                item.clear();
            }
        });
    }
    
    /**
     * 清空所有卡片
     */
    public void clearAllCards() {
        Platform.runLater(() -> {
            for (AiCardItem item : items) {
                item.clear();
            }
            items.clear();
            grid.getChildren().clear();
            layoutManager.setGridSize(rows, cols);
        });
    }
    
    /**
     * 添加测试数据
     */
    public void addTestData() {
        String[] ranks = {"A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K"};
        AiCardItem.Suit[] suits = AiCardItem.Suit.values();
        
        Platform.runLater(() -> {
            for (int i = 0; i < items.size(); i++) {
                String rank = ranks[i % ranks.length];
                AiCardItem.Suit suit = suits[i % suits.length];
                items.get(i).setCard(rank, suit);
            }
        });
    }
    
    // ============ 获取信息方法 ============
    
    public int getRows() { return rows; }
    public int getCols() { return cols; }
    public boolean isHorizontalLayout() { return isHorizontalLayout; }
    public int getCardCount() { return items.size(); }
    
    public Node getRoot() {
        return scroll;
    }
    
    public ScrollPane getScroll() {
        return scroll;
    }
    
    /**
     * 清理资源
     */
    public void cleanup() {
        clearAllCards();
        System.out.println("✅ AiGridView 资源已清理");
    }
}
