package com.acard.acard.ui;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

/**
 * 相机主界面应用程序启动类
 * 负责初始化和启动相机主界面
 */
public class CameraMainApplication extends Application {
    
    // 改为启动登录窗口
    private static final String FXML_PATH = "/com/acard/acard/login-view.fxml";
    private static final String APPLICATION_TITLE = "登录";
    
    private CameraMainController controller;
    
    @Override
    public void start(Stage primaryStage) throws Exception {
        // 加载登录FXML文件
        FXMLLoader loader = new FXMLLoader(getClass().getResource(FXML_PATH));
        Parent root = loader.load();
        
        // 登录窗口：标准标题栏
        primaryStage.initStyle(StageStyle.DECORATED);
        primaryStage.setTitle(APPLICATION_TITLE);
        
        // ⭐ 设置任务栏图标
        try {
            Image icon = new Image(getClass().getResourceAsStream("/images/icon.png"));
            primaryStage.getIcons().add(icon);
        } catch (Exception e) {
            System.err.println("加载图标失败: " + e.getMessage());
        }
        
        // 根据屏幕尺寸设定一个适中的窗口大小并居中显示
        Screen screen = Screen.getPrimary();
        double screenWidth = screen.getVisualBounds().getWidth();
        double screenHeight = screen.getVisualBounds().getHeight();
        double windowWidth = Math.max(520, Math.min(720, screenWidth * 0.5));
        double windowHeight = Math.max(400, Math.min(600, screenHeight * 0.6));
        
        Scene scene = new Scene(root, windowWidth, windowHeight);
        primaryStage.setScene(scene);
        
        // 设置最小窗口大小与居中
        primaryStage.setMinWidth(480);
        primaryStage.setMinHeight(360);
        primaryStage.setX((screenWidth - windowWidth) / 2);
        primaryStage.setY((screenHeight - windowHeight) / 2);
        
        // 允许调整窗口大小
        primaryStage.setResizable(true);
        
        // 显示窗口
        primaryStage.show();
        
        // 关闭事件：退出应用
        primaryStage.setOnCloseRequest(event -> javafx.application.Platform.exit());
    }
    
    /**
     * 应用程序入口点
     */
    public static void main(String[] args) {
        launch(args);
    }
    
    /**
     * 获取控制器实例（主界面模式下使用；登录模式不使用）
     */
    public CameraMainController getController() {
        return controller;
    }
}