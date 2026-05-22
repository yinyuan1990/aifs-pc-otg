package com.acard.acard.utils;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * 全局提示弹框工具类
 *
 * 用法：
 * - AlertUtil.success("操作成功");
 * - AlertUtil.error("操作失败", "详细错误信息");
 * - AlertUtil.warning("警告信息");
 * - AlertUtil.info("提示信息");
 * - AlertUtil.confirm("确认删除？", result -> { if(result) { ... } });
 * - AlertUtil.toast("自动消失的提示", 2000);
 */
public class AlertUtil {

    private static Stage primaryStage;  // 主窗口引用（用于居中显示）

    /**
     * 设置主窗口（用于居中显示）
     * 建议在 Application.start() 中调用
     */
    public static void setPrimaryStage(Stage stage) {
        primaryStage = stage;
    }

    // ========== 成功提示 ==========

    /**
     * 显示成功提示
     */
    public static void success(String message) {
        success("成功", message);
    }

    /**
     * 显示成功提示（自定义标题）
     */
    public static void success(String title, String message) {
        Platform.runLater(() -> showAlert(Alert.AlertType.INFORMATION, title, message,
                "-fx-text-fill: #28a745;"));
    }

    // ========== 错误提示 ==========

    /**
     * 显示错误提示（深色主题风格）
     */
    public static void error(String message) {
        error("提示", message);
    }

    /**
     * 显示错误提示（深色主题风格，自定义标题）
     */
    public static void error(String title, String message) {
        Platform.runLater(() -> showDarkErrorAlert(title, message));
    }
    
    /**
     * ⭐ 显示深色主题错误提示弹框
     */
    private static void showDarkErrorAlert(String title, String message) {
        Stage errorDialog = new Stage();
        errorDialog.initModality(Modality.APPLICATION_MODAL);
        errorDialog.initStyle(StageStyle.TRANSPARENT);
        errorDialog.setTitle(title);
        
        // 主容器
        VBox container = new VBox(16);
        container.setStyle("-fx-background-color: #1F1F1F; -fx-background-radius: 12; -fx-padding: 24; -fx-border-color: #3a3a3a; -fx-border-radius: 12; -fx-border-width: 1;");
        container.setPrefWidth(320);
        container.setAlignment(Pos.CENTER);
        
        // 错误图标
        Label iconLabel = new Label("⚠️");
        iconLabel.setStyle("-fx-font-size: 32px;");
        
        // 标题
        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-text-fill: #FAFAFA; -fx-font-size: 18px; -fx-font-weight: bold;");
        
        // 消息内容
        Label messageLabel = new Label(message);
        messageLabel.setStyle("-fx-text-fill: #ef4444; -fx-font-size: 14px; -fx-text-alignment: center;");
        messageLabel.setAlignment(Pos.CENTER);
        messageLabel.setWrapText(true);
        
        // 确定按钮
        Button okBtn = new Button("知道了");
        okBtn.setStyle("-fx-background-color: #607AFB; -fx-text-fill: white; -fx-font-size: 14px; -fx-padding: 10 40; -fx-background-radius: 8; -fx-cursor: hand;");
        okBtn.setOnMouseEntered(e -> okBtn.setStyle("-fx-background-color: #7089fc; -fx-text-fill: white; -fx-font-size: 14px; -fx-padding: 10 40; -fx-background-radius: 8; -fx-cursor: hand;"));
        okBtn.setOnMouseExited(e -> okBtn.setStyle("-fx-background-color: #607AFB; -fx-text-fill: white; -fx-font-size: 14px; -fx-padding: 10 40; -fx-background-radius: 8; -fx-cursor: hand;"));
        okBtn.setOnAction(e -> errorDialog.close());
        
        container.getChildren().addAll(iconLabel, titleLabel, messageLabel, okBtn);
        
        // 设置场景
        Scene scene = new Scene(container);
        scene.setFill(Color.TRANSPARENT);
        errorDialog.setScene(scene);
        
        // 居中显示
        if (primaryStage != null) {
            errorDialog.initOwner(primaryStage);
            errorDialog.setOnShown(e -> {
                errorDialog.setX(primaryStage.getX() + (primaryStage.getWidth() - errorDialog.getWidth()) / 2);
                errorDialog.setY(primaryStage.getY() + (primaryStage.getHeight() - errorDialog.getHeight()) / 2);
            });
        }
        
        // 窗口拖动
        final double[] dragOffset = new double[2];
        container.setOnMousePressed(e -> {
            dragOffset[0] = e.getSceneX();
            dragOffset[1] = e.getSceneY();
        });
        container.setOnMouseDragged(e -> {
            errorDialog.setX(e.getScreenX() - dragOffset[0]);
            errorDialog.setY(e.getScreenY() - dragOffset[1]);
        });
        
        errorDialog.showAndWait();
    }

