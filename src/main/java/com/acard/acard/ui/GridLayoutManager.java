package com.acard.acard.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.StackPane;

import java.util.List;

/**
 * ⚡ 高效网格布局管理器
 * - 0延迟切换排列方向（横向/纵向）
 * - 增量更新，不清空重建
 * - 智能尺寸计算和适配
 * - 支持动态行列数调整
 */
public class GridLayoutManager {
    
    private final GridPane grid;
    private final ScrollPane scroll;
    private final List<CaptureItemView> items;
    
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
        "-fx-font-size: 36px; " +
        "-fx-font-weight: bold;";
    
    public GridLayoutManager(GridPane grid, ScrollPane scroll, List<CaptureItemView> items) {
        this.grid = grid;
        this.scroll = scroll;
        this.items = items;
        
        // ⚡ 延迟设置网格约束和计算初始尺寸（等待 ScrollPane 布局完成）
        Platform.runLater(() -> {
            Platform.runLater(() -> {
                // 设置网格约束
                updateGridConstraints();
                
                // 计算单元格尺寸
                cellWidth = calculateCellWidth();
                cellHeight = calculateCellHeight();
                if (cellWidth > 0 && cellHeight > 0) {
                    System.out.println("⚡ GridLayoutManager 初始化完成: " + cellWidth + "x" + cellHeight);
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
        
        // ⚡ 立即同步更新尺寸缓存（避免新item使用旧尺寸）
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
                CaptureItemView item = items.get(idx);
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
        });
    }
    
    /**
     * ⚡ 增量添加item（不触发完整布局）
     */
    public void addItem(CaptureItemView item, int index) {
        Platform.runLater(() -> {
            Position pos = calculatePosition(index);
            
            if (!pos.isValid()) {
                System.out.println("⚠️ Item[" + index + "] 超出网格范围");
                return;
            }
            
            // ⚡ 尝试从网格约束中获取精确尺寸
            double itemWidth = 0;
            double itemHeight = 0;
            
            if (pos.col < grid.getColumnConstraints().size() && pos.row < grid.getRowConstraints().size()) {
                // 优先使用网格约束中的精确尺寸
                itemWidth = grid.getColumnConstraints().get(pos.col).getPrefWidth();
                itemHeight = grid.getRowConstraints().get(pos.row).getPrefHeight();
            }
            
            // 如果网格约束还没设置，使用计算的平均值
            if (itemWidth <= 0 || itemHeight <= 0) {
                itemWidth = calculateCellWidth();
                itemHeight = calculateCellHeight();
            }
            
            // 更新缓存（用于快速判断）
            if (itemWidth > 0 && cellWidth <= 0) {
                cellWidth = itemWidth;
            }
            if (itemHeight > 0 && cellHeight <= 0) {
                cellHeight = itemHeight;
            }
            
            // 设置item尺寸（用于适配图片大小）
            if (itemWidth > 0 && itemHeight > 0) {
                item.setPrefSize(itemWidth, itemHeight);
                item.setMaxSize(itemWidth, itemHeight);
                item.setMinSize(itemWidth, itemHeight);
                item.lockViewportSize(itemWidth, itemHeight);
                System.out.println("⚡ Item[" + index + "] 尺寸设置: " + itemWidth + "x" + itemHeight + " (位置: " + pos.row + "," + pos.col + ")");
            } else {
                System.out.println("⚠️ Item[" + index + "] 尺寸无效(" + itemWidth + "x" + itemHeight + ")，等待后续刷新");
            }
            
            // 添加到grid
            grid.add(item, pos.col, pos.row);
            GridPane.setFillWidth(item, true);
            GridPane.setFillHeight(item, true);
            
            // 设置样式和事件
            item.setStyle("-fx-background-color: #292929;");
            item.setOnMouseEntered(e -> item.OnMouseEntered());
            item.setOnMouseExited(e -> item.OnMouseExited());
            
            // 移除对应位置的占位符
            removePlaceholderAt(pos.row, pos.col);
        });
    }
    
    /**
     * ⚡ 增量删除item（删除后数据往前排）
     */
    public void removeItem(CaptureItemView item, int index) {
        Platform.runLater(() -> {
            // 从 grid 中移除 item
            grid.getChildren().remove(item);
            
            // ⭐ 计算网格最大容量
            int maxVisible = rows * cols;
            
            // ⭐ 重新排列所有剩余的 items（往前移动，包括溢出的item）
            for (int idx = 0; idx < items.size(); idx++) {
                CaptureItemView currentItem = items.get(idx);
                Position pos = calculatePosition(idx);
                
                if (pos.isValid()) {
                    boolean isInGrid = grid.getChildren().contains(currentItem);
                    
                    if (!isInGrid && idx < maxVisible) {
                        // ⭐ 关键修复：溢出的item现在有空位了，添加到grid中
                        System.out.println("🔧 溢出Item[" + idx + "] 现在有空位，添加到位置(" + pos.row + "," + pos.col + ")");
                        
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
                        
                        currentItem.setStyle("-fx-background-color: #292929;");
                        currentItem.setOnMouseEntered(e -> currentItem.OnMouseEntered());
                        currentItem.setOnMouseExited(e -> currentItem.OnMouseExited());
                        
                        removePlaceholderAt(pos.row, pos.col);
                        
                        if (itemWidth > 0 && itemHeight > 0) {
                            currentItem.lockViewportSize(itemWidth, itemHeight);
                        }
                    } else if (isInGrid) {
                        // 更新已有 item 的位置
                        GridPane.setRowIndex(currentItem, pos.row);
                        GridPane.setColumnIndex(currentItem, pos.col);
                    }
                }
            }
            
            // 更新占位符（在所有 item 后面添加占位符）
            updatePlaceholders();
            
            // 重新适配剩余item的尺寸
            refreshItemSizes();
            
            System.out.println("✅ 删除后重新排列，剩余 " + items.size() + " 个 item，网格容量 " + maxVisible);
        });
    }
    
    /**
     * ⚡ 刷新布局（用于拖动、改变行列数等）
     */
    public void refreshLayout() {
        Platform.runLater(() -> {
            // ⚡ 设置网格约束（像原版一样精确控制）
            updateGridConstraints();
            
            // 重新计算单元格尺寸（用于缓存和初始判断）
            cellWidth = calculateCellWidth();
            cellHeight = calculateCellHeight();
            
            System.out.println("⚡ refreshLayout: 网格约束已更新，开始更新所有item...");
            
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
            
            // 更新所有item的位置和尺寸
            for (int idx = 0; idx < items.size(); idx++) {
                CaptureItemView item = items.get(idx);
                Position pos = calculatePosition(idx);
                boolean isInGrid = grid.getChildren().contains(item);
                
                if (pos.isValid()) {
                    // ⚡ 获取该 item 所在位置的精确尺寸
                    double itemWidth = colWidths[pos.col];
                    double itemHeight = rowHeights[pos.row];
                    
                    if (!isInGrid) {
                        // 🔥 关键修复：如果 item 不在 grid 中，先添加它
                        System.out.println("🔧 Item[" + idx + "] 不在 grid 中，添加到位置(" + pos.row + "," + pos.col + ")，尺寸: " + itemWidth + "x" + itemHeight);
                        
                        // ⚡ 先设置精确尺寸
                        if (itemWidth > 0 && itemHeight > 0) {
                            item.setPrefSize(itemWidth, itemHeight);
                            item.setMaxSize(itemWidth, itemHeight);
                            item.setMinSize(itemWidth, itemHeight);
                        }
                        
                        // 然后添加到 grid
                        grid.add(item, pos.col, pos.row);
                        GridPane.setFillWidth(item, true);
                        GridPane.setFillHeight(item, true);
                        
                        // 设置样式和事件
                        item.setStyle("-fx-background-color: #292929;");
                        item.setOnMouseEntered(e -> item.OnMouseEntered());
                        item.setOnMouseExited(e -> item.OnMouseExited());
                        
                        // 移除占位符
                        removePlaceholderAt(pos.row, pos.col);
                        
                        // ⚡ 最后锁定视口尺寸并触发渲染
                        if (itemWidth > 0 && itemHeight > 0) {
                            item.lockViewportSize(itemWidth, itemHeight);
                        }
                    } else {
                        // 更新已有 item 的位置
                        GridPane.setRowIndex(item, pos.row);
                        GridPane.setColumnIndex(item, pos.col);
                        
                        // ⚡ 更新精确尺寸
                        if (itemWidth > 0 && itemHeight > 0) {
                            item.setPrefSize(itemWidth, itemHeight);
                            item.setMaxSize(itemWidth, itemHeight);
                            item.setMinSize(itemWidth, itemHeight);
                            item.lockViewportSize(itemWidth, itemHeight);
                            System.out.println("⚡ Item[" + idx + "] 位置(" + pos.row + "," + pos.col + ") 尺寸: " + itemWidth + "x" + itemHeight);
                        }
                    }
                } else {
                    // ⭐ 关键修复：超出网格范围的 item 从 grid 中移除（但保留在 items 列表中）
                    if (isInGrid) {
                        grid.getChildren().remove(item);
                        System.out.println("🔧 Item[" + idx + "] 超出网格范围，从 grid 中移除（保留数据）");
                    }
                }
            }
            
            // 更新占位符
            updatePlaceholders();
            
            System.out.println("✅ refreshLayout 完成，所有item已更新为精确尺寸");
        });
    }
    
    /**
     * 刷新所有item的尺寸（用于拖动时）
     */
    public void refreshItemSizes() {
        Platform.runLater(() -> {
            // ⚡ 先更新网格约束
            updateGridConstraints();
            
            double newCellWidth = calculateCellWidth();
            double newCellHeight = calculateCellHeight();
            
            if (newCellWidth > 0 && newCellHeight > 0) {
                // 只在尺寸确实改变时才更新
                boolean sizeChanged = Math.abs(newCellWidth - cellWidth) > 1 || 
                                     Math.abs(newCellHeight - cellHeight) > 1;
                
                cellWidth = newCellWidth;
                cellHeight = newCellHeight;
                
                if (sizeChanged || items.isEmpty()) {
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
                    
                    // ⚡ 为每个 item 设置其所在位置的精确尺寸
                    for (int idx = 0; idx < items.size(); idx++) {
                        CaptureItemView item = items.get(idx);
                        Position pos = calculatePosition(idx);
                        
                        if (pos.isValid()) {
                            double itemWidth = colWidths[pos.col];
                            double itemHeight = rowHeights[pos.row];
                            
                            item.setPrefSize(itemWidth, itemHeight);
                            item.setMaxSize(itemWidth, itemHeight);
                            item.setMinSize(itemWidth, itemHeight);
                            item.lockViewportSize(itemWidth, itemHeight);
                        }
                    }
                    
                    if (!items.isEmpty() || sizeChanged) {
                        System.out.println("⚡ 刷新所有 Item 精确尺寸，共 " + items.size() + " 个");
                    }
                }
            } else {
                System.out.println("⚠️ 刷新尺寸失败：视口尺寸无效 (" + newCellWidth + "x" + newCellHeight + ")");
            }
        });
    }
    
    /**
     * 计算指定索引的位置
     */
    private Position calculatePosition(int index) {
        int r, c;
        
        if (isHorizontalLayout) {
            // 行优先排列
            r = index / cols;
            c = index % cols;
        } else {
            // 列优先排列
            c = index / rows;
            r = index % rows;
        }
        
        return new Position(r, c, r < rows && c < cols);
    }
    
    /**
     * 计算单元格宽度
     */
    private double calculateCellWidth() {
        double gw = 0;
        
        // 优先使用 ScrollPane 的实际宽度
        if (scroll != null && scroll.getWidth() > 0) {
            gw = scroll.getWidth();
        } else if (scroll != null && scroll.getViewportBounds() != null) {
            gw = scroll.getViewportBounds().getWidth();
        } else if (grid != null) {
            gw = grid.getWidth();
        }
        
        if (gw <= 0) {
            System.out.println("⚠️ calculateCellWidth: 无法获取有效宽度");
            return 0;
        }
        
        // 扣除内边距和间距
        Insets insets = grid.getInsets();
        double padH = insets != null ? insets.getLeft() + insets.getRight() : 4; // 默认 2*2 padding
        double hgap = grid.getHgap();
        double contentW = gw - padH - Math.max(0, cols - 1) * hgap;
        
        double cellW = Math.floor(contentW / Math.max(1, cols));
        // System.out.println("📐 计算单元格宽度: 总宽=" + gw + ", 内边距=" + padH + ", 间距=" + hgap + ", 列数=" + cols + " => " + cellW);
        return cellW;
    }
    
    /**
     * 计算单元格高度
     */
    private double calculateCellHeight() {
        double gh = 0;
        
        // 优先使用 ScrollPane 的实际高度
        if (scroll != null && scroll.getHeight() > 0) {
            gh = scroll.getHeight();
        } else if (scroll != null && scroll.getViewportBounds() != null) {
            gh = scroll.getViewportBounds().getHeight();
        } else if (grid != null) {
            gh = grid.getHeight();
        }
        
        if (gh <= 0) {
            System.out.println("⚠️ calculateCellHeight: 无法获取有效高度");
            return 0;
        }
        
        // 扣除内边距和间距
        Insets insets = grid.getInsets();
        double padV = insets != null ? insets.getTop() + insets.getBottom() : 4; // 默认 2*2 padding
        double vgap = grid.getVgap();
        double contentH = gh - padV - Math.max(0, rows - 1) * vgap;
        
        double cellH = Math.floor(contentH / Math.max(1, rows));
        // System.out.println("📐 计算单元格高度: 总高=" + gh + ", 内边距=" + padV + ", 间距=" + vgap + ", 行数=" + rows + " => " + cellH);
        return cellH;
    }
    
    /**
     * ⚡ 更新网格约束（像原版 Element1Controller 一样）
     */
    private void updateGridConstraints() {
        // 获取视口尺寸
        double gw = 0, gh = 0;
        if (scroll != null && scroll.getViewportBounds() != null) {
            gw = scroll.getViewportBounds().getWidth();
            gh = scroll.getViewportBounds().getHeight();
        }
        if (gw <= 0) gw = scroll != null ? scroll.getWidth() : grid.getWidth();
        if (gh <= 0) gh = scroll != null ? scroll.getHeight() : grid.getHeight();
        
        if (gw <= 0 || gh <= 0) {
            System.out.println("⚠️ updateGridConstraints: 视口尺寸无效");
            return;
        }
        
        // 计算内容区域
        Insets insets = grid.getInsets();
        double padH = insets != null ? insets.getLeft() + insets.getRight() : 4;
        double padV = insets != null ? insets.getTop() + insets.getBottom() : 4;
        double hgap = grid.getHgap();
        double vgap = grid.getVgap();
        double contentW = gw - padH - Math.max(0, cols - 1) * hgap;
        double contentH = gh - padV - Math.max(0, rows - 1) * vgap;
        
        if (contentW <= 0 || contentH <= 0) return;
        
        // 计算每列/行的精确尺寸
        double[] colWidths = new double[cols];
        double[] rowHeights = new double[rows];
        double baseCol = Math.floor(contentW / cols);
        double baseRow = Math.floor(contentH / rows);
        
        for (int i = 0; i < cols - 1; i++) colWidths[i] = baseCol;
        for (int i = 0; i < rows - 1; i++) rowHeights[i] = baseRow;
        
        // 最后一列/行吸收余数
        double usedW = baseCol * Math.max(0, cols - 1);
        double usedH = baseRow * Math.max(0, rows - 1);
        colWidths[cols - 1] = Math.max(0, contentW - usedW);
        rowHeights[rows - 1] = Math.max(0, contentH - usedH);
        
        // 设置列约束
        grid.getColumnConstraints().clear();
        for (int i = 0; i < cols; i++) {
            javafx.scene.layout.ColumnConstraints cc = new javafx.scene.layout.ColumnConstraints();
            cc.setMinWidth(colWidths[i]);
            cc.setPrefWidth(colWidths[i]);
            cc.setMaxWidth(colWidths[i]);
            cc.setFillWidth(true);
            grid.getColumnConstraints().add(cc);
        }
        
        // 设置行约束
        grid.getRowConstraints().clear();
        for (int i = 0; i < rows; i++) {
            javafx.scene.layout.RowConstraints rc = new javafx.scene.layout.RowConstraints();
            rc.setMinHeight(rowHeights[i]);
            rc.setPrefHeight(rowHeights[i]);
            rc.setMaxHeight(rowHeights[i]);
            rc.setFillHeight(true);
            grid.getRowConstraints().add(rc);
        }
        
        System.out.println("⚡ 网格约束已更新: " + cols + "列 x " + rows + "行, " +
                          "单元格大小约 " + (int)baseCol + "x" + (int)baseRow);
    }
    
    /**
     * 更新占位符
     */
    private void updatePlaceholders() {
        // 移除所有占位符
        grid.getChildren().removeIf(node -> node instanceof StackPane && 
                                             !(node instanceof CaptureItemView));
        
        // 添加新的占位符
        int capacity = rows * cols;
        for (int idx = items.size(); idx < capacity; idx++) {
            addPlaceholderAt(idx);
        }
    }
    
    /**
     * 在指定索引添加占位符
     */
    private void addPlaceholderAt(int index) {
        Position pos = calculatePosition(index);
        if (!pos.isValid()) return;
        
        // 计算显示数字
        int displayNumber;
        if (isHorizontalLayout) {
            displayNumber = index + 1;
        } else {
            displayNumber = pos.col * rows + pos.row + 1;
        }
        
        StackPane placeholder = createPlaceholder(displayNumber);
        grid.add(placeholder, pos.col, pos.row);
    }
    
    /**
     * 移除指定位置的占位符
     */
    private void removePlaceholderAt(int row, int col) {
        grid.getChildren().removeIf(node -> {
            if (node instanceof StackPane && !(node instanceof CaptureItemView)) {
                Integer r = GridPane.getRowIndex(node);
                Integer c = GridPane.getColumnIndex(node);
                return (r != null && r == row) && (c != null && c == col);
            }
            return false;
        });
    }
    
    /**
     * 创建占位符
     */
    private StackPane createPlaceholder(int number) {
        StackPane box = new StackPane();
        box.setStyle(PLACEHOLDER_STYLE);
        
        javafx.scene.control.Label label = new javafx.scene.control.Label(String.valueOf(number));
        label.setStyle(NUMBER_LABEL_STYLE);
        box.getChildren().add(label);
        
        return box;
    }
    
    /**
     * 位置类
     */
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
}

