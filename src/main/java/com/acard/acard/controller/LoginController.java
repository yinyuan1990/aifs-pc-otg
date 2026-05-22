package com.acard.acard.controller;

import com.acard.acard.viewmodel.LoginViewModel;
import com.acard.acard.net.NetworkManager;
import com.acard.acard.net.NetworkConfig;
import com.acard.acard.net.StompWebSocketClient;
import com.acard.acard.storage.AuthStore;
import com.acard.acard.net.LoginResponse;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.StringConverter;

import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ResourceBundle;

/**
 * 登录界面控制器
 * 实现MVVM模式中的Controller层，连接View和ViewModel
 */
public class LoginController implements Initializable {
    

    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private ComboBox<String> accountComboBox;
    @FXML private Button loginButton;
    @FXML private Button registerButton;
    @FXML private Button settingsButton;
    @FXML private Button clearButton;
    @FXML private Button bindButton;
    @FXML private Label errorLabel;
    @FXML private ProgressIndicator progressIndicator;
    
    private LoginViewModel viewModel;
    
    @Override
    public void initialize(URL location, ResourceBundle resources) {
        // 初始化ViewModel
        viewModel = new LoginViewModel();
        
        // 设置数据绑定
        setupDataBinding();
        
        // 设置事件处理
        setupEventHandlers();
        
        // 初始化UI状态
        initializeUI();
    }
    