    // ========== 警告提示 ==========

    /**
     * 显示警告提示
     */
    public static void warning(String message) {
        warning("警告", message);
    }

    /**
     * 显示警告提示（自定义标题）
     */
    public static void warning(String title, String message) {
        Platform.runLater(() -> showAlert(Alert.AlertType.WARNING, title, message,
                "-fx-text-fill: #ffc107;"));
    }

    // ========== 信息提示 ==========

    /**
     * 显示信息提示
     */
    public static void info(String message) {
        info("提示", message);
    }

    /**
     * 显示信息提示（自定义标题）
     */
    public static void info(String title, String message) {
        Platform.runLater(() -> showAlert(Alert.AlertType.INFORMATION, title, message,
                "-fx-text-fill: #17a2b8;"));
    }

    // ========== 确认对话框 ==========

    /**
     * 显示确认对话框（阻塞）
     * @return true=确认, false=取消
     */
    public static boolean confirmSync(String message) {
        return confirmSync("确认", message);
    }

    /**
     * 显示确认对话框（阻塞，自定义标题）
     * @return true=确认, false=取消
     */
    public static boolean confirmSync(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);

        // 自定义按钮文本
        ButtonType btnYes = new ButtonType("确认", ButtonBar.ButtonData.YES);
        ButtonType btnNo = new ButtonType("取消", ButtonBar.ButtonData.NO);
        alert.getButtonTypes().setAll(btnYes, btnNo);

        // 居中显示
        if (primaryStage != null) {
            alert.initOwner(primaryStage);
        }

