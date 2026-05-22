package com.acard.acard.ui;

import javafx.application.Platform;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Popup;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import com.acard.acard.config.ShortcutConfig;
import com.acard.acard.store.ShortcutStore;
import com.acard.acard.tools.FileToos;

/**
 * 快捷键设置对话框
 * ✅ 改用 Popup，解决弹框层级和快捷键问题（参考 ParameterSettingsDialogController）
 */
public class ShortcutSettingsDialog {
    
    private Popup popup;
    private Stage owner;
    private ShortcutConfig config;
    private ShortcutStore store;
    
    // UI组件
    private TextField rowAdjustField;
    private TextField colAdjustField;
    private TextField rowSubAdjustField;  // ⭐ 行数减少
    private TextField colSubAdjustField;  // ⭐ 列数减少
    private TextField cameraSwitchField;
    private TextField settingsField;
    private TextField slowMotionField;
    private TextField captureField;
    private TextField clearField;
    private TextField deleteLastField;  // ⭐ 删除最后一项
    
    // ⭐ 新增快捷键字段
    private TextField captureClearField;  // 抓拍清空
    
    // ⭐ 滚轮帧率设置（非快捷键，ComboBox）
    private ComboBox<Integer> scrollFrameRateCombo;
    
    private TextField fullscreenField;
    private TextField realtimeWindowField;
    private TextField slowMotionWindowField;
    private TextField fullscreenViewerField;  // ⭐ 全屏查看/取消
    
    // 当前正在编辑的字段
    private TextField currentEditingField;
    
    public ShortcutSettingsDialog() {
        this.config = new ShortcutConfig();
        this.store = ShortcutStore.getInstance();
    }
    
    /**
     * 显示快捷键设置对话框（居中显示）
     */
    public static void show(Stage owner) {
        ShortcutSettingsDialog dialog = new ShortcutSettingsDialog();
        dialog.createAndShowDialog(owner, null);
    }
    
    /**
     * 显示快捷键设置对话框（在指定节点下方显示）
     */
    public static void showBelowNode(Stage owner, javafx.scene.Node node) {
        ShortcutSettingsDialog dialog = new ShortcutSettingsDialog();
        dialog.createAndShowDialog(owner, node);
    }
    
