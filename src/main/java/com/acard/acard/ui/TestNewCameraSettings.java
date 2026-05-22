package com.acard.acard.ui;

import javafx.application.Application;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

public class TestNewCameraSettings extends Application {

    @Override
    public void start(Stage primaryStage) {
        primaryStage.setTitle("测试新相机设置界面");
        
        // 创建测试按钮
        Button testButton = new Button("打开新相机设置对话框");
        testButton.setPrefSize(200, 50);
        testButton.setStyle("-fx-font-size: 14px;");
        
        // 设置按钮事件
        testButton.setOnAction(e -> {
            NewCameraSettingsDialogLauncher.testDialog(primaryStage);
        });
        
        // 创建布局
        VBox root = new VBox(20);
        root.setAlignment(Pos.CENTER);
        root.getChildren().add(testButton);
        root.setStyle("-fx-padding: 50;");
        
        // 设置场景
        Scene scene = new Scene(root, 400, 200);
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}