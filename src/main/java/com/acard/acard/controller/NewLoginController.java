package com.acard.acard.controller;

import com.acard.acard.tools.LogTools;
import com.acard.acard.viewmodel.LoginViewModel;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.io.File;
import java.net.URL;
import java.util.ResourceBundle;

/**
 * 新登录界面控制器
 * 参考 login.html 样式设计
 */
public class NewLoginController implements Initializable {
    
    // 主容器和控制按钮
    @FXML private VBox mainContainer;
    @FXML private Button closeButton;
    
    // Tab 切换按钮
    @FXML private Button tabLogin;
    @FXML private Button tabRegister;
    @FXML private Button tabSwitch;
    
    // 表单容器
    @FXML private VBox loginForm;
    @FXML private VBox registerForm;
    @FXML private VBox switchAccountForm;
    
    // 登录表单元素
    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private Button togglePasswordBtn;
    @FXML private Button loginButton;
    @FXML private Label errorLabel;
    @FXML private ProgressIndicator progressIndicator;
    @FXML private HBox passwordContainer;  // 密码输入框的容器
    
    // 注册表单元素
    @FXML private TextField regUsernameField;
    @FXML private PasswordField regPasswordField;
    @FXML private TextField regNicknameField;
    @FXML private Button refreshNicknameBtn;
    @FXML private Button registerButton;
    @FXML private Label registerErrorLabel;
    @FXML private ProgressIndicator registerProgressIndicator;
    
    // 切换账号表单元素
    @FXML private ListView<String> accountListView;
    @FXML private Label switchAccountHintLabel;
    @FXML private VBox accountListContainer;
    @FXML private ScrollPane accountScrollPane;
    @FXML private Button refreshAccountsBtn;
    @FXML private Button switchLoginButton;
    
    // 切换账号选中状态
    private String selectedControlUsername = null;
    private String selectedDeviceUsername = null;
    private java.util.Map<String, java.util.List<com.acard.acard.net.OnlineStatusResponse.OnlineStatusItem>> accountDeviceMap = new java.util.HashMap<>();
    
    // ⭐ 切换账号时待选择的设备（优先级最高）
    private String pendingDeviceUsername = null;
    // ⭐ 标记是否是切换账号模式（跳过本地缓存，直接远程获取）
    private boolean isSwitchAccountLoading = false;
    
    // 设备选择相关
    @FXML private VBox deviceSelectContainer;
    @FXML private ComboBox<com.acard.acard.net.BindingDevice> deviceComboBox;
    @FXML private Button refreshDeviceBtn;
    @FXML private Label deviceStatusLabel;
    
    // 视频播放器
    @FXML private MediaView videoView;
    
    private LoginViewModel viewModel;
    private MediaPlayer mediaPlayer;
    private TextField visiblePasswordField; // 用于显示明文密码
    private boolean isPasswordVisible = false; // 密码是否可见
    
    // 窗口拖动相关
    private double xOffset = 0;
    private double yOffset = 0;
    
    @Override
    public void initialize(URL location, ResourceBundle resources) {
        // 初始化 ViewModel
        viewModel = new LoginViewModel();
        
        // 设置数据绑定
        setupDataBinding();
        
        // 设置事件处理
        setupEventHandlers();
        
        // 视频播放已移除
        // initializeVideoPlayer();
        
        // 设置窗口拖动和关闭功能
        setupWindowControls();
        
        // 应用圆角样式
        applyRoundedStyles();
        
        // 应用窗口圆角
        Platform.runLater(this::applyWindowRoundedCorners);
        
        // 初始化设备选择下拉框
        setupDeviceComboBox();
        
        // 加载上次登录的账号
        loadLastUsername();
        
        // 设置焦点
        Platform.runLater(() -> {
            if (usernameField.getText().isEmpty()) {
                usernameField.requestFocus();
            } else {
                passwordField.requestFocus();
            }
        });
        
        // 初始化账号列表
        loadAccountList();
        
        // ✅ 检查更新（静默检查，不提示已是最新）
        com.acard.acard.update.UpdateChecker.getInstance().checkUpdateAsync(false);
    }
    
    /**
     * 设置窗口拖动和关闭功能
     */
    private void setupWindowControls() {
        // 关闭按钮
        if (closeButton != null) {
            closeButton.setOnAction(e -> {
                Platform.exit();
            });
        }
        
        // 窗口拖动（在主容器上）
        if (mainContainer != null) {
            mainContainer.setOnMousePressed(event -> {
                xOffset = event.getSceneX();
                yOffset = event.getSceneY();
            });
            
            mainContainer.setOnMouseDragged(event -> {
                javafx.stage.Stage stage = (javafx.stage.Stage) mainContainer.getScene().getWindow();
                stage.setX(event.getScreenX() - xOffset);
                stage.setY(event.getScreenY() - yOffset);
            });
        }
    }
    
    /**
     * 应用圆角样式（通过代码强制设置）
     */
    private void applyRoundedStyles() {
        // 账号输入框圆角
        if (usernameField != null) {
            usernameField.setStyle(usernameField.getStyle() + 
                "; -fx-background-radius: 28; -fx-border-radius: 28;");
        }
        
        // 密码输入框圆角（左侧）
        if (passwordField != null) {
            passwordField.setStyle(passwordField.getStyle() + 
                "; -fx-background-radius: 28 0 0 28; -fx-border-radius: 28 0 0 28;");
        }
        
        // 眼睛按钮圆角（右侧）
        if (togglePasswordBtn != null) {
            togglePasswordBtn.setStyle(togglePasswordBtn.getStyle() + 
                "; -fx-background-radius: 0 28 28 0; -fx-border-radius: 0 28 28 0;");
        }
        
        // 登录按钮圆角
        if (loginButton != null) {
            loginButton.setStyle(loginButton.getStyle() + 
                "; -fx-background-radius: 28; -fx-border-radius: 28;");
        }
    }
    
    /**
     * 应用窗口圆角（裁剪整个窗口为圆角矩形）
     */
    private void applyWindowRoundedCorners() {
        if (mainContainer == null || mainContainer.getScene() == null) {
            return;
        }
        
        javafx.scene.Scene scene = mainContainer.getScene();
        javafx.scene.Parent root = scene.getRoot();
        
        // 创建圆角矩形裁剪
        javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle();
        clip.setArcWidth(32);  // 圆角宽度
        clip.setArcHeight(32); // 圆角高度
        
        // 绑定裁剪区域到窗口尺寸
        clip.widthProperty().bind(scene.widthProperty());
        clip.heightProperty().bind(scene.heightProperty());
        
        // 应用裁剪
        root.setClip(clip);
        
        LogTools.getInstance().logRecord3("✅ 已应用窗口圆角效果");
    }
    
