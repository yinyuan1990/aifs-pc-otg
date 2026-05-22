package com.acard.acard.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.RowConstraints;
import javafx.scene.layout.StackPane;

import java.util.List;

/**
 * ⚡ AI 网格布局管理器
 * - 0延迟切换排列方向（横向/纵向）
 * - 增量更新，不清空重建
 * - 智能尺寸计算和适配
 * - 支持动态行列数调整
 * 
 * 复用 GridLayoutManager 的逻辑，但使用 AiCardItem 作为 item
 */
public class AiGridLayoutManager {
    
    private final GridPane grid;
    private final ScrollPane scroll;
    private final List<AiCardItem> items;
    
    private int rows = 1;
    private int cols = 1;
    private boolean isHorizontalLayout = true;
    
    // 缓存的单元格尺寸
    private double cellWidth = 0;
    private double cellHeight = 0;
    
    // 占位符样式 - 深色主题
    private static final String PLACEHOLDER_STYLE = 
        "-fx-background-color: #292929; " +
        "-fx-border-color: #292929; " +
        "-fx-border-width: 2px; " +
        "-fx-border-radius: 2; " +
        "-fx-background-radius: 2;";
    
    private static final String NUMBER_LABEL_STYLE =
        "-fx-text-fill: #666666; " +
        "-fx-font-size: 12px; " +
        "-fx-font-weight: bold;";
    
    public AiGridLayoutManager(GridPane grid, ScrollPane scroll, List<AiCardItem> items) {
        this.grid = grid;
        this.scroll = scroll;
        this.items = items;
        
        // ⚡ 延迟设置网格约束和计算初始尺寸（等待 ScrollPane 布局完成）
        Platform.runLater(() -> {
            Platform.runLater(() -> {
                updateGridConstraints();
                cellWidth = calculateCellWidth();
                cellHeight = calculateCellHeight();
                if (cellWidth > 0 && cellHeight > 0) {
                    System.out.println("⚡ AiGridLayoutManager 初始化完成: " + cellWidth + "x" + cellHeight);
                }
            });
        });
    }
    
    /**
     * 设置行列数
     */
    public void setGridSize(int rows, int cols) {
        this.rows = Math.max(1, Math.min(10, rows));
        this.cols = Math.max(1, Math.min(10, cols));
        
        cellWidth = calculateCellWidth();
        cellHeight = calculateCellHeight();
        System.out.println("⚡ setGridSize: 同步更新缓存尺寸 " + cellWidth + "x" + cellHeight);
        
        refreshLayout();
    }
    
    /**
     * ⚡ 切换排列方向（0延迟）
     */
    public void setLayoutDirection(boolean horizontal) {
        if (this.isHorizontalLayout == horizontal) {
            return;
        }
        this.isHorizontalLayout = horizontal;
        
        Platform.runLater(() -> {
            // ⚡ 直接移动现有item到新位置
            for (int idx = 0; idx < items.size(); idx++) {
                AiCardItem item = items.get(idx);
                Position pos = calculatePosition(idx);
                boolean isInGrid = grid.getChildren().contains(item);
                
                if (pos.isValid()) {
                    if (isInGrid) {
                        GridPane.setRowIndex(item, pos.row);
                        GridPane.setColumnIndex(item, pos.col);
                    }
                } else {
                    // ⭐ 超出范围的 item 从 grid 中移除
                    if (isInGrid) {
                        grid.getChildren().remove(item);
                    }
                }
            }
            
            // 更新占位符
            updatePlaceholders();
            
            // ⭐ 切换方向后也要刷新尺寸
            refreshItemSizes();
        });
    }
    
