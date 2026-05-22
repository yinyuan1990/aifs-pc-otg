import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import Aifs.Components 1.0

// 登录/注册卡片
Rectangle {
    id: loginPage
    color: "#1e1e1e"  // Cursor 编辑器背景色
    radius: 2
    border.color: "#3c3c3c"
    border.width: 1
    clip: true
    
    signal loginSuccess(string server)
    
    // 当前视图：login / register
    property string currentView: "login"
    property bool isLoggingIn: false
    property int loggingInLevel: 0  // 正在登录的等级：0=无, 1=豪华版, 2=至尊版
    property string loginError: ""
    property string pendingLoginUsername: ""  // 注册成功后待填充的用户名
    property bool toastSwitchToLogin: false   // Toast 消失后是否切换到登录页
    
    // 生成唯一用户名（V开头，共9位）
    function generateUsername() {
        var now = new Date()
        // 格式：V + 月日时分秒 = 1 + 8 = 9位（基于时间保证唯一）
        var timeStr = (now.getMonth() + 1).toString().padStart(2, '0') +
                      now.getDate().toString().padStart(2, '0') +
                      now.getHours().toString().padStart(2, '0') +
                      now.getMinutes().toString().padStart(2, '0')
        return "V" + timeStr  // 总共 1 + 8 = 9 位，例如：V01091430
    }
    
    // 生成6位数字昵称（不显示，只用于注册）
    function generateNickname() {
        var nanoTime = Date.now() * 1000 + Math.floor(Math.random() * 1000)
        var nanoStr = nanoTime.toString()
        if (nanoStr.length >= 6) {
            return nanoStr.slice(-6)
        } else {
            return nanoStr.padStart(6, '0')
        }
    }
    
    // 全局点击监听（用于关闭下拉列表）
    MouseArea {
        anchors.fill: parent
        z: -1  // 放在最底层
        onClicked: {
            // 关闭账号下拉列表
            usernameColumn.dropdownVisible = false
            // 关闭监控账号下拉列表
            accountDropdown.visible = false
        }
    }
    
    // 监听 HttpClient 登录结果
    Connections {
        target: HttpClient
        
        function onLoginSuccess(token, deviceId, deviceUsername, bindingList, pcActivationLevel, pcLevelName, pcExpireAt, deviceLevel, levelFps, levelExposureFps, iceServersFromLogin) {
            console.log("登录成功! DeviceId:", deviceId, "DeviceUsername:", deviceUsername, "PcLevel:", pcActivationLevel, "PcLevelName:", pcLevelName, "PcExpireAt:", pcExpireAt, "DeviceLevel:", deviceLevel, "LevelFps:", JSON.stringify(levelFps), "LevelExposureFps:", JSON.stringify(levelExposureFps), "IceServers:", iceServersFromLogin ? iceServersFromLogin.length : 0)
            isLoggingIn = false
            loggingInLevel = 0
            loginError = ""
            
            // 保存账号密码和设备信息
            HttpClient.saveAccount(
                loginUsername.text.trim(), 
                loginPassword.text.trim(),
                monitorAccountColumn.selectedDeviceUsername,
                monitorAccountColumn.selectedAccount
            )
            
            // 刷新历史账号列表
            refreshSavedAccounts()
            
            // 触发登录成功信号（跳转主页）
            loginPage.loginSuccess(HttpClient.baseUrl())
        }
        
        // 获取绑定设备列表成功
        function onBindingDevicesReceived(devices) {
            console.log("获取设备列表成功:", devices.length)
            isLoggingIn = false
            
            // 更新设备列表
            var deviceList = []
            var displayList = []
            for (var i = 0; i < devices.length; i++) {
                var device = devices[i]
                var devUsername = device.deviceUsername || ""
                var devNickname = device.deviceNickname || devUsername
                var remark = device.remark || ""
                var online = device.online || false
                
                deviceList.push({
                    deviceUsername: devUsername,
                    deviceNickname: devNickname,
                    remark: remark,
                    online: online,
                    deviceId: device.deviceId || ""
                })
                
                // 显示文本
                var displayText = devNickname
                if (remark) {
                    var maskedName = devNickname.length > 3 ? devNickname.substring(0, 3) + "**" : devNickname
                    displayText = maskedName + "(" + remark + ")"
                }
                displayList.push(displayText)
            }
            
            monitorAccountColumn.deviceDataList = deviceList
            monitorAccountColumn.accountList = displayList
            
            // 恢复之前选中的设备
            var savedDeviceUsername = HttpClient.getSavedDeviceUsername()
            if (savedDeviceUsername) {
                for (var j = 0; j < deviceList.length; j++) {
                    if (deviceList[j].deviceUsername === savedDeviceUsername) {
                        monitorAccountColumn.selectedAccount = displayList[j]
                        monitorAccountColumn.selectedDeviceUsername = savedDeviceUsername
                        break
                    }
                }
            }
        }
        
        function onBindingDevicesFailed(code, message) {
            console.log("获取设备列表失败:", code, message)
            isLoggingIn = false
        }
        
        function onLoginFailed(code, message) {
            console.log("登录失败:", code, message)
            isLoggingIn = false
            loggingInLevel = 0
            // ⭐ 特殊处理 code=1005（至尊版到期或未开通）
            if (code === 1005) {
                loginError = message || "至尊版已到期或未开通，请联系管理员"
            } else {
                loginError = message
            }
        }
        
        function onRegisterSuccess(username, message) {
            console.log("注册成功:", username, message)
            isLoggingIn = false
            loggingInLevel = 0
            loginError = ""
            // 保存用户名用于自动填充
            pendingLoginUsername = username
            // 显示成功提示
            showToast("注册成功！", true)
        }
        
        function onRegisterFailed(code, message) {
            console.log("注册失败:", code, message)
            isLoggingIn = false
            loggingInLevel = 0
            // 显示失败提示
            showToast(message || "注册失败", false)
        }
    }
    
    // 支持拖动窗口（排除右上角关闭按钮区域）
    MouseArea {
        id: dragArea
        anchors.top: parent.top
        anchors.left: parent.left
        anchors.right: parent.right
        height: 40
        z: 100
        
        property point clickPos: "0,0"
        
        // 检查是否在关闭按钮区域
        function isInCloseButton(mouseX, mouseY) {
            // 关闭按钮在右侧，距离右边 12px，宽 28px
            var closeRight = dragArea.width - 12
            var closeLeft = closeRight - 28
            return mouseX >= closeLeft && mouseX <= closeRight && mouseY >= 12 && mouseY <= 40
        }
        
        onPressed: function(mouse) {
            if (isInCloseButton(mouse.x, mouse.y)) {
                mouse.accepted = false
                return
            }
            clickPos = Qt.point(mouse.x, mouse.y)
        }
        
        onPositionChanged: function(mouse) {
            var delta = Qt.point(mouse.x - clickPos.x, mouse.y - clickPos.y)
            mainWindow.x = mainWindow.x + delta.x
            mainWindow.y = mainWindow.y + delta.y
        }
    }
    
    // 顶部固定区域（Logo + 设备号 + 关闭按钮）
    RowLayout {
        id: topBar
        anchors.top: parent.top
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.topMargin: 12
        anchors.leftMargin: 5
        anchors.rightMargin: 12
        height: 20
        spacing: 4
        z: 1000  // 确保在所有表单之上
        
        Image {
            source: "images/yql.png"
            sourceSize.width: 18
            sourceSize.height: 18
            fillMode: Image.PreserveAspectFit
        }
        
        Text {
            text: "金凤凰"
            font.family: "PingFang HK"
            font.pixelSize: 14
            font.weight: Font.Bold
            color: "#E0E0E0"
        }
        
        Item { Layout.fillWidth: true }
        
        // ⭐ 设备序列号（居中显示，本地生成，始终可见）
        Row {
            spacing: 4
            Layout.alignment: Qt.AlignVCenter
            
            Text {
                text: "设备号: " + HttpClient.pcDeviceId()
                font.family: "PingFang HK"
                font.pixelSize: 11
                color: "#888888"
                anchors.verticalCenter: parent.verticalCenter
            }
            
            // 复制按钮
            Rectangle {
                width: 32
                height: 18
                radius: 3
                color: copyDeviceIdArea.containsMouse ? "#4a4a4a" : "#3c3c3c"
                anchors.verticalCenter: parent.verticalCenter
                
                Text {
                    anchors.centerIn: parent
                    text: "复制"
                    font.family: "PingFang HK"
                    font.pixelSize: 10
                    color: copyDeviceIdArea.containsMouse ? "#3993D2" : "#AAAAAA"
                }
                
                MouseArea {
                    id: copyDeviceIdArea
                    anchors.fill: parent
                    hoverEnabled: true
                    cursorShape: Qt.PointingHandCursor
                    onClicked: {
                        HttpClient.copyToClipboard(HttpClient.pcDeviceId())
                        showToast("设备号已复制", false)
                    }
                }
            }
        }
        
        Item { Layout.fillWidth: true }
        
        Rectangle {
            id: closeButtonRect
            width: 28
            height: 28
            color: closeButtonArea.containsMouse ? "#e0e0e0" : "transparent"
            radius: 4
            z: 2000  // 确保在最上层
            
            Text {
                anchors.centerIn: parent
                text: "✕"
                font.pixelSize: 14
                color: closeButtonArea.containsMouse ? "#000000" : "#9E9E9E"
            }
            
            MouseArea {
                id: closeButtonArea
                anchors.fill: parent
                hoverEnabled: true
                cursorShape: Qt.PointingHandCursor
                propagateComposedEvents: false
                onPressed: function(mouse) {
                    console.log("🚪 关闭按钮 pressed")
                    mouse.accepted = true
                }
                onClicked: function(mouse) {
                    console.log("🚪 关闭按钮点击，退出程序")
                    mouse.accepted = true
                    Qt.callLater(Qt.quit)
                }
            }
        }
    }
    
    // ============ 登录表单 ============
    Rectangle {
        id: loginForm
        anchors.fill: parent
        color: "transparent"
        visible: opacity > 0
        
        ColumnLayout {
            anchors.fill: parent
            anchors.leftMargin: 40
            anchors.rightMargin: 40
            anchors.topMargin: 44
            anchors.bottomMargin: 40
            spacing: 0
            
            Item { Layout.preferredHeight: 56 }
            
            // 输入框
            ColumnLayout {
                Layout.fillWidth: true
                spacing: 32
                
                // 用户名（带历史账号下拉）
                Column {
                    id: usernameColumn
                    Layout.fillWidth: true
                    spacing: 0
                    
                    property var savedAccounts: []  // 历史账号列表
                    property bool dropdownVisible: false
                    
                    Item {
                        width: parent.width
                        height: 30
                        
                        TextField {
                            id: loginUsername
                            anchors.left: parent.left
                            anchors.right: loginUsernameClearBtn.left
                            height: 30
                            placeholderText: "请输入用户名"
                            font.family: "PingFang HK"
                            font.pixelSize: 16
                            color: "#E0E0E0"
                            placeholderTextColor: "#808080"
                            background: Item {}
                            leftPadding: 0
                            rightPadding: 0
                            verticalAlignment: TextInput.AlignVCenter
                            
                            cursorDelegate: Rectangle {
                                width: 2; height: 18; color: "#3993D2"
                                visible: loginUsername.activeFocus
                                SequentialAnimation on opacity {
                                    loops: Animation.Infinite
                                    running: loginUsername.activeFocus
                                    NumberAnimation { to: 0; duration: 500 }
                                    NumberAnimation { to: 1; duration: 500 }
                                }
                            }
                            
                            onTextChanged: {
                                monitorAccountColumn.accountList = []
                                monitorAccountColumn.deviceDataList = []
                                monitorAccountColumn.selectedAccount = ""
                                monitorAccountColumn.selectedDeviceUsername = ""
                            }
                        }
                        
                        // 清除按钮
                        Item {
                            id: loginUsernameClearBtn
                            anchors.right: usernameDropdownBtn.left
                            anchors.rightMargin: 2
                            anchors.verticalCenter: parent.verticalCenter
                            width: loginUsername.text.length > 0 ? 20 : 0
                            height: parent.height
                            visible: loginUsername.text.length > 0
                            
                            Text {
                                anchors.centerIn: parent
                                text: "✕"
                                font.pixelSize: 10
                                color: loginUsernameClearArea.containsMouse ? "#ff4444" : "#808080"
                            }
                            
                            MouseArea {
                                id: loginUsernameClearArea
                                anchors.fill: parent
                                hoverEnabled: true
                                cursorShape: Qt.PointingHandCursor
                                onClicked: {
                                    loginUsername.text = ""
                                    loginPassword.text = ""
                                    loginUsername.forceActiveFocus()
                                }
                            }
                        }
                        
                        // 下拉按钮（始终显示）
                        Item {
                            id: usernameDropdownBtn
                            anchors.right: parent.right
                            anchors.verticalCenter: parent.verticalCenter
                            width: 20
                            height: parent.height
                            
                            Text {
                                anchors.centerIn: parent
                                text: usernameColumn.dropdownVisible ? "▲" : "▼"
                                font.pixelSize: 10
                                color: usernameDropdownArea.containsMouse ? "#3993D2" : "#808080"
                            }
                            
                            MouseArea {
                                id: usernameDropdownArea
                                anchors.fill: parent
                                hoverEnabled: true
                                cursorShape: Qt.PointingHandCursor
                                onClicked: {
                                    if (usernameColumn.savedAccounts.length > 0) {
                                        usernameColumn.dropdownVisible = !usernameColumn.dropdownVisible
                                    }
                                }
                            }
                        }
                    }
                    Rectangle { 
                        width: parent.width
                        height: 1.4
                        color: loginUsername.activeFocus || usernameColumn.dropdownVisible ? "#3993D2" : "#4a4a4a" 
                    }
                    
                    // 历史账号下拉列表
                    Rectangle {
                        id: usernameDropdown
                        width: parent.width
                        height: Math.min(usernameColumn.savedAccounts.length * 36, 180)
                        visible: usernameColumn.dropdownVisible && usernameColumn.savedAccounts.length > 0
                        color: "#2d2d2d"
                        border.color: "#3c3c3c"
                        border.width: 1
                        radius: 4
                        z: 100
                        clip: true
                        
                        ListView {
                            id: usernameListView
                            anchors.fill: parent
                            anchors.margins: 2
                            model: usernameColumn.savedAccounts
                            clip: true
                            
                            delegate: Rectangle {
                                width: usernameListView.width
                                height: 36
                                color: usernameItemArea.containsMouse ? "#3c3c3c" : "#2d2d2d"
                                
                                Row {
                                    anchors.fill: parent
                                    anchors.leftMargin: 8
                                    anchors.rightMargin: 8
                                    spacing: 8
                                    
                                    Text {
                                        anchors.verticalCenter: parent.verticalCenter
                                        text: modelData
                                        font.family: "PingFang HK"
                                        font.pixelSize: 14
                                        color: "#CCCCCC"
                                        elide: Text.ElideRight
                                        width: parent.width - deleteUsernameBtn.width - 16
                                    }
                                    
                                    // 删除按钮
                                    Rectangle {
                                        id: deleteUsernameBtn
                                        anchors.verticalCenter: parent.verticalCenter
                                        width: 20
                                        height: 20
                                        radius: 10
                                        color: deleteUsernameBtnArea.containsMouse ? "#5c3c3c" : "transparent"
                                        
                                        Text {
                                            anchors.centerIn: parent
                                            text: "✕"
                                            font.pixelSize: 10
                                            color: deleteUsernameBtnArea.containsMouse ? "#ff4444" : "#808080"
                                        }
                                        
                                        MouseArea {
                                            id: deleteUsernameBtnArea
                                            anchors.fill: parent
                                            hoverEnabled: true
                                            cursorShape: Qt.PointingHandCursor
                                            onClicked: {
                                                HttpClient.removeAccount(modelData)
                                                usernameColumn.savedAccounts = HttpClient.getSavedAccounts()
                                            }
                                        }
                                    }
                                }
                                
                                MouseArea {
                                    id: usernameItemArea
                                    anchors.fill: parent
                                    hoverEnabled: true
                                    cursorShape: Qt.PointingHandCursor
                                    // 点击整行选择账号，但排除删除按钮区域
                                    onClicked: {
                                        var username = modelData
                                        loginUsername.text = username
                                        loginPassword.text = HttpClient.getAccountPassword(username)
                                        usernameColumn.dropdownVisible = false
                                        // 自动获取该账号的设备列表
                                        fetchDeviceList()
                                    }
                                }
                            }
                        }
                    }
                }
                
                // 密码
                Column {
                    id: passwordColumn
                    Layout.fillWidth: true
                    spacing: 0
                    
                    property bool passwordVisible: false  // 控制密码是否可见
                    
                    Item {
                        width: parent.width
                        height: 30
                        
                        TextField {
                            id: loginPassword
                            anchors.left: parent.left
                            anchors.right: passwordEyeBtn.left
                            anchors.rightMargin: 8
                            height: 30
                            placeholderText: "请输入密码"
                            font.family: "PingFang HK"
                            font.pixelSize: 16
                            color: "#E0E0E0"
                            placeholderTextColor: "#808080"
                            echoMode: passwordColumn.passwordVisible ? TextInput.Normal : TextInput.Password
                            background: Item {}
                            leftPadding: 0
                            rightPadding: 0
                            verticalAlignment: TextInput.AlignVCenter
                            
                            cursorDelegate: Rectangle {
                                width: 2; height: 18; color: "#3993D2"
                                visible: loginPassword.activeFocus
                                SequentialAnimation on opacity {
                                    loops: Animation.Infinite
                                    running: loginPassword.activeFocus
                                    NumberAnimation { to: 0; duration: 500 }
                                    NumberAnimation { to: 1; duration: 500 }
                                }
                            }
                        }
                        
                        // 眼睛图标按钮
                        Text {
                            id: passwordEyeBtn
                            anchors.right: parent.right
                            anchors.verticalCenter: parent.verticalCenter
                            text: passwordColumn.passwordVisible ? "👁" : "👁‍🗨"
                            font.pixelSize: 16
                            color: passwordEyeArea.containsMouse ? "#3993D2" : "#808080"
                            
                            MouseArea {
                                id: passwordEyeArea
                                anchors.fill: parent
                                anchors.margins: -6
                                hoverEnabled: true
                                cursorShape: Qt.PointingHandCursor
                                onClicked: passwordColumn.passwordVisible = !passwordColumn.passwordVisible
                            }
                        }
                    }
                    Rectangle { width: parent.width; height: 1.4; color: loginPassword.activeFocus ? "#3993D2" : "#4a4a4a" }
                }
                
                // 监控账号选择（非必选）
                Column {
                    id: monitorAccountColumn
                    Layout.fillWidth: true
                    spacing: 0
                    
                    property bool isActive: monitorAccountArea.containsMouse || monitorAccountArea.pressed || accountDropdown.visible
                    property string selectedAccount: ""
                    property string selectedDeviceUsername: ""  // 选中的设备账号（用于登录）
                    property var accountList: []  // 显示文本列表
                    property var deviceDataList: []  // 完整设备数据列表
                    
                    Item {
                        id: monitorAccountField
                        width: parent.width
                        height: 30
                        
                        Text {
                            anchors.left: parent.left
                            anchors.verticalCenter: parent.verticalCenter
                            text: monitorAccountColumn.selectedAccount || "请选择监控账号(非必选)"
                            font.family: "PingFang HK"
                            font.pixelSize: 16
                            color: monitorAccountColumn.selectedAccount ? "#E0E0E0" : "#808080"
                        }
                        
                        // 下拉三角形
                        Item {
                            anchors.right: parent.right
                            anchors.verticalCenter: parent.verticalCenter
                            width: 20
                            height: 30
                            z: 10
                            
                            Text {
                                anchors.centerIn: parent
                                text: accountDropdown.visible ? "▲" : "▼"
                                font.pixelSize: 10
                                color: dropdownBtnArea.containsMouse ? "#3993D2" : "#808080"
                            }
                            
                            MouseArea {
                                id: dropdownBtnArea
                                anchors.fill: parent
                                hoverEnabled: true
                                cursorShape: Qt.PointingHandCursor
                                onClicked: {
                                    if (accountDropdown.visible) {
                                        accountDropdown.visible = false
                                    } else {
                                        // 从服务器获取设备列表
                                        fetchDeviceList()
                                        accountDropdown.visible = true
                                    }
                                }
                            }
                        }
                        
                        MouseArea {
                            id: monitorAccountArea
                            anchors.fill: parent
                            hoverEnabled: true
                            cursorShape: Qt.PointingHandCursor
                            // 不拦截右侧按钮区域的点击
                            onClicked: {
                                if (accountDropdown.visible) {
                                    accountDropdown.visible = false
                                } else {
                                    // 从服务器获取设备列表
                                    fetchDeviceList()
                                    accountDropdown.visible = true
                                }
                            }
                        }
                    }
                    Rectangle { 
                        id: monitorAccountUnderline
                        width: parent.width
                        height: 1.4
                        color: parent.isActive ? "#3993D2" : "#4a4a4a"
                    }
                }
            }
            
            Item { Layout.preferredHeight: 16 }
            
            // ⭐ 豪华版登录按钮（pcLevel=1）
            Item {
                Layout.fillWidth: true
                Layout.preferredHeight: 58
                
                Rectangle {
                    anchors.fill: loginBtn1
                    anchors.topMargin: 2
                    radius: 6
                    color: "#30000000"
                }
                
                Rectangle {
                    id: loginBtn1
                    anchors.left: parent.left
                    anchors.right: parent.right
                    anchors.top: parent.top
                    height: 54
                    radius: 6
                    color: loginBtn1Mouse.containsMouse ? "#4a90d9" : "#3993D2"
                    
                    Text {
                        anchors.centerIn: parent
                        text: loggingInLevel === 1 ? "登录中..." : "豪华版登录"
                        font.family: "PingFang HK"
                        font.pixelSize: 24
                        font.weight: Font.Medium
                        color: "#FFFFFF"
                    }
                    
                    MouseArea {
                        id: loginBtn1Mouse
                        enabled: !isLoggingIn
                        anchors.fill: parent
                        hoverEnabled: true
                        cursorShape: Qt.PointingHandCursor
                        onClicked: {
                            if (isLoggingIn) return
                            if (loginUsername.text.trim() === "" || loginPassword.text.trim() === "") {
                                loginError = "请输入用户名和密码"
                                return
                            }
                            isLoggingIn = true
                            loggingInLevel = 1
                            loginError = ""
                            HttpClient.login(loginUsername.text.trim(), loginPassword.text.trim(), 1, monitorAccountColumn.selectedDeviceUsername)
                        }
                    }
                }
            }
            
            // ⭐ 至尊版登录按钮（pcLevel=2）
            Item {
                Layout.fillWidth: true
                Layout.preferredHeight: 58
                
                Rectangle {
                    anchors.fill: loginBtn2
                    anchors.topMargin: 2
                    radius: 6
                    color: "#30000000"
                }
                
                Rectangle {
                    id: loginBtn2
                    anchors.left: parent.left
                    anchors.right: parent.right
                    anchors.top: parent.top
                    height: 54
                    radius: 6
                    color: loginBtn2Mouse.containsMouse ? "#D4A017" : "#C49000"
                    
                    Text {
                        anchors.centerIn: parent
                        text: loggingInLevel === 2 ? "登录中..." : "至尊版登录"
                        font.family: "PingFang HK"
                        font.pixelSize: 24
                        font.weight: Font.Medium
                        color: "#FFFFFF"
                    }
                    
                    MouseArea {
                        id: loginBtn2Mouse
                        enabled: !isLoggingIn
                        anchors.fill: parent
                        hoverEnabled: true
                        cursorShape: Qt.PointingHandCursor
                        onClicked: {
                            if (isLoggingIn) return
                            if (loginUsername.text.trim() === "" || loginPassword.text.trim() === "") {
                                loginError = "请输入用户名和密码"
                                return
                            }
                            isLoggingIn = true
                            loggingInLevel = 2
                            loginError = ""
                            HttpClient.login(loginUsername.text.trim(), loginPassword.text.trim(), 2, monitorAccountColumn.selectedDeviceUsername)
                        }
                    }
                }
            }
            
            // 错误提示
            Text {
                Layout.fillWidth: true
                Layout.preferredHeight: 16
                text: loginError
                font.family: "PingFang HK"
                font.pixelSize: 14
                color: "#ff4444"
                visible: loginError !== ""
                horizontalAlignment: Text.AlignHCenter
            }
            
            Item { Layout.preferredHeight: loginError !== "" ? 4 : 8 }
            
            // 注册按钮
            Item {
                Layout.fillWidth: true
                Layout.preferredHeight: 48
                
                Rectangle {
                    anchors.fill: regBtn
                    anchors.topMargin: 2
                    radius: 6
                    color: "#30000000"
                }
                
                Rectangle {
                    id: regBtn
                    anchors.left: parent.left
                    anchors.right: parent.right
                    anchors.top: parent.top
                    height: 44
                    radius: 6
                    color: regLinkMouse.containsMouse ? "#4a4a4a" : "#3c3c3c"
                    
                    Text {
                        anchors.centerIn: parent
                        text: "注 册"
                        font.family: "PingFang HK"
                        font.pixelSize: 20
                        font.weight: Font.Medium
                        color: "#CCCCCC"
                    }
                    
                    MouseArea {
                        id: regLinkMouse
                        anchors.fill: parent
                        hoverEnabled: true
                        cursorShape: Qt.PointingHandCursor
                        onClicked: {
                            console.log("注册按钮点击")
                            showRegister()
                        }
                    }
                }
            }
            
            Item { Layout.fillHeight: true }
        }
        
    }
    
    // ============ 注册表单 ============
    Rectangle {
        id: registerForm
        anchors.fill: parent
        color: "#1e1e1e"  // Cursor 编辑器背景色
        y: parent.height  // 初始在下方隐藏
        visible: currentView === "register"
        
        ColumnLayout {
            anchors.fill: parent
            anchors.leftMargin: 40
            anchors.rightMargin: 40
            anchors.topMargin: 44
            anchors.bottomMargin: 32
            spacing: 0
            
            Item { Layout.preferredHeight: 56 }
            
            Text {
                text: "注册账号"
                font.family: "PingFang HK"
                font.pixelSize: 32
                font.weight: Font.Medium
                color: "#E0E0E0"
                Layout.fillWidth: true
            }
            
            Item { Layout.preferredHeight: 40 }
            
            // 输入框
            ColumnLayout {
                Layout.fillWidth: true
                spacing: 32
                
                // 用户名
                Column {
                    Layout.fillWidth: true
                    spacing: 0
                    
                    Item {
                        width: parent.width
                        height: 30
                        
                        TextField {
                            id: regUsername
                            anchors.left: parent.left
                            anchors.right: regUsernameClearBtn.left
                            height: 30
                            placeholderText: "请输入账号 (11-14位字母数字)"
                            font.family: "PingFang HK"
                            font.pixelSize: 16
                            color: "#E0E0E0"
                            placeholderTextColor: "#808080"
                            background: Item {}
                            leftPadding: 0
                            rightPadding: 0
                            verticalAlignment: TextInput.AlignVCenter
                            maximumLength: 14
                            
                            cursorDelegate: Rectangle {
                                width: 2; height: 18; color: "#3993D2"
                                visible: regUsername.activeFocus
                                SequentialAnimation on opacity {
                                    loops: Animation.Infinite
                                    running: regUsername.activeFocus
                                    NumberAnimation { to: 0; duration: 500 }
                                    NumberAnimation { to: 1; duration: 500 }
                                }
                            }
                        }
                        
                        // 清除按钮
                        Item {
                            id: regUsernameClearBtn
                            anchors.right: parent.right
                            anchors.verticalCenter: parent.verticalCenter
                            width: regUsername.text.length > 0 ? 20 : 0
                            height: parent.height
                            visible: regUsername.text.length > 0
                            
                            Text {
                                anchors.centerIn: parent
                                text: "✕"
                                font.pixelSize: 10
                                color: regUsernameClearArea.containsMouse ? "#ff4444" : "#808080"
                            }
                            
                            MouseArea {
                                id: regUsernameClearArea
                                anchors.fill: parent
                                hoverEnabled: true
                                cursorShape: Qt.PointingHandCursor
                                onClicked: {
                                    regUsername.text = ""
                                    regUsername.forceActiveFocus()
                                }
                            }
                        }
                    }
                    Rectangle { width: parent.width; height: 1.4; color: regUsername.activeFocus ? "#3993D2" : "#4a4a4a" }
                }
                
                // 密码
                Column {
                    Layout.fillWidth: true
                    spacing: 0
                    
                        TextField {
                            id: regPassword
                            width: parent.width
                            height: 30
                            placeholderText: "请输入密码 (6-20位)"
                            font.family: "PingFang HK"
                            font.pixelSize: 16
                            color: "#E0E0E0"
                        placeholderTextColor: "#808080"
                        echoMode: TextInput.Password
                        maximumLength: 20
                        background: Item {}
                        leftPadding: 0
                        rightPadding: 0
                        verticalAlignment: TextInput.AlignVCenter
                        
                        cursorDelegate: Rectangle {
                            width: 2; height: 18; color: "#3993D2"
                            visible: regPassword.activeFocus
                            SequentialAnimation on opacity {
                                loops: Animation.Infinite
                                running: regPassword.activeFocus
                                NumberAnimation { to: 0; duration: 500 }
                                NumberAnimation { to: 1; duration: 500 }
                            }
                        }
                    }
                    Rectangle { width: parent.width; height: 1.4; color: regPassword.activeFocus ? "#3993D2" : "#4a4a4a" }
                }
                
                // 确认密码
                Column {
                    Layout.fillWidth: true
                    spacing: 0
                    
                        TextField {
                            id: regConfirmPwd
                            width: parent.width
                            height: 30
                            placeholderText: "请确认密码 (6-20位)"
                            font.family: "PingFang HK"
                            font.pixelSize: 16
                            color: "#E0E0E0"
                        placeholderTextColor: "#808080"
                        echoMode: TextInput.Password
                        background: Item {}
                        leftPadding: 0
                        rightPadding: 0
                        verticalAlignment: TextInput.AlignVCenter
                        
                        cursorDelegate: Rectangle {
                            width: 2; height: 18; color: "#3993D2"
                            visible: regConfirmPwd.activeFocus
                            SequentialAnimation on opacity {
                                loops: Animation.Infinite
                                running: regConfirmPwd.activeFocus
                                NumberAnimation { to: 0; duration: 500 }
                                NumberAnimation { to: 1; duration: 500 }
                            }
                        }
                    }
                    Rectangle { width: parent.width; height: 1.4; color: regConfirmPwd.activeFocus ? "#3993D2" : "#4a4a4a" }
                }
            }
            
            Item { Layout.preferredHeight: 16 }
            
            // 注册按钮
            Item {
                Layout.fillWidth: true
                Layout.preferredHeight: 58
                
                Rectangle {
                    anchors.fill: regSubmitBtn
                    anchors.topMargin: 2
                    radius: 6
                    color: "#30000000"
                }
                
                Rectangle {
                    id: regSubmitBtn
                    anchors.left: parent.left
                    anchors.right: parent.right
                    anchors.top: parent.top
                    height: 54
                    radius: 6
                    color: regSubmitBtnMouse.containsMouse ? "#4a90d9" : "#3993D2"
                    
                    Text {
                        anchors.centerIn: parent
                        text: "注册"
                        font.family: "PingFang HK"
                        font.pixelSize: 24
                        font.weight: Font.Medium
                        color: "#FFFFFF"
                    }
                    
                    MouseArea {
                        id: regSubmitBtnMouse
                        anchors.fill: parent
                        hoverEnabled: true
                        cursorShape: Qt.PointingHandCursor
                        onClicked: {
                            if (isLoggingIn) return
                            
                            var username = regUsername.text.trim()
                            var password = regPassword.text.trim()
                            
                            // 验证用户名
                            if (username === "") {
                                showToast("请输入账号", false)
                                return
                            }
                            if (!/^[a-zA-Z0-9]{11,14}$/.test(username)) {
                                showToast("账号必须是11-14位字母或数字", false)
                                return
                            }
                            
                            // 验证密码
                            if (password === "") {
                                showToast("请输入密码", false)
                                return
                            }
                            if (password.length < 6 || password.length > 20) {
                                showToast("密码长度必须在6到20位之间", false)
                                return
                            }
                            if (regPassword.text !== regConfirmPwd.text) {
                                showToast("两次输入的密码不一致", false)
                                return
                            }
                            
                            // 自动生成昵称（6位数字）
                            var nickname = generateNickname()
                            console.log("注册账号:", username, "昵称:", nickname)
                            
                            isLoggingIn = true
                            loginError = ""
                            HttpClient.registerUser(username, password, nickname)
                        }
                    }
                }
            }
            
            Item { Layout.preferredHeight: 16 }
            
            // 返回登录（带阴影）
            Item {
                Layout.fillWidth: true
                Layout.preferredHeight: 58
                
                Rectangle {
                    anchors.fill: backLoginBtn
                    anchors.topMargin: 2
                    radius: 6
                    color: "#30000000"
                }
                
                Rectangle {
                    id: backLoginBtn
                    anchors.left: parent.left
                    anchors.right: parent.right
                    anchors.top: parent.top
                    height: 54
                    radius: 6
                    color: backLoginMouse.containsMouse ? "#4a4a4a" : "#3c3c3c"
                    
                    Text {
                        anchors.centerIn: parent
                        text: "返回登录"
                        font.family: "PingFang HK"
                        font.pixelSize: 24
                        font.weight: Font.Medium
                        color: "#CCCCCC"
                    }
                    
                    MouseArea {
                        id: backLoginMouse
                        anchors.fill: parent
                        hoverEnabled: true
                        cursorShape: Qt.PointingHandCursor
                        onClicked: showLogin()
                    }
                }
            }
            
            Item { Layout.fillHeight: true }
        }
    }
    
    // ============ 账号下拉列表遮罩 ============
    MouseArea {
        id: dropdownOverlay
        anchors.fill: parent
        visible: accountDropdown.visible
        z: 499
        onClicked: accountDropdown.visible = false
    }
    
    // ============ 账号下拉列表（绝对定位）============
    Rectangle {
        id: accountDropdown
        x: 12
        y: 0
        width: monitorAccountColumn.width
        height: 164  // 固定高度，显示4个项目，超过4个显示滚动条
        visible: false
        color: "#2d2d2d"
        radius: 4
        z: 500
        clip: true
        
        onVisibleChanged: {
            if (visible && monitorAccountUnderline) {
                var pos = monitorAccountUnderline.mapToItem(loginPage, 0, monitorAccountUnderline.height)
                accountDropdown.x = pos.x
                accountDropdown.y = pos.y
            }
        }
        
        // 只保留底部圆角
        Rectangle {
            anchors.top: parent.top
            anchors.left: parent.left
            anchors.right: parent.right
            height: 4
            color: parent.color
        }
        
        // 阴影效果
        Rectangle {
            anchors.fill: parent
            anchors.topMargin: 2
            color: "#28000000"
            radius: 4
            z: -1
        }
        
        ListView {
            id: accountListView
            anchors.fill: parent
            anchors.topMargin: 20
            anchors.bottomMargin: 20
            anchors.leftMargin: 16
            anchors.rightMargin: 16
            clip: true
            spacing: 20
            model: monitorAccountColumn.accountList
            boundsBehavior: Flickable.StopAtBounds
            
            delegate: Text {
                width: accountListView.width
                height: 16
                text: modelData
                font.family: "PingFang HK"
                font.pixelSize: 16
                color: delegateMouseArea.containsMouse ? "#3993D2" : "#CCCCCC"
                
                MouseArea {
                    id: delegateMouseArea
                    anchors.fill: parent
                    hoverEnabled: true
                    cursorShape: Qt.PointingHandCursor
                    onClicked: {
                        monitorAccountColumn.selectedAccount = modelData
                        // 从设备数据列表中获取对应的 deviceUsername
                        var idx = monitorAccountColumn.accountList.indexOf(modelData)
                        if (idx >= 0 && idx < monitorAccountColumn.deviceDataList.length) {
                            monitorAccountColumn.selectedDeviceUsername = monitorAccountColumn.deviceDataList[idx].deviceUsername
                            console.log("选择设备:", modelData, "deviceUsername:", monitorAccountColumn.selectedDeviceUsername)
                        }
                        accountDropdown.visible = false
                    }
                }
            }
            
            ScrollBar.vertical: ScrollBar {
                policy: accountListView.contentHeight > accountListView.height ? ScrollBar.AsNeeded : ScrollBar.AlwaysOff
            }
        }
    }
    
    // ============ 绑定对话框遮罩 ============
    Rectangle {
        id: bindOverlay
        anchors.fill: parent
        color: "#40000000"
        visible: bindDialog.visible
        z: 200
        
        MouseArea {
            anchors.fill: parent
            onClicked: bindDialog.visible = false
        }
    }
    
    // ============ 绑定对话框 ============
    Rectangle {
        id: bindDialog
        width: 480
        height: 342
        anchors.centerIn: parent
        color: "#2d2d2d"
        radius: 2
        visible: false
        z: 201
        
        // 阴影
        Rectangle {
            anchors.fill: parent
            anchors.topMargin: 2
            color: "#20000000"
            radius: 2
            z: -1
        }
        
        ColumnLayout {
            anchors.fill: parent
            anchors.margins: 12
            anchors.topMargin: 24
            spacing: 0
            
            // 标题
            Text {
                text: "绑定账号"
                font.family: "PingFang HK"
                font.pixelSize: 32
                font.weight: Font.Medium
                color: "#E0E0E0"
                Layout.fillWidth: true
            }
            
            Item { Layout.preferredHeight: 40 }
            
            // 输入框区域
            ColumnLayout {
                Layout.fillWidth: true
                spacing: 32
                
                // 输入框1 - 账号
                Column {
                    Layout.fillWidth: true
                    spacing: 0
                    
                        TextField {
                            id: bindAccount
                            width: parent.width
                            height: 30
                            text: ""
                            placeholderText: "请输入账号"
                            font.family: "PingFang HK"
                            font.pixelSize: 16
                            font.bold: true
                            color: "#CCCCCC"
                        placeholderTextColor: "#808080"
                        background: Item {}
                        leftPadding: 0
                        rightPadding: 0
                        verticalAlignment: TextInput.AlignVCenter
                        
                        cursorDelegate: Rectangle {
                            width: 2; height: 18; color: "#3993D2"
                            visible: bindAccount.activeFocus
                            SequentialAnimation on opacity {
                                loops: Animation.Infinite
                                running: bindAccount.activeFocus
                                NumberAnimation { to: 0; duration: 500 }
                                NumberAnimation { to: 1; duration: 500 }
                            }
                        }
                    }
                    Rectangle { width: parent.width; height: 1.4; color: bindAccount.activeFocus ? "#3993D2" : "#4a4a4a" }
                }
                
                // 输入框2 - 安全密码
                Column {
                    Layout.fillWidth: true
                    spacing: 0
                    
                        TextField {
                            id: bindPassword
                            width: parent.width
                            height: 30
                            placeholderText: "请输入安全密码"
                            font.family: "PingFang HK"
                            font.pixelSize: 16
                            font.bold: true
                            color: "#CCCCCC"
                        placeholderTextColor: "#808080"
                        echoMode: TextInput.Password
                        background: Item {}
                        leftPadding: 0
                        rightPadding: 0
                        verticalAlignment: TextInput.AlignVCenter
                        
                        cursorDelegate: Rectangle {
                            width: 2; height: 18; color: "#3993D2"
                            visible: bindPassword.activeFocus
                            SequentialAnimation on opacity {
                                loops: Animation.Infinite
                                running: bindPassword.activeFocus
                                NumberAnimation { to: 0; duration: 500 }
                                NumberAnimation { to: 1; duration: 500 }
                            }
                        }
                    }
                    Rectangle { width: parent.width; height: 1.4; color: bindPassword.activeFocus ? "#3993D2" : "#4a4a4a" }
                }
                
                // 输入框3 - 验证码
                Column {
                    Layout.fillWidth: true
                    spacing: 0
                    
                        TextField {
                            id: bindCode
                            width: parent.width
                            height: 30
                            placeholderText: "请输入验证码"
                            font.family: "PingFang HK"
                            font.pixelSize: 16
                            font.bold: true
                            color: "#CCCCCC"
                        placeholderTextColor: "#808080"
                        background: Item {}
                        leftPadding: 0
                        rightPadding: 0
                        verticalAlignment: TextInput.AlignVCenter
                        
                        cursorDelegate: Rectangle {
                            width: 2; height: 18; color: "#3993D2"
                            visible: bindCode.activeFocus
                            SequentialAnimation on opacity {
                                loops: Animation.Infinite
                                running: bindCode.activeFocus
                                NumberAnimation { to: 0; duration: 500 }
                                NumberAnimation { to: 1; duration: 500 }
                            }
                        }
                    }
                    Rectangle { width: parent.width; height: 1.4; color: bindCode.activeFocus ? "#3993D2" : "#4a4a4a" }
                }
            }
            
            Item { Layout.fillHeight: true }
            
            // 底部按钮
            RowLayout {
                Layout.fillWidth: true
                Layout.preferredHeight: 40
                spacing: 8
                Layout.alignment: Qt.AlignBottom
                
                Item { Layout.fillWidth: true }
                
                // 取消按钮
                Rectangle {
                    width: 68
                    height: 36
                    radius: 6
                    color: cancelBtnMouse.containsMouse ? "#4a4a4a" : "#3c3c3c"
                    opacity: 0.8
                    Layout.alignment: Qt.AlignTop
                    
                    Text {
                        anchors.centerIn: parent
                        text: "取消"
                        font.family: "PingFang HK"
                        font.pixelSize: 14
                        font.weight: Font.Medium
                        color: "#CCCCCC"
                    }
                    
                    MouseArea {
                        id: cancelBtnMouse
                        anchors.fill: parent
                        hoverEnabled: true
                        cursorShape: Qt.PointingHandCursor
                        onClicked: bindDialog.visible = false
                    }
                }
                
                // 确定按钮（带阴影）
                Item {
                    width: 68
                    height: 40
                    Layout.alignment: Qt.AlignTop
                    
                    Rectangle {
                        anchors.fill: confirmBtn
                        anchors.topMargin: 2
                        radius: 6
                        color: "#30000000"
                    }
                    
                    Rectangle {
                        id: confirmBtn
                        width: 68
                        height: 36
                        radius: 6
                        color: confirmBtnMouse.containsMouse ? "#4a90d9" : "#3993D2"
                        
                        Text {
                            anchors.centerIn: parent
                            text: "确定"
                            font.family: "PingFang HK"
                            font.pixelSize: 14
                            font.weight: Font.Medium
                            color: "#FFFFFF"
                        }
                        
                        MouseArea {
                            id: confirmBtnMouse
                            anchors.fill: parent
                            hoverEnabled: true
                            cursorShape: Qt.PointingHandCursor
                            onClicked: {
                                console.log("绑定确定")
                                bindDialog.visible = false
                            }
                        }
                    }
                }
            }
        }
    }
    
    // ============ 账号管理对话框遮罩 ============
    Rectangle {
        id: accountOverlay
        anchors.fill: parent
        color: "#40000000"
        visible: accountDialog.visible
        z: 300
        
        MouseArea {
            anchors.fill: parent
            onClicked: accountDialog.visible = false
        }
    }
    
    // ============ 账号管理对话框 ============
    Rectangle {
        id: accountDialog
        width: 400
        height: 240
        anchors.centerIn: parent
        color: "#2d2d2d"
        radius: 2
        visible: false
        z: 301
        
        // 阴影
        Rectangle {
            anchors.fill: parent
            anchors.topMargin: 2
            color: "#20000000"
            radius: 2
            z: -1
        }
        
        Column {
            anchors.fill: parent
            spacing: 0
            
            // 标题栏
            Rectangle {
                width: parent.width
                height: 49
                color: "#3993D2"
                
                Text {
                    anchors.left: parent.left
                    anchors.leftMargin: 20
                    anchors.verticalCenter: parent.verticalCenter
                    text: "账号管理"
                    font.family: "PingFang HK"
                    font.pixelSize: 18
                    font.weight: Font.Medium
                    color: "#E0E0E0"
                }
                
                // 关闭按钮
                Rectangle {
                    anchors.right: parent.right
                    anchors.rightMargin: 12
                    anchors.verticalCenter: parent.verticalCenter
                    width: 20
                    height: 20
                    color: accountCloseArea.containsMouse ? "#2d6da8" : "transparent"
                    radius: 2
                    
                    Text {
                        anchors.centerIn: parent
                        text: "✕"
                        font.pixelSize: 12
                        color: accountCloseArea.containsMouse ? "#FFFFFF" : "#D0D0D0"
                    }
                    
                    MouseArea {
                        id: accountCloseArea
                        anchors.fill: parent
                        hoverEnabled: true
                        cursorShape: Qt.PointingHandCursor
                        onClicked: accountDialog.visible = false
                    }
                }
            }
            
            // 账号列表区域
            Item {
                width: parent.width
                height: parent.height - 49 - 56
                
                Column {
                    anchors.fill: parent
                    anchors.margins: 20
                    spacing: 20
                    
                    // 账号项1
                    Row {
                        width: parent.width
                        height: 16
                        
                        Text {
                            text: "Y-251229004"
                            font.family: "PingFang HK"
                            font.pixelSize: 16
                            color: "#CCCCCC"
                            anchors.verticalCenter: parent.verticalCenter
                        }
                        
                        Item { width: parent.width - 104 - 16; height: 1 }
                        
                        // 删除按钮
                        Rectangle {
                            width: 16
                            height: 16
                            color: "transparent"
                            anchors.verticalCenter: parent.verticalCenter
                            
                            Text {
                                anchors.centerIn: parent
                                text: "✕"
                                font.pixelSize: 10
                                color: delBtn1Area.containsMouse ? "#ff4444" : "#808080"
                            }
                            
                            MouseArea {
                                id: delBtn1Area
                                anchors.fill: parent
                                hoverEnabled: true
                                cursorShape: Qt.PointingHandCursor
                                onClicked: console.log("删除账号1")
                            }
                        }
                    }
                    
                    // 账号项2
                    Row {
                        width: parent.width
                        height: 16
                        
                        Text {
                            text: "Y-251229005"
                            font.family: "PingFang HK"
                            font.pixelSize: 16
                            color: "#CCCCCC"
                            anchors.verticalCenter: parent.verticalCenter
                        }
                        
                        Item { width: parent.width - 104 - 16; height: 1 }
                        
                        // 删除按钮
                        Rectangle {
                            width: 16
                            height: 16
                            color: "transparent"
                            anchors.verticalCenter: parent.verticalCenter
                            
                            Text {
                                anchors.centerIn: parent
                                text: "✕"
                                font.pixelSize: 10
                                color: delBtn2Area.containsMouse ? "#ff4444" : "#808080"
                            }
                            
                            MouseArea {
                                id: delBtn2Area
                                anchors.fill: parent
                                hoverEnabled: true
                                cursorShape: Qt.PointingHandCursor
                                onClicked: console.log("删除账号2")
                            }
                        }
                    }
                }
            }
        }
    }
    
    // ============ Toast 提示框 ============
    Rectangle {
        id: toastContainer
        anchors.horizontalCenter: parent.horizontalCenter
        anchors.bottom: parent.bottom
        anchors.bottomMargin: 100
        width: toastText.width + 40
        height: 44
        radius: 22
        color: "#E0000000"
        visible: false
        z: 1000
        
        Text {
            id: toastText
            anchors.centerIn: parent
            text: ""
            font.family: "PingFang HK"
            font.pixelSize: 14
            color: "#FFFFFF"
        }
        
        // 渐入渐出动画
        opacity: 0
        
        Behavior on opacity {
            NumberAnimation { duration: 200 }
        }
    }
    
    Timer {
        id: toastTimer
        interval: 2000
        onTriggered: {
            toastContainer.opacity = 0
            toastHideTimer.start()
        }
    }
    
    Timer {
        id: toastHideTimer
        interval: 200
        onTriggered: {
            toastContainer.visible = false
            // 注册成功后切换到登录页面
            if (toastSwitchToLogin && pendingLoginUsername !== "") {
                showLogin()
                loginUsername.text = pendingLoginUsername
                // 清空设备列表（新注册的账号没有绑定设备）
                monitorAccountColumn.accountList = []
                monitorAccountColumn.deviceDataList = []
                monitorAccountColumn.selectedAccount = ""
                monitorAccountColumn.selectedDeviceUsername = ""
                // 重新获取设备列表
                fetchDeviceList()
                pendingLoginUsername = ""
                toastSwitchToLogin = false
            }
        }
    }
    
    // ============ 函数 ============
    
    function showToast(message, switchToLogin) {
        toastText.text = message
        toastContainer.visible = true
        toastContainer.opacity = 1
        toastSwitchToLogin = switchToLogin || false
        toastTimer.restart()
    }
    
    function showRegister() {
        currentView = "register"
        registerForm.y = 0
        // 清空输入框，让用户手动输入
        regUsername.text = ""
        regPassword.text = ""
        regConfirmPwd.text = ""
        loginError = ""
    }
    
    function showLogin() {
        currentView = "login"
        registerForm.y = loginPage.height
    }
    
    function showBindDialog() {
        bindAccount.text = ""
        bindPassword.text = ""
        bindCode.text = ""
        bindDialog.visible = true
    }
    
    function showAccountDialog() {
        accountDialog.visible = true
    }
    
    // 加载保存的账号信息
    function loadSavedAccount() {
        // 加载历史账号列表
        usernameColumn.savedAccounts = HttpClient.getSavedAccounts()
        
        // 加载上次登录的账号
        var savedUsername = HttpClient.getSavedUsername()
        var savedPassword = HttpClient.getSavedPassword()
        var savedDeviceUsername = HttpClient.getSavedDeviceUsername()
        var savedDeviceDisplay = HttpClient.getSavedDeviceDisplay()
        
        if (savedUsername) {
            loginUsername.text = savedUsername
        }
        if (savedPassword) {
            loginPassword.text = savedPassword
        }
        if (savedDeviceUsername && savedDeviceDisplay) {
            monitorAccountColumn.selectedDeviceUsername = savedDeviceUsername
            monitorAccountColumn.selectedAccount = savedDeviceDisplay
        }
        
        console.log("加载保存的账号:", savedUsername, "设备:", savedDeviceUsername, "历史账号数:", usernameColumn.savedAccounts.length)
    }
    
    // 刷新历史账号列表
    function refreshSavedAccounts() {
        usernameColumn.savedAccounts = HttpClient.getSavedAccounts()
    }
    
    // 从服务器获取设备列表
    function fetchDeviceList() {
        var username = loginUsername.text.trim()
        if (username === "") {
            // 不显示错误，静默返回
            return
        }
        isLoggingIn = true
        loginError = ""
        HttpClient.getBindingDevices(username)
    }
    
    Component.onCompleted: {
        loadSavedAccount()
    }
}