    /**
     * 初始化设备选择下拉框
     */
    private void setupDeviceComboBox() {
        if (deviceComboBox == null) return;
        
        // 设置单元格工厂，自定义显示
        deviceComboBox.setCellFactory(listView -> new javafx.scene.control.ListCell<com.acard.acard.net.BindingDevice>() {
            @Override
            protected void updateItem(com.acard.acard.net.BindingDevice device, boolean empty) {
                super.updateItem(device, empty);
                if (empty || device == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    // 创建自定义显示
                    javafx.scene.layout.HBox hbox = new javafx.scene.layout.HBox(8);
                    hbox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                    
                    // 在线状态指示器
                    javafx.scene.shape.Circle statusDot = new javafx.scene.shape.Circle(5);
                    statusDot.setFill(device.isOnline() 
                        ? javafx.scene.paint.Color.web("#34C759")  // 绿色-在线 
                        : javafx.scene.paint.Color.web("#666666")); // 灰色-离线
                    
                    // 昵称（使用 getDisplayText 方法：deviceNickname 或 deviceNickname(remark)）
                    String displayName = device.getDisplayText();
                    Label nameLabel = new Label(displayName);
                    nameLabel.setStyle("-fx-text-fill: white; -fx-font-size: 14px;");
                    
                    // 在线状态文字
                    Label statusLabel = new Label(device.isOnline() ? "在线" : "离线");
                    statusLabel.setStyle("-fx-text-fill: " + (device.isOnline() ? "#34C759" : "#666666") + "; -fx-font-size: 12px;");
                    
                    hbox.getChildren().addAll(statusDot, nameLabel, statusLabel);
                    setGraphic(hbox);
                    setText(null);
                }
                // 设置单元格背景
                setStyle("-fx-background-color: #292929; -fx-padding: 8 12;");
            }
        });
        
        // 设置按钮单元格（选中后显示的内容）
        deviceComboBox.setButtonCell(new javafx.scene.control.ListCell<com.acard.acard.net.BindingDevice>() {
            @Override
            protected void updateItem(com.acard.acard.net.BindingDevice device, boolean empty) {
                super.updateItem(device, empty);
                if (empty || device == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    javafx.scene.layout.HBox hbox = new javafx.scene.layout.HBox(8);
                    hbox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                    
                    javafx.scene.shape.Circle statusDot = new javafx.scene.shape.Circle(5);
                    statusDot.setFill(device.isOnline() 
                        ? javafx.scene.paint.Color.web("#34C759") 
                        : javafx.scene.paint.Color.web("#666666"));
                    
                    // 昵称（使用 getDisplayText 方法：deviceNickname 或 deviceNickname(remark)）
                    String displayName = device.getDisplayText();
                    Label nameLabel = new Label(displayName);
                    nameLabel.setStyle("-fx-text-fill: white; -fx-font-size: 14px;");
                    
                    Label statusLabel = new Label(device.isOnline() ? "在线" : "离线");
                    statusLabel.setStyle("-fx-text-fill: " + (device.isOnline() ? "#34C759" : "#666666") + "; -fx-font-size: 12px;");
                    
                    hbox.getChildren().addAll(statusDot, nameLabel, statusLabel);
                    setGraphic(hbox);
                    setText(null);
                }
                setStyle("-fx-background-color: #292929;");
            }
        });
        
        // 监听选择变化
        deviceComboBox.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                viewModel.setSelectedDeviceUsername(newVal.getDeviceUsername());
                System.out.println("🔗 选择设备: " + newVal.getDeviceNickname() + " (" + newVal.getDeviceUsername() + ")");
            }
        });
        
        // 添加滚轮支持
        deviceComboBox.setOnScroll(event -> {
            if (event.getDeltaY() < 0) {
                // 向下滚动，选择下一个
                int currentIndex = deviceComboBox.getSelectionModel().getSelectedIndex();
                if (currentIndex < deviceComboBox.getItems().size() - 1) {
                    deviceComboBox.getSelectionModel().select(currentIndex + 1);
                }
            } else if (event.getDeltaY() > 0) {
                // 向上滚动，选择上一个
                int currentIndex = deviceComboBox.getSelectionModel().getSelectedIndex();
                if (currentIndex > 0) {
                    deviceComboBox.getSelectionModel().select(currentIndex - 1);
                }
            }
        });
        
        // 刷新按钮点击事件
        if (refreshDeviceBtn != null) {
            refreshDeviceBtn.setOnAction(e -> refreshDeviceList());
        }
        
        // ⭐ 账号变化时清空设备列表，不显示本地缓存
        // 只有密码正确后才加载设备列表
        if (usernameField != null) {
            usernameField.textProperty().addListener((obs, oldVal, newVal) -> {
                // 账号变化时清空设备列表
                if (oldVal != null && !oldVal.equals(newVal)) {
                    Platform.runLater(() -> {
                        if (deviceComboBox != null) {
                            deviceComboBox.getItems().clear();
                        }
                        if (deviceStatusLabel != null) {
                            deviceStatusLabel.setText("请输入密码后查询设备");
                            deviceStatusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #64748b;");
                            deviceStatusLabel.setVisible(true);
                        }
                    });
                }
            });
        }
        
        // ⭐ 密码输入后自动加载设备列表（失去焦点时触发）
        if (passwordField != null) {
            passwordField.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
                if (!isFocused) {
                    // ⭐ 切换账号模式下，不触发自动刷新（优先级最高）
                    if (isSwitchAccountLoading) {
                        LogTools.getInstance().logRecord3("📱 切换账号模式 - 跳过密码焦点触发的自动刷新");
                        return;
                    }
                    // 密码框失去焦点，检查是否有账号和密码
                    String username = usernameField != null ? usernameField.getText() : null;
                    String password = passwordField.getText();
                    if (username != null && !username.isEmpty() && password != null && !password.isEmpty()) {
                        // 自动加载设备列表
                        refreshDeviceList();
                    }
                }
            });
        }
    }
    
    /**
     * 加载设备列表（仅从本地缓存加载，不自动查询远程）
     * ⭐ 远程查询需要用户点击刷新按钮，且需要输入密码
     */
    private void loadDeviceList(String username) {
        if (username == null || username.isEmpty()) {
            updateDeviceListUI(new java.util.ArrayList<>(), false);
            return;
        }
        
        // 仅从本地缓存加载
        java.util.List<com.acard.acard.net.BindingDevice> cachedDevices = 
            com.acard.acard.storage.DeviceListStore.getInstance().getDevices(username);
        
        if (!cachedDevices.isEmpty()) {
            updateDeviceListUI(cachedDevices, false);  // ⭐ 本地缓存
            if (deviceStatusLabel != null) {
                deviceStatusLabel.setText("已加载本地缓存");
                deviceStatusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #64748b;");
            }
        } else {
            // 本地无缓存，提示用户输入密码后查询
            if (deviceStatusLabel != null) {
                deviceStatusLabel.setText("请输入密码后点击刷新");
                deviceStatusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #64748b;");
            }
        }
    }
    
    /**
     * 从远程查询设备列表
     * ⭐ 逻辑：查询远程 → 增量更新本地缓存 → 刷新列表
     * @param username 控制端账号
     * @param showLoading 是否显示加载提示
     */
    private void fetchDeviceListFromRemote(String username, boolean showLoading) {
        if (username == null || username.isEmpty()) {
            return;
        }
        
        if (showLoading && deviceStatusLabel != null) {
            Platform.runLater(() -> {
                deviceStatusLabel.setText("正在刷新设备列表...");
                deviceStatusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #64748b;");
            });
        }
        
        com.acard.acard.net.NetworkManager.getInstance()
            .getBindingDevices(username)
            .thenAccept(resp -> {
                Platform.runLater(() -> {
                    if (resp.isSuccess() && resp.getData() != null && resp.getData().getDevices() != null) {
                        java.util.List<com.acard.acard.net.BindingDevice> newDevices = resp.getData().getDevices();
                        
                        // ⭐ 增量更新本地缓存
                        com.acard.acard.storage.DeviceListStore.getInstance().updateDevices(username, newDevices);
                        
                        // ⭐ 获取更新后的完整列表并刷新UI
                        java.util.List<com.acard.acard.net.BindingDevice> updatedDevices = 
                            com.acard.acard.storage.DeviceListStore.getInstance().getDevices(username);
                        updateDeviceListUI(updatedDevices, true);  // ⭐ 远程数据
                        
                        // ⭐ 设备列表加载完成，隐藏状态文字
                        if (deviceStatusLabel != null) {
                            deviceStatusLabel.setText("");
                            deviceStatusLabel.setVisible(false);
                        }
                    } else {
                        // 查询失败，但如果已有本地缓存则不更新UI
                        if (deviceStatusLabel != null && deviceComboBox.getItems().isEmpty()) {
                            deviceStatusLabel.setText("暂无绑定设备");
                            deviceStatusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #64748b;");
                        } else if (deviceStatusLabel != null) {
                            deviceStatusLabel.setText("查询失败，使用本地缓存");
                            deviceStatusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #f59e0b;");
                        }
                    }
                });
            })
            .exceptionally(ex -> {
                Platform.runLater(() -> {
                    // 网络错误，如果有本地缓存则保留
                    if (deviceStatusLabel != null && deviceComboBox.getItems().isEmpty()) {
                        deviceStatusLabel.setText("网络错误，请稍后重试");
                        deviceStatusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #ef4444;");
                    } else if (deviceStatusLabel != null) {
                        deviceStatusLabel.setText("同步失败，使用本地缓存");
                        deviceStatusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #f59e0b;");
                    }
                });
                return null;
            });
    }
    
    /**
     * 刷新设备列表（手动刷新按钮）
     * ⭐ 只有输入了账号和密码才能查询设备列表
     * 逻辑：先本地缓存 → 查询远程 → 增量更新 → 刷新列表
     */
    private void refreshDeviceList() {
        String username = usernameField.getText();
        String password = passwordField.getText();
        
        if (username == null || username.isEmpty()) {
            if (deviceStatusLabel != null) {
                deviceStatusLabel.setText("请先输入账号");
                deviceStatusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #f59e0b;");
            }
            return;
        }
        
        if (password == null || password.isEmpty()) {
            if (deviceStatusLabel != null) {
                deviceStatusLabel.setText("请先输入密码");
                deviceStatusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #f59e0b;");
            }
            return;
        }
        
        // ⭐ 密码已输入，执行设备列表加载
        // 步骤1：先从本地缓存加载并显示
        java.util.List<com.acard.acard.net.BindingDevice> cachedDevices = 
            com.acard.acard.storage.DeviceListStore.getInstance().getDevices(username);
        
        // ⭐ 如果是切换账号模式，跳过本地缓存
        if (!isSwitchAccountLoading && !cachedDevices.isEmpty()) {
            updateDeviceListUI(cachedDevices, false);  // ⭐ 本地缓存
            if (deviceStatusLabel != null) {
                deviceStatusLabel.setText("已加载本地缓存，正在同步...");
                deviceStatusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #64748b;");
            }
        } else {
            if (deviceStatusLabel != null) {
                deviceStatusLabel.setText("正在查询设备列表...");
                deviceStatusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #64748b;");
            }
        }
        
        // 步骤2：查询远程获取最新数据 → 增量更新 → 刷新列表
        fetchDeviceListFromRemote(username, false);
    }
    
    /**
     * 更新设备列表UI
     * @param isFromRemote 是否来自远程数据（true=远程，false=本地缓存）
     */
    private void updateDeviceListUI(java.util.List<com.acard.acard.net.BindingDevice> devices, boolean isFromRemote) {
        if (deviceComboBox == null) return;
        
        Platform.runLater(() -> {
            // 保存当前选中的设备
            com.acard.acard.net.BindingDevice previousSelected = deviceComboBox.getSelectionModel().getSelectedItem();
            String previousDeviceUsername = previousSelected != null ? previousSelected.getDeviceUsername() : null;
            
            deviceComboBox.getItems().clear();
            
            if (devices == null || devices.isEmpty()) {
                if (deviceStatusLabel != null) {
                    deviceStatusLabel.setText("暂无绑定设备");
                    deviceStatusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #64748b;");
                }
                return;
            }
            
            deviceComboBox.getItems().addAll(devices);
            
            // ⭐ 设备选择优先级：
            // 1. 切换账号模式：pendingDeviceUsername（最高优先级）
            // 2. 用户已选择的设备：previousDeviceUsername
            // 3. 兜底：第一个在线设备
            com.acard.acard.net.BindingDevice toSelect = null;
            
            // 1. 切换账号模式 + 远程数据到达 => 使用 pendingDeviceUsername（最高优先级）
            if (isSwitchAccountLoading && isFromRemote && pendingDeviceUsername != null && !pendingDeviceUsername.isEmpty()) {
                String targetDevice = pendingDeviceUsername;
                toSelect = devices.stream()
                    .filter(d -> targetDevice.equals(d.getDeviceUsername()))
                    .findFirst()
                    .orElse(null);
                if (toSelect != null) {
                    LogTools.getInstance().logRecord3("📱 ✅ 切换账号 - 已选中指定设备: " + toSelect.getDeviceUsername());
                } else {
                    LogTools.getInstance().logRecord3("📱 ⚠️ 切换账号 - 未找到指定设备: " + targetDevice);
                }
                // ⭐ 清除标记，只使用一次
                pendingDeviceUsername = null;
                isSwitchAccountLoading = false;
            }
            
            // 2. 普通模式：保留用户之前选择的设备（即使 previousDeviceUsername 来自本地缓存的第一次选择）
            if (toSelect == null && previousDeviceUsername != null && !previousDeviceUsername.isEmpty()) {
                toSelect = devices.stream()
                    .filter(d -> previousDeviceUsername.equals(d.getDeviceUsername()))
                    .findFirst()
                    .orElse(null);
                if (toSelect != null) {
                    LogTools.getInstance().logRecord3("📱 保留用户已选设备: " + toSelect.getDeviceUsername());
                }
            }
            
            // 3. 兜底：只有在没有任何选择时才选择第一个在线设备
            if (toSelect == null) {
                toSelect = devices.stream()
                    .filter(d -> d.isOnline())
                    .findFirst()
                    .orElse(devices.isEmpty() ? null : devices.get(0));
                if (toSelect != null) {
                    LogTools.getInstance().logRecord3("📱 兜底选择设备: " + toSelect.getDeviceUsername());
                }
            }
            if (toSelect != null) {
                deviceComboBox.getSelectionModel().select(toSelect);
            }
            
            // ⭐ 设备列表加载完成，隐藏状态文字
            if (deviceStatusLabel != null) {
                deviceStatusLabel.setText("");
                deviceStatusLabel.setVisible(false);
            }
        });
    }
    
    /**
     * 设置数据绑定
     */
    private void setupDataBinding() {
        // 双向绑定用户输入字段
        usernameField.textProperty().bindBidirectional(viewModel.getUser().usernameProperty());
        passwordField.textProperty().bindBidirectional(viewModel.getUser().passwordProperty());
        
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
        // Tab 切换事件
        tabLogin.setOnAction(e -> switchToLoginTab());
        tabRegister.setOnAction(e -> switchToRegisterTab());
        tabSwitch.setOnAction(e -> switchToSwitchAccountTab());
        
        // 登录表单事件
        usernameField.setOnAction(e -> handleLogin());
        passwordField.setOnAction(e -> handleLogin());
        togglePasswordBtn.setOnAction(e -> togglePasswordVisibility());
        
        // 注册表单事件
        if (registerButton != null) {
            registerButton.setOnAction(e -> handleRegister());
        }
        if (regUsernameField != null) {
            regUsernameField.setOnAction(e -> handleRegister());
        }
        if (regPasswordField != null) {
            regPasswordField.setOnAction(e -> handleRegister());
        }
        
        // 昵称换一个按钮事件
        if (refreshNicknameBtn != null) {
            refreshNicknameBtn.setOnAction(e -> generateAndSetNickname());
        }
        
        // 初始化昵称
        generateAndSetNickname();

        
        // 账号列表点击事件
        if (accountListView != null) {
            accountListView.setOnMouseClicked(e -> {
                if (e.getClickCount() == 1) { // 单击
                    handleAccountSelection();
                }
            });
            
            // 隐藏 ListView 滚动条
            accountListView.setStyle(
                "-fx-background-color: transparent; " +
                "-fx-background: transparent; " +
                "-fx-padding: 0; " +
                "-fx-border-width: 0;"
            );
            // 通过 CSS 隐藏滚动条
            accountListView.getStylesheets().add("data:text/css," +
                ".list-view .scroll-bar:vertical { -fx-opacity: 0; -fx-pref-width: 0; }" +
                ".list-view .scroll-bar:horizontal { -fx-opacity: 0; -fx-pref-height: 0; }"
            );
        }
    }
    
    /**
     * 初始化视频播放器
     * 从 resources 目录加载视频（支持打包后的 JAR/EXE）
     */
    private void initializeVideoPlayer() {
        try {
            // 优先从 resources 加载（适用于 JAR/EXE）
            java.net.URL videoUrl = getClass().getResource("/vedio/ai.mp4");
            
            // 如果 resources 中没有，尝试外部文件（开发环境）
            if (videoUrl == null) {
                LogTools.getInstance().logRecord3("⚠️ resources/vedio/ai.mp4 不存在，尝试外部路径...");
                
                // 尝试外部路径
                String[] externalPaths = {
                    "vedio/ai.mp4",
                    "video/ai.mp4",
                    "runtime/video/ai.mp4"
                };
                
                for (String path : externalPaths) {
                    File f = new File(path);
                    if (f.exists()) {
                        videoUrl = f.toURI().toURL();
                        LogTools.getInstance().logRecord3("✅ 找到外部视频: " + f.getAbsolutePath());
                        break;
                    }
                }
            } else {
                LogTools.getInstance().logRecord3("✅ 找到 resources 视频: /vedio/ai.mp4");
            }
            
            if (videoUrl != null) {
                Media media = new Media(videoUrl.toString());
                mediaPlayer = new MediaPlayer(media);
                
                // 设置循环播放
                mediaPlayer.setCycleCount(MediaPlayer.INDEFINITE);
                
                // 静音播放
                mediaPlayer.setMute(true);
                
                // 设置自动播放
                mediaPlayer.setAutoPlay(true);
                
                // 绑定到 MediaView
                videoView.setMediaPlayer(mediaPlayer);
                
                // 视频加载完成后调整尺寸
                mediaPlayer.setOnReady(() -> {
                    adjustVideoForVerticalDisplay();
                });
                
                // 错误处理
                mediaPlayer.setOnError(() -> {
                    LogTools.getInstance().logRecord3("❌ 视频播放出错: " + mediaPlayer.getError().getMessage());
                });
                
                LogTools.getInstance().logRecord3("✅ 视频加载成功: " + videoUrl);
            } else {
                LogTools.getInstance().logRecord3("❌ 找不到视频文件 ai.mp4");
                LogTools.getInstance().logRecord3("   请将视频文件放到: src/main/resources/vedio/ai.mp4");
                // 隐藏视频视图
                if (videoView != null) {
                    videoView.setVisible(false);
                }
            }
        } catch (Exception e) {
            LogTools.getInstance().logRecord3("❌ 视频初始化失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 调整视频以适配竖屏显示
     * 视频铺满右侧区域 400x780
     */
    private void adjustVideoForVerticalDisplay() {
        if (mediaPlayer == null) return;
        
        double videoWidth = mediaPlayer.getMedia().getWidth();
        double videoHeight = mediaPlayer.getMedia().getHeight();
        
        LogTools.getInstance().logRecord3("📹 视频原始尺寸: " + videoWidth + "x" + videoHeight);
        LogTools.getInstance().logRecord3("✅ 视频已设置为铺满右侧 400x780");
    }
    
    /**
     * 切换到登录 Tab
     */
    private void switchToLoginTab() {
        updateTabStyles(tabLogin, tabRegister, tabSwitch);
        loginForm.setVisible(true);
        loginForm.setManaged(true);
        registerForm.setVisible(false);
        registerForm.setManaged(false);
        switchAccountForm.setVisible(false);
        switchAccountForm.setManaged(false);
    }
    
    /**
     * 切换到注册 Tab
     */
    private void switchToRegisterTab() {
        updateTabStyles(tabRegister, tabLogin, tabSwitch);
        loginForm.setVisible(false);
        loginForm.setManaged(false);
        registerForm.setVisible(true);
        registerForm.setManaged(true);
        switchAccountForm.setVisible(false);
        switchAccountForm.setManaged(false);
        
        // 切换到注册页面时，如果昵称为空则自动生成
        if (regNicknameField != null && (regNicknameField.getText() == null || regNicknameField.getText().isEmpty())) {
            generateAndSetNickname();
        }
    }
    
    /**
     * 切换到切换账号 Tab
     */
    private void switchToSwitchAccountTab() {
        updateTabStyles(tabSwitch, tabLogin, tabRegister);
        loginForm.setVisible(false);
        loginForm.setManaged(false);
        registerForm.setVisible(false);
        registerForm.setManaged(false);
        switchAccountForm.setVisible(true);
        switchAccountForm.setManaged(true);
    }
    
    /**
     * 更新 Tab 样式
     */
    private void updateTabStyles(Button activeTab, Button... inactiveTabs) {
        // 激活的 Tab - 选中时白色文字
        activeTab.setStyle(
            "-fx-background-color: transparent; " +
            "-fx-text-fill: white; " +
            "-fx-font-size: 14px; " +
            "-fx-font-weight: bold;" +
            "-fx-border-color: transparent transparent #607AFB transparent;" +
            "-fx-border-width: 0 0 3 0;" +
            "-fx-padding: 16 0 13 0;" +
            "-fx-cursor: hand;" +
            "-fx-background-insets: 0;" +
            "-fx-background-radius: 0;"
        );
        
        // 未激活的 Tabs
        for (Button tab : inactiveTabs) {
            tab.setStyle(
                "-fx-background-color: transparent; " +
                "-fx-text-fill: #94a3b8; " +
                "-fx-font-size: 14px; " +
                "-fx-font-weight: bold;" +
                "-fx-border-color: transparent;" +
                "-fx-border-width: 0 0 3 0;" +
                "-fx-padding: 16 0 13 0;" +
                "-fx-cursor: hand;" +
                "-fx-background-insets: 0;" +
                "-fx-background-radius: 0;"
            );
        }
    }
    
    /**
     * 切换密码可见性
     */
    private void togglePasswordVisibility() {
        LogTools.getInstance().logRecord3("🔐 切换密码可见性被调用");
        
        if (passwordField == null) {
            LogTools.getInstance().logRecord3("❌ passwordField 为 null，无法切换");
            return;
        }
        
        // 如果 passwordContainer 为 null，尝试从 passwordField 的父节点获取
        if (passwordContainer == null) {
            if (passwordField.getParent() instanceof HBox) {
                passwordContainer = (HBox) passwordField.getParent();
                LogTools.getInstance().logRecord3("🔐 从父节点获取 passwordContainer");
            } else if (visiblePasswordField != null && visiblePasswordField.getParent() instanceof HBox) {
                passwordContainer = (HBox) visiblePasswordField.getParent();
                LogTools.getInstance().logRecord3("🔐 从 visiblePasswordField 父节点获取 passwordContainer");
            } else {
                LogTools.getInstance().logRecord3("❌ 无法获取 passwordContainer");
                return;
            }
        }
        
        isPasswordVisible = !isPasswordVisible;
        LogTools.getInstance().logRecord3("🔐 isPasswordVisible: " + isPasswordVisible);
        
        if (isPasswordVisible) {
            // 显示密码 - 创建 TextField 显示明文
            if (visiblePasswordField == null) {
                visiblePasswordField = new TextField();
                visiblePasswordField.getStyleClass().addAll(passwordField.getStyleClass());
                visiblePasswordField.setStyle(passwordField.getStyle());
                visiblePasswordField.setPromptText(passwordField.getPromptText());
                visiblePasswordField.setPrefHeight(passwordField.getPrefHeight());
                HBox.setHgrow(visiblePasswordField, javafx.scene.layout.Priority.ALWAYS);
                
                // 回车键登录
                visiblePasswordField.setOnAction(e -> handleLogin());
                
                // 双向绑定文本
                visiblePasswordField.textProperty().bindBidirectional(passwordField.textProperty());
            }
            
            // 替换 PasswordField 为 TextField
            int index = passwordContainer.getChildren().indexOf(passwordField);
            if (index >= 0) {
                passwordContainer.getChildren().set(index, visiblePasswordField);
            }
            
            // 更新按钮图标为"隐藏"
            togglePasswordBtn.setText("👁️‍🗨️");  // 或使用其他图标
            
        } else {
            // 隐藏密码 - 恢复 PasswordField
            if (visiblePasswordField != null) {
                int index = passwordContainer.getChildren().indexOf(visiblePasswordField);
                if (index >= 0) {
                    passwordContainer.getChildren().set(index, passwordField);
                }
            }
            
            // 更新按钮图标为"显示"
            togglePasswordBtn.setText("👁");
        }
    }
    
    /**
     * 处理登录按钮点击事件
     */
    @FXML
    private void handleLogin() {
        // 设置选中的设备用户名到 ViewModel
        com.acard.acard.net.BindingDevice selectedDevice = deviceComboBox != null 
            ? deviceComboBox.getSelectionModel().getSelectedItem() 
            : null;
        if (selectedDevice != null) {
            viewModel.setSelectedDeviceUsername(selectedDevice.getDeviceUsername());
        } else {
            viewModel.setSelectedDeviceUsername(null);
        }
        
        viewModel.login(
            this::onLoginSuccess,
            this::onLoginFailure
        );
    }
    
    /**
     * 加载上次登录的账号（同时自动填充密码，如有密码则自动登录）
     */
    private void loadLastUsername() {
        try {
            // ⭐ 使用静态变量判断是否是切换账号模式
            boolean isFromSwitchAccount = com.acard.acard.tools.FileToos.isSwitchAccountMode;
            
            // 优先从 LocalStorage 获取（切换账号时设置的）
            String lastUsername = com.acard.acard.storage.LocalStorage.getInstance().getLastAccount();
            
            // 如果为空，尝试从 LoginPreferences 获取
            if (lastUsername == null || lastUsername.trim().isEmpty()) {
            com.acard.acard.storage.LoginPreferences prefs = 
                com.acard.acard.storage.LoginPreferences.getInstance();
                lastUsername = prefs.getLastUsername();
            }
            
            // ⭐ 获取切换时选中的设备（如果有）
            String switchDeviceUsername = com.acard.acard.storage.LocalStorage.getInstance().getString("switch_device_username", null);
            // 清除临时存储
            if (switchDeviceUsername != null && !switchDeviceUsername.isEmpty()) {
                com.acard.acard.storage.LocalStorage.getInstance().putString("switch_device_username", null);
                LogTools.getInstance().logRecord3("📱 切换账号时选中的设备: " + switchDeviceUsername);
            }
            
            if (lastUsername != null && !lastUsername.trim().isEmpty()) {
                final String username = lastUsername;
                final String targetDevice = switchDeviceUsername;
                usernameField.setText(username);
                LogTools.getInstance().logRecord3("✅ 已自动填充上次登录账号: " + username);
                
                // 尝试从账号列表获取保存的密码
                com.acard.acard.storage.AccountListStore.Account account = 
                    com.acard.acard.storage.AccountListStore.getInstance().getAccount(username);
                
                // ⭐ 只有从主页切换账号进来的，才自动填充密码并登录
                if (isFromSwitchAccount) {
                    if (account != null && account.getPassword() != null && !account.getPassword().isEmpty()) {
                        // ⭐⭐ 关键：在设置密码之前就设置切换账号标记，防止焦点变化触发刷新
                        if (targetDevice != null && !targetDevice.isEmpty()) {
                            this.pendingDeviceUsername = targetDevice;
                            this.isSwitchAccountLoading = true;
                            LogTools.getInstance().logRecord3("📱 切换账号 - 预设待选设备: " + targetDevice);
                        }
                        
                        passwordField.setText(account.getPassword());
                        LogTools.getInstance().logRecord3("✅ 切换账号 - 已自动填充保存的密码");
                        
                        // 加载设备列表（直接远程获取）
                        Platform.runLater(() -> {
                            if (deviceStatusLabel != null) {
                                deviceStatusLabel.setText("正在获取设备列表...");
                                deviceStatusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #64748b;");
                            }
                            fetchDeviceListFromRemote(username, false);
                        });
                        
                        // 清除切换账号标记
                        com.acard.acard.tools.FileToos.isSwitchAccountMode = false;
                        
                        // 延迟执行自动登录（等待UI和设备列表初始化完成）
                        new Thread(() -> {
                            try {
                                Thread.sleep(800); // ⭐ 增加等待时间，确保远程数据返回
                            } catch (InterruptedException ignore) {}
                            Platform.runLater(() -> {
                                LogTools.getInstance().logRecord3("🚀 切换账号 - 自动执行登录");
                                handleLogin();
                            });
                        }).start();
                    }
                } else {
                    // ⭐ 普通打开登录界面，不自动填充密码，只填充账号
                    LogTools.getInstance().logRecord3("📝 普通登录 - 不自动填充密码，请手动输入");
                    if (deviceStatusLabel != null) {
                        Platform.runLater(() -> {
                            deviceStatusLabel.setText("请输入密码后点击刷新查询设备");
                            deviceStatusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #64748b;");
                        });
                    }
                }
            }
        } catch (Exception e) {
            LogTools.getInstance().logRecord3("⚠️ 加载上次登录账号失败: " + e.getMessage());
        }
    }
    
    /**
     * 加载设备列表并自动选中指定设备
     * ⭐ 切换账号模式：直接远程获取，不走本地缓存
     */
    private void loadDeviceListAndSelect(String username, String targetDeviceUsername) {
        // ⭐ 保存待选择的设备（会在远程数据到达后使用）
        if (targetDeviceUsername != null && !targetDeviceUsername.isEmpty()) {
            this.pendingDeviceUsername = targetDeviceUsername;
            this.isSwitchAccountLoading = true;  // ⭐ 标记切换账号模式
            LogTools.getInstance().logRecord3("📱 切换账号模式 - 待选设备: " + targetDeviceUsername);
        }
        
        // ⭐ 切换账号时直接远程获取，不走本地缓存
        String password = passwordField.getText();
        if (password != null && !password.isEmpty()) {
            if (deviceStatusLabel != null) {
                deviceStatusLabel.setText("正在获取设备列表...");
                deviceStatusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #64748b;");
            }
            // 直接远程获取，跳过本地缓存
            fetchDeviceListFromRemote(username, false);
        } else {
            // 没有密码，走普通流程
            refreshDeviceList();
        }
    }
    
    /**
     * 登录成功回调
     */
    private void onLoginSuccess() {
        Platform.runLater(() -> {
            try {
                // 保存本次登录的账号
                saveLoginUsername();
                
                // 更新选中的设备信息到 LoginResponse
                com.acard.acard.net.LoginResponse loginResp = 
                    com.acard.acard.storage.AuthStore.getInstance().getLoginResponse();
                com.acard.acard.net.BindingDevice selectedDevice = deviceComboBox != null 
                    ? deviceComboBox.getSelectionModel().getSelectedItem() 
                    : null;
                    
                if (loginResp != null && selectedDevice != null) {
                    loginResp.setCurrentDeviceId(selectedDevice.getDeviceId());
                    loginResp.setCurrentDeviceUsername(selectedDevice.getDeviceUsername());
                    // 更新缓存
                    com.acard.acard.storage.AuthStore.getInstance().saveLoginResponse(loginResp);
                    LogTools.getInstance().logRecord3("📱 选择设备: " + selectedDevice.getDeviceNickname() + " (" + selectedDevice.getDeviceUsername() + ")");
                }
                
                // 登录成功后更新本地设备列表缓存
                String username = usernameField.getText();
                if (username != null && !username.isEmpty() && loginResp != null && loginResp.getBindingList() != null) {
                    com.acard.acard.storage.DeviceListStore.getInstance().saveDevices(username, loginResp.getBindingList());
                }
                
                // 停止视频播放
                if (mediaPlayer != null) {
                    mediaPlayer.stop();
                    mediaPlayer.dispose();
                }
                
                // 打开主界面
                openCameraMainInterface();
                
                // 关闭登录窗口
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
            String password = passwordField.getText();
            
            if (username != null && !username.trim().isEmpty()) {
                // 保存到旧的偏好设置
                com.acard.acard.storage.LoginPreferences prefs = 
                    com.acard.acard.storage.LoginPreferences.getInstance();
                prefs.saveLastUsername(username);
                
                // 保存到账号列表（带密码）
                if (password != null && !password.isEmpty()) {
                    com.acard.acard.storage.AccountListStore.getInstance()
                        .addOrUpdateAccount(username, password);
                }
                
                LogTools.getInstance().logRecord3("✅ 登录成功，已保存账号: " + username);
            }
        } catch (Exception e) {
            LogTools.getInstance().logRecord3("⚠️ 保存登录账号失败: " + e.getMessage());
        }
    }
    
    /**
     * 打开相机主界面
     */
    private void openCameraMainInterface() throws Exception {
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
        scene.setFill(javafx.scene.paint.Color.TRANSPARENT);
        
        // 配置舞台
        mainStage.setTitle("相机主界面系统");
        mainStage.setScene(scene);
        mainStage.initStyle(StageStyle.TRANSPARENT);
        
        // ⭐ 设置任务栏图标
        try {
            javafx.scene.image.Image icon = new javafx.scene.image.Image(getClass().getResourceAsStream("/images/icon.png"));
            mainStage.getIcons().add(icon);
        } catch (Exception e) {
            System.err.println("加载主窗口图标失败: " + e.getMessage());
        }
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
     * 登录失败回调
     */
    private void onLoginFailure() {
        Platform.runLater(() -> {
            // 获取错误码
            int errorCode = viewModel.getLastErrorCode();
            
            // ⭐ 错误码 1001: 账户不存在，清空账户和密码输入框，刷新切换账号列表
            if (errorCode == 1001) {
                LogTools.getInstance().logRecord3("⚠️ 账户不存在，清空输入框并刷新账号列表");
                // 清空账户输入框
                usernameField.clear();
                // 清空密码输入框
                passwordField.clear();
                // 清空设备列表
                if (deviceComboBox != null) {
                    deviceComboBox.getItems().clear();
                }
                if (deviceStatusLabel != null) {
                    deviceStatusLabel.setText("请输入账号密码");
                    deviceStatusLabel.setVisible(true);
                }
                // 刷新切换账号列表
                loadAccountList();
                // 焦点设置到账户输入框
                usernameField.requestFocus();
            } else {
                // 其他错误只清空密码
                passwordField.clear();
                passwordField.requestFocus();
            }
        });
    }
    
    /**
     * 生成6位数字昵称（基于时间纳秒）
     * @return 6位数字字符串
     */
    private String generateNickname() {
        // 使用纳秒时间生成唯一6位数字
        long nanoTime = System.nanoTime();
        // 取纳秒的后6位数字，确保唯一性
        String nanoStr = String.valueOf(Math.abs(nanoTime));
        if (nanoStr.length() >= 6) {
            return nanoStr.substring(nanoStr.length() - 6);
        } else {
            // 不足6位则补零
            return String.format("%06d", Math.abs(nanoTime % 1000000));
        }
    }
    
    /**
     * 生成并设置昵称到输入框
     */
    private void generateAndSetNickname() {
        if (regNicknameField != null) {
            String nickname = generateNickname();
            regNicknameField.setText(nickname);
            LogTools.getInstance().logRecord3("🎲 生成新昵称: " + nickname);
        }
    }
    
    /**
     * 处理注册按钮点击事件
     */
    @FXML
    private void handleRegister() {
        // 清除之前的错误信息
        registerErrorLabel.setText("");
        registerErrorLabel.setVisible(false);
        
        // 获取输入值
        String username = regUsernameField.getText().trim();
        String password = regPasswordField.getText();
        String nickname = regNicknameField != null ? regNicknameField.getText().trim() : "";
        
        // 验证输入
        if (username.isEmpty() || password.isEmpty()) {
            showRegisterError("请填写所有字段");
            return;
        }
        
        // 验证昵称（6位数字）
        if (nickname.isEmpty() || !nickname.matches("^\\d{6}$")) {
            // 如果昵称无效，自动重新生成
            generateAndSetNickname();
            nickname = regNicknameField.getText().trim();
        }
        
        // 验证用户名格式：8-15位字母或数字
        if (!username.matches("^[a-zA-Z0-9]{8,15}$")) {
            showRegisterError("账号必须是8-15位字母或数字");
            return;
        }
        
        // 验证密码长度：6-20位
        if (password.length() < 6 || password.length() > 20) {
            showRegisterError("密码长度必须在6到20位之间");
            return;
        }
        

        
        // 显示进度指示器
        registerProgressIndicator.setVisible(true);
        registerButton.setDisable(true);
        
        // 调用注册接口
        com.acard.acard.net.NetworkManager network = com.acard.acard.net.NetworkManager.getInstance();
        network.setBaseUrl(com.acard.acard.net.NetworkConfig.getInstance().getBaseUrl());
        
        final String finalNickname = nickname;
        network.registerControl(username, password, nickname)
            .thenAccept(response -> {
                Platform.runLater(() -> {
                    registerProgressIndicator.setVisible(false);
                    registerButton.setDisable(false);
                    


                    // 调试日志：查看响应状态
                    LogTools.getInstance().logRecord3("📡 注册响应 - isSuccess: " + response.isSuccess());
                    LogTools.getInstance().logRecord3("📡 注册响应 - getData: " + (response.getData() != null ? "有数据" : "null"));
                    
                    if (response.getCode() == 200 && response.getData() != null) {
                        com.acard.acard.net.RegisterResponse data = response.getData();
                        
                        // 调试日志：查看返回数据
                        LogTools.getInstance().logRecord3("📦 返回数据 - userId: " + data.getUserId());
                        LogTools.getInstance().logRecord3("📦 返回数据 - username: " + data.getUsername());
                        LogTools.getInstance().logRecord3("📦 返回数据 - userType: " + data.getUserType());
                        LogTools.getInstance().logRecord3("📦 返回数据 - message: " + data.getMessage());
                        LogTools.getInstance().logRecord3("📦 返回数据 - error: " + data.getError());
                        
                        // HTTP 200 就认为注册成功，检查是否有错误字段
                        boolean hasError = data.getError() != null && !data.getError().isEmpty();
                        
                        if (!hasError) {
                            // ✅ 注册成功（HTTP 200 且无 error 字段）
                            LogTools.getInstance().logRecord3("✅ 注册成功: " + username);
                            
                            try {
                                // 1. 保存到账号列表
                                LogTools.getInstance().logRecord3("📝 步骤1: 保存账号到列表...");
                                com.acard.acard.storage.AccountListStore.getInstance()
                                    .addOrUpdateAccount(username, password);
                                LogTools.getInstance().logRecord3("✓ 步骤1完成");
                                
                                // 2. 清空注册表单
                                LogTools.getInstance().logRecord3("📝 步骤2: 清空注册表单...");
                                regUsernameField.clear();
                                regPasswordField.clear();
                                if (regNicknameField != null) {
                                    regNicknameField.clear();
                                }
                                registerErrorLabel.setVisible(false);
                                LogTools.getInstance().logRecord3("✓ 步骤2完成");
                                
                                // 3. 自动填充到登录表单
                                LogTools.getInstance().logRecord3("📝 步骤3: 填充登录表单...");
                                usernameField.setText(username);
                                passwordField.setText(password);
                                LogTools.getInstance().logRecord3("✓ 步骤3完成");
                                
                                // 4. 切换到登录页面
                                LogTools.getInstance().logRecord3("📝 步骤4: 切换到登录页面...");
                                switchToLoginTab();
                                LogTools.getInstance().logRecord3("✓ 步骤4完成");
                                
                                // 5. 显示成功提示（非阻塞）
                                LogTools.getInstance().logRecord3("📝 步骤5: 显示成功提示...");
                                showSuccessMessage("注册成功！账号密码已自动填入，点击登录即可");
                                LogTools.getInstance().logRecord3("✓ 步骤5完成");
                                
                                // 6. 让登录按钮获得焦点
                                LogTools.getInstance().logRecord3("📝 步骤6: 设置登录按钮焦点...");
                                Platform.runLater(() -> {
                                    loginButton.requestFocus();
                                    LogTools.getInstance().logRecord3("✓ 步骤6完成");
                                });
                                
                                LogTools.getInstance().logRecord3("🎉 注册流程全部完成！");
                                
                            } catch (Exception e) {
                                LogTools.getInstance().logRecord3("❌ 注册成功后处理异常: " + e.getMessage());
                                e.printStackTrace();
                            }
                            
                        } else {
                            // ❌ HTTP 200 但有 error 字段（业务失败）
                            String errorMsg = data.getError();
                            showRegisterError(errorMsg);
                            LogTools.getInstance().logRecord3("❌ 注册业务失败: " + errorMsg);
                        }
                    } else {
                        // ❌ 请求失败
                        String errorMsg = response.getMessage() != null && !response.getMessage().isEmpty()
                            ? response.getMessage()
                            : "注册失败，请检查网络连接";
                        showRegisterError(errorMsg);
                        LogTools.getInstance().logRecord3("❌ 注册请求失败: " + errorMsg);
                    }
                });
            })
            .exceptionally(ex -> {
                Platform.runLater(() -> {
                    registerProgressIndicator.setVisible(false);
                    registerButton.setDisable(false);
                    
                    // 网络异常处理
                    String errorMsg = "网络错误：";
                    if (ex.getMessage() != null) {
                        if (ex.getMessage().contains("Connection refused")) {
                            errorMsg += "无法连接到服务器，请检查网络或服务器地址";
                        } else if (ex.getMessage().contains("timeout")) {
                            errorMsg += "连接超时，请检查网络";
                        } else {
                            errorMsg += ex.getMessage();
                        }
                    } else {
                        errorMsg += "请检查网络连接";
                    }
                    
                    showRegisterError(errorMsg);
                    LogTools.getInstance().logRecord3("❌ 注册网络异常: " + ex.getMessage());
                });
                return null;
            });
    }
    
    /**
     * 显示注册错误信息
     */
    private void showRegisterError(String message) {
        registerErrorLabel.setText(message);
        registerErrorLabel.setVisible(true);
    }
    
    /**
     * 显示成功消息（在登录页面）
     */
    private void showSuccessMessage(String message) {
        errorLabel.setText("✅ " + message);
        // 绿色主题的成功样式
        errorLabel.setStyle(
            "-fx-text-fill: #10b981; " +
            "-fx-font-size: 14px; " +
            "-fx-font-weight: 500; " +
            "-fx-padding: 8 12; " +
            "-fx-background-color: rgba(16, 185, 129, 0.1); " +
            "-fx-background-radius: 8; " +
            "-fx-border-color: rgba(16, 185, 129, 0.3); " +
            "-fx-border-width: 1; " +
            "-fx-border-radius: 8;"
        );
        errorLabel.setVisible(true);
        
        // 5秒后自动隐藏
        new Thread(() -> {
            try {
                Thread.sleep(5000);
                Platform.runLater(() -> {
                    errorLabel.setVisible(false);
                    // 恢复红色错误样式
                    errorLabel.setStyle(
                        "-fx-text-fill: #ef4444; " +
                        "-fx-font-size: 14px; " +
                        "-fx-font-weight: 500; " +
                        "-fx-padding: 8 12; " +
                        "-fx-background-color: rgba(239, 68, 68, 0.1); " +
                        "-fx-background-radius: 8; " +
                        "-fx-border-color: rgba(239, 68, 68, 0.3); " +
                        "-fx-border-width: 1; " +
                        "-fx-border-radius: 8;"
                    );
                });
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }).start();
    }
    
    /**
     * 加载账号列表（带二级设备列表）
     */
    private void loadAccountList() {
        // 获取所有账号
        java.util.List<com.acard.acard.storage.AccountListStore.Account> accounts = 
            com.acard.acard.storage.AccountListStore.getInstance().getAllAccounts();
        
        if (accounts.isEmpty()) {
            if (switchAccountHintLabel != null) {
                switchAccountHintLabel.setText("还没有保存的账号，请先登录");
            }
            return;
        }
        
        // 更新提示信息
        if (switchAccountHintLabel != null) {
            switchAccountHintLabel.setText("正在加载设备信息...");
        }
        
        // 批量查询所有账号的设备绑定状态
        java.util.List<String> usernames = new java.util.ArrayList<>();
        for (com.acard.acard.storage.AccountListStore.Account account : accounts) {
            usernames.add(account.getUsername());
        }
        
        // 先用本地数据渲染列表
        renderAccountListUI(accounts, null);
        
        // 然后查询远程获取最新设备状态
        fetchAccountsOnlineStatus(usernames);
        
        // 设置刷新按钮事件
        if (refreshAccountsBtn != null) {
            refreshAccountsBtn.setOnAction(e -> {
                if (switchAccountHintLabel != null) {
                    switchAccountHintLabel.setText("正在刷新...");
                }
                fetchAccountsOnlineStatus(usernames);
            });
        }
        
        // 隐藏登录按钮，改用双击切换
        if (switchLoginButton != null) {
            switchLoginButton.setVisible(false);
            switchLoginButton.setManaged(false);
        }
    }
    
    /**
     * 批量查询账号的在线状态
     */
    private void fetchAccountsOnlineStatus(java.util.List<String> usernames) {
        LogTools.getInstance().logRecord3("📡 开始查询账号设备状态，账号数: " + usernames.size());
        
        com.acard.acard.net.NetworkManager.getInstance()
            .getOnlineStatus(usernames)
            .thenAccept(resp -> {
                Platform.runLater(() -> {
                    if (resp.isSuccess() && resp.getData() != null && resp.getData().getList() != null) {
                        java.util.List<com.acard.acard.net.OnlineStatusResponse.OnlineStatusItem> list = resp.getData().getList();
                        LogTools.getInstance().logRecord3("✅ 获取设备状态成功，总记录数: " + list.size());
                        
                        // 按控制端账号分组
                        accountDeviceMap.clear();
                        for (com.acard.acard.net.OnlineStatusResponse.OnlineStatusItem item : list) {
                            String controlUsername = item.getControlUsername();
                            if (!accountDeviceMap.containsKey(controlUsername)) {
                                accountDeviceMap.put(controlUsername, new java.util.ArrayList<>());
                            }
                            accountDeviceMap.get(controlUsername).add(item);
                        }
                        
                        // 打印每个账号的设备列表
                        for (java.util.Map.Entry<String, java.util.List<com.acard.acard.net.OnlineStatusResponse.OnlineStatusItem>> entry : accountDeviceMap.entrySet()) {
                            String controlUser = entry.getKey();
                            java.util.List<com.acard.acard.net.OnlineStatusResponse.OnlineStatusItem> devices = entry.getValue();
                            
                            if (devices.size() == 1) {
                                // 单个设备（使用 getDisplayText：昵称 或 昵称(备注)）
                                com.acard.acard.net.OnlineStatusResponse.OnlineStatusItem device = devices.get(0);
                                LogTools.getInstance().logRecord3("📱 [" + controlUser + "] 单个设备: " + 
                                    device.getDisplayText() + 
                                    " (在线=" + device.isOnline() + ", 已绑定=" + device.isBound() + ")");
                            } else {
                                // 多个设备
                                StringBuilder sb = new StringBuilder();
                                sb.append("📱 [").append(controlUser).append("] 多个设备(").append(devices.size()).append("): ");
                                for (int i = 0; i < devices.size(); i++) {
                                    com.acard.acard.net.OnlineStatusResponse.OnlineStatusItem device = devices.get(i);
                                    if (i > 0) sb.append(", ");
                                    sb.append(device.getDisplayText());
                                    sb.append("[").append(device.isOnline() ? "在线" : "离线").append("]");
                                }
                                LogTools.getInstance().logRecord3(sb.toString());
                            }
                        }
                        
                        // 重新渲染列表
                        java.util.List<com.acard.acard.storage.AccountListStore.Account> accounts = 
                            com.acard.acard.storage.AccountListStore.getInstance().getAllAccounts();
                        renderAccountListUI(accounts, accountDeviceMap);
                        
                        if (switchAccountHintLabel != null) {
                            switchAccountHintLabel.setText("点击设备即可登录");
                        }
                    } else {
                        LogTools.getInstance().logRecord3("⚠️ 获取设备状态失败: " + (resp.getMessage() != null ? resp.getMessage() : "未知错误"));
                        if (switchAccountHintLabel != null) {
                            switchAccountHintLabel.setText("获取设备状态失败，使用本地数据");
                        }
                    }
                });
            })
            .exceptionally(ex -> {
                LogTools.getInstance().logRecord3("❌ 查询设备状态网络错误: " + ex.getMessage());
                Platform.runLater(() -> {
                    if (switchAccountHintLabel != null) {
                        switchAccountHintLabel.setText("网络错误，使用本地数据");
                    }
                });
                return null;
            });
    }
    
    /**
     * 渲染账号列表UI（二级结构）
     */
    private void renderAccountListUI(
            java.util.List<com.acard.acard.storage.AccountListStore.Account> accounts,
            java.util.Map<String, java.util.List<com.acard.acard.net.OnlineStatusResponse.OnlineStatusItem>> deviceMap) {
        
        if (accountListContainer == null) return;
        
        Platform.runLater(() -> {
            // ⭐ 保存当前选中的账号和设备，重新渲染后恢复
            String prevSelectedControl = this.selectedControlUsername;
            String prevSelectedDevice = this.selectedDeviceUsername;
            
            accountListContainer.getChildren().clear();
            
            for (com.acard.acard.storage.AccountListStore.Account account : accounts) {
                String username = account.getUsername();
                java.util.List<com.acard.acard.net.OnlineStatusResponse.OnlineStatusItem> devices = 
                    deviceMap != null ? deviceMap.get(username) : null;
                
                // ⭐ 判断是否是之前选中的账号，恢复其设备选择
                String defaultDeviceForCard = null;
                if (username.equals(prevSelectedControl) && prevSelectedDevice != null) {
                    defaultDeviceForCard = prevSelectedDevice;
                }
                
                // 创建账号卡片（可展开/折叠）
                VBox accountCard = createAccountCard(account, devices, defaultDeviceForCard);
                accountListContainer.getChildren().add(accountCard);
            }
            
            // 更新提示：点击设备即可登录
            if (switchAccountHintLabel != null && !accounts.isEmpty()) {
                switchAccountHintLabel.setText("点击设备即可登录");
            }
        });
    }
    
    /**
     * 创建账号卡片（包含设备二级列表）
     * @param defaultDeviceUsername 默认选中的设备（用于恢复之前的选择）
     */
    private VBox createAccountCard(
            com.acard.acard.storage.AccountListStore.Account account,
            java.util.List<com.acard.acard.net.OnlineStatusResponse.OnlineStatusItem> devices,
            String defaultDeviceUsername) {
        
        String username = account.getUsername();
        VBox card = new VBox(0);
        card.setStyle("-fx-background-color: #292929; -fx-background-radius: 12;");
        
        // ========== 一级：控制端账号头部 ==========
        javafx.scene.layout.HBox header = new javafx.scene.layout.HBox(12);
        header.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        header.setStyle("-fx-padding: 14 16; -fx-cursor: hand;");
        
        // 头像
        javafx.scene.layout.StackPane avatar = new javafx.scene.layout.StackPane();
        avatar.setPrefSize(44, 44);
        avatar.setMinSize(44, 44);
        avatar.setMaxSize(44, 44);
        avatar.setStyle("-fx-background-color: linear-gradient(to bottom right, #607AFB, #8B5CF6); -fx-background-radius: 22;");
        Label avatarLabel = new Label(username.substring(0, 1).toUpperCase());
        avatarLabel.setStyle("-fx-text-fill: white; -fx-font-size: 18px; -fx-font-weight: bold;");
        avatar.getChildren().add(avatarLabel);
        
        // 文字区域
        VBox textBox = new VBox(2);
        javafx.scene.layout.HBox.setHgrow(textBox, javafx.scene.layout.Priority.ALWAYS);
        
        Label usernameLabel = new Label(username);
        usernameLabel.setStyle("-fx-text-fill: white; -fx-font-size: 15px; -fx-font-weight: 600;");
        
        // 设备数量提示
        int deviceCount = devices != null ? devices.size() : 0;
        long onlineCount = devices != null ? devices.stream().filter(d -> d.isOnline()).count() : 0;
        String hint = deviceCount > 0 
            ? deviceCount + " 个设备，" + onlineCount + " 个在线" 
            : "未绑定设备";
        Label hintLabel = new Label(hint);
        hintLabel.setStyle("-fx-text-fill: #64748b; -fx-font-size: 12px;");
        
        textBox.getChildren().addAll(usernameLabel, hintLabel);
        
        // 展开/折叠箭头
        Label arrowLabel = new Label(deviceCount > 0 ? "▼" : "→");
        arrowLabel.setStyle("-fx-text-fill: #64748b; -fx-font-size: 12px;");
        
        // 选中指示器
        javafx.scene.shape.Circle selectIndicator = new javafx.scene.shape.Circle(6);
        selectIndicator.setFill(javafx.scene.paint.Color.TRANSPARENT);
        selectIndicator.setStroke(javafx.scene.paint.Color.web("#64748b"));
        selectIndicator.setStrokeWidth(1.5);
        
        header.getChildren().addAll(selectIndicator, avatar, textBox, arrowLabel);
        card.getChildren().add(header);
        
        // ========== 二级：设备列表（默认折叠） ==========
        VBox deviceListBox = new VBox(4);
        deviceListBox.setStyle("-fx-padding: 0 16 12 76;"); // 左边缩进
        deviceListBox.setVisible(false);
        deviceListBox.setManaged(false);
        
        // 用于记录当前选中的设备
        final String[] selectedDeviceInCard = {null};
        
        if (devices != null && !devices.isEmpty()) {
            // ⭐ 优先使用传入的默认设备，否则选中第一个
            final String checkDevice = defaultDeviceUsername;
            String deviceToSelect;
            if (checkDevice != null && devices.stream().anyMatch(d -> checkDevice.equals(d.getDeviceUsername()))) {
                deviceToSelect = checkDevice;
            } else {
                deviceToSelect = devices.get(0).getDeviceUsername();
            }
            selectedDeviceInCard[0] = deviceToSelect;
            
            for (com.acard.acard.net.OnlineStatusResponse.OnlineStatusItem device : devices) {
                javafx.scene.layout.HBox deviceRow = createDeviceRow(device, selectedDeviceInCard, card);
                deviceListBox.getChildren().add(deviceRow);
            }
        }
        
        card.getChildren().add(deviceListBox);
        
        // ========== 交互逻辑 ==========
        // 点击头部：展开/折叠设备列表
        header.setOnMouseClicked(e -> {
            if (deviceCount > 0) {
                boolean isExpanded = deviceListBox.isVisible();
                deviceListBox.setVisible(!isExpanded);
                deviceListBox.setManaged(!isExpanded);
                arrowLabel.setText(isExpanded ? "▼" : "▲");
            }
            
            // 选中此账号
            selectAccount(username, selectedDeviceInCard[0], selectIndicator, card);
        });
        
        // 悬停效果（统一圆角）
        header.setOnMouseEntered(e -> header.setStyle("-fx-padding: 14 16; -fx-cursor: hand; -fx-background-color: #3a3a3a; -fx-background-radius: 12;"));
        header.setOnMouseExited(e -> header.setStyle("-fx-padding: 14 16; -fx-cursor: hand;"));
        
        // 保存选中设备的引用
        card.setUserData(new Object[]{username, selectedDeviceInCard, selectIndicator});
        
        return card;
    }
    
    /**
     * 创建设备行
     */
    private javafx.scene.layout.HBox createDeviceRow(
            com.acard.acard.net.OnlineStatusResponse.OnlineStatusItem device,
            String[] selectedDeviceInCard,
            VBox parentCard) {
        
        javafx.scene.layout.HBox row = new javafx.scene.layout.HBox(8);
        row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        row.setStyle("-fx-padding: 16 12; -fx-background-color: #1F1F1F; -fx-background-radius: 8; -fx-cursor: hand;");
        
        // 选中指示器（单选）
        javafx.scene.shape.Circle radioIndicator = new javafx.scene.shape.Circle(5);
        boolean isSelected = device.getDeviceUsername() != null && device.getDeviceUsername().equals(selectedDeviceInCard[0]);
        radioIndicator.setFill(isSelected ? javafx.scene.paint.Color.web("#607AFB") : javafx.scene.paint.Color.TRANSPARENT);
        radioIndicator.setStroke(javafx.scene.paint.Color.web(isSelected ? "#607AFB" : "#64748b"));
        radioIndicator.setStrokeWidth(1.5);
        
        // 在线状态点
        javafx.scene.shape.Circle statusDot = new javafx.scene.shape.Circle(4);
        statusDot.setFill(device.isOnline() 
            ? javafx.scene.paint.Color.web("#34C759") 
            : javafx.scene.paint.Color.web("#666666"));
        
        // 设备名称（使用 getDisplayText：昵称 或 昵称(备注)）
        String displayName = device.getDisplayText();
        
        Label nameLabel = new Label(displayName);
        nameLabel.setStyle("-fx-text-fill: white; -fx-font-size: 13px;");
        javafx.scene.layout.HBox.setHgrow(nameLabel, javafx.scene.layout.Priority.ALWAYS);
        
        // 状态文字
        Label statusLabel = new Label(device.isOnline() ? "在线" : "离线");
        statusLabel.setStyle("-fx-text-fill: " + (device.isOnline() ? "#34C759" : "#666666") + "; -fx-font-size: 12px;");
        
        row.getChildren().addAll(radioIndicator, statusDot, nameLabel, statusLabel);
        
        // 点击选中此设备，双击直接切换登录
        row.setOnMouseClicked(e -> {
            e.consume(); // 阻止事件冒泡
            
            // 更新选中状态
            selectedDeviceInCard[0] = device.getDeviceUsername();
            
            // 更新UI（需要遍历同级别的所有设备行）
            VBox deviceListBox = (VBox) row.getParent();
            for (javafx.scene.Node node : deviceListBox.getChildren()) {
                if (node instanceof javafx.scene.layout.HBox) {
                    javafx.scene.layout.HBox deviceRow = (javafx.scene.layout.HBox) node;
                    javafx.scene.shape.Circle indicator = (javafx.scene.shape.Circle) deviceRow.getChildren().get(0);
                    // 检查是否是当前选中的设备
                    Label label = (Label) deviceRow.getChildren().get(2);
                    boolean isThisSelected = device.getDeviceUsername() != null && device.getDeviceUsername().equals(label.getText());
                    indicator.setFill(isThisSelected ? javafx.scene.paint.Color.web("#607AFB") : javafx.scene.paint.Color.TRANSPARENT);
                    indicator.setStroke(javafx.scene.paint.Color.web(isThisSelected ? "#607AFB" : "#64748b"));
                }
            }
            
            // 同时选中此账号
            Object[] userData = (Object[]) parentCard.getUserData();
            if (userData != null) {
                String controlUsername = (String) userData[0];
                javafx.scene.shape.Circle selectIndicator = (javafx.scene.shape.Circle) userData[2];
                selectAccount(controlUsername, device.getDeviceUsername(), selectIndicator, parentCard);
            }
            
            // ⭐ 单击直接执行登录（不需要切换到登录表单）
            handleSwitchAccountLogin();
        });
        
        // 悬停效果
        row.setOnMouseEntered(e -> row.setStyle("-fx-padding: 16 12; -fx-background-color: #3a3a3a; -fx-background-radius: 8; -fx-cursor: hand;"));
        row.setOnMouseExited(e -> row.setStyle("-fx-padding: 16 12; -fx-background-color: #1F1F1F; -fx-background-radius: 8; -fx-cursor: hand;"));
        
        return row;
    }
    
    /**
     * 选中账号
     */
    private void selectAccount(String controlUsername, String deviceUsername, 
            javafx.scene.shape.Circle indicator, VBox card) {
        // 清除其他账号的选中状态
        if (accountListContainer != null) {
            for (javafx.scene.Node node : accountListContainer.getChildren()) {
                if (node instanceof VBox) {
                    VBox otherCard = (VBox) node;
                    Object[] userData = (Object[]) otherCard.getUserData();
                    if (userData != null) {
                        javafx.scene.shape.Circle otherIndicator = (javafx.scene.shape.Circle) userData[2];
                        otherIndicator.setFill(javafx.scene.paint.Color.TRANSPARENT);
                        otherIndicator.setStroke(javafx.scene.paint.Color.web("#64748b"));
                    }
                }
            }
        }
        
        // 设置当前选中
        selectedControlUsername = controlUsername;
        selectedDeviceUsername = deviceUsername;
        indicator.setFill(javafx.scene.paint.Color.web("#607AFB"));
        indicator.setStroke(javafx.scene.paint.Color.web("#607AFB"));
        
        LogTools.getInstance().logRecord3("✅ 选中账号: " + controlUsername + ", 设备: " + deviceUsername);
    }
    
    /**
     * 处理切换账号登录
     * ⭐ 直接执行登录，不再切换到登录表单
     */
    private void handleSwitchAccountLogin() {
        if (selectedControlUsername == null || selectedControlUsername.isEmpty()) {
            if (switchAccountHintLabel != null) {
                switchAccountHintLabel.setText("请先选择一个账号");
                switchAccountHintLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #f59e0b;");
            }
            return;
        }
        
        // 从存储中获取账号信息
        com.acard.acard.storage.AccountListStore.Account account = 
            com.acard.acard.storage.AccountListStore.getInstance().getAccount(selectedControlUsername);
        
        if (account == null || account.getPassword() == null || account.getPassword().isEmpty()) {
            if (switchAccountHintLabel != null) {
                switchAccountHintLabel.setText("账号密码未保存，请手动登录");
                switchAccountHintLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #f59e0b;");
            }
            // 切换到登录表单让用户手动输入密码
            usernameField.setText(selectedControlUsername);
            switchToLoginTab();
            return;
        }
        
        // ⭐ 直接执行登录流程
        if (switchAccountHintLabel != null) {
            switchAccountHintLabel.setText("正在登录...");
            switchAccountHintLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #64748b;");
        }
        
        // 设置登录信息（后台使用，不需要切换到登录表单）
        usernameField.setText(account.getUsername());
        passwordField.setText(account.getPassword());
        
        // 设置选中的设备
        if (selectedDeviceUsername != null) {
            viewModel.setSelectedDeviceUsername(selectedDeviceUsername);
        }
        
        LogTools.getInstance().logRecord3("🚀 直接登录: " + selectedControlUsername + ", 设备: " + selectedDeviceUsername);
        
        // ⭐ 直接调用登录
        viewModel.login(
            this::onLoginSuccess,
            () -> {
                // 登录失败，切换到登录表单让用户重试
                Platform.runLater(() -> {
                    if (switchAccountHintLabel != null) {
                        switchAccountHintLabel.setText("登录失败，请重试");
                        switchAccountHintLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #ef4444;");
                    }
                    switchToLoginTab();
                });
            }
        );
    }
    
    /**
     * 处理账号选择事件（旧版，保留兼容）
     */
    private void handleAccountSelection() {
        if (accountListView == null) return;
        
        String selectedUsername = accountListView.getSelectionModel().getSelectedItem();
        if (selectedUsername == null || selectedUsername.isEmpty()) {
            return;
        }
        
        // 从存储中获取账号信息
        com.acard.acard.storage.AccountListStore.Account account = 
            com.acard.acard.storage.AccountListStore.getInstance().getAccount(selectedUsername);
        
        if (account != null) {
            // 自动填充到登录表单
            usernameField.setText(account.getUsername());
            passwordField.setText(account.getPassword());
            
            // 切换到登录页面
            switchToLoginTab();
            
            // 焦点到登录按钮
            Platform.runLater(() -> loginButton.requestFocus());
            
            LogTools.getInstance().logRecord3("✅ 已选择账号: " + selectedUsername);
        }
    }
    
    /**
     * 清理资源
     */
    public void cleanup() {
        if (mediaPlayer != null) {
            mediaPlayer.stop();
            mediaPlayer.dispose();
        }
    }
}