    /**
     * ⚡ 增量添加item（不触发完整布局）
     */
    public void addItem(AiCardItem item, int index) {
        Platform.runLater(() -> {
            Position pos = calculatePosition(index);
            
            if (!pos.isValid()) {
                System.out.println("⚠️ AiCardItem[" + index + "] 超出网格范围");
                return;
            }
            
            double itemWidth = 0;
            double itemHeight = 0;
            
            if (pos.col < grid.getColumnConstraints().size() && pos.row < grid.getRowConstraints().size()) {
                itemWidth = grid.getColumnConstraints().get(pos.col).getPrefWidth();
                itemHeight = grid.getRowConstraints().get(pos.row).getPrefHeight();
            }
            
            if (itemWidth <= 0 || itemHeight <= 0) {
                itemWidth = calculateCellWidth();
                itemHeight = calculateCellHeight();
            }
            
            if (itemWidth > 0 && cellWidth <= 0) {
                cellWidth = itemWidth;
            }
            if (itemHeight > 0 && cellHeight <= 0) {
                cellHeight = itemHeight;
            }
            
            if (itemWidth > 0 && itemHeight > 0) {
                item.setPrefSize(itemWidth, itemHeight);
                item.setMaxSize(itemWidth, itemHeight);
                item.setMinSize(itemWidth, itemHeight);
            }
            
            grid.add(item, pos.col, pos.row);
            GridPane.setFillWidth(item, true);
            GridPane.setFillHeight(item, true);
            
            removePlaceholderAt(pos.row, pos.col);
        });
    }
    
    /**
     * ⚡ 增量删除item（删除后数据往前排，和抓拍一样）
     */
    public void removeItem(AiCardItem item, int index) {
        Platform.runLater(() -> {
            // 从 grid 中移除 item
            grid.getChildren().remove(item);
            
            // 计算网格最大容量
            int maxVisible = rows * cols;
            
            // 重新排列所有剩余的 items（往前移动，包括溢出的item）
            for (int idx = 0; idx < items.size(); idx++) {
                AiCardItem currentItem = items.get(idx);
                Position pos = calculatePosition(idx);
                
                if (pos.isValid()) {
                    boolean isInGrid = grid.getChildren().contains(currentItem);
                    
                    if (!isInGrid && idx < maxVisible) {
                        // 溢出的item现在有空位了，添加到grid中
                        System.out.println("🔧 溢出AiCardItem[" + idx + "] 现在有空位，添加到位置(" + pos.row + "," + pos.col + ")");
                        
                        double itemWidth = calculateCellWidth();
                        double itemHeight = calculateCellHeight();
                        
                        if (itemWidth > 0 && itemHeight > 0) {
                            currentItem.setPrefSize(itemWidth, itemHeight);
                            currentItem.setMaxSize(itemWidth, itemHeight);
                            currentItem.setMinSize(itemWidth, itemHeight);
                        }
                        
                        grid.add(currentItem, pos.col, pos.row);
                        GridPane.setFillWidth(currentItem, true);
                        GridPane.setFillHeight(currentItem, true);
                        
                        removePlaceholderAt(pos.row, pos.col);
                    } else if (isInGrid) {
                        // 更新已有 item 的位置
                        GridPane.setRowIndex(currentItem, pos.row);
                        GridPane.setColumnIndex(currentItem, pos.col);
                    }
                }
            }
            
            // 更新占位符
            updatePlaceholders();
            
            // 重新适配剩余item的尺寸
            refreshItemSizes();
            
            System.out.println("✅ 删除后重新排列，剩余 " + items.size() + " 个 AI卡片，网格容量 " + maxVisible);
        });
    }
    
    /**
     * ⚡ 刷新布局（窗口切换后使用）
     * 先清除所有 items，再重新添加，避免重叠
     */
    public void refreshLayout() {
        Platform.runLater(() -> {
            // ⚡ 先清除 grid 中的所有子节点（包括 items 和占位符）
            grid.getChildren().clear();
            
            // 更新网格约束
            updateGridConstraints();
            
            cellWidth = calculateCellWidth();
            cellHeight = calculateCellHeight();
            
            double[] colWidths = new double[cols];
            double[] rowHeights = new double[rows];
            
            for (int i = 0; i < cols; i++) {
                if (i < grid.getColumnConstraints().size()) {
                    colWidths[i] = grid.getColumnConstraints().get(i).getPrefWidth();
                } else {
                    colWidths[i] = cellWidth;
                }
            }
            
            for (int i = 0; i < rows; i++) {
                if (i < grid.getRowConstraints().size()) {
                    rowHeights[i] = grid.getRowConstraints().get(i).getPrefHeight();
                } else {
                    rowHeights[i] = cellHeight;
                }
            }
            
            // ⚡ 重新添加所有 items
            for (int idx = 0; idx < items.size(); idx++) {
                AiCardItem item = items.get(idx);
                Position pos = calculatePosition(idx);
                
                if (pos.isValid()) {
                    double itemWidth = colWidths[pos.col];
                    double itemHeight = rowHeights[pos.row];
                    
                    if (itemWidth > 0 && itemHeight > 0) {
                        item.setPrefSize(itemWidth, itemHeight);
                        item.setMaxSize(itemWidth, itemHeight);
                        item.setMinSize(itemWidth, itemHeight);
                    }
                    
                    grid.add(item, pos.col, pos.row);
                    GridPane.setFillWidth(item, true);
                    GridPane.setFillHeight(item, true);
                }
            }
            
            // 添加占位符
            updatePlaceholders();
            
            System.out.println("⚡ AI 布局已刷新，共 " + items.size() + " 个卡片");
        });
    }
    