    private void createAndShowDialog(Stage owner, javafx.scene.Node anchorNode) {
        this.owner = owner;
        
        // 创建主布局
        BorderPane mainLayout = createMainLayout();
        // ✅ 深色主题风格
        mainLayout.setStyle(
            "-fx-background-color: #1F1F1F; " +
            "-fx-background-radius: 12; " +
            "-fx-border-color: #333333; " +
            "-fx-border-width: 1; " +
            "-fx-border-radius: 12; " +
            "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.5), 20, 0.3, 0, 8);"
        );
        
        // ✅ 创建 Popup（参考 ParameterSettingsDialogController）
        popup = new Popup();
        popup.setAutoHide(true);  // 点击外部自动关闭
        popup.setHideOnEscape(true);  // 按 ESC 关闭
        popup.getContent().add(mainLayout);
        
        // ✅ 计算弹框位置
        try {
            if (anchorNode != null) {
                // 如果提供了锚点节点，则显示在节点正下方
                javafx.geometry.Bounds bounds = anchorNode.localToScreen(anchorNode.getBoundsInLocal());
                double x = bounds.getMinX();
                double y = bounds.getMaxY() + 5; // 节点下方5px
                popup.setX(x);
                popup.setY(y);
            } else {
                // 否则相对于主窗口居中
                double centerX = owner.getX() + Math.max(0, owner.getWidth() - 650) / 2;
                double centerY = owner.getY() + Math.max(0, owner.getHeight() - 500) / 2;
                popup.setX(centerX);
                popup.setY(centerY);
            }
        } catch (Throwable ignore) {}
        
        // ⭐ 监听主窗口最小化/失去焦点，自动关闭弹框
        if (owner != null) {
            owner.iconifiedProperty().addListener((obs, wasIconified, isIconified) -> {
                if (isIconified && popup.isShowing()) {
                    popup.hide();
                }
            });
            owner.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
                if (!isFocused && popup.isShowing()) {
                    javafx.application.Platform.runLater(() -> {
                        if (!owner.isFocused() && popup.isShowing()) {
                            popup.hide();
                        }
                    });
                }
            });
        }
        
        // 显示 Popup
        popup.show(owner);
    }
    
    private BorderPane createMainLayout() {
        // ✅ 创建可拖动的标题栏（深色主题）
        HBox titleBar = new HBox();
        titleBar.setAlignment(Pos.CENTER_LEFT);
        titleBar.setStyle(
            "-fx-background-color: #292929; " +
            "-fx-padding: 16 20; " +
            "-fx-background-radius: 12 12 0 0;"
        );
        
        Label titleLabel = new Label("⌨ 快捷键设置");
        titleLabel.setStyle(
            "-fx-text-fill: #FFFFFF; " +
            "-fx-font-size: 18px; " +
            "-fx-font-weight: bold;"
        );
        
        // ✅ 添加关闭按钮（深色风格）
        Button closeBtn = new Button("×");
        closeBtn.setStyle(
            "-fx-background-color: transparent; " +
            "-fx-text-fill: #888888; " +
            "-fx-font-size: 24px; " +
            "-fx-font-weight: bold; " +
            "-fx-cursor: hand; " +
            "-fx-padding: 0 8;"
        );
        closeBtn.setOnMouseEntered(e -> closeBtn.setStyle(
            "-fx-background-color: #DC2626; " +
            "-fx-text-fill: #FFFFFF; " +
            "-fx-font-size: 24px; " +
            "-fx-font-weight: bold; " +
            "-fx-cursor: hand; " +
            "-fx-padding: 0 8; " +
            "-fx-background-radius: 4;"
        ));
        closeBtn.setOnMouseExited(e -> closeBtn.setStyle(
            "-fx-background-color: transparent; " +
            "-fx-text-fill: #888888; " +
            "-fx-font-size: 24px; " +
            "-fx-font-weight: bold; " +
            "-fx-cursor: hand; " +
            "-fx-padding: 0 8;"
        ));
        closeBtn.setOnAction(e -> popup.hide());
        
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        titleBar.getChildren().addAll(titleLabel, spacer, closeBtn);
        
        // ✅ 设置标题栏拖动功能
        final double[] dragOffset = new double[2];
        titleBar.setOnMousePressed(mouseEvent -> {
            dragOffset[0] = mouseEvent.getX();
            dragOffset[1] = mouseEvent.getY();
        });
        titleBar.setOnMouseDragged(mouseEvent -> {
            popup.setX(mouseEvent.getScreenX() - dragOffset[0]);
            popup.setY(mouseEvent.getScreenY() - dragOffset[1]);
        });
        
        // ✅ 顶部中间说明文字（滚轮操作提示）
        Label scrollHintLabel = new Label("视频区域：滚轮（画面缩放）    抓拍区域：Ctrl+滚轮（图片放大）");
        scrollHintLabel.setStyle(
            "-fx-font-size: 13px; " +
            "-fx-text-fill: #60A5FA; " +  // 蓝色高亮
            "-fx-padding: 8 0; " +
            "-fx-background-color: #2D3748; " +
            "-fx-background-radius: 6; " +
            "-fx-label-padding: 8 16;"
        );
        scrollHintLabel.setAlignment(Pos.CENTER);
        scrollHintLabel.setMaxWidth(Double.MAX_VALUE);
        
        // 说明文字（深色主题）
        Label instructionLabel = new Label("💡 点击输入框，然后按下您想要设置的快捷键");
        instructionLabel.setStyle(
            "-fx-font-size: 13px; " +
            "-fx-text-fill: #888888; " +
            "-fx-padding: 12 0;"
        );
        instructionLabel.setAlignment(Pos.CENTER);
        
        // 快捷键设置区域（深色主题）
        ScrollPane scrollPane = new ScrollPane();
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle(
            "-fx-background-color: transparent; " +
            "-fx-background: #1F1F1F;"
        );
        
        GridPane settingsGrid = createSettingsGrid();
        scrollPane.setContent(settingsGrid);
        
        // 按钮区域
        HBox buttonBox = createButtonBox();
        
        // ✅ 使用 BorderPane 布局（标题栏在顶部）
        BorderPane mainLayout = new BorderPane();
        mainLayout.setTop(titleBar);
        
        VBox centerBox = new VBox(10);
        centerBox.setPadding(new Insets(16));
        centerBox.setStyle("-fx-background-color: #1F1F1F;");
        centerBox.getChildren().addAll(scrollHintLabel, instructionLabel, scrollPane, buttonBox);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);
        
        mainLayout.setCenter(centerBox);
        
        // ✅ 设置弹窗宽度
        mainLayout.setPrefWidth(650);
        
        return mainLayout;
    }
    
    private GridPane createSettingsGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(15);
        grid.setVgap(12);
        grid.setPadding(new Insets(10));
        grid.setStyle("-fx-background-color: #1F1F1F;");
        
        // ⭐ 设置4列约束（label1, field1, label2, field2）- 适配650宽度
        ColumnConstraints labelCol1 = new ColumnConstraints();
        labelCol1.setMinWidth(120);
        labelCol1.setPrefWidth(130);
        labelCol1.setHalignment(HPos.RIGHT);
        
        ColumnConstraints fieldCol1 = new ColumnConstraints();
        fieldCol1.setMinWidth(120);
        fieldCol1.setPrefWidth(130);
        fieldCol1.setMaxWidth(150);
        
        ColumnConstraints labelCol2 = new ColumnConstraints();
        labelCol2.setMinWidth(120);
        labelCol2.setPrefWidth(130);
        labelCol2.setHalignment(HPos.RIGHT);
        
        ColumnConstraints fieldCol2 = new ColumnConstraints();
        fieldCol2.setMinWidth(120);
        fieldCol2.setPrefWidth(130);
        fieldCol2.setMaxWidth(150);
        
        grid.getColumnConstraints().addAll(labelCol1, fieldCol1, labelCol2, fieldCol2);
        
        int row = 0;
        
        // 基础功能快捷键
        grid.add(createSectionLabel("基础功能"), 0, row++, 4, 1);
        
        // ⭐ 第一行：行数增加 + 列数增加
        rowAdjustField = createShortcutField(config.getRowAdjustKey());
        grid.add(createFieldLabel("行数增加:"), 0, row);
        grid.add(rowAdjustField, 1, row);
        
        colAdjustField = createShortcutField(config.getColAdjustKey());
        grid.add(createFieldLabel("列数增加:"), 2, row);
        grid.add(colAdjustField, 3, row++);
        
        // ⭐ 第二行：行数减少 + 列数减少
        rowSubAdjustField = createShortcutField(config.getRowSubAdjustKey());
        grid.add(createFieldLabel("行数减少:"), 0, row);
        grid.add(rowSubAdjustField, 1, row);
        
        colSubAdjustField = createShortcutField(config.getColSubAdjustKey());
        grid.add(createFieldLabel("列数减少:"), 2, row);
        grid.add(colSubAdjustField, 3, row++);
        
        // ⭐ 第三行：镜头切换 + 设置
        cameraSwitchField = createShortcutField(config.getCameraSwitchKey());
        grid.add(createFieldLabel("镜头切换:"), 0, row);
        grid.add(cameraSwitchField, 1, row);
        
        settingsField = createShortcutField(config.getSettingsKey());
        grid.add(createFieldLabel("设置:"), 2, row);
        grid.add(settingsField, 3, row++);
        
        // ⭐ 第四行：删除最后一项 + 抓拍
        deleteLastField = createShortcutField(config.getDeleteLastKey());
        grid.add(createFieldLabel("删除最后一项:"), 0, row);
        grid.add(deleteLastField, 1, row);
        
        captureField = createShortcutField(config.getCaptureKey());
        grid.add(createFieldLabel("抓拍:"), 2, row);
        grid.add(captureField, 3, row++);
        
        // ⭐ 第五行：抓拍清空 + 滚轮帧率
        captureClearField = createShortcutField(config.getCaptureClearKey());
        grid.add(createFieldLabel("抓拍清空:"), 0, row);
        grid.add(captureClearField, 1, row);
        
        // ⭐ 滚轮帧率设置（非快捷键）
        scrollFrameRateCombo = createScrollFrameRateCombo();
        grid.add(createFieldLabel("滚轮帧率:"), 2, row);
        grid.add(scrollFrameRateCombo, 3, row++);
        
        
        // 录制功能快捷键
        grid.add(createSectionLabel("录制功能"), 0, row++, 4, 1);
        
        // ⭐ 第一行：慢放 + 清空
        slowMotionField = createShortcutField(config.getSlowMotionKey());
        grid.add(createFieldLabel("慢放:"), 0, row);
        grid.add(slowMotionField, 1, row);
        
        clearField = createShortcutField(config.getClearKey());
        grid.add(createFieldLabel("清空:"), 2, row);
        grid.add(clearField, 3, row++);
        
        // 窗口控制快捷键
        grid.add(createSectionLabel("窗口控制"), 0, row++, 4, 1);
        
        // ⭐ 第一行：全屏 + 实时窗口切换
        fullscreenField = createShortcutField(config.getFullscreenKey());
        grid.add(createFieldLabel("抓拍全屏:"), 0, row);
        grid.add(fullscreenField, 1, row);
        
        realtimeWindowField = createShortcutField(config.getRealtimeWindowKey());
        grid.add(createFieldLabel("实时窗口:"), 2, row);
        grid.add(realtimeWindowField, 3, row++);
        
        // ⭐ 第二行：慢放窗口切换 + 全屏查看/取消
        slowMotionWindowField = createShortcutField(config.getSlowmoWindowKey());
        grid.add(createFieldLabel("慢放窗口:"), 0, row);
        grid.add(slowMotionWindowField, 1, row);
        
        fullscreenViewerField = createShortcutField(config.getFullscreenViewerKey());
        grid.add(createFieldLabel("全屏查看/取消:"), 2, row);
        grid.add(fullscreenViewerField, 3, row++);
        
        return grid;
    }
    
    /**
     * ✅ 创建分段标签（深色主题）
     */
    private Label createSectionLabel(String text) {
        Label label = new Label(text);
        label.setStyle(
            "-fx-font-size: 14px; " +
            "-fx-font-weight: bold; " +
            "-fx-text-fill: #4A9EFF; " +
            "-fx-padding: 12 0 6 0;"
        );
        return label;
    }
    
    /**
     * ✅ 创建字段标签（深色主题）
     */
    private Label createFieldLabel(String text) {
        Label label = new Label(text);
        label.setStyle(
            "-fx-font-size: 13px; " +
            "-fx-text-fill: #CCCCCC;"
        );
        return label;
    }
    
    /**
     * ⭐ 创建滚轮帧率下拉选择器（0-10，支持滚轮调整，自动保存）
     * 风格与主页顶部菜单偏移下拉一致
     */
    private ComboBox<Integer> createScrollFrameRateCombo() {
        ComboBox<Integer> combo = new ComboBox<>();
        
        // 添加选项 0-10
        for (int i = 0; i <= 10; i++) {
            combo.getItems().add(i);
        }
        
        // 读取本地存储的值
        int savedValue = store.getScrollFrameRate();
        combo.setValue(savedValue);
        
        // ✅ 深色主题样式（与主页顶部菜单一致）
        combo.setPrefWidth(80);
        combo.setMaxWidth(80);
        combo.setStyle(
            "-fx-background-color: #292929; " +
            "-fx-text-fill: #FFFFFF; " +
            "-fx-border-color: #404040; " +
            "-fx-border-radius: 6; " +
            "-fx-background-radius: 6; " +
            "-fx-font-size: 13px; " +
            "-fx-mark-color: #888888;"  // ⭐ 下拉箭头颜色（深灰色）
        );
        
        // ⭐ 通过 CSS 设置下拉列表和滚动条的深色样式
        combo.getStylesheets().add("data:text/css," +
            ".combo-box .list-cell { -fx-background-color: %23292929; -fx-text-fill: %23FFFFFF; } " +
            ".combo-box-popup .list-view { -fx-background-color: %23292929; -fx-border-color: %23404040; } " +
            ".combo-box-popup .list-view .list-cell:hover { -fx-background-color: %23404040; } " +
            ".combo-box-popup .list-view .list-cell:filled:selected { -fx-background-color: %23505050; } " +
            ".combo-box .arrow-button { -fx-background-color: %23292929; } " +
            ".combo-box .arrow { -fx-background-color: %23888888; } " +
            ".scroll-bar { -fx-background-color: %23292929; } " +
            ".scroll-bar .thumb { -fx-background-color: %23555555; } " +
            ".scroll-bar .increment-button, .scroll-bar .decrement-button { -fx-background-color: %23292929; }"
        );
        
        // ✅ 设置下拉列表样式（通过 CSS）
        combo.setCellFactory(lv -> {
            ListCell<Integer> cell = new ListCell<>() {
                @Override
                protected void updateItem(Integer item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) {
                        setText(null);
                    } else {
                        setText(String.valueOf(item));
                    }
                    setStyle("-fx-text-fill: #FFFFFF; -fx-background-color: #292929; -fx-font-size: 13px;");
                }
            };
            cell.setOnMouseEntered(e -> cell.setStyle("-fx-text-fill: #FFFFFF; -fx-background-color: #404040; -fx-font-size: 13px;"));
            cell.setOnMouseExited(e -> cell.setStyle("-fx-text-fill: #FFFFFF; -fx-background-color: #292929; -fx-font-size: 13px;"));
            return cell;
        });
        
        // ✅ 设置按钮单元格样式
        combo.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(Integer item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(String.valueOf(item));
                }
                setStyle("-fx-text-fill: #FFFFFF; -fx-font-size: 13px;");
            }
        });
        
        // 选择变化时自动保存并同步更新主界面
        combo.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                store.setScrollFrameRate(newVal);
                // ✅ 发送事件通知主界面更新滚轮帧数显示
                FileToos.updateSpeed();
            }
        });
        
        // ⭐ 添加滚轮支持
        combo.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, event -> {
            int currentVal = combo.getValue() != null ? combo.getValue() : 0;
            
            if (event.getDeltaY() > 0) {
                currentVal++;
            } else if (event.getDeltaY() < 0) {
                currentVal--;
            }
            
            currentVal = Math.max(0, Math.min(10, currentVal));
            combo.setValue(currentVal);
            
            event.consume();
        });
        
        return combo;
    }
    
    private TextField createShortcutField(KeyCode keyCode) {
        TextField field = new TextField();
        field.setText(keyCode != null ? keyCode.getName() : "");
        field.setEditable(false);
        field.setPrefWidth(120);
        field.setMaxWidth(100);
        // ✅ 深色主题输入框样式
        field.setStyle(
            "-fx-background-color: #292929; " +
            "-fx-text-fill: #FFFFFF; " +
            "-fx-border-color: #404040; " +
            "-fx-border-radius: 6; " +
            "-fx-background-radius: 6; " +
            "-fx-padding: 8; " +
            "-fx-cursor: hand;"
        );
        
        // 点击时开始监听按键
        field.setOnMouseClicked(e -> startKeyCapture(field));
        
        // 获得焦点时开始监听按键
        field.setOnMousePressed(e -> {
            field.requestFocus();
            startKeyCapture(field);
        });
        
        return field;
    }
    
    private TextField createDisabledShortcutField(KeyCode keyCode) {
        TextField field = new TextField();
        field.setText(keyCode != null ? keyCode.getName() : "");
        field.setPrefWidth(120);
        field.setMaxWidth(100);
        // ✅ 深色主题禁用样式
        field.setStyle(
            "-fx-background-color: #252525; " +
            "-fx-text-fill: #666666; " +
            "-fx-border-color: #333333; " +
            "-fx-border-radius: 6; " +
            "-fx-background-radius: 6; " +
            "-fx-padding: 8;"
        );
        // 添加工具提示说明为什么不能更改（快速显示）
        Tooltip tooltip = new Tooltip("抓拍快捷键固定为空格键，无法更改");
        tooltip.setShowDelay(javafx.util.Duration.millis(10));
        tooltip.setShowDuration(javafx.util.Duration.seconds(10));
        tooltip.setHideDelay(javafx.util.Duration.millis(50));
        Tooltip.install(field, tooltip);
        
        return field;
    }
    
    private void startKeyCapture(TextField field) {
        if (currentEditingField != null && currentEditingField != field) {
            // 重置之前的字段样式
            resetFieldStyle(currentEditingField);
        }
        
        currentEditingField = field;
        // ✅ 深色主题编辑状态样式
        field.setStyle(
            "-fx-background-color: #1A3A5C; " +
            "-fx-text-fill: #4A9EFF; " +
            "-fx-border-color: #4A9EFF; " +
            "-fx-border-width: 2; " +
            "-fx-border-radius: 6; " +
            "-fx-background-radius: 6; " +
            "-fx-padding: 8;"
        );
        field.setText("请按下快捷键...");
        
        // 设置按键监听
        field.setOnKeyPressed(this::handleKeyPressed);
    }
    
    private void handleKeyPressed(KeyEvent event) {
        KeyCode keyCode = event.getCode();
        
        // 忽略修饰键
        if (keyCode == KeyCode.SHIFT || keyCode == KeyCode.CONTROL || 
            keyCode == KeyCode.ALT || keyCode == KeyCode.META) {
            return;
        }
        
        // 设置新的快捷键
        if (currentEditingField != null) {
            currentEditingField.setText(keyCode.getName());
            resetFieldStyle(currentEditingField);
            currentEditingField.setOnKeyPressed(null); // 移除监听器
            currentEditingField = null;
        }
        
        event.consume();
    }
    
    private void resetFieldStyle(TextField field) {
        // ✅ 深色主题恢复默认样式
        field.setStyle(
            "-fx-background-color: #292929; " +
            "-fx-text-fill: #FFFFFF; " +
            "-fx-border-color: #404040; " +
            "-fx-border-radius: 6; " +
            "-fx-background-radius: 6; " +
            "-fx-padding: 8; " +
            "-fx-cursor: hand;"
        );
    }
    
    private HBox createButtonBox() {
        HBox buttonBox = new HBox(12);
        buttonBox.setAlignment(Pos.CENTER_RIGHT);
        buttonBox.setPadding(new Insets(16, 0, 0, 0));
        
        // ✅ 重置按钮（橙色警告）
        Button resetButton = new Button("重置默认");
        resetButton.setStyle(
            "-fx-background-color: #D97706; " +
            "-fx-text-fill: #FFFFFF; " +
            "-fx-font-size: 13px; " +
            "-fx-padding: 10 20; " +
            "-fx-background-radius: 8; " +
            "-fx-cursor: hand;"
        );
        resetButton.setOnMouseEntered(e -> resetButton.setStyle(
            "-fx-background-color: #B45309; " +
            "-fx-text-fill: #FFFFFF; " +
            "-fx-font-size: 13px; " +
            "-fx-padding: 10 20; " +
            "-fx-background-radius: 8; " +
            "-fx-cursor: hand;"
        ));
        resetButton.setOnMouseExited(e -> resetButton.setStyle(
            "-fx-background-color: #D97706; " +
            "-fx-text-fill: #FFFFFF; " +
            "-fx-font-size: 13px; " +
            "-fx-padding: 10 20; " +
            "-fx-background-radius: 8; " +
            "-fx-cursor: hand;"
        ));
        resetButton.setOnAction(e -> resetToDefaults());
        
        // ✅ 取消按钮（深色）
        Button cancelButton = new Button("取消");
        cancelButton.setStyle(
            "-fx-background-color: #292929; " +
            "-fx-text-fill: #FFFFFF; " +
            "-fx-font-size: 13px; " +
            "-fx-padding: 10 20; " +
            "-fx-background-radius: 8; " +
            "-fx-cursor: hand;"
        );
        cancelButton.setOnMouseEntered(e -> cancelButton.setStyle(
            "-fx-background-color: #3D3D3D; " +
            "-fx-text-fill: #FFFFFF; " +
            "-fx-font-size: 13px; " +
            "-fx-padding: 10 20; " +
            "-fx-background-radius: 8; " +
            "-fx-cursor: hand;"
        ));
        cancelButton.setOnMouseExited(e -> cancelButton.setStyle(
            "-fx-background-color: #292929; " +
            "-fx-text-fill: #FFFFFF; " +
            "-fx-font-size: 13px; " +
            "-fx-padding: 10 20; " +
            "-fx-background-radius: 8; " +
            "-fx-cursor: hand;"
        ));
        cancelButton.setOnAction(e -> popup.hide());
        
        // ✅ 保存按钮（绿色强调）
        Button saveButton = new Button("保存");
        saveButton.setStyle(
            "-fx-background-color: #16A34A; " +
            "-fx-text-fill: #FFFFFF; " +
            "-fx-font-size: 13px; " +
            "-fx-font-weight: bold; " +
            "-fx-padding: 10 24; " +
            "-fx-background-radius: 8; " +
            "-fx-cursor: hand;"
        );
        saveButton.setOnMouseEntered(e -> saveButton.setStyle(
            "-fx-background-color: #15803D; " +
            "-fx-text-fill: #FFFFFF; " +
            "-fx-font-size: 13px; " +
            "-fx-font-weight: bold; " +
            "-fx-padding: 10 24; " +
            "-fx-background-radius: 8; " +
            "-fx-cursor: hand;"
        ));
        saveButton.setOnMouseExited(e -> saveButton.setStyle(
            "-fx-background-color: #16A34A; " +
            "-fx-text-fill: #FFFFFF; " +
            "-fx-font-size: 13px; " +
            "-fx-font-weight: bold; " +
            "-fx-padding: 10 24; " +
            "-fx-background-radius: 8; " +
            "-fx-cursor: hand;"
        ));
        saveButton.setOnAction(e -> saveSettings());
        
        buttonBox.getChildren().addAll(resetButton, cancelButton, saveButton);
        
        return buttonBox;
    }
    
    private void resetToDefaults() {
        // ✅ 直接重置为默认值，无需确认弹框
        rowAdjustField.setText(ShortcutStore.DEFAULT_ROW_ADD_ADJUST_KEY.getName());
        colAdjustField.setText(ShortcutStore.DEFAULT_COL_ADD_ADJUST_KEY.getName());
        rowSubAdjustField.setText(ShortcutStore.DEFAULT_ROW_SUB_ADJUST_KEY.getName());
        colSubAdjustField.setText(ShortcutStore.DEFAULT_COL_SUB_ADJUST_KEY.getName());
        cameraSwitchField.setText(ShortcutStore.DEFAULT_CAMERA_SWITCH_KEY.getName());
        settingsField.setText(ShortcutStore.DEFAULT_SETTINGS_KEY.getName());
        slowMotionField.setText(ShortcutStore.DEFAULT_SLOW_MOTION_KEY.getName());
        captureField.setText(ShortcutStore.DEFAULT_CAPTURE_KEY.getName().toUpperCase());
        clearField.setText(ShortcutStore.DEFAULT_CLEAR_KEY.getName());
        deleteLastField.setText(ShortcutStore.DEFAULT_DELETE_LAST_KEY.getName());
        
        // ⭐ 重置新增快捷键
        captureClearField.setText(ShortcutStore.DEFAULT_CAPTURE_CLEAR_KEY.getName());
        
        fullscreenField.setText(ShortcutStore.DEFAULT_FULLSCREEN_KEY.getName());
        realtimeWindowField.setText(ShortcutStore.DEFAULT_REALTIME_WINDOW_KEY.getName());
        slowMotionWindowField.setText(ShortcutStore.DEFAULT_SLOWMO_WINDOW_KEY.getName());
        fullscreenViewerField.setText(ShortcutStore.DEFAULT_FULLSCREEN_VIEWER_KEY.getName());
    }
    
    private void saveSettings() {
        try {
            // ⭐ 先检查快捷键冲突
            String conflictMessage = checkShortcutConflicts();
            if (conflictMessage != null) {
                Alert conflictAlert = new Alert(Alert.AlertType.WARNING);
                if (owner != null) {
                    conflictAlert.initOwner(owner);  // ⭐ 设置父窗口，防止全屏时层级错乱
                    // ⭐ 全屏保护
                    conflictAlert.setOnShowing(e -> owner.toFront());
                    conflictAlert.setOnHidden(e -> Platform.runLater(() -> { owner.toFront(); owner.requestFocus(); }));
                }
                conflictAlert.setTitle(null);
                conflictAlert.setHeaderText(null);
                conflictAlert.setGraphic(null);
                applyDarkAlertStyle(conflictAlert, "⚠", "快捷键冲突", conflictMessage);
                conflictAlert.showAndWait();
                return; // 不保存，返回让用户修改
            }
            
            // 保存所有快捷键设置（⭐ 使用 toUpperCase() 避免 Space/SPACE 等大小写问题）
            store.setRowAdjustKey(KeyCode.valueOf(rowAdjustField.getText().toUpperCase()));
            store.setColAdjustKey(KeyCode.valueOf(colAdjustField.getText().toUpperCase()));
            store.setRowSubAdjustKey(KeyCode.valueOf(rowSubAdjustField.getText().toUpperCase()));
            store.setColSubAdjustKey(KeyCode.valueOf(colSubAdjustField.getText().toUpperCase()));
            store.setCameraSwitchKey(KeyCode.valueOf(cameraSwitchField.getText().toUpperCase()));
            store.setSettingsKey(KeyCode.valueOf(settingsField.getText().toUpperCase()));
            store.setSlowMotionKey(KeyCode.valueOf(slowMotionField.getText().toUpperCase()));
            store.setCaptureKey(KeyCode.valueOf(captureField.getText().toUpperCase()));
            store.setClearKey(KeyCode.valueOf(clearField.getText().toUpperCase()));
            store.setDeleteLastKey(KeyCode.valueOf(deleteLastField.getText().toUpperCase()));
            
            // ⭐ 保存新增快捷键
            store.setCaptureClearKey(KeyCode.valueOf(captureClearField.getText().toUpperCase()));
            
            // 保存窗口控制快捷键
            store.setFullscreenKey(KeyCode.valueOf(fullscreenField.getText().toUpperCase()));
            store.setRealtimeWindowKey(KeyCode.valueOf(realtimeWindowField.getText().toUpperCase()));
            store.setSlowmoWindowKey(KeyCode.valueOf(slowMotionWindowField.getText().toUpperCase()));
            store.setFullscreenViewerKey(KeyCode.valueOf(fullscreenViewerField.getText().toUpperCase()));
            
            // 显示成功消息
            Alert successAlert = new Alert(Alert.AlertType.INFORMATION);
            if (owner != null) {
                successAlert.initOwner(owner);  // ⭐ 设置父窗口，防止全屏时层级错乱
                // ⭐ 全屏保护
                successAlert.setOnShowing(e -> owner.toFront());
                successAlert.setOnHidden(e -> Platform.runLater(() -> { owner.toFront(); owner.requestFocus(); }));
            }
            successAlert.setTitle(null);
            successAlert.setHeaderText(null);
            successAlert.setGraphic(null);
            applyDarkAlertStyle(successAlert, "✓", "保存成功", "新的快捷键设置已生效！");
            successAlert.showAndWait();

            FileToos.updateSpeed();
            // 关闭 Popup
            popup.hide();
            
        } catch (Exception e) {
            // 显示错误消息
            Alert errorAlert = new Alert(Alert.AlertType.ERROR);
            if (owner != null) {
                errorAlert.initOwner(owner);  // ⭐ 设置父窗口，防止全屏时层级错乱
                // ⭐ 全屏保护
                errorAlert.setOnShowing(ev -> owner.toFront());
                errorAlert.setOnHidden(ev -> Platform.runLater(() -> { owner.toFront(); owner.requestFocus(); }));
            }
            errorAlert.setTitle(null);
            errorAlert.setHeaderText(null);
            errorAlert.setGraphic(null);
            applyDarkAlertStyle(errorAlert, "✗", "保存失败", "错误信息：" + e.getMessage());
            errorAlert.showAndWait();
        }
    }
    
    /**
     * ✅ 为 Alert 弹框应用深色主题样式（无系统标题栏，透明背景）
     */
    private void applyDarkAlertStyle(Alert alert, String icon, String title, String message) {
        DialogPane dialogPane = alert.getDialogPane();
        
        // ✅ 透明窗口样式（去掉系统标题栏 + 透明背景，解决四角白色问题）
        alert.initStyle(StageStyle.TRANSPARENT);
        
        // ✅ DialogPane 背景透明
        dialogPane.setStyle("-fx-background-color: transparent;");
        
        // ✅ 创建深色圆角容器
        VBox contentBox = new VBox(16);
        contentBox.setAlignment(Pos.CENTER);
        contentBox.setStyle(
            "-fx-background-color: #1F1F1F; " +
            "-fx-background-radius: 12; " +
            "-fx-padding: 28; " +
            "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.5), 20, 0.3, 0, 4);"
        );
        
        // 图标
        Label iconLabel = new Label(icon);
        String iconColor = switch (icon) {
            case "✓" -> "#16A34A";  // 绿色
            case "✗" -> "#DC2626";  // 红色
            case "⚠" -> "#D97706";  // 橙色
            default -> "#4A9EFF";   // 蓝色
        };
        iconLabel.setStyle(
            "-fx-font-size: 42px; " +
            "-fx-text-fill: " + iconColor + ";"
        );
        
        // 标题
        Label titleLabel = new Label(title);
        titleLabel.setStyle(
            "-fx-font-size: 16px; " +
            "-fx-font-weight: bold; " +
            "-fx-text-fill: #FFFFFF;"
        );
        
        // 消息
        Label messageLabel = new Label(message);
        messageLabel.setStyle(
            "-fx-font-size: 13px; " +
            "-fx-text-fill: #CCCCCC; " +
            "-fx-text-alignment: center;"
        );
        messageLabel.setWrapText(true);
        messageLabel.setMaxWidth(300);
        messageLabel.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        
        // ✅ 自定义按钮区域
        HBox buttonBox = new HBox(12);
        buttonBox.setAlignment(Pos.CENTER);
        buttonBox.setPadding(new Insets(12, 0, 0, 0));
        
        Button okButton = new Button("确定");
        okButton.setStyle(
            "-fx-background-color: #292929; " +
            "-fx-text-fill: #FFFFFF; " +
            "-fx-font-size: 13px; " +
            "-fx-background-radius: 8; " +
            "-fx-padding: 10 28; " +
            "-fx-cursor: hand;"
        );
        okButton.setOnAction(e -> {
            alert.setResult(ButtonType.OK);
            alert.close();
        });
        buttonBox.getChildren().add(okButton);
        
        contentBox.getChildren().addAll(iconLabel, titleLabel, messageLabel, buttonBox);
        dialogPane.setContent(contentBox);
        
        // ✅ 隐藏默认按钮区域
        dialogPane.getButtonTypes().clear();
        dialogPane.getButtonTypes().add(ButtonType.OK);
        
        dialogPane.setMinWidth(320);
        dialogPane.setMinHeight(200);
        
        // ✅ 弹框显示时立即设置 Scene 透明（不用 Platform.runLater，避免延迟）
        alert.setOnShowing(e -> {
            // 设置 Scene 背景透明
            if (dialogPane.getScene() != null) {
                dialogPane.getScene().setFill(Color.TRANSPARENT);
            }
            
            // 隐藏默认按钮区域
            dialogPane.lookupAll(".button-bar").forEach(node -> {
                node.setStyle("-fx-pref-height: 0; -fx-min-height: 0; -fx-max-height: 0; -fx-padding: 0; -fx-background-color: transparent;");
                node.setVisible(false);
                node.setManaged(false);
            });
            
            dialogPane.lookupAll(".button").forEach(node -> {
                if (node instanceof Button btn && !"确定".equals(btn.getText())) {
                    btn.setVisible(false);
                    btn.setManaged(false);
                }
            });
        });
        
        // ✅ 双重保险：显示后再次确保透明
        alert.setOnShown(e -> {
            if (dialogPane.getScene() != null) {
                dialogPane.getScene().setFill(Color.TRANSPARENT);
            }
        });
    }
    
    /**
     * ⭐ 检查快捷键冲突
     * @return 如果有冲突返回冲突信息，否则返回 null
     */
    private String checkShortcutConflicts() {
        // 创建一个 Map 来存储快捷键和对应的功能名称
        java.util.Map<String, java.util.List<String>> keyMap = new java.util.HashMap<>();
        
        // ⭐ 添加所有快捷键（跳过抓拍，因为它是固定的空格键且禁用的）
        addKeyToMap(keyMap, rowAdjustField.getText(), "行数增加");
        addKeyToMap(keyMap, colAdjustField.getText(), "列数增加");
        addKeyToMap(keyMap, rowSubAdjustField.getText(), "行数减少");
        addKeyToMap(keyMap, colSubAdjustField.getText(), "列数减少");
        addKeyToMap(keyMap, cameraSwitchField.getText(), "镜头切换");
        addKeyToMap(keyMap, settingsField.getText(), "设置");
        addKeyToMap(keyMap, deleteLastField.getText(), "删除最后一项");
        
        // ⭐ 新增快捷键
        addKeyToMap(keyMap, captureClearField.getText(), "抓拍清空");
        // ⭐ 相机缩放和相机焦距：不在UI显示，使用默认值，不检查冲突
        // addKeyToMap(keyMap, cameraZoomField.getText(), "相机缩放");
        // addKeyToMap(keyMap, cameraFocusField.getText(), "相机焦距");
        
        // 录制功能快捷键
        addKeyToMap(keyMap, slowMotionField.getText(), "慢放");
        // ⭐ 修复：慢放抓拍现在可以自定义，需要检查冲突
        addKeyToMap(keyMap, captureField.getText(), "慢放抓拍");
        addKeyToMap(keyMap, clearField.getText(), "清空");
        
        // 窗口控制快捷键
        addKeyToMap(keyMap, fullscreenField.getText(), "抓拍全屏");
        addKeyToMap(keyMap, realtimeWindowField.getText(), "实时窗口");
        addKeyToMap(keyMap, slowMotionWindowField.getText(), "慢放窗口");
        addKeyToMap(keyMap, fullscreenViewerField.getText(), "全屏查看/取消");
        
        // ⭐ 检查是否有冲突
        StringBuilder conflicts = new StringBuilder();
        for (java.util.Map.Entry<String, java.util.List<String>> entry : keyMap.entrySet()) {
            if (entry.getValue().size() > 1) {
                if (conflicts.length() > 0) {
                    conflicts.append("\n");
                }
                conflicts.append("快捷键 [")
                        .append(entry.getKey())
                        .append("] 被以下功能重复使用：\n");
                for (int i = 0; i < entry.getValue().size(); i++) {
                    conflicts.append("  ").append(i + 1).append(". ")
                            .append(entry.getValue().get(i));
                    if (i < entry.getValue().size() - 1) {
                        conflicts.append("\n");
                    }
                }
            }
        }
        
        return conflicts.length() > 0 ? conflicts.toString() : null;
    }
    
    /**
     * ⭐ 将快捷键添加到 Map 中
     */
    private void addKeyToMap(java.util.Map<String, java.util.List<String>> keyMap, String keyText, String functionName) {
        if (keyText == null || keyText.trim().isEmpty()) {
            return;
        }
        
        String key = keyText.trim().toUpperCase();
        keyMap.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(functionName);
    }
    
}