    /**
     * 设置数据绑定
     */
    private void setupDataBinding() {
        // 双向绑定用户输入字段
        usernameField.textProperty().bindBidirectional(viewModel.getUser().usernameProperty());
        passwordField.textProperty().bindBidirectional(viewModel.getUser().passwordProperty());
        
        // 绑定账号选择
        accountComboBox.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null) {
                viewModel.getUser().setAccountNumber(newValue);
            }
        });
        
        // 绑定登录按钮状态
        loginButton.disableProperty().bind(viewModel.loginButtonDisabledProperty());
        
        // 绑定错误信息显示
        errorLabel.textProperty().bind(viewModel.errorMessageProperty());
        errorLabel.visibleProperty().bind(viewModel.errorMessageProperty().isNotEmpty());
        
        // 绑定进度指示器
        progressIndicator.visibleProperty().bind(viewModel.loginInProgressProperty());
        
        // 绑定登录按钮文本
        loginButton.textProperty().bind(
            Bindings.createStringBinding(
                () -> viewModel.loginInProgressProperty().get() ? "登录中..." : "登录",
                viewModel.loginInProgressProperty()
            )
        );
    }
    
    /**
     * 设置事件处理器
     */
    private void setupEventHandlers() {
        // 清空按钮事件
        clearButton.setOnAction(event -> handleClear());
        
        // 注册按钮事件
        registerButton.setOnAction(event -> handleRegister());

        // 相机设定弹窗事件
        if (settingsButton != null) {
            settingsButton.setOnAction(event -> {
                try {
                    com.acard.acard.ui.CameraSettingsDialogController.showDialog((Stage) loginButton.getScene().getWindow());
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            });
        }
        
        // 绑定按钮事件
        bindButton.setOnAction(event -> handleBind());
        
        // 回车键登录
        usernameField.setOnAction(event -> handleLogin());
        passwordField.setOnAction(event -> handleLogin());
    }
    
    /**
     * 初始化UI状态
     */
    private void initializeUI() {
        // 添加账号选项
        accountComboBox.getItems().addAll("账号1", "账号2", "账号3");
        
        // 设置账号下拉框的显示格式
        accountComboBox.setConverter(new StringConverter<String>() {
            @Override
            public String toString(String object) {
                return object != null ? object : "";
            }
            
            @Override
            public String fromString(String string) {
                return string;
            }
        });
        
        // ✅ 加载上次登录的账号
        loadLastUsername();
        
        // 设置焦点到用户名输入框
        Platform.runLater(() -> usernameField.requestFocus());
    }
    
    /**
     * 加载上次登录的账号
     */
    private void loadLastUsername() {
        try {
            com.acard.acard.storage.LoginPreferences prefs = 
                com.acard.acard.storage.LoginPreferences.getInstance();
            String lastUsername = prefs.getLastUsername();
            
            if (lastUsername != null && !lastUsername.trim().isEmpty()) {
                usernameField.setText(lastUsername);
                // 如果有上次的账号，焦点移到密码框
                Platform.runLater(() -> passwordField.requestFocus());
                System.out.println("✅ 已自动填充上次登录账号: " + lastUsername);
            }
        } catch (Exception e) {
            System.err.println("⚠️ 加载上次登录账号失败: " + e.getMessage());
        }
    }
    
    /**
     * 处理登录按钮点击事件
     */
    @FXML
    private void handleLogin() {
        viewModel.login(
            this::onLoginSuccess,
            this::onLoginFailure
        );
    }
    
    /**
     * 处理注册按钮点击事件
     */
    @FXML
    private void handleRegister() {
        // 显示注册对话框或跳转到注册页面
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("注册");
        alert.setHeaderText("注册功能");
        alert.setContentText("注册功能正在开发中，请联系管理员获取账号。");
        alert.showAndWait();
    }
    
    /**
     * 处理绑定按钮点击事件
     */
    @FXML
    private void handleBind() {
        // 显示绑定对话框或跳转到绑定页面
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("绑定");
        alert.setHeaderText("绑定功能");
        alert.setContentText("绑定功能正在开发中，请联系管理员进行设备绑定。");
        alert.showAndWait();
    }
    
    /**
     * 处理清空按钮点击事件
     */
    private void handleClear() {

        //viewModel.clearForm();
        accountComboBox.setValue(null);
        //Platform.runLater(() -> usernameField.requestFocus());
    }
    
    /**
     * 登录成功回调
     */
    private void onLoginSuccess() {
        Platform.runLater(() -> {
            try {
                // ✅ 保存本次登录的账号
                saveLoginUsername();
                
                openCameraMainInterface();
                Stage currentStage = (Stage) loginButton.getScene().getWindow();
                currentStage.close();
            } catch (Exception e) {
                e.printStackTrace();
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setTitle("错误");
                alert.setHeaderText("无法打开主界面");
                alert.setContentText("打开相机主界面时发生错误：" + e.getMessage());
                alert.showAndWait();
            }
        });
    }
    
    /**
     * 保存本次登录的账号
     */
    private void saveLoginUsername() {
        try {
            String username = usernameField.getText();
            if (username != null && !username.trim().isEmpty()) {
                com.acard.acard.storage.LoginPreferences prefs = 
                    com.acard.acard.storage.LoginPreferences.getInstance();
                prefs.saveLastUsername(username);
                System.out.println("✅ 登录成功，已保存账号: " + username);
            }
        } catch (Exception e) {
            System.err.println("⚠️ 保存登录账号失败: " + e.getMessage());
        }
    }
    
    /**
     * 打开相机主界面
     */
    private void openCameraMainInterface() throws Exception {
        // 加载相机主界面FXML

        //HelloApplication.class.getResource("login-view.fxml")
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/acard/acard/ui/CameraMainUi.fxml"));
        Parent root = loader.load();
        
        // 计算窗口大小（屏幕的80%）
        Screen screen = Screen.getPrimary();
        double screenWidth = screen.getVisualBounds().getWidth();
        double screenHeight = screen.getVisualBounds().getHeight();
        double windowWidth = screenWidth * 0.8;
        double windowHeight = screenHeight * 0.8;
        
        // 创建新的舞台
        Stage mainStage = new Stage();
        Scene scene = new Scene(root, windowWidth, windowHeight);
        // ⭐⭐⭐ 设置 Scene 透明背景（必须！）
        scene.setFill(javafx.scene.paint.Color.TRANSPARENT);
        // 设置全局快捷键
        setupGlobalKeyBindings(scene, mainStage);
        
        // 配置舞台
        mainStage.setTitle("相机主界面系统");
        mainStage.setScene(scene);
        mainStage.initStyle(StageStyle.TRANSPARENT); // ⭐⭐⭐ 改为透明背景
        
        // ⭐ 设置任务栏图标
        try {
            javafx.scene.image.Image icon = new javafx.scene.image.Image(getClass().getResourceAsStream("/images/icon.png"));
            mainStage.getIcons().add(icon);
        } catch (Exception e) {
            System.err.println("加载主窗口图标失败: " + e.getMessage());
        }
        
        // 设置窗口居中
        mainStage.setX((screenWidth - windowWidth) / 2);
        mainStage.setY((screenHeight - windowHeight) / 2);
        
        // 设置最小窗口大小
        mainStage.setMinWidth(800);
        mainStage.setMinHeight(600);
        
        // 允许调整窗口大小
        mainStage.setResizable(true);
        
        // 显示窗口
        mainStage.show();


        // 设置关闭事件
        mainStage.setOnCloseRequest(event -> {
            Platform.exit();
        });
    }
    
    /**
     * 设置全局快捷键绑定
     */
    private void setupGlobalKeyBindings(Scene scene, Stage stage) {
        // F键 - 全屏切换

    }
    
    /**
     * 登录失败回调
     */
    private void onLoginFailure() {
        Platform.runLater(() -> {
            // 清空密码字段
            passwordField.clear();
            passwordField.requestFocus();
        });
    }
    
    /**
     * 获取ViewModel（用于测试或其他需要）
     */
    public LoginViewModel getViewModel() {
        return viewModel;
    }
}