    /**
     * 刷新所有item的尺寸（用于拖动时）
     * 注意：占位符通过网格约束自动适应，不需要手动设置尺寸
     */
    public void refreshItemSizes() {
        Platform.runLater(() -> {
            // ⚡ 先更新网格约束
            updateGridConstraints();
            
            double newCellWidth = calculateCellWidth();
            double newCellHeight = calculateCellHeight();
            
            if (newCellWidth > 0 && newCellHeight > 0) {
                // 更新缓存
                cellWidth = newCellWidth;
                cellHeight = newCellHeight;
                
                // ⚡ 获取精确的列宽和行高（从网格约束中读取）
                double[] colWidths = new double[cols];
                double[] rowHeights = new double[rows];
                
                for (int i = 0; i < cols; i++) {
                    if (i < grid.getColumnConstraints().size()) {
                        colWidths[i] = grid.getColumnConstraints().get(i).getPrefWidth();
                    } else {
                        colWidths[i] = cellWidth;
                    }
                }
                
                for (int i = 0; i < rows; i++) {
                    if (i < grid.getRowConstraints().size()) {
                        rowHeights[i] = grid.getRowConstraints().get(i).getPrefHeight();
                    } else {
                        rowHeights[i] = cellHeight;
                    }
                }
                
                // ⚡ 更新所有 AiCardItem 尺寸
                for (int idx = 0; idx < items.size(); idx++) {
                    AiCardItem item = items.get(idx);
                    Position pos = calculatePosition(idx);
                    
                    if (pos.isValid()) {
                        double itemWidth = colWidths[pos.col];
                        double itemHeight = rowHeights[pos.row];
                        
                        item.setPrefSize(itemWidth, itemHeight);
                        item.setMaxSize(itemWidth, itemHeight);
                        item.setMinSize(itemWidth, itemHeight);
                    }
                }
            }
        });
    }
    
    private Position calculatePosition(int index) {
        int r, c;
        
        if (isHorizontalLayout) {
            r = index / cols;
            c = index % cols;
        } else {
            c = index / rows;
            r = index % rows;
        }
        
        return new Position(r, c, r < rows && c < cols);
    }
    
    private double calculateCellWidth() {
        double gw = 0;
        
        if (scroll != null && scroll.getWidth() > 0) {
            gw = scroll.getWidth();
        } else if (scroll != null && scroll.getViewportBounds() != null) {
            gw = scroll.getViewportBounds().getWidth();
        } else if (grid != null) {
            gw = grid.getWidth();
        }
        
        if (gw <= 0) return 0;
        
        Insets insets = grid.getInsets();
        double padH = insets != null ? insets.getLeft() + insets.getRight() : 4;
        double hgap = grid.getHgap();
        double contentW = gw - padH - Math.max(0, cols - 1) * hgap;
        
        return Math.floor(contentW / Math.max(1, cols));
    }
    
    private double calculateCellHeight() {
        double gh = 0;
        
        if (scroll != null && scroll.getHeight() > 0) {
            gh = scroll.getHeight();
        } else if (scroll != null && scroll.getViewportBounds() != null) {
            gh = scroll.getViewportBounds().getHeight();
        } else if (grid != null) {
            gh = grid.getHeight();
        }
        
        if (gh <= 0) return 0;
        
        Insets insets = grid.getInsets();
        double padV = insets != null ? insets.getTop() + insets.getBottom() : 4;
        double vgap = grid.getVgap();
        double contentH = gh - padV - Math.max(0, rows - 1) * vgap;
        
        return Math.floor(contentH / Math.max(1, rows));
    }
    
    private void updateGridConstraints() {
        double gw = 0, gh = 0;
        if (scroll != null && scroll.getViewportBounds() != null) {
            gw = scroll.getViewportBounds().getWidth();
            gh = scroll.getViewportBounds().getHeight();
        }
        if (gw <= 0) gw = scroll != null ? scroll.getWidth() : grid.getWidth();
        if (gh <= 0) gh = scroll != null ? scroll.getHeight() : grid.getHeight();
        
        if (gw <= 0 || gh <= 0) return;
        
        Insets insets = grid.getInsets();
        double padH = insets != null ? insets.getLeft() + insets.getRight() : 4;
        double padV = insets != null ? insets.getTop() + insets.getBottom() : 4;
        double hgap = grid.getHgap();
        double vgap = grid.getVgap();
        double contentW = gw - padH - Math.max(0, cols - 1) * hgap;
        double contentH = gh - padV - Math.max(0, rows - 1) * vgap;
        
        if (contentW <= 0 || contentH <= 0) return;
        
        double[] colWidths = new double[cols];
        double[] rowHeights = new double[rows];
        double baseCol = Math.floor(contentW / cols);
        double baseRow = Math.floor(contentH / rows);
        
        for (int i = 0; i < cols - 1; i++) colWidths[i] = baseCol;
        for (int i = 0; i < rows - 1; i++) rowHeights[i] = baseRow;
        
        double usedW = baseCol * Math.max(0, cols - 1);
        double usedH = baseRow * Math.max(0, rows - 1);
        colWidths[cols - 1] = Math.max(0, contentW - usedW);
        rowHeights[rows - 1] = Math.max(0, contentH - usedH);
        
        grid.getColumnConstraints().clear();
        for (int i = 0; i < cols; i++) {
            ColumnConstraints cc = new ColumnConstraints();
            cc.setMinWidth(colWidths[i]);
            cc.setPrefWidth(colWidths[i]);
            cc.setMaxWidth(colWidths[i]);
            cc.setFillWidth(true);
            grid.getColumnConstraints().add(cc);
        }
        
        grid.getRowConstraints().clear();
        for (int i = 0; i < rows; i++) {
            RowConstraints rc = new RowConstraints();
            rc.setMinHeight(rowHeights[i]);
            rc.setPrefHeight(rowHeights[i]);
            rc.setMaxHeight(rowHeights[i]);
            rc.setFillHeight(true);
            grid.getRowConstraints().add(rc);
        }
    }
    
    private void updatePlaceholders() {
        grid.getChildren().removeIf(node -> node instanceof StackPane && 
                                             !(node instanceof AiCardItem));
        
        int capacity = rows * cols;
        for (int idx = items.size(); idx < capacity; idx++) {
            addPlaceholderAt(idx);
        }
    }
    
    private void addPlaceholderAt(int index) {
        Position pos = calculatePosition(index);
        if (!pos.isValid()) return;
        
        int displayNumber;
        if (isHorizontalLayout) {
            displayNumber = index + 1;
        } else {
            displayNumber = pos.col * rows + pos.row + 1;
        }
        
        StackPane placeholder = createPlaceholder(displayNumber);
        grid.add(placeholder, pos.col, pos.row);
    }
    
    private void removePlaceholderAt(int row, int col) {
        grid.getChildren().removeIf(node -> {
            if (node instanceof StackPane && !(node instanceof AiCardItem)) {
                Integer r = GridPane.getRowIndex(node);
                Integer c = GridPane.getColumnIndex(node);
                return (r != null && r == row) && (c != null && c == col);
            }
            return false;
        });
    }
    
    private StackPane createPlaceholder(int number) {
        StackPane box = new StackPane();
        box.setStyle(PLACEHOLDER_STYLE);
        
        javafx.scene.control.Label label = new javafx.scene.control.Label(String.valueOf(number));
        label.setStyle(NUMBER_LABEL_STYLE);
        box.getChildren().add(label);
        
        return box;
    }
    
    private static class Position {
        final int row;
        final int col;
        final boolean valid;
        
        Position(int row, int col, boolean valid) {
            this.row = row;
            this.col = col;
            this.valid = valid;
        }
        
        boolean isValid() {
            return valid;
        }
    }
    
    public int getRows() { return rows; }
    public int getCols() { return cols; }
    public boolean isHorizontalLayout() { return isHorizontalLayout; }
}