        Optional<ButtonType> result = alert.showAndWait();
        return result.isPresent() && result.get() == btnYes;
    }

    /**
     * 显示确认对话框（非阻塞，回调方式）
     */
    public static void confirm(String message, Consumer<Boolean> callback) {
        confirm("确认", message, callback);
    }

    /**
     * 显示确认对话框（非阻塞，回调方式，自定义标题）
     */
    public static void confirm(String title, String message, Consumer<Boolean> callback) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(message);

            // 自定义按钮文本
            ButtonType btnYes = new ButtonType("确认", ButtonBar.ButtonData.YES);
            ButtonType btnNo = new ButtonType("取消", ButtonBar.ButtonData.NO);
            alert.getButtonTypes().setAll(btnYes, btnNo);

            // 居中显示
            if (primaryStage != null) {
                alert.initOwner(primaryStage);
            }

            alert.showAndWait().ifPresent(result -> {
                if (callback != null) {
                    callback.accept(result == btnYes);
                }
            });
        });
    }

    // ========== Toast 自动消失提示 ==========

    /**
     * 显示 Toast 提示（2秒后自动消失）
     */
    public static void toast(String message) {
        toast(message, 2000);
    }

    /**
     * 显示 Toast 提示（自定义持续时间）
     * @param durationMs 持续时间（毫秒）
     */
    public static void toast(String message, int durationMs) {
        Platform.runLater(() -> showToast(message, durationMs, "-fx-background-color: rgba(0, 0, 0, 0.8); -fx-text-fill: white;"));
    }

    /**
     * 显示成功 Toast（绿色）
     */
    public static void toastSuccess(String message) {
        Platform.runLater(() -> showToast(message, 2000, "-fx-background-color: #28a745; -fx-text-fill: white;"));
    }

    /**
     * 显示错误 Toast（红色）
     */
    public static void toastError(String message) {
        Platform.runLater(() -> showToast(message, 2000, "-fx-background-color: #dc3545; -fx-text-fill: white;"));
    }

    /**
     * 显示警告 Toast（黄色）
     */
    public static void toastWarning(String message) {
        Platform.runLater(() -> showToast(message, 2000, "-fx-background-color: #ffc107; -fx-text-fill: #333;"));
    }

    // ========== 内部实现方法 ==========

    /**
     * 显示标准对话框
     */
    private static void showAlert(Alert.AlertType type, String title, String message, String headerStyle) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);

        // 自定义样式
        DialogPane dialogPane = alert.getDialogPane();
        if (headerStyle != null && !headerStyle.isEmpty()) {
            // 这里可以添加自定义样式（如果需要）
        }

        // 居中显示
        if (primaryStage != null) {
            alert.initOwner(primaryStage);
        }

        alert.showAndWait();
    }

    /**
     * 显示 Toast 提示（自动消失）
     */
    private static void showToast(String message, int durationMs, String style) {
        Stage toastStage = new Stage();
        toastStage.initStyle(StageStyle.TRANSPARENT);
        toastStage.initModality(Modality.NONE);
        toastStage.setAlwaysOnTop(true);

        // 创建提示标签
        Label label = new Label(message);
        label.setStyle(style + "; -fx-padding: 15px 30px; -fx-font-size: 14px; -fx-background-radius: 10px;");
        label.setWrapText(true);
        label.setMaxWidth(400);

        // 创建容器
        StackPane root = new StackPane(label);
        root.setStyle("-fx-background-color: transparent;");
        root.setPadding(new Insets(20));

        Scene scene = new Scene(root);
        scene.setFill(Color.TRANSPARENT);
        toastStage.setScene(scene);

        // 居中显示
        if (primaryStage != null) {
            toastStage.initOwner(primaryStage);
            toastStage.setX(primaryStage.getX() + primaryStage.getWidth() / 2 - 200);
            toastStage.setY(primaryStage.getY() + primaryStage.getHeight() - 150);
        }

        toastStage.show();

        // 自动关闭
        Timeline timeline = new Timeline(new KeyFrame(Duration.millis(durationMs), e -> toastStage.close()));
        timeline.play();
    }

    // ========== 输入对话框 ==========

    /**
     * 显示输入对话框
     * @return 用户输入的内容（取消返回 null）
     */
    public static String input(String title, String message, String defaultValue) {
        TextInputDialog dialog = new TextInputDialog(defaultValue);
        dialog.setTitle(title);
        dialog.setHeaderText(null);
        dialog.setContentText(message);

        // 居中显示
        if (primaryStage != null) {
            dialog.initOwner(primaryStage);
        }

        Optional<String> result = dialog.showAndWait();
        return result.orElse(null);
    }

    /**
     * 显示输入对话框（无默认值）
     */
    public static String input(String title, String message) {
        return input(title, message, "");
    }

    // ========== 自定义对话框 ==========

    /**
     * 显示自定义内容对话框
     */
    public static void custom(String title, javafx.scene.Node content) {
        Alert alert = new Alert(Alert.AlertType.NONE);
        alert.setTitle(title);
        alert.setHeaderText(null);

        DialogPane dialogPane = alert.getDialogPane();
        dialogPane.setContent(content);
        dialogPane.getButtonTypes().add(ButtonType.CLOSE);

        // 居中显示
        if (primaryStage != null) {
            alert.initOwner(primaryStage);
        }

        alert.showAndWait();
    }
}
