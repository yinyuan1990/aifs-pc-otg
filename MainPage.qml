import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import QtMultimedia
import QtCore
import Aifs.Components 1.0

Rectangle {
    id: mainPage
    color: mainPage.pcActivationLevel >= 2 ? "#C8DFC0" : "#CAD9F2"  // 等级2绿色，等级1蓝色
    focus: true  // ⭐ 获取键盘焦点
    
    // ⭐ S键按下/释放检测（用于 S+滚轮 缩放）
    Keys.onPressed: function(event) {
        if (event.key === Qt.Key_S && !event.isAutoRepeat) {
            sKeyPressed = true
            event.accepted = false  // 允许事件继续传递（S键的其他快捷键仍可用）
        }
    }
    Keys.onReleased: function(event) {
        if (event.key === Qt.Key_S && !event.isAutoRepeat) {
            sKeyPressed = false
            event.accepted = false
        }
    }
    
    // 退出登录信号
    signal logoutRequested()
    
    // PC端激活等级：0=未激活，1=豪华版，2=至尊版
    property int pcActivationLevel: 0
    onPcActivationLevelChanged: {
        console.log("[抓拍全屏] pcActivationLevel 变化: 新值=" + pcActivationLevel + ", 抓拍全屏菜单项应该显示:" + (pcActivationLevel >= 2))
    }
    property string pcLevelName: ""        // 等级名称："豪华版"或"至尊版"
    property string pcExpireAt: ""         // 至尊版到期时间（豪华版为空）
    
    // 从 Main.qml 传入的参数
    property string srsServer: ""
    property string currentStream: "VID_59C9232BFF5576718C575E19EDE7"
    
    // P2P 直连模式属性
    property int connectMode: 0                        // 0=SRS模式, 1=P2P直连模式（来自CONFIG_STATE.state.connectstype）
    property string pairedIosDeviceId: ""              // 配对的 iOS 设备 ID
    property var iceServers: []                        // 从登录接口获取的 ICE 服务器列表
    
    // 行列调节防抖
    property int pendingRows: captureManager.gridRows
    property int pendingCols: captureManager.gridCols
    
    // 视频旋转角度 (0, 90, 180, 270)
    property int videoRotation: 0
    // 视频镜像: "none" / "horizontal" / "vertical"
    property string videoMirrorMode: "none"
    
    // 视频缩放相关属性
    property real videoZoom: 1.0           // 缩放倍数 1.0 - 5.0
    property real videoOffsetX: 0          // X轴偏移（相对于中心）
    property real videoOffsetY: 0          // Y轴偏移（相对于中心）

    // ⭐ 慢放回放窗口的 独立 局部缩放 (S+滚轮 触发, 鼠标位置为中心)
    //    跟实时流的 videoZoom 解耦 — 实时流缩放不再带动慢放, 慢放也不会反过来影响实时流.
    //    需求来源: 用户希望对回放画面单独放大查看细节, 同时实时流保持原视角.
    //    pcActivationLevel >= 2 才生效 (与原"慢放跟随放大"门槛一致).
    property real slowmoZoom: 1.0          // 慢放本地缩放 1.0 - 5.0
    property real slowmoOffsetX: 0
    property real slowmoOffsetY: 0
    
    // ⭐ 每个抓拍 item 的初始缩放（抓拍时保存当前 videoZoom）
    property var itemZoomMap: ({})         // { itemIndex: { zoom, offsetX, offsetY } }

    // ⭐ Ctrl + 滚轮/单击 → 整 grid 所有 item 同步动作（联动模式广播 signal）
    //   gridCell delegate 用 Connections 监听, 收到就对自己执行同样操作.
    //   适用于"几张牌同时翻帧"、"同时缩放对比"的场景.
    signal gridSyncFrameStep(string direction)                 // direction: "prev" / "next"
    signal gridSyncZoomDelta(real deltaZoom)                   // 同步缩放增量 (各 item 以自己中心缩放)
    signal gridSyncDrag(real dx, real dy)                      // 同步拖拽偏移
    signal gridSyncResetZoom()                                  // 同步重置缩放到 1.0
    
    // ⭐ 本地设置存储（持久化）
    Settings {
        id: appSettings
        property int screenshotQuality: 60  // 截图质量，默认60
        property real panelColorH: 0     // 面板颜色色相 (0-1)，默认90%白色
        property real panelColorS: 0     // 面板颜色饱和度 (0-1)，默认90%白色
        property real panelColorV: 0.9   // 面板颜色明度 (0-1)，默认90%白色
        property bool halfScreenViewMode: false  // 放大查看模式：false=全屏，true=半屏（覆盖截图view）
    }
    
    // ⭐ 面板背景色（使用完整 HSV）
    property color panelBgColor: Qt.hsva(appSettings.panelColorH, appSettings.panelColorS, appSettings.panelColorV, 1)
    
    // ⭐ 面板文字颜色（根据背景亮度自动选择）
    property color panelTextColor: {
        // 计算背景亮度
        var c = panelBgColor
        var luminance = 0.299 * c.r + 0.587 * c.g + 0.114 * c.b
        return luminance > 0.5 ? "#263238" : "#FFFFFF"
    }
    
    // 全屏查看相关属性
    property bool fullscreenViewerVisible: false
    property int fullscreenItemIndex: -1
    property int fullscreenFrameIndex: 0
    property real fullscreenZoom: 1.0
    property real fullscreenOffsetX: 0  // ⭐ 缩放偏移X
    property real fullscreenOffsetY: 0  // ⭐ 缩放偏移Y
    property int fullscreenRefreshToken: 0  // ⭐ 强制刷新令牌
    property int fullscreenViewerMode: 0  // ⭐ 0=全屏, 1=半屏（只覆盖截图区域）
    
    // ⭐ 列预览相关属性（数字键0-9触发，0代表第10列）
    property bool columnPreviewVisible: false
    property int columnPreviewCol: -1  // 当前预览的列号（0-based）
    property var columnPreviewItems: []  // 该列所有有数据的 dataIndex 列表
    property int columnPreviewRefreshToken: 0
    property var columnPreviewFrames: []   // 每张图的当前帧index
    property var columnPreviewZooms: []    // 每张图的缩放倍率
    property var columnPreviewOffsetX: []  // 每张图的X偏移
    property var columnPreviewOffsetY: []  // 每张图的Y偏移
    property bool columnPreviewStretch: true   // 拉伸开关（true=铺满，false=等比适应）
    property int columnPreviewHoveredIndex: -1 // 鼠标悬停的图片索引
    property int columnPreviewZoomItemIdx: -1  // A键放大的图片索引（-1=无）
    property int columnPreviewZoomFrame: 0     // A键放大图片的帧index
    property real columnPreviewZoomScale: 1.0  // A键放大图片的缩放
    property real columnPreviewZoomOffX: 0     // A键放大图片的X偏移
    property real columnPreviewZoomOffY: 0     // A键放大图片的Y偏移
    
    // S键按下状态（用于 S+滚轮 缩放）
    property bool sKeyPressed: false

    // ⭐ 上下帧跳跃步长 — F5=1 / F6=2 / F7=3 / F8=4. 单 item / Ctrl 同步 / 列预览 / 全屏 都生效, 不影响慢放
    property int frameStep: 1
    
    // 窗口布局模式
    // 0 = 默认：左侧抓拍grid，右侧上实时下慢放
    // 1 = 实时窗口切换：左侧实时流，右侧上抓拍grid下慢放
    // 2 = 慢放窗口切换：左侧慢放，右侧上实时下抓拍grid
    property int windowLayoutMode: 0
    
    // Grid全屏模式（左侧占满宽度，右侧隐藏）
    property bool gridFullscreenMode: false
    
    // 抓拍全屏开关（当抓拍个数达到行×列时自动全屏）
    property bool autoFullscreenOnCaptureFull: false
    
    // 设备状态（来自 CONFIG_STATE 消息）
    property int deviceKbps: 0              // 码率
    property int deviceBattery: -1          // 电量（-1表示未知）
    property string deviceNetworkQuality: ""  // 网络质量（excellent/good/fair/poor）
    property string deviceNetworkType: ""   // 网络类型（WiFi/5G等）
    
    // 会员等级控制（来自 CONFIG_STATE 消息）
    property bool memberActivated: false           // 是否已激活
    property int memberActivationLevel: 0          // 激活等级 (0=试用全开放, 1=高清, 2=超清, 3=超高帧, 4=超超清)
    property string memberActivationLevelName: ""  // 等级名称
    property var levelFps: [240, 120, 180, 180, 240]  // ⭐ 各等级FPS上限（从登录接口获取，下标0=试用,1=高清,2=超清,3=超高清,4=超高帧）
    property var levelExposureFps: [600, 120, 180, 240, 600]  // ⭐ 各等级超级帧率上限（从登录接口获取，下标0=试用,1=高清,2=超清,3=超高清,4=超高帧）
    property var memberQualityAccess: []           // 可用画质列表
    property bool isDailyTrial: false              // 是否日试用
    property int activationRemainingSeconds: 0     // 剩余有效秒数
    property bool highSpeed240Enabled: false       // 240fps高速模式开关（从登录接口获取）
    
    // 保存右侧上下分割的高度比例 (topHeight / totalHeight)
    property real savedHeightRatio: 0.5
    
    // ⭐ 保存左右分割的宽度比例 (rightPanelWidth / totalWidth)
    property real savedWidthRatio: 0.25  // 默认右侧占25%
    
    // ⭐ 标记是否正在恢复比例（避免在恢复时触发保存）
    property bool isRestoringRatio: false
    property bool isRestoringWidthRatio: false  // 标记是否正在恢复宽度比例
    
    Timer {
        id: gridUpdateTimer
        interval: 100
        onTriggered: {
            if (pendingRows !== captureManager.gridRows) {
                captureManager.gridRows = pendingRows
            }
            if (pendingCols !== captureManager.gridCols) {
                captureManager.gridCols = pendingCols
            }
        }
    }
    
    // ⭐ 防抖保存高度比例的 Timer（用户拖动后延迟保存）
    Timer {
        id: saveHeightRatioTimer
        interval: 300  // 300ms 防抖
        onTriggered: {
            if (!gridFullscreenMode && !isRestoringRatio) {
                var topH = rightTopHolder.height
                var middleH = rightMiddleHolder.height
                var total = topH + middleH
                if (total > 0) {
                    savedHeightRatio = topH / total
                    console.log("💾 用户拖动后自动保存高度比例:", savedHeightRatio)
                }
            }
        }
    }
    
    // ⭐ 防抖保存宽度比例的 Timer（用户拖动后延迟保存）
    Timer {
        id: saveWidthRatioTimer
        interval: 300  // 300ms 防抖
        onTriggered: {
            if (!gridFullscreenMode && !isRestoringWidthRatio && rightPanel.width > 0 && mainSplitView.width > 0) {
                savedWidthRatio = rightPanel.width / mainSplitView.width
                console.log("💾 用户拖动后自动保存宽度比例:", savedWidthRatio, "rightPanel=", rightPanel.width, "total=", mainSplitView.width)
            }
        }
    }
    
    // FPS 显示（来自 GstPlayer 统计的实际接收帧率，已 x4）
    Item {
        id: fpsRow
        property int displayFps: gstPlayer.receiveFps
    }

    // 拉流心跳：每秒通知 iOS"我在看"（基于画面是否显示）
    Timer {
        id: viewerHeartbeatTimer
        interval: 1000
        repeats: true
        running: true
        onTriggered: {
            // 只在画面实际显示时发送（receiveFps > 0）
            if (gstPlayer.receiveFps > 0) {
                var deviceId = HttpClient.currentDeviceId()
                if (!deviceId) return
                var payload = {
                    "type": "VIEWER_HEARTBEAT",
                    "deviceId": deviceId,
                    "fps": gstPlayer.receiveFps,
                    "timestamp": Date.now()
                }
                var destination = "/topic/device/" + deviceId + "/config"
                WebSocketClient.sendMessageJson(destination, JSON.stringify(payload))
            }
        }
    }

    // ============ 核心组件 ============
    
    GpuPipeline {
        id: gpuPipeline
        Component.onCompleted: {
            console.log("📦 GpuPipeline: Component.onCompleted 开始初始化...")
            
            // 关联 captureManager 和 slowMotionPlayer
            captureManager.slowMotionPlayer = slowMotionPlayer
            
            if (init()) {
                console.log("✅ GPU Pipeline initialized:", status)
            } else {
                console.log("❌ GPU Pipeline init failed:", status)
            }
        }
        onKeyframeNeeded: { gstPlayer.requestKeyFrame() }  // ⭐ 改用 GstPlayer
        onFrameReady: function(frameIndex) {
            if (frameIndex % 30 === 0) {
                liveInfoFps.text = "FPS: 60 | Frame: " + frameIndex
            }
            captureManager.onFrameIndexReady(frameIndex)
        }
        onJpegEncoderError: function(message) {
            errorDialog.text = message
            errorDialog.open()
        }
        onJpegDecoderError: function(message) {
            errorDialog.text = message
            errorDialog.open()
        }
    }
    
    Dialog {
        id: errorDialog
        property alias text: errorText.text
        title: "硬件加速不可用"
        modal: true
        anchors.centerIn: parent
        standardButtons: Dialog.Ok
        
        Label {
            id: errorText
            wrapMode: Text.WordWrap
            width: 400
        }
        
        onAccepted: Qt.quit()
    }
    
    // ⭐ WebRTCClient 已废弃，改用 GstPlayer.connectWebRTC()
    // WebRTC 功能现在集成在 GstPlayer 中，使用 GStreamer WebRTCBin
    // WebRTCClient {
    //     id: webrtcClient
    //     ...
    // }
    
    // ⭐ 为了兼容旧代码，创建一个 webrtcClient 别名
    QtObject {
        id: webrtcClient
        function connect(host, app, stream) {
            gstPlayer.connectWebRTC(host, app, stream)
        }
        function disconnect() {
            gstPlayer.disconnectWebRTC()
        }
        function isConnected() {
            return gstPlayer.isWebRTCConnected()
        }
        function requestKeyFrame() {
            gstPlayer.requestKeyFrame()
        }
    }
    
    // GStreamer 播放器（d3d11h264dec 硬解 + WebRTCBin，所有 Windows PC 兼容）
    GstPlayer {
        id: gstPlayer
        onFirstFrameReceived: {
            console.log("🎬 GstPlayer 首帧已接收")
            if (pairedIosDeviceId && pairedIosDeviceId.length > 0) {
                WebSocketClient.sendWebRTCSignaling("VIEWER_CONNECTED", pairedIosDeviceId)
                console.log("📤 通知 iOS PC 已收到画面: " + pairedIosDeviceId)
            }
        }
        onVideoSizeChanged: {
            console.log("🎬 GstPlayer 分辨率:", videoWidth, "x", videoHeight)
        }
        onError: function(message) {
            console.log("❌ GstPlayer 错误:", message)
            statusText.text = "GStreamer 错误: " + message
            // 🔥 v14: 发生错误时清除连接中标志，允许重试
            isConnecting = false
        }
        // ⭐ WebRTC 信号（替代 WebRTCClient）
        onWebrtcConnected: {
            console.log("✅ WebRTC 已连接 (GStreamer WebRTCBin)")
            statusText.text = "WebRTC 已连接 (GStreamer WebRTCBin)"
            isConnecting = false  // 🔥 v14: 连接成功，清除标志
            // ⭐ 不再 reset（会清空抓拍列表），断线重连后保留之前的抓拍
            // captureManager.reset()
        }
        onWebrtcDisconnected: {
            console.log("🔌 WebRTC 已断开")
            statusText.text = "WebRTC 已断开"
            if (pairedIosDeviceId && pairedIosDeviceId.length > 0) {
                WebSocketClient.sendWebRTCSignaling("VIEWER_DISCONNECTED", pairedIosDeviceId)
                console.log("📤 通知 iOS PC 已断开: " + pairedIosDeviceId)
            }
            // 🔥 v14: 只在非连接过程中才重置 publishState（避免 stopAll 期间重置导致死循环）
            if (!isConnecting) {
                publishState = 0
            }
        }
        onWebrtcStatusChanged: function(status) {
            console.log("🌐 WebRTC 状态:", status)
            // 🔥 v14: 连接成功或正在播放时清除 isConnecting 标志
            if (status === "Connected" || status === "Playing" || status === "P2P Connected") {
                isConnecting = false
            }
            // 🔥 v14: 只在非连接过程中才重置 publishState（避免死循环）
            if (status === "Failed" || status === "Closed" || status === "Disconnected" || status === "P2P Hangup") {
                if (!isConnecting) {
                    console.log("🔄 WebRTC 状态异常，重置 publishState 以允许重连")
                    publishState = 0
                }
            }
        }
        
        // P2P 信令信号桥接 → WebSocket
        onSendSdpAnswer: function(sdp, toDevice) {
            console.log("[P2P-QML] 发送 Answer SDP 给 " + toDevice)
            WebSocketClient.sendWebRTCSignaling("WEBRTC_SDP", toDevice, "answer", sdp)
        }
        onSendIceCandidate: function(candidate, sdpMid, sdpMLineIndex, toDevice) {
            console.log("[P2P-QML] 发送 ICE 候选者给 " + toDevice)
            WebSocketClient.sendWebRTCSignaling("WEBRTC_ICE", toDevice, "", "", candidate, sdpMid, sdpMLineIndex)
        }
        onSendHangup: function(reason, toDevice) {
            console.log("[P2P-QML] 发送挂断给 " + toDevice)
            WebSocketClient.sendWebRTCSignaling("WEBRTC_HANGUP", toDevice, "", "", "", "", -1, reason)
        }
        onSendViewRequest: function(toDevice) {
            console.log("[P2P-QML] 发送观看请求给 " + toDevice)
            WebSocketClient.sendWebRTCSignaling("WEBRTC_REQUEST", toDevice)
        }
        
        // ⭐⭐⭐ 第二道防线：收到降帧请求，通知前端iOS调整推流帧率
        // v9.3 新增：urgency 紧急度 + reason 触发原因
        // v9.3 优化：根据【会员等级】+【当前档位】来决定可用的帧率档位
        // 
        // 帧率上限规则：
        //   4K档位：任意等级 → 最大120（对应30fps）
        //   其他档位 + 等级1(高清)：最大120（对应30fps）
        //   其他档位 + 等级2(超清)：最大180（对应45fps）
        //   其他档位 + 等级3(超高帧)：最大180（对应45fps）
        //   其他档位 + 等级4(超超清)：最大240（对应60fps）
        //   试用/日试用：最大240（对应60fps）
        //
        // 四档阶梯（服务器格式）：240(60fps) → 180(45fps) → 120(30fps) → 60(15fps)
        onRequestFpsChange: function(targetFps, urgency, reason) {
            // ⭐ 获取当前档位和会员等级信息
            var qualityType = iosCameraSettingsPopup.qualityType
            var level = mainPage.memberActivationLevel
            var maxFps = getMaxFpsForQuality(qualityType)
            var currentFps = iosCameraSettingsPopup.fpsValue  // 当前滑块值（服务器fps格式）
            
            console.log("📉 收到帧率调整请求:", targetFps, "fps | urgency=" + urgency + 
                       " | 档位=" + qualityType + " 等级=" + level + " 上限=" + maxFps)
            
            // ⭐ 根据等级和档位生成可用的四档阶梯
            // 服务器格式：240=60fps, 180=45fps, 120=30fps, 60=15fps
            var allTiers = [240, 180, 120, 60]
            var availableTiers = allTiers.filter(function(tier) { return tier <= maxFps })
            
            // 确保至少有最低档
            if (availableTiers.length === 0) {
                availableTiers = [60]
            }
            
            console.log("📊 可用档位:", JSON.stringify(availableTiers), 
                       "(最高=" + availableTiers[0] + " 最低=" + availableTiers[availableTiers.length - 1] + ")")
            
            var finalFps = currentFps
            
            if (targetFps === 0) {
                // ⭐ 恢复帧率：升到【当前等级+档位】允许的最高档
                finalFps = availableTiers[0]
                console.log("📈 恢复帧率: 当前=" + currentFps + " → 最高档=" + finalFps + 
                           " (等级" + level + " " + qualityType + "档位上限=" + maxFps + ")")
            } else {
                // ⭐ 降帧请求：targetFps 是实际帧率（如 30fps）
                var requestedServerFps = targetFps * 4  // 转换为服务器格式
                
                // 找到 <= requestedServerFps 的最高档位
                finalFps = availableTiers[availableTiers.length - 1]  // 默认最低档
                for (var i = 0; i < availableTiers.length; i++) {
                    if (availableTiers[i] <= requestedServerFps) {
                        finalFps = availableTiers[i]
                        break
                    }
                }
                
                // 确保不低于当前档位的最低值
                if (finalFps > currentFps) {
                    // 如果计算出的帧率比当前还高，说明要降帧，找下一档
                    var currentIndex = availableTiers.indexOf(currentFps)
                    if (currentIndex >= 0 && currentIndex < availableTiers.length - 1) {
                        finalFps = availableTiers[currentIndex + 1]  // 降一档
                    } else {
                        finalFps = availableTiers[availableTiers.length - 1]  // 最低档
                    }
                }
                
                console.log("📉 降帧: 目标=" + targetFps + "fps(服务器=" + requestedServerFps + 
                           ") 当前=" + currentFps + " → 最终=" + finalFps +
                           " | 等级" + level + " " + qualityType + "档")
            }
            
            // 如果帧率没变且不是恢复请求，不发送
            if (finalFps === currentFps && targetFps !== 0) {
                console.log("⏸️ 帧率未变化(" + finalFps + ")，跳过发送")
                return
            }
            
            // ⭐⭐⭐ v9.3 发送带 urgency 的 set_fps 命令
            var fpsPayload = {
                "cmd": "set_fps",
                "fps": finalFps,  // 服务器 fps 格式
                "urgency": urgency || "normal",
                "reason": reason || "manual",
                "timestamp": Date.now()
            }
            
            // 更新本地状态
            iosCameraSettingsPopup.fpsValue = finalFps
            fpsSlider.value = finalFps
            
            // ⭐ v9.3: 同步帧率给 gstPlayer（用于网络质量检测）
            gstPlayer.setConfigFps(finalFps / 4)  // 服务器fps转实际fps
            
            // 通过 HTTP 和 WebSocket 发送
            HttpClient.updateFps(finalFps)
            sendConfigUpdate("fps", fpsPayload)
            console.log("📤 已发送set_fps到iOS:", JSON.stringify(fpsPayload), 
                       "| 等级" + level + " " + qualityType + "档")
        }
    }
    
    CaptureManager {
        id: captureManager
        objectName: "captureManager"
        gpuPipeline: gpuPipeline
        gstPlayer: gstPlayer  // GStreamer 播放器（JPEG 读取）
        videoRotation: mainPage.videoRotation  // 同步视频旋转角度
        videoZoom: mainPage.videoZoom          // 同步视频缩放
        videoOffsetX: mainPage.videoOffsetX    // 同步缩放偏移X
        videoOffsetY: mainPage.videoOffsetY    // 同步缩放偏移Y
        displayWidth: videoContainer.width     // 显示区域宽度
        displayHeight: videoContainer.height   // 显示区域高度
        onGridRowsChanged: rowsInput.currentIndex = gridRows - 1
        onGridColsChanged: colsInput.currentIndex = gridCols - 1
        onPreFrameCountChanged: {
            // model: ["10", "15", "20", "30", "40", "50", "60", "80", "100", "120"]  最大120
            var map = {"10": 0, "15": 1, "20": 2, "30": 3, "40": 4, "50": 5, "60": 6, "80": 7, "100": 8, "120": 9}
            preFramesInput.currentIndex = map[preFrameCount.toString()] ?? 9  // 默认120
        }
        onPostFrameCountChanged: {
            var map = {"10": 0, "15": 1, "20": 2, "30": 3, "40": 4, "50": 5, "60": 6, "80": 7, "100": 8, "120": 9, "150": 10, "180": 11, "200": 12, "240": 13, "1000": 14}
            postFramesInput.currentIndex = map[postFrameCount.toString()] ?? 0
        }
    }
    
    // ============ EventBus 连接（因为通过 Loader 加载，需要在这里连接）============
    Connections {
        target: EventBus
        function onCaptureTriggered() {
            // ⭐ 抓拍时保存当前缩放状态，新 item 将继承这个缩放
            // ⭐ PC等级1(豪华版)：不保存缩放，截图item始终1倍
            var nextIndex = captureManager.count  // 下一个 item 的索引
            var newMap = mainPage.itemZoomMap
            if (mainPage.pcActivationLevel >= 2) {
                newMap[nextIndex] = {
                    zoom: mainPage.videoZoom,
                    offsetX: mainPage.videoOffsetX,
                    offsetY: mainPage.videoOffsetY
                }
            } else {
                newMap[nextIndex] = { zoom: 1.0, offsetX: 0, offsetY: 0 }
            }
            mainPage.itemZoomMap = newMap
            captureManager.zoomLog("📸 抓拍保存: index=" + nextIndex + " zoom=" + mainPage.videoZoom + " offsetX=" + mainPage.videoOffsetX + " offsetY=" + mainPage.videoOffsetY + " pcLevel=" + mainPage.pcActivationLevel)
            captureManager.zoomLog("📸 itemZoomMap: " + JSON.stringify(mainPage.itemZoomMap))
            
            captureManager.capture()
        }
        function onClearTriggered() {
            // ⭐ 清空缩放记录
            mainPage.itemZoomMap = {}
            
            // 先退出抓拍全屏状态
            if (mainPage.gridFullscreenMode) {
                mainPage.gridFullscreenMode = false
                console.log("🖥️ 抓拍清空：退出抓拍grid全屏")
            }
            // 如果处于单个抓拍项全屏查看状态，也关闭
            if (fullscreenViewerVisible) {
                closeFullscreenViewer()
                console.log("🖥️ 抓拍清空：关闭全屏查看")
            }
            // ⭐ 如果列查看器打开，也关闭
            if (columnPreviewVisible) {
                columnPreviewVisible = false
                console.log("🖥️ 抓拍清空：关闭列查看器")
            }
            // 弹框确认
            if (captureManager.count > 0) {
                clearCaptureConfirmDialog.open()
            }
        }
    }
    
    // 监听抓拍完成事件，检查是否需要自动全屏
    Connections {
        target: captureManager
        function onCaptureComplete(index) {
            // 抓拍完成后检查是否需要自动全屏
            // ⭐ PC等级2(至尊版)才能自动触发抓拍全屏，pc=1不允许自动触发
            console.log("[抓拍全屏] onCaptureComplete: autoFullscreenOnCaptureFull=" + mainPage.autoFullscreenOnCaptureFull + ", count=" + captureManager.count + ", pcLevel=" + mainPage.pcActivationLevel)
            
            // PC等级检查：只有pc=2才能自动触发
            if (mainPage.pcActivationLevel < 2) {
                console.log("[抓拍全屏] PC等级1不允许自动触发抓拍全屏")
                return
            }
            
            if (mainPage.autoFullscreenOnCaptureFull) {
                var targetCount = captureManager.gridRows * captureManager.gridCols
                console.log("[抓拍全屏] 目标数量: " + targetCount + ", 当前数量: " + captureManager.count)
                
                if (captureManager.count === targetCount && targetCount > 0) {
                    if (!mainPage.gridFullscreenMode) {
                        console.log("[抓拍全屏] 准备自动全屏，当前 gridFullscreenMode=" + mainPage.gridFullscreenMode + ", pcLevel=" + mainPage.pcActivationLevel)
                        mainPage.gridFullscreenMode = true
                        console.log("[抓拍全屏] 达到", targetCount, "个，自动全屏成功 (pcLevel=" + mainPage.pcActivationLevel + ", gridFullscreenMode=" + mainPage.gridFullscreenMode + ")")
                    } else {
                        console.log("[抓拍全屏] 已经处于全屏模式，跳过")
                    }
                } else {
                    console.log("[抓拍全屏] 数量未达到目标: " + captureManager.count + " != " + targetCount)
                }
            } else {
                console.log("[抓拍全屏] 自动全屏开关未开启")
            }
        }
    }
    
    SlowMotionPlayer {
        id: slowMotionPlayer
        objectName: "slowMotionPlayer"
        gpuPipeline: gpuPipeline
        gstPlayer: gstPlayer  // GStreamer 播放器（JPEG 读取）
        // 不再需要 onFrameReady，Image 通过 ImageProvider 自动获取帧
    }
    
    // EventBus 连接 SlowMotionPlayer
    Connections {
        target: EventBus
        function onSlowmoToggleTriggered() {
            // 根据状态切换
            if (slowMotionPlayer.state === SlowMotionPlayer.IDLE) {
                slowMotionPlayer.startRecording()
                captureManager.slowMotionActive = true  // 开启慢放抓拍模式
            } else if (slowMotionPlayer.state === SlowMotionPlayer.RECORDING) {
                slowMotionPlayer.stopRecording()
            } else {
                slowMotionPlayer.togglePlay()
            }
        }
        function onNextFrameTriggered() {
            slowMotionPlayer.nextFrame()
        }
        function onPrevFrameTriggered() {
            slowMotionPlayer.prevFrame()
        }
    }

    // ============ 顶部菜单栏 ============
    Rectangle {
        id: topMenuBar
        anchors.top: parent.top
        anchors.left: parent.left
        anchors.right: parent.right
        height: 56
        color: mainPage.pcActivationLevel >= 2 ? "#C8DFC0" : "#CAD9F2"  // 等级2绿色，等级1蓝色
        
        // 窗口拖动区域（z=0，在菜单项之下）
        MouseArea {
            id: dragArea
            anchors.fill: parent
            z: 0
            property point clickPos: Qt.point(0, 0)
            
            onPressed: function(mouse) {
                mainPage.forceActiveFocus()  // ⭐ 点击导航栏时恢复焦点
                clickPos = Qt.point(mouse.x, mouse.y)
            }
            
            onPositionChanged: function(mouse) {
                if (pressed) {
                    var delta = Qt.point(mouse.x - clickPos.x, mouse.y - clickPos.y)
                    var newX = mainWindow.x + delta.x
                    var newY = mainWindow.y + delta.y
                    
                    // ⭐ 边界限制：确保窗口不会完全移出屏幕
                    // 至少保留 100 像素在屏幕内
                    var minVisible = 100
                    newX = Math.max(-mainWindow.width + minVisible, Math.min(newX, Screen.width - minVisible))
                    newY = Math.max(0, Math.min(newY, Screen.height - minVisible))  // 顶部不能超出
                    
                    mainWindow.x = newX
                    mainWindow.y = newY
                }
            }
            
            onDoubleClicked: toggleFullscreen()
        }
        
        RowLayout {
            z: 1  // 在拖动区域之上
            anchors.fill: parent
            anchors.leftMargin: 48
            anchors.rightMargin: 10  // 减小右边距，让头像能靠近右边缘
            spacing: 0
            
            // ===== 左侧菜单项 =====
            RowLayout {
                Layout.fillHeight: true
                spacing: 36
                
                // 窗口布局下拉菜单
                Text {
                    id: windowLayoutText
                    text: "窗口布局 ▼"
                    font.family: "PingFang HK"
                    font.pixelSize: 14
                    color: "#263238"
                    
                    MouseArea {
                        anchors.fill: parent
                        cursorShape: Qt.PointingHandCursor
                        onClicked: windowLayoutMenu.open()
                    }
                    
                    Menu {
                        id: windowLayoutMenu
                        y: windowLayoutText.height + 4
                        width: 200
                        
                        // ⭐ 抓拍全屏菜单项：等级1不显示，等级2才显示
                        // 使用 Repeater 条件创建菜单项
                        Repeater {
                            model: mainPage.pcActivationLevel >= 2 ? 1 : 0
                            delegate: MenuItem {
                                text: "抓拍全屏 (" + ShortcutStore.gridFullscreenKey + ")"
                                onTriggered: toggleGridFullscreen()
                                
                                Component.onCompleted: {
                                    console.log("[抓拍全屏] Repeater 创建菜单项，pcActivationLevel:", mainPage.pcActivationLevel)
                                }
                            }
                        }
                        
                        MenuItem {
                            text: "实时窗口切换 (" + ShortcutStore.realtimeWindowKey + ")"
                            onTriggered: swapRealtimeWindow()
                        }
                        MenuItem {
                            text: "慢放窗口切换 (" + ShortcutStore.slowmoWindowKey + ")"
                            onTriggered: swapSlowmoWindow()
                        }
                        MenuSeparator { }
                        MenuItem {
                            text: "快捷键说明"
                            onTriggered: shortcutHelpPopup.open()
                        }
                    }
                }
                
                // 设备绑定
                Text {
                    id: deviceBindText
                    text: "设备绑定"
                    font.family: "PingFang HK"
                    font.pixelSize: 14
                    color: "#263238"
                    
                    MouseArea {
                        anchors.fill: parent
                        cursorShape: Qt.PointingHandCursor
                        onClicked: bindMenu.open()
                    }
                    
                    // 绑定菜单
                    Menu {
                        id: bindMenu
                        y: deviceBindText.height + 4
                        width: 80
                        
                        MenuItem {
                            text: "扫码绑定"
                            onTriggered: showScanBindPopup()
                        }
                        MenuItem {
                            text: "手动绑定"
                            onTriggered: manualBindDialog.open()
                        }
                    }
                }
                
                // 相机设定
                Text {
                    id: cameraSettingText
                    text: "相机设定(R)"
                    font.family: "PingFang HK"
                    font.pixelSize: 14
                    color: "#263238"

                    MouseArea {
                        anchors.fill: parent
                        cursorShape: Qt.PointingHandCursor
                        onClicked: showIosCameraSettings()
                    }
                }

                // ⭐ iOS 滤镜入口已隐藏 — 快捷键 P 替代菜单项, 见 Shortcut "P"

                // 抓拍全屏开关
                Row {
                    spacing: 6
                    
                    Text {
                        text: "抓拍全屏"
                        font.family: "PingFang HK"
                        font.pixelSize: 14
                        color: "#263238"
                        anchors.verticalCenter: parent.verticalCenter
                    }
                    
                    Rectangle {
                        id: autoFullscreenSwitch
                        width: 40
                        height: 22
                        radius: 11
                        color: mainPage.autoFullscreenOnCaptureFull ? "#4CAF50" : "#90A4AE"
                        anchors.verticalCenter: parent.verticalCenter
                        
                        Rectangle {
                            width: 18
                            height: 18
                            radius: 9
                            color: "#FFFFFF"
                            x: mainPage.autoFullscreenOnCaptureFull ? parent.width - width - 2 : 2
                            anchors.verticalCenter: parent.verticalCenter
                            
                            Behavior on x { NumberAnimation { duration: 150 } }
                        }
                        
                        MouseArea {
                            anchors.fill: parent
                            cursorShape: Qt.PointingHandCursor
                            onClicked: mainPage.autoFullscreenOnCaptureFull = !mainPage.autoFullscreenOnCaptureFull
                        }
                    }
                }
                
                // 横向/纵向切换
                Text {
                    text: captureManager.isHorizontalLayout ? "横向" : "纵向"
                    font.family: "PingFang HK"
                    font.pixelSize: 14
                    color: "#263238"
                    
                    MouseArea {
                        anchors.fill: parent
                        cursorShape: Qt.PointingHandCursor
                        onClicked: captureManager.isHorizontalLayout = !captureManager.isHorizontalLayout
                    }
                }
                
                // 截图质量下拉列表
                Row {
                    spacing: 4
                    height: parent.height
                    
                    Text {
                        id: jpegQualityLabel
                        text: "截图质量"
                        font.family: "PingFang HK"
                        font.pixelSize: 14
                        color: "#263238"
                        anchors.verticalCenter: parent.verticalCenter
                        
                        MouseArea {
                            anchors.fill: parent
                            hoverEnabled: true
                            ToolTip.visible: containsMouse
                            ToolTip.text: "质量越高 电脑配置越高"
                            ToolTip.delay: 300
                        }
                    }
                    
                    ComboBox {
                        id: jpegQualityCombo
                        width: 55
                        height: 22
                        anchors.verticalCenter: parent.verticalCenter
                        
                        // 至尊版(2): 60-100 步进5；豪华版(1)及以下: 只有60
                        model: mainPage.pcActivationLevel >= 2 ? [60, 65, 70, 75, 80, 85, 90, 95, 100] : [60]
                        
                        // ⭐ 从本地设置读取对应 index
                        currentIndex: {
                            if (mainPage.pcActivationLevel >= 2) {
                                // 至尊版：计算 index: (value - 60) / 5，确保在有效范围
                                var savedQ = Math.max(60, Math.min(100, appSettings.screenshotQuality))
                                return Math.max(0, Math.min(8, (savedQ - 60) / 5))
                            } else {
                                return 0  // 豪华版只有60，固定index 0
                            }
                        }
                        
                        // 豪华版禁用下拉（只有一个选项）
                        enabled: mainPage.pcActivationLevel >= 2
                        
                        Component.onCompleted: {
                            // 启动时应用保存的截图质量（强制最低60）
                            var quality = Math.max(60, appSettings.screenshotQuality)
                            gstPlayer.setJpegQuality(quality)
                            appSettings.screenshotQuality = quality
                            console.log("📸 截图质量已恢复:", quality)
                        }
                        
                        onCurrentValueChanged: {
                            gstPlayer.setJpegQuality(currentValue)
                            // ⭐ 保存到本地设置
                            appSettings.screenshotQuality = currentValue
                            console.log("📸 截图质量调整为:", currentValue, "(已保存)")
                        }
                        
                        // 自定义外观
                        background: Rectangle {
                            color: jpegQualityCombo.down ? "#C8E6C9" : "#E8F5E9"
                            border.color: "#A5D6A7"
                            border.width: 1
                            radius: 3
                        }
                        
                        contentItem: Text {
                            leftPadding: 6
                            text: jpegQualityCombo.displayText
                            font.family: "Consolas"
                            font.pixelSize: 12
                            color: "#263238"
                            verticalAlignment: Text.AlignVCenter
                        }
                        
                        indicator: Text {
                            x: jpegQualityCombo.width - width - 6
                            y: (jpegQualityCombo.height - height) / 2
                            text: "▼"
                            font.pixelSize: 8
                            color: "#546E7A"
                        }
                    }
                }
                
                // ⭐ 放大查看模式开关（A键放大）
                Row {
                    spacing: 4
                    height: parent.height
                    
                    Text {
                        text: "半屏"
                        font.family: "PingFang HK"
                        font.pixelSize: 14
                        color: "#263238"
                        anchors.verticalCenter: parent.verticalCenter
                        
                        MouseArea {
                            anchors.fill: parent
                            hoverEnabled: true
                            ToolTip.visible: containsMouse
                            ToolTip.text: "A键放大查看模式：关闭=全屏，打开=覆盖截图区域"
                            ToolTip.delay: 300
                        }
                    }
                    
                    // 开关控件
                    Rectangle {
                        id: halfScreenSwitch
                        width: 36
                        height: 18
                        radius: 9
                        anchors.verticalCenter: parent.verticalCenter
                        color: appSettings.halfScreenViewMode ? "#4CAF50" : "#B0BEC5"
                        
                        Rectangle {
                            width: 14
                            height: 14
                            radius: 7
                            color: "#FFFFFF"
                            x: appSettings.halfScreenViewMode ? parent.width - width - 2 : 2
                            anchors.verticalCenter: parent.verticalCenter
                            
                            Behavior on x { NumberAnimation { duration: 150 } }
                        }
                        
                        MouseArea {
                            anchors.fill: parent
                            cursorShape: Qt.PointingHandCursor
                            onClicked: {
                                appSettings.halfScreenViewMode = !appSettings.halfScreenViewMode
                                console.log("📺 放大查看模式:", appSettings.halfScreenViewMode ? "半屏" : "全屏")
                            }
                        }
                    }
                }
                
                // ⭐ 面板颜色调节（点击打开 PS 风格颜色选择器）
                Row {
                    spacing: 8
                    height: parent.height
                    
                    Text {
                        text: "面板色"
                        font.family: "PingFang HK"
                        font.pixelSize: 14
                        color: "#263238"
                        anchors.verticalCenter: parent.verticalCenter
                    }
                    
                    // 颜色预览方块（点击打开选择器）
                    Rectangle {
                        id: panelColorPreview
                        width: 24
                        height: 24
                        radius: 4
                        color: panelBgColor
                        border.color: "#666666"
                        border.width: 1
                        anchors.verticalCenter: parent.verticalCenter
                        
                        MouseArea {
                            anchors.fill: parent
                            cursorShape: Qt.PointingHandCursor
                            hoverEnabled: true
                            onClicked: colorPickerPopup.open()
                            
                            ToolTip.visible: containsMouse
                            ToolTip.text: "点击选择面板颜色"
                            ToolTip.delay: 300
                        }
                    }
                }
                
                // ⭐ 快捷键说明按钮
                Rectangle {
                    width: shortcutBtnText.width + 16
                    height: 24
                    radius: 4
                    color: shortcutBtnArea.containsMouse ? "#C8E6C9" : "#E8F5E9"
                    border.color: "#A5D6A7"
                    border.width: 1
                    anchors.verticalCenter: parent.verticalCenter
                    
                    Text {
                        id: shortcutBtnText
                        anchors.centerIn: parent
                        text: "快捷键说明"
                        font.family: "PingFang HK"
                        font.pixelSize: 12
                        color: "#263238"
                    }
                    
                    MouseArea {
                        id: shortcutBtnArea
                        anchors.fill: parent
                        hoverEnabled: true
                        cursorShape: Qt.PointingHandCursor
                        onClicked: shortcutHelpPopup.open()
                    }
                }
                
                // ⭐ AI自动识别按钮
                Rectangle {
                    width: aiBtnText.width + 16
                    height: 24
                    radius: 4
                    color: aiBtnArea.containsMouse ? "#C8E6C9" : "#E8F5E9"
                    border.color: "#A5D6A7"
                    border.width: 1
                    anchors.verticalCenter: parent.verticalCenter
                    
                    Text {
                        id: aiBtnText
                        anchors.centerIn: parent
                        text: "AI自动识别"
                        font.family: "PingFang HK"
                        font.pixelSize: 12
                        color: "#263238"
                    }
                    
                    MouseArea {
                        id: aiBtnArea
                        anchors.fill: parent
                        hoverEnabled: true
                        cursorShape: Qt.PointingHandCursor
                        onClicked: {
                            aiComingSoonTip.visible = true
                            aiComingSoonTimer.restart()
                        }
                        
                        ToolTip.visible: containsMouse
                        ToolTip.text: "敬请期待"
                        ToolTip.delay: 300
                    }
                    
                    // "敬请期待"提示
                    Rectangle {
                        id: aiComingSoonTip
                        visible: false
                        width: aiTipText.width + 20
                        height: 28
                        radius: 6
                        color: "#333333"
                        anchors.top: parent.bottom
                        anchors.topMargin: 6
                        anchors.horizontalCenter: parent.horizontalCenter
                        
                        Text {
                            id: aiTipText
                            anchors.centerIn: parent
                            text: "敬请期待"
                            font.family: "PingFang HK"
                            font.pixelSize: 13
                            color: "#FFFFFF"
                        }
                        
                        Timer {
                            id: aiComingSoonTimer
                            interval: 2000
                            onTriggered: aiComingSoonTip.visible = false
                        }
                    }
                }
                
            }
            
            // 中间弹性空间
            Item { Layout.fillWidth: true }
            
            // ===== 右侧状态信息 =====
            Row {
                spacing: 16
                height: 32
                
                // ⭐ 缓冲队列显示（已隐藏）
                Row {
                    spacing: 2
                    height: parent.height
                    visible: false  // 不再显示
                    
                    Text {
                        text: gstPlayer.bufferSize + "/" + gstPlayer.bufferTarget
                        font.family: "Consolas"
                        font.pixelSize: 12
                        font.bold: true
                        // 颜色根据水位变化
                        color: {
                            var waterLevel = gstPlayer.bufferTarget > 0 ? gstPlayer.bufferSize / gstPlayer.bufferTarget : 1.0
                            if (waterLevel < 0.15) return "#dd0000"       // 红 - 紧急
                            else if (waterLevel < 0.35) return "#ff8800"  // 橙 - 恢复中
                            else if (waterLevel > 1.05) return "#0066ff"  // 蓝 - 追帧
                            else return "#00bb00"                         // 绿 - 正常
                        }
                        anchors.verticalCenter: parent.verticalCenter
                    }
                    Text {
                        text: "帧"
                        font.family: "PingFang HK"
                        font.pixelSize: 10
                        color: "#78909C"
                        anchors.verticalCenter: parent.verticalCenter
                    }
                }
                
                // 分隔线（缓冲队列和FPS之间，已隐藏）
                Rectangle { 
                    width: 1; height: 14; color: "#A5D6A7"
                    anchors.verticalCenter: parent.verticalCenter
                    visible: false  // 跟随缓冲队列一起隐藏
                }
                
                // FPS 显示
                Row {
                    spacing: 4
                    height: parent.height
                    Text {
                        text: mainPage.connectMode === 1 ? "P2P" : "SRS"
                        font.family: "Consolas"
                        font.pixelSize: 10
                        font.bold: true
                        color: mainPage.connectMode === 1 ? "#4CAF50" : "#FF9800"
                        anchors.verticalCenter: parent.verticalCenter
                    }
                    Text {
                        text: fpsRow.displayFps + ""
                        font.family: "Consolas"
                        font.pixelSize: 13
                        font.bold: true
                        // 颜色跟随网络质量变化
                        color: {
                            switch (mainPage.deviceNetworkQuality) {
                                case "excellent": return "#00bb00"  // 绿
                                case "good": return "#0066ff"       // 蓝
                                case "fair": return "#ff8800"       // 橙
                                case "poor": return "#dd0000"       // 红
                                default: return "#78909C"           // 灰
                            }
                        }
                        anchors.verticalCenter: parent.verticalCenter
                    }
                    Text {
                        text: "FPS"
                        font.family: "PingFang HK"
                        font.pixelSize: 11
                        color: "#546E7A"
                        anchors.verticalCenter: parent.verticalCenter
                    }
                }
                
                // 分隔线
                Rectangle { width: 1; height: 14; color: "#A5D6A7"; anchors.verticalCenter: parent.verticalCenter }
                
                // 码率显示 + 网络类型
                Row {
                    spacing: 4
                    height: parent.height
                    
                    // ⭐ 网络质量颜色（码率和网络类型共用）
                    property color networkColor: {
                        switch (mainPage.deviceNetworkQuality) {
                            case "excellent": return "#00bb00"  // 绿
                            case "good": return "#0066ff"       // 蓝
                            case "fair": return "#ff8800"       // 橙
                            case "poor": return "#dd0000"       // 红
                            default: return "#78909C"           // 灰
                        }
                    }
                    
                    Text {
                        text: mainPage.deviceKbps > 0 ? (mainPage.deviceKbps * 2) : "0"  // ⭐ x2 显示
                        font.family: "Consolas"
                        font.pixelSize: 13
                        font.bold: true
                        color: parent.networkColor
                        anchors.verticalCenter: parent.verticalCenter
                    }
                    Text {
                        text: "kb/s"
                        font.family: "PingFang HK"
                        font.pixelSize: 11
                        color: "#546E7A"
                        anchors.verticalCenter: parent.verticalCenter
                    }
                    // ⭐ 网络类型显示（颜色跟随码率）
                    Text {
                        text: mainPage.deviceNetworkType ? mainPage.deviceNetworkType : ""
                        font.family: "PingFang HK"
                        font.pixelSize: 11
                        font.bold: true
                        color: parent.networkColor
                        anchors.verticalCenter: parent.verticalCenter
                        visible: mainPage.deviceNetworkType !== ""
                    }
                }
                
                // 分隔线
                Rectangle { width: 1; height: 14; color: "#A5D6A7"; anchors.verticalCenter: parent.verticalCenter }
                
                // 电量显示
                Row {
                    spacing: 4
                    height: parent.height
                    Text {
                        text: mainPage.deviceBattery >= 0 ? mainPage.deviceBattery + "%" : "-"
                        font.family: "Consolas"
                        font.pixelSize: 13
                        font.bold: true
                        // 电量颜色：<20红色，20-50橙色，>50绿色
                        color: {
                            if (mainPage.deviceBattery < 0) return "#90A4AE"  // 未知灰色
                            if (mainPage.deviceBattery < 20) return "#dd0000"  // 红色危险
                            if (mainPage.deviceBattery <= 50) return "#ff8800"  // 橙色警告
                            return "#00bb00"  // 绿色正常
                        }
                        anchors.verticalCenter: parent.verticalCenter
                    }
                    Text {
                        text: "电量"
                        font.family: "PingFang HK"
                        font.pixelSize: 11
                        color: "#546E7A"
                        anchors.verticalCenter: parent.verticalCenter
                    }
                }
                
                // 分隔线
                Rectangle { width: 1; height: 14; color: "#A5D6A7"; anchors.verticalCenter: parent.verticalCenter }
                
                // 全屏按钮（替换原网络质量显示）
                Rectangle {
                    width: fullscreenBtnText.width + 16
                    height: 22
                    radius: 4
                    color: fullscreenBtnArea.containsMouse ? "#C8E6C9" : "#E8F5E9"
                    anchors.verticalCenter: parent.verticalCenter
                    
                    Text {
                        id: fullscreenBtnText
                        anchors.centerIn: parent
                        text: (mainWindow.visibility === Window.Maximized || mainWindow.visibility === Window.FullScreen) ? "退出全屏" : "全屏"
                        font.family: "PingFang HK"
                        font.pixelSize: 12
                        font.bold: true
                        color: "#263238"
                    }
                    
                    MouseArea {
                        id: fullscreenBtnArea
                        anchors.fill: parent
                        hoverEnabled: true
                        cursorShape: Qt.PointingHandCursor
                        onClicked: {
                            if (mainWindow.visibility === Window.Maximized || mainWindow.visibility === Window.FullScreen) {
                                mainWindow.showNormal()
                            } else {
                                mainWindow.showMaximized()
                            }
                        }
                    }
                }
            }
            
            Item { width: 8 }

            // 版本号
            Text {
                text: "v" + AutoUpdater.currentVersion
                font.family: "Consolas"
                font.pixelSize: 11
                color: "#78909C"
                anchors.verticalCenter: parent.verticalCenter
            }

            Item { width: 4 }

            // ===== 等级信息 + 到期天数（点击弹出版本说明）=====
            Text {
                visible: mainPage.pcActivationLevel >= 1
                text: {
                    var name = mainPage.pcLevelName || (mainPage.pcActivationLevel >= 2 ? "至尊版" : "豪华版")
                    if (mainPage.pcActivationLevel >= 2 && mainPage.pcExpireAt && mainPage.pcExpireAt !== "" && mainPage.pcExpireAt !== "null") {
                        var expDate = new Date(mainPage.pcExpireAt)
                        var now = new Date()
                        var daysLeft = Math.ceil((expDate - now) / (1000 * 60 * 60 * 24))
                        if (daysLeft < 0) return name + " 已到期"
                        if (daysLeft === 0) return name + " 今天到期"
                        return name + " " + daysLeft + "天"
                    }
                    return name
                }
                font.family: "PingFang HK"
                font.pixelSize: 11
                font.weight: Font.Medium
                color: {
                    if (mainPage.pcActivationLevel >= 2 && mainPage.pcExpireAt && mainPage.pcExpireAt !== "" && mainPage.pcExpireAt !== "null") {
                        var expDate2 = new Date(mainPage.pcExpireAt)
                        var now2 = new Date()
                        var days = Math.ceil((expDate2 - now2) / (1000 * 60 * 60 * 24))
                        if (days <= 7) return "#D32F2F"
                        if (days <= 30) return "#E65100"
                    }
                    return "#37474F"
                }
                anchors.verticalCenter: parent.verticalCenter
                
                MouseArea {
                    id: levelInfoMouse
                    anchors.fill: parent
                    hoverEnabled: true
                    cursorShape: Qt.PointingHandCursor
                    onClicked: versionCompareDialog.open()
                }
            }
            
            // ===== 右侧头像（固定距离右边缘 10px）=====
            Item { width: 8 }  // 状态栏与头像之间的间距
            Rectangle {
                width: 32
                height: 32
                radius: 16
                color: "#E8F5E9"
                clip: true
                
                Image {
                    anchors.fill: parent
                    source: "images/avatar.png"
                    fillMode: Image.PreserveAspectCrop
                }
                
                MouseArea {
                    anchors.fill: parent
                    cursorShape: Qt.PointingHandCursor
                    onClicked: avatarMenu.open()
                }
                
                Menu {
                    id: avatarMenu
                    y: parent.height + 5
                    width: 80
                    
                    MenuItem {
                        text: "切换账号"
                        onTriggered: showSwitchAccountDialog()
                    }
                    MenuItem {
                        text: "退出登录"
                        onTriggered: handleLogout()
                    }
                    MenuSeparator {}
                    MenuItem {
                        text: "最小化"
                        onTriggered: mainWindow.showMinimized()
                    }
                    MenuItem {
                        text: "关闭"
                        onTriggered: Qt.quit()
                    }
                }
            }
        }
    }
    
    // ============ 主布局 ============
    SplitView {
        id: mainSplitView
        anchors.top: topMenuBar.bottom
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.bottom: parent.bottom
        anchors.margins: 8
        orientation: Qt.Horizontal
        
        // 左右分割线样式（透明，悬停时显示）
        handle: Rectangle {
            implicitWidth: 8
            implicitHeight: parent.height
            color: "transparent"
            
            // 悬停时显示的指示条
            Rectangle {
                anchors.centerIn: parent
                width: 4
                height: parent.height
                radius: 2
                color: mainSplitHandleArea.containsMouse ? "#4DB6AC" : "transparent"
                opacity: mainSplitHandleArea.containsMouse ? 0.8 : 0
                
                Behavior on opacity { NumberAnimation { duration: 150 } }
            }
            
            MouseArea {
                id: mainSplitHandleArea
                anchors.fill: parent
                hoverEnabled: true
                cursorShape: Qt.SplitHCursor
                acceptedButtons: Qt.NoButton
            }
        }

        // ===== 左侧容器（Holder）=====
        Item {
            id: leftHolder
            SplitView.fillHeight: true
            SplitView.fillWidth: true  // 左侧填充剩余空间
            SplitView.minimumWidth: mainSplitView.width * 0.3  // 最小30%
        }

        // ===== 右侧：三部分面板 =====
        ColumnLayout {
            id: rightPanel
            clip: true
            SplitView.fillHeight: true
            SplitView.preferredWidth: gridFullscreenMode ? 0 : mainSplitView.width * savedWidthRatio
            SplitView.minimumWidth: gridFullscreenMode ? 0 : mainSplitView.width * 0.15
            SplitView.maximumWidth: gridFullscreenMode ? 0 : Infinity
            opacity: gridFullscreenMode ? 0 : 1  // 全屏时透明但保持visible
            spacing: 4
            
            // ⭐ 监听宽度变化，在用户拖动后自动保存左右分割比例
            onWidthChanged: {
                if (!gridFullscreenMode && !isRestoringWidthRatio && width > 0 && mainSplitView.width > 0) {
                    // 用户拖动时，延迟保存比例（防抖）
                    saveWidthRatioTimer.stop()
                    saveWidthRatioTimer.start()
                }
            }

            // ----- 可拖动分割的上下两部分（实时流 + 慢放）-----
            SplitView {
                id: rightSplitView
                Layout.fillWidth: true
                Layout.fillHeight: true
                orientation: Qt.Vertical
                
                // 分割线样式（透明，悬停时显示）
                handle: Rectangle {
                    implicitWidth: parent.width
                    implicitHeight: 8
                    color: "transparent"
                    
                    // 悬停时显示的指示条
                    Rectangle {
                        anchors.centerIn: parent
                        width: parent.width
                        height: 4
                        radius: 2
                        color: splitHandleArea.containsMouse ? "#4DB6AC" : "transparent"
                        opacity: splitHandleArea.containsMouse ? 0.8 : 0
                        
                        Behavior on opacity { NumberAnimation { duration: 150 } }
                    }
                    
                    MouseArea {
                        id: splitHandleArea
                        anchors.fill: parent
                        hoverEnabled: true
                        cursorShape: Qt.SplitVCursor
                        acceptedButtons: Qt.NoButton
                    }
                }

                // ----- 右侧第一部分容器（实时流）-----
                Item {
                    id: rightTopHolder
                    SplitView.fillWidth: true
                    SplitView.preferredHeight: 330
                    SplitView.minimumHeight: 150
                    
                    // ⭐ 监听高度变化，在用户拖动后自动保存比例
                    onHeightChanged: {
                        if (!gridFullscreenMode && !isRestoringRatio) {
                            // 用户拖动时，延迟保存比例（防抖）
                            saveHeightRatioTimer.stop()
                            saveHeightRatioTimer.start()
                        }
                    }
                }

                // ----- 右侧第二部分容器（慢放）-----
                Item {
                    id: rightMiddleHolder
                    SplitView.fillWidth: true
                    SplitView.preferredHeight: 330
                    SplitView.minimumHeight: 150
                }
            }

            // ----- 右侧第三部分容器（控制面板，固定位置）-----
            Item {
                id: rightBottomHolder
                Layout.fillWidth: true
                Layout.preferredHeight: 216
                Layout.minimumHeight: 140
                Layout.maximumHeight: 300
            }
        }
    }
    
    // ============ 可交换的内容组件 ============
    
    // 抓拍网格内容
    Item {
        id: captureGridContent
        // 根据布局模式选择父容器
        parent: windowLayoutMode === 0 ? leftHolder : 
                (windowLayoutMode === 1 ? rightTopHolder : rightMiddleHolder)
        anchors.fill: parent
        
        // 🔥 跟踪鼠标是否在截图区域内（HoverHandler 不与子元素 MouseArea 冲突）
        property bool mouseInCaptureArea: captureAreaHoverHandler.hovered
        
        HoverHandler {
            id: captureAreaHoverHandler
            onHoveredChanged: {
                if (hovered) {
                    // ⭐ 鼠标进入截图区域时恢复焦点（确保S键检测正常）
                    mainPage.forceActiveFocus()
                }
            }
        }
        
        // 🔥 监听新截图添加，只有鼠标不在区域内时才自动选中
        Connections {
            target: captureManager
            function onItemAdded(itemIndex) {
                if (!captureGridContent.mouseInCaptureArea) {
                    // 鼠标不在截图区域内，自动选中新截图
                    captureManager.currentItemIndex = itemIndex
                }
                // 否则保持当前选中状态（由鼠标悬停控制）
            }
        }
        
        GridView {
                    id: captureGrid
                    anchors.fill: parent
                    cellWidth: Math.max(1, Math.floor(width / captureManager.gridCols))
                    cellHeight: Math.max(1, Math.floor(height / captureManager.gridRows))
                    model: captureManager.gridRows * captureManager.gridCols
                    clip: true
                    interactive: false
                    cacheBuffer: 0
                    
                    function getDataIndex(displayIndex) {
                        if (captureManager.isHorizontalLayout) {
                            return displayIndex
                        } else {
                            var displayRow = Math.floor(displayIndex / captureManager.gridCols)
                            var displayCol = displayIndex % captureManager.gridCols
                            return displayCol * captureManager.gridRows + displayRow
                        }
                    }

                    delegate: Rectangle {
                        id: gridCell
                        width: captureGrid.cellWidth - 1
                        height: captureGrid.cellHeight - 1
                        x: 0.5
                        y: 0.5
                        
                        property int dataIndex: captureGrid.getDataIndex(index)
                        property bool hasData: dataIndex < captureManager.count
                        property bool isSelected: hasData && captureManager.currentItemIndex === dataIndex
                        property int currentFrame: hasData ? captureManager.getCurrentOffset(dataIndex) : 0
                        property int totalFrames: hasData ? captureManager.getTotalFrames(dataIndex) : 0
                        
                        function rebindFrameProperties() {
                            gridCell.currentFrame = Qt.binding(function() {
                                return gridCell.hasData ? captureManager.getCurrentOffset(gridCell.dataIndex) : 0
                            })
                            gridCell.totalFrames = Qt.binding(function() {
                                return gridCell.hasData ? captureManager.getTotalFrames(gridCell.dataIndex) : 0
                            })
                        }
                        
                        Connections {
                            target: captureManager
                            function onCaptureComplete(itemIndex) {
                                if (itemIndex === gridCell.dataIndex) {
                                    gridCell.rebindFrameProperties()
                                }
                            }
                            function onItemAdded(itemIndex) {
                                if (itemIndex === gridCell.dataIndex) {
                                    gridCell.rebindFrameProperties()
                                }
                            }
                            function onGridSettingsChanged() {
                                gridCell.rebindFrameProperties()
                            }
                            function onCaptureSettingsChanged() {
                                gridCell.rebindFrameProperties()
                            }
                            function onFrameChanged(itemIndex, frameOffset) {
                                // 调试日志
                                console.log("📥 QML onFrameChanged: itemIndex=" + itemIndex + " frameOffset=" + frameOffset + " myDataIndex=" + gridCell.dataIndex)
                                if (itemIndex === gridCell.dataIndex) {
                                    console.log("✅ 匹配！设置 currentFrame=" + frameOffset)
                                    gridCell.currentFrame = frameOffset
                                }
                            }
                        }
                        
                        color: mainPage.panelBgColor  // 面板背景色（滑块可调）
                        border.color: isSelected ? "#4CAF50" : (itemMouseArea.containsMouse ? "#81C784" : "#707070")  // 选中绿色，悬停浅绿，默认深灰
                        border.width: isSelected ? 5 : (itemMouseArea.containsMouse ? 3 : 2)  // 选中加粗，悬停中等
                        radius: 4

                        // item 缩放属性（从抓拍时的 videoZoom 继承，之后用户可手动调整）
                        property real itemZoom: 1.0
                        property real itemOffsetX: 0
                        property real itemOffsetY: 0
                        property bool zoomInitialized: false  // ⭐ 标记是否已初始化
                        
                        // 🔍 追踪 itemZoom 变化
                        onItemZoomChanged: {
                            captureManager.zoomLog("⚡ itemZoom变化: dataIndex=" + dataIndex + " newZoom=" + itemZoom.toFixed(2))
                        }
                        
                        // ⭐ 只在组件首次加载且有数据时初始化缩放
                        Component.onCompleted: {
                            initZoomFromMap()
                        }
                        
                        // ⭐ 当有新数据时初始化缩放
                        onHasDataChanged: {
                            captureManager.zoomLog("🔄 hasDataChanged: dataIndex=" + dataIndex + " hasData=" + hasData + " zoomInitialized=" + zoomInitialized)
                            if (hasData) {
                                // 每次有新数据时都从 map 加载缩放
                                initZoomFromMap()
                            } else {
                                // 数据被清除时，重置初始化标记，以便下次重新加载
                                zoomInitialized = false
                                itemZoom = 1.0
                                itemOffsetX = 0
                                itemOffsetY = 0
                            }
                        }
                        
                        function initZoomFromMap() {
                            captureManager.zoomLog("🔧 initZoomFromMap: dataIndex=" + dataIndex + " hasData=" + hasData + " zoomInitialized=" + zoomInitialized)
                            if (dataIndex >= 0 && mainPage.itemZoomMap[dataIndex]) {
                                var saved = mainPage.itemZoomMap[dataIndex]
                                itemZoom = saved.zoom
                                
                                // ⭐ 边界约束：根据当前容器大小重新计算有效偏移范围
                                var maxOffsetX = imageContainer.width * (saved.zoom - 1) / 2
                                var maxOffsetY = imageContainer.height * (saved.zoom - 1) / 2
                                itemOffsetX = Math.max(-maxOffsetX, Math.min(maxOffsetX, saved.offsetX))
                                itemOffsetY = Math.max(-maxOffsetY, Math.min(maxOffsetY, saved.offsetY))
                                
                                zoomInitialized = true
                                captureManager.zoomLog("📸 item " + dataIndex + " 初始化: zoom=" + saved.zoom + " offsetX=" + itemOffsetX.toFixed(1) + " (从map, maxOffset=" + maxOffsetX.toFixed(1) + ")")
                            } else if (hasData) {
                                itemZoom = 1.0
                                itemOffsetX = 0
                                itemOffsetY = 0
                                zoomInitialized = true
                                captureManager.zoomLog("📸 item " + dataIndex + " 初始化: zoom=1.0 (默认)")
                            }
                        }
                        
                        // ⭐ 接收 Ctrl 联动广播 — 整 grid 所有 item 同步切帧 / 缩放 / 拖拽
                        Connections {
                            target: mainPage
                            function onGridSyncFrameStep(direction) {
                                if (!gridCell.hasData || gridCell.totalFrames <= 0) return
                                mainPage.stepCaptureFrame(gridCell.dataIndex, direction)
                                gridCell.currentFrame = captureManager.getCurrentOffset(gridCell.dataIndex)
                            }
                            function onGridSyncZoomDelta(deltaZoom) {
                                if (!gridCell.hasData) return
                                var oldZoom = gridCell.itemZoom
                                var newZoom = Math.max(1.0, Math.min(3.0, oldZoom + deltaZoom))
                                if (newZoom === oldZoom) return
                                // 联动模式: 各 item 以自己容器中心缩放 (鼠标坐标对每个格子不同, 简化为中心)
                                if (newZoom === 1.0) {
                                    gridCell.itemOffsetX = 0
                                    gridCell.itemOffsetY = 0
                                } else {
                                    // 按比例约束已有偏移到新范围
                                    var maxOffsetX = imageContainer.width * (newZoom - 1) / 2
                                    var maxOffsetY = imageContainer.height * (newZoom - 1) / 2
                                    gridCell.itemOffsetX = Math.max(-maxOffsetX, Math.min(maxOffsetX, gridCell.itemOffsetX))
                                    gridCell.itemOffsetY = Math.max(-maxOffsetY, Math.min(maxOffsetY, gridCell.itemOffsetY))
                                }
                                gridCell.itemZoom = newZoom
                            }
                            function onGridSyncDrag(dx, dy) {
                                if (!gridCell.hasData || gridCell.itemZoom <= 1.0) return
                                var maxOffsetX = imageContainer.width * (gridCell.itemZoom - 1) / 2
                                var maxOffsetY = imageContainer.height * (gridCell.itemZoom - 1) / 2
                                gridCell.itemOffsetX = Math.max(-maxOffsetX, Math.min(maxOffsetX, gridCell.itemOffsetX + dx))
                                gridCell.itemOffsetY = Math.max(-maxOffsetY, Math.min(maxOffsetY, gridCell.itemOffsetY + dy))
                            }
                            function onGridSyncResetZoom() {
                                if (!gridCell.hasData) return
                                gridCell.itemZoom = 1.0
                                gridCell.itemOffsetX = 0
                                gridCell.itemOffsetY = 0
                            }
                        }

                        MouseArea {
                            id: itemMouseArea
                            anchors.fill: parent
                            hoverEnabled: true
                            acceptedButtons: Qt.LeftButton | Qt.RightButton

                            // ⭐ 统一处理焦点和选中（避免重复代码）
                            function ensureFocusAndSelect() {
                                mainPage.forceActiveFocus()
                                if (gridCell.hasData && captureManager.currentItemIndex !== gridCell.dataIndex) {
                                    captureManager.currentItemIndex = gridCell.dataIndex
                                }
                            }

                            onEntered: {
                                ensureFocusAndSelect()
                            }
                            
                            // ⭐ 修复：GridView 重建 delegate 时鼠标已在 item 上，onEntered 不触发
                            // onPositionChanged 在鼠标移动时触发，补偿 onEntered 缺失的情况
                            onPositionChanged: {
                                if (!gridCell.isSelected) {
                                    ensureFocusAndSelect()
                                }
                            }
                            
                            onClicked: function(mouse) {
                                ensureFocusAndSelect()
                                // ⭐ Shift+点击：打开该item所在列的列预览（原 Ctrl+点击, 让位给联动）
                                if (mouse.modifiers & Qt.ShiftModifier && gridCell.hasData) {
                                    var cols = captureManager.gridCols
                                    var displayIndex = index
                                    var displayCol
                                    if (captureManager.isHorizontalLayout) {
                                        displayCol = displayIndex % cols
                                    } else {
                                        displayCol = Math.floor(gridCell.dataIndex / captureManager.gridRows)
                                    }
                                    toggleColumnPreview(displayCol + 1)  // 1-based
                                    return
                                }
                                // ⭐ Ctrl+点击：广播给所有 grid item 同步切帧
                                if (mouse.modifiers & Qt.ControlModifier && gridCell.hasData) {
                                    if (mouse.button === Qt.LeftButton) {
                                        mainPage.gridSyncFrameStep("prev")
                                    } else if (mouse.button === Qt.RightButton) {
                                        mainPage.gridSyncFrameStep("next")
                                    }
                                    return
                                }
                                // ⭐ 左键=上一帧，右键=下一帧（单 item, 受 frameStep 影响）
                                if (gridCell.hasData && gridCell.totalFrames > 0) {
                                    if (mouse.button === Qt.LeftButton) {
                                        mainPage.stepCaptureFrame(gridCell.dataIndex, "prev")
                                        gridCell.currentFrame = captureManager.getCurrentOffset(gridCell.dataIndex)
                                    } else if (mouse.button === Qt.RightButton) {
                                        mainPage.stepCaptureFrame(gridCell.dataIndex, "next")
                                        gridCell.currentFrame = captureManager.getCurrentOffset(gridCell.dataIndex)
                                    }
                                }
                            }
                            
                            onWheel: function(wheel) {
                                wheel.accepted = true  // 阻止事件传播到其他区域
                                // ⭐ 确保焦点和选中（防止首次滚轮时 S 键不生效）
                                ensureFocusAndSelect()
                                if (!gridCell.hasData || gridCell.totalFrames <= 0) return

                                // ⭐ Ctrl 按住: 全 grid 联动 (滚轮切帧 / S+滚轮缩放)
                                if (wheel.modifiers & Qt.ControlModifier) {
                                    if (mainPage.sKeyPressed) {
                                        // Ctrl + S + 滚轮: 全部 item 同步缩放 (各自以容器中心)
                                        mainPage.gridSyncZoomDelta(wheel.angleDelta.y > 0 ? 0.2 : -0.2)
                                    } else {
                                        // Ctrl + 滚轮: 全部 item 同步切帧
                                        mainPage.gridSyncFrameStep(wheel.angleDelta.y > 0 ? "prev" : "next")
                                    }
                                    return
                                }

                                // 🔍 调试日志：显示当前状态
                                captureManager.zoomLog("🎡 wheel: dataIndex=" + gridCell.dataIndex + " itemZoom=" + gridCell.itemZoom.toFixed(2) + " offsetX=" + gridCell.itemOffsetX.toFixed(1) + " offsetY=" + gridCell.itemOffsetY.toFixed(1) + " frame=" + gridCell.currentFrame + " sKey=" + mainPage.sKeyPressed)
                                captureManager.zoomLog("🎡 实时流: videoZoom=" + mainPage.videoZoom.toFixed(2) + " videoOffsetX=" + mainPage.videoOffsetX.toFixed(1) + " videoOffsetY=" + mainPage.videoOffsetY.toFixed(1))

                                if (mainPage.sKeyPressed) {
                                    // S + 滚轮：以鼠标为中心缩放
                                    var oldZoom = gridCell.itemZoom
                                    var delta = wheel.angleDelta.y > 0 ? 0.2 : -0.2
                                    var newZoom = Math.max(1.0, Math.min(3.0, oldZoom + delta))
                                    
                                    if (newZoom !== oldZoom) {
                                        // 计算鼠标相对于容器中心的位置
                                        var containerCenterX = imageContainer.width / 2
                                        var containerCenterY = imageContainer.height / 2
                                        var mouseRelX = wheel.x - containerCenterX
                                        var mouseRelY = wheel.y - containerCenterY
                                        
                                        // 计算缩放比例变化
                                        var zoomRatio = newZoom / oldZoom
                                        
                                        // 调整偏移以保持鼠标位置不变
                                        var newOffsetX = mouseRelX - (mouseRelX - gridCell.itemOffsetX) * zoomRatio
                                        var newOffsetY = mouseRelY - (mouseRelY - gridCell.itemOffsetY) * zoomRatio
                                        
                                        // ⭐ 边界约束：确保偏移量在有效范围内
                                        var maxOffsetX = imageContainer.width * (newZoom - 1) / 2
                                        var maxOffsetY = imageContainer.height * (newZoom - 1) / 2
                                        gridCell.itemOffsetX = Math.max(-maxOffsetX, Math.min(maxOffsetX, newOffsetX))
                                        gridCell.itemOffsetY = Math.max(-maxOffsetY, Math.min(maxOffsetY, newOffsetY))
                                        
                                        gridCell.itemZoom = newZoom
                                        
                                        // 如果缩放到1倍，重置偏移
                                        if (newZoom === 1.0) {
                                            gridCell.itemOffsetX = 0
                                            gridCell.itemOffsetY = 0
                                        }
                                        
                                        captureManager.zoomLog("🔍 item缩放: dataIndex=" + gridCell.dataIndex + " zoom=" + newZoom.toFixed(2) + " offsetX=" + gridCell.itemOffsetX.toFixed(1) + " maxOffset=" + maxOffsetX.toFixed(1))
                                    }
                                } else {
                                    // 普通滚轮：切换帧
                                    // 🔍 滚帧前的图片尺寸（包括实际绘制尺寸）
                                    captureManager.zoomLog("📏 滚帧前: dataIndex=" + gridCell.dataIndex + " frame=" + gridCell.currentFrame + 
                                        " W=" + itemImage.width.toFixed(0) + " H=" + itemImage.height.toFixed(0) +
                                        " paintedW=" + itemImage.paintedWidth.toFixed(0) + " paintedH=" + itemImage.paintedHeight.toFixed(0) +
                                        " implicitW=" + itemImage.implicitWidth.toFixed(0) + " implicitH=" + itemImage.implicitHeight.toFixed(0) +
                                        " itemZoom=" + gridCell.itemZoom.toFixed(2))
                                    
                                    if (wheel.angleDelta.y > 0) {
                                        mainPage.stepCaptureFrame(gridCell.dataIndex, "prev")
                                    } else {
                                        mainPage.stepCaptureFrame(gridCell.dataIndex, "next")
                                    }
                                    // 直接获取最新帧偏移（frameChanged 信号会更新，这里作为备份）
                                    gridCell.currentFrame = captureManager.getCurrentOffset(gridCell.dataIndex)
                                    
                                    // 🔍 滚帧后的图片尺寸
                                    captureManager.zoomLog("📏 滚帧后: dataIndex=" + gridCell.dataIndex + " frame=" + gridCell.currentFrame + 
                                        " W=" + itemImage.width.toFixed(0) + " H=" + itemImage.height.toFixed(0) +
                                        " paintedW=" + itemImage.paintedWidth.toFixed(0) + " paintedH=" + itemImage.paintedHeight.toFixed(0) +
                                        " implicitW=" + itemImage.implicitWidth.toFixed(0) + " implicitH=" + itemImage.implicitHeight.toFixed(0) +
                                        " itemZoom=" + gridCell.itemZoom.toFixed(2))
                                }
                            }
                        }

                        // 图片容器（用于缩放）
                        Item {
                            id: imageContainer
                            anchors.fill: parent
                            anchors.margins: 2
                            clip: true
                            
                            onWidthChanged: {
                                captureManager.zoomLog("📦 容器尺寸变化: dataIndex=" + gridCell.dataIndex + " containerW=" + width.toFixed(0))
                                // ⭐ 容器大小变化时重新计算偏移量边界
                                if (gridCell.itemZoom > 1.0 && width > 0) {
                                    var maxOffsetX = width * (gridCell.itemZoom - 1) / 2
                                    var maxOffsetY = height * (gridCell.itemZoom - 1) / 2
                                    gridCell.itemOffsetX = Math.max(-maxOffsetX, Math.min(maxOffsetX, gridCell.itemOffsetX))
                                    gridCell.itemOffsetY = Math.max(-maxOffsetY, Math.min(maxOffsetY, gridCell.itemOffsetY))
                                }
                            }
                            
                            Image {
                                id: itemImage
                                // ⭐ 完全铺满容器（拉伸填充）
                                // 使用手动居中 + 偏移量实现缩放拖动
                                x: parent.width / 2 - width / 2 + gridCell.itemOffsetX
                                y: parent.height / 2 - height / 2 + gridCell.itemOffsetY
                                width: parent.width * gridCell.itemZoom
                                height: parent.height * gridCell.itemZoom
                                
                                source: gridCell.hasData ? "image://capture/frame/" + gridCell.dataIndex + "/" + gridCell.currentFrame : ""
                                fillMode: Image.Stretch  // 拉伸铺满，完全填充容器
                                cache: false
                                visible: gridCell.hasData
                                asynchronous: false
                                mirror: mainPage.videoMirrorMode === "horizontal"
                                mirrorVertically: mainPage.videoMirrorMode === "vertical"
                                
                                layer.enabled: false  // 不再使用 shader，颜色调整由 GStreamer videobalance 和 gamma 处理
                                
                                onStatusChanged: {
                                    if (status === Image.Ready) {
                                        captureManager.zoomLog("🖼️ 图片加载: dataIndex=" + gridCell.dataIndex + " frame=" + gridCell.currentFrame + 
                                            " W=" + width.toFixed(0) + " H=" + height.toFixed(0) +
                                            " paintedW=" + paintedWidth.toFixed(0) + " paintedH=" + paintedHeight.toFixed(0) +
                                            " implicitW=" + implicitWidth.toFixed(0) + " implicitH=" + implicitHeight.toFixed(0) +
                                            " sourceW=" + sourceSize.width + " sourceH=" + sourceSize.height +
                                            " itemZoom=" + gridCell.itemZoom.toFixed(2))
                                    } else if (status === Image.Error && gridCell.hasData) {
                                        source = "image://capture/thumbnail/" + gridCell.dataIndex
                                    }
                                }
                                
                                // 🔍 追踪实际渲染尺寸
                                onWidthChanged: {
                                    captureManager.zoomLog("📐 Image尺寸: dataIndex=" + gridCell.dataIndex + " W=" + width.toFixed(0) + " H=" + height.toFixed(0) + " x=" + x.toFixed(0) + " y=" + y.toFixed(0) + " parentW=" + parent.width.toFixed(0) + " itemZoom=" + gridCell.itemZoom.toFixed(2) + " itemOffsetX=" + gridCell.itemOffsetX.toFixed(1))
                                }
                            }
                        }
                        
                        // 无数据时显示数字
                        Item {
                            anchors.fill: parent
                            visible: !gridCell.hasData
                            
                            Text {
                                anchors.centerIn: parent
                                text: gridCell.dataIndex + 1
                                font.family: "PingFang HK"
                                font.weight: Font.Medium
                                font.pixelSize: 32
                                color: "#90A4AE"
                            }
                        }
                        
                        // 左上角帧率数字（无背景，白色文字70%透明度）
                        Text {
                            id: frameIndexText
                            anchors.left: parent.left
                            anchors.top: parent.top
                            anchors.margins: 6
                            visible: gridCell.hasData && gridCell.totalFrames > 0
                            z: 1
                            text: (gridCell.currentFrame + 1)  // 只显示当前帧数
                            font.pixelSize: 10  // 字体增加4px（6 -> 10）
                            font.bold: false
                            color: "#B3FFFFFF"  // 白色，透明度70%（0xB3 ≈ 179 ≈ 70%）
                        }
                        
                    }
                }
    }
    
    // ============ 实时流内容（可交换）============
    Item {
        id: livePanelContent
        // 根据布局模式选择父容器: 模式1时放左侧，否则放右上
        parent: windowLayoutMode === 1 ? leftHolder : rightTopHolder
        anchors.fill: parent
        
        // 阴影
        Rectangle {
            anchors.fill: livePanel
            anchors.topMargin: 1
            anchors.bottomMargin: -1
            radius: 4
            color: "#1A000000"
        }
        
        Rectangle {
            id: livePanel
            anchors.fill: parent
            color: mainPage.panelBgColor  // 面板背景色（滑块可调）
            radius: 4
            
            // 用于追踪整个区域的hover状态
            property bool isHovering: livePanelHover.containsMouse

                    // 视频容器（用于旋转）
                    Item {
                        id: videoContainer
                        anchors.fill: parent
                        anchors.margins: 2
                        clip: true
                        
                        // 视频输出
                        VideoOutput {
                            id: liveVideoPlayer
                            // 根据旋转角度调整宽高
                            width: (mainPage.videoRotation === 90 || mainPage.videoRotation === 270) 
                                   ? parent.height : parent.width
                            height: (mainPage.videoRotation === 90 || mainPage.videoRotation === 270) 
                                    ? parent.width : parent.height
                            fillMode: VideoOutput.Stretch
                            
                            // 设置变换原点为中心
                            x: parent.width / 2 - width / 2 + mainPage.videoOffsetX
                            y: parent.height / 2 - height / 2 + mainPage.videoOffsetY
                            
                            transform: [
                                Rotation {
                                    origin.x: liveVideoPlayer.width / 2
                                    origin.y: liveVideoPlayer.height / 2
                                    angle: mainPage.videoRotation
                                },
                                Scale {
                                    origin.x: liveVideoPlayer.width / 2
                                    origin.y: liveVideoPlayer.height / 2
                                    xScale: mainPage.videoMirrorMode === "horizontal" ? -mainPage.videoZoom : mainPage.videoZoom
                                    yScale: mainPage.videoMirrorMode === "vertical" ? -mainPage.videoZoom : mainPage.videoZoom
                                }
                            ]
                            
                            Component.onCompleted: {
                                // 使用 GstPlayer 输出到 VideoOutput
                                gstPlayer.videoSink = liveVideoPlayer.videoSink
                            }
                            
                            layer.enabled: false  // 不再使用 shader，颜色调整由 GStreamer videobalance 和 gamma 处理
                        }
                        
                        
                        // 滚轮：S+滚轮控制镜头变倍(1.0-3.0)，普通滚轮控制本地缩放(1.0-5.0)
                        MouseArea {
                            id: videoZoomArea
                            anchors.fill: parent
                            hoverEnabled: true
                            
                            onEntered: {
                                // ⭐ 恢复键盘焦点（确保S键检测正常工作）
                                mainPage.forceActiveFocus()
                            }
                            
                            onClicked: {
                                // 点击视频区域时关闭档位下拉菜单
                                qualityMenu.visible = false
                                // 鼠标左键 = 空格键，触发抓拍
                                EventBus.triggerCapture()
                            }
                            
                            onWheel: function(wheel) {
                                if (mainPage.sKeyPressed) {
                                    // S+滚轮：镜头变倍 (lens zoom 1.0-3.0)
                                    var oldLensZoom = iosCameraSettingsPopup.lensZoom
                                    var delta = wheel.angleDelta.y > 0 ? 0.1 : -0.1
                                    var newLensZoom = Math.max(1.0, Math.min(3.0, oldLensZoom + delta))
                                    
                                    if (newLensZoom !== oldLensZoom) {
                                        iosCameraSettingsPopup.lensZoom = newLensZoom
                                        HttpClient.updateZoom(newLensZoom)
                                        sendConfigUpdate("zoom", {"zoom": newLensZoom})
                                        console.log("🔍 S+滚轮 镜头变倍:", oldLensZoom.toFixed(1), "->", newLensZoom.toFixed(1))
                                    }
                                } else {
                                    // 普通滚轮：本地显示缩放 (1.0-5.0)
                                    // ⭐ 实时流本地放大始终可用，PC等级限制只影响截图item继承和慢放
                                    var oldZoom = mainPage.videoZoom
                                    var delta = wheel.angleDelta.y > 0 ? 0.2 : -0.2
                                    var newZoom = Math.max(1.0, Math.min(5.0, oldZoom + delta))
                                    
                                    if (newZoom !== oldZoom) {
                                        // 计算鼠标相对于容器中心的位置
                                        var containerCenterX = videoContainer.width / 2
                                        var containerCenterY = videoContainer.height / 2
                                        var mouseRelX = wheel.x - containerCenterX
                                        var mouseRelY = wheel.y - containerCenterY
                                        
                                        // 计算缩放比例变化
                                        var zoomRatio = newZoom / oldZoom
                                        
                                        // 调整偏移以保持鼠标位置不变
                                        var newOffsetX = mouseRelX - (mouseRelX - mainPage.videoOffsetX) * zoomRatio
                                        var newOffsetY = mouseRelY - (mouseRelY - mainPage.videoOffsetY) * zoomRatio
                                        
                                        // ⭐ 边界约束：确保偏移量在有效范围内
                                        // 有效范围 = ±(containerSize * (zoom - 1) / 2)
                                        var maxOffsetX = videoContainer.width * (newZoom - 1) / 2
                                        var maxOffsetY = videoContainer.height * (newZoom - 1) / 2
                                        mainPage.videoOffsetX = Math.max(-maxOffsetX, Math.min(maxOffsetX, newOffsetX))
                                        mainPage.videoOffsetY = Math.max(-maxOffsetY, Math.min(maxOffsetY, newOffsetY))
                                        
                                        mainPage.videoZoom = newZoom
                                        captureManager.zoomLog("🎥 实时流缩放: videoZoom=" + newZoom.toFixed(2) + " offsetX=" + mainPage.videoOffsetX.toFixed(1) + " offsetY=" + mainPage.videoOffsetY.toFixed(1) + " maxOffset=" + maxOffsetX.toFixed(1))
                                        
                                        // 如果缩放到1倍，重置偏移
                                        if (newZoom === 1.0) {
                                            mainPage.videoOffsetX = 0
                                            mainPage.videoOffsetY = 0
                                        }
                                        console.log("🔍 滚轮 本地缩放:", oldZoom.toFixed(1), "->", newZoom.toFixed(1))
                                        
                                        // ⭐ 推送本地视觉效果到其他PC
                                        sendLocalViewUpdate(mainPage.videoZoom, mainPage.videoOffsetX, mainPage.videoOffsetY)
                                    }
                                }
                            }
                        }
                    }
                    
                    // 设备状态文字层（当设备睡眠/唤醒时显示）
                    Rectangle {
                        id: deviceStatusOverlay
                        anchors.fill: videoContainer
                        color: mainPage.panelBgColor  // 面板背景色（滑块可调）
                        z: 1  // 层级在noVideoOverlay之上，但在控制栏之下
                        visible: mainPage.deviceStatus !== ""
                        
                        Text {
                            anchors.centerIn: parent
                            text: mainPage.deviceStatus === "sleeping" ? "睡眠中..." : "唤醒中..."
                            font.family: "PingFang HK"
                            font.pixelSize: 18
                            font.bold: true
                            color: mainPage.panelTextColor  // 文字色随面板调整
                        }
                    }
                    
                    // 暂无画面（未推流时显示，覆盖在视频上）
                    Rectangle {
                        id: noVideoOverlay
                        anchors.fill: videoContainer
                        color: mainPage.panelBgColor  // 面板背景色（滑块可调）
                        visible: mainPage.publishState !== 1 && mainPage.deviceStatus === ""
                        
                        Column {
                            anchors.centerIn: parent
                            spacing: 4

                            Image {
                                source: "images/zwtp.png"
                                width: 108
                                height: 108
                                anchors.horizontalCenter: parent.horizontalCenter
                            }

                            Text {
                                text: "暂无画面"
                                font.family: "PingFang HK"
                                font.pixelSize: 14
                                color: mainPage.panelTextColor  // 文字色随面板调整
                                anchors.horizontalCenter: parent.horizontalCenter
                            }
                        }
                    }
                    

                    Rectangle {
                        anchors.top: parent.top
                        anchors.left: parent.left
                        anchors.margins: 8
                        width: liveInfoCol.width + 16
                        height: liveInfoCol.height + 12
                        color: "#80000000"
                        radius: 4
                        visible: webrtcClient.isConnected()

                        Column {
                            id: liveInfoCol
                            anchors.centerIn: parent
                            spacing: 2

                            Text {
                                id: liveInfoFps
                                text: "FPS: --"
                                color: "#4caf50"
                                font.pixelSize: 11
                            }
                            Text {
                                text: gstPlayer.videoWidth + "×" + gstPlayer.videoHeight
                                color: "#ffffff"
                                font.pixelSize: 11
                            }
                        }
                    }

            // LIVE 状态指示（只在连接时显示）
            Text {
                anchors.top: parent.top
                anchors.right: parent.right
                anchors.margins: 8
                z: 10
                text: "● LIVE"
                color: "#4caf50"
                font.pixelSize: 12
                font.bold: true
                visible: webrtcClient.isConnected()
            }
            
            // 底部控制栏（移到 livePanel 层级，不被覆盖层遮挡）
            Row {
                id: liveControlBar
                anchors.left: parent.left
                anchors.right: parent.right
                anchors.bottom: parent.bottom
                anchors.margins: 10
                spacing: 8
                z: 100  // 确保在覆盖层之上
                visible: livePanel.isHovering
                opacity: livePanel.isHovering ? 1.0 : 0.0
                Behavior on opacity { NumberAnimation { duration: 200 } }
                // onVisibleChanged: console.log("🎮 liveControlBar visible:", visible)
                
                // 档位切换下拉列表
                Rectangle {
                    id: qualityDropdown
                    width: 70
                    height: 32
                    radius: 4
                    color: qualityDropdownArea.containsMouse || qualityMenu.visible ? "#C8E6C9" : "#80000000"
                    
                    Row {
                        anchors.centerIn: parent
                        spacing: 4
                        
                        Text {
                            id: qualityButtonText
                            text: "高清"
                            font.pixelSize: 12
                            font.family: "PingFang HK"
                            font.bold: true
                            color: qualityDropdownArea.containsMouse || qualityMenu.visible ? "#263238" : "#FFFFFF"
                        }
                        
                        Text {
                            text: "▼"
                            font.pixelSize: 8
                            color: qualityDropdownArea.containsMouse || qualityMenu.visible ? "#263238" : "#FFFFFF"
                            anchors.verticalCenter: parent.verticalCenter
                        }
                    }
                    
                    MouseArea {
                        id: qualityDropdownArea
                        anchors.fill: parent
                        hoverEnabled: true
                        cursorShape: Qt.PointingHandCursor
                        onClicked: qualityMenu.visible = !qualityMenu.visible
                    }
                    
                    // 下拉菜单
                    Rectangle {
                        id: qualityMenu
                        visible: false
                        width: parent.width
                        height: qualityColumn.height + 8
                        anchors.bottom: parent.top
                        anchors.bottomMargin: 4
                        anchors.horizontalCenter: parent.horizontalCenter
                        color: "#E8F5E9"
                        radius: 4
                        border.color: "#A5D6A7"
                        border.width: 1
                        
                        Column {
                            id: qualityColumn
                            anchors.centerIn: parent
                            spacing: 2
                            
                            Repeater {
                                model: [
                                    { label: "超低网", type: "low" },
                                    { label: "高清", type: "standard" },
                                    { label: "超清", type: "high" },
                                    { label: "超高清", type: "p4k" },
                                    { label: "超高帧", type: "ultra" },
                                    { label: "超快帧", type: "ultrafast" }
                                ]
                                
                                Rectangle {
                                    property bool accessible: isQualityAccessible(modelData.label)
                                    property bool isActive: modelData.type === "ultrafast" ? mainPage.highSpeed240Enabled : (qualityButtonText.text === modelData.label)
                                    width: qualityMenu.width - 8
                                    height: 28
                                    radius: 3
                                    color: !accessible ? "#ECEFF1" : (qualityItemArea.containsMouse ? "#C8E6C9" : (isActive ? "#A5D6A7" : "transparent"))

                                    Text {
                                        anchors.centerIn: parent
                                        text: modelData.label
                                        font.pixelSize: 12
                                        font.family: "PingFang HK"
                                        font.bold: parent.isActive
                                        color: parent.accessible ? "#263238" : "#90A4AE"
                                    }
                                    
                                    MouseArea {
                                        id: qualityItemArea
                                        anchors.fill: parent
                                        hoverEnabled: true
                                        cursorShape: parent.accessible ? Qt.PointingHandCursor : Qt.ForbiddenCursor
                                        onClicked: {
                                            if (parent.accessible) {
                                                if (modelData.type === "ultrafast") {
                                                    // 超快帧：开发中，暂不开放
                                                    showQualityAccessDeniedTip("超快帧（开发中）")
                                                    qualityMenu.visible = false
                                                } else {
                                                    // 切到其他档位时，如果当前在超快帧模式则自动退出
                                                    if (mainPage.highSpeed240Enabled) {
                                                        mainPage.highSpeed240Enabled = false
                                                        sendConfigUpdate("highspeed", {"fps": 30})
                                                        console.log("⚡ 切换档位，自动退出超快帧模式")
                                                    }
                                                    switchQuality(modelData.type, modelData.label)
                                                    qualityMenu.visible = false
                                                }
                                            } else {
                                                showQualityAccessDeniedTip(modelData.label)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                
                // 镜头变倍按钮
                Rectangle {
                    id: lensZoomButtonRect
                    width: 50
                    height: 32
                    radius: 4
                    color: lensZoomBtnArea.containsMouse ? "#C8E6C9" : "#80000000"
                    
                    Text {
                        id: lensZoomButtonText
                        anchors.centerIn: parent
                        text: iosCameraSettingsPopup.lensZoom.toFixed(1) + "倍"
                        font.pixelSize: 12
                        font.family: "PingFang HK"
                        font.bold: true
                        color: lensZoomBtnArea.containsMouse ? "#263238" : "#FFFFFF"
                    }
                    
                    MouseArea {
                        id: lensZoomBtnArea
                        anchors.fill: parent
                        hoverEnabled: true
                        cursorShape: Qt.PointingHandCursor
                        onClicked: {
                            iosCameraSettingsPopup.lensZoom = 1.0
                            HttpClient.updateZoom(1.0)
                            sendConfigUpdate("zoom", {"zoom": 1.0})
                        }
                        onWheel: function(wheel) {
                            var oldZoom = iosCameraSettingsPopup.lensZoom
                            var delta = wheel.angleDelta.y > 0 ? 0.1 : -0.1
                            var newZoom = Math.max(1.0, Math.min(3.0, oldZoom + delta))
                            if (newZoom !== oldZoom) {
                                iosCameraSettingsPopup.lensZoom = newZoom
                                HttpClient.updateZoom(newZoom)
                                sendConfigUpdate("zoom", {"zoom": newZoom})
                            }
                        }
                    }
                }
                
                // 前后置切换按钮
                Rectangle {
                    width: 36
                    height: 32
                    radius: 4
                    color: switchCameraBtn.containsMouse ? "#C8E6C9" : "#80000000"
                    
                    Text {
                        id: switchCameraText
                        anchors.centerIn: parent
                        // direction: "1"=后置(显示"前"表示点击切换到前置), "-1"=前置(显示"后"表示点击切换到后置)
                        text: iosCameraSettingsPopup.directionValue === "1" ? "前" : "后"
                        font.pixelSize: 12
                        font.family: "PingFang HK"
                        font.bold: true
                        color: switchCameraBtn.containsMouse ? "#263238" : "#FFFFFF"
                    }
                    
                    MouseArea {
                        id: switchCameraBtn
                        anchors.fill: parent
                        hoverEnabled: true
                        cursorShape: Qt.PointingHandCursor
                        onClicked: {
                            // 切换前后置：1 变 -1，-1 变 1
                            var currentDir = iosCameraSettingsPopup.directionValue
                            var newDir = (currentDir === "1") ? "-1" : "1"
                            iosCameraSettingsPopup.directionValue = newDir
                            HttpClient.updateDirection(newDir)
                            sendConfigUpdate("direction", {"direction": newDir})
                            console.log("📷 切换摄像头方向:", currentDir, "->", newDir)
                        }
                    }
                }

                // 镜像下拉菜单
                Rectangle {
                    id: mirrorDropdown
                    width: 50
                    height: 32
                    radius: 4
                    color: mirrorDropdownArea.containsMouse || mirrorMenu.visible
                           ? "#C8E6C9"
                           : (mainPage.videoMirrorMode !== "none" ? "#4CAF50" : "#80000000")

                    Row {
                        anchors.centerIn: parent
                        spacing: 3

                        Text {
                            id: mirrorBtnText
                            text: mainPage.videoMirrorMode === "horizontal" ? "水平"
                                : mainPage.videoMirrorMode === "vertical" ? "垂直" : "镜像"
                            font.pixelSize: 12
                            font.family: "PingFang HK"
                            font.bold: true
                            color: mirrorDropdownArea.containsMouse || mirrorMenu.visible ? "#263238" : "#FFFFFF"
                        }

                        Text {
                            text: "▼"
                            font.pixelSize: 8
                            color: mirrorBtnText.color
                            anchors.verticalCenter: parent.verticalCenter
                        }
                    }

                    MouseArea {
                        id: mirrorDropdownArea
                        anchors.fill: parent
                        hoverEnabled: true
                        cursorShape: Qt.PointingHandCursor
                        onClicked: mirrorMenu.visible = !mirrorMenu.visible
                    }

                    Rectangle {
                        id: mirrorMenu
                        visible: false
                        width: 60
                        height: mirrorCol.height + 8
                        anchors.bottom: parent.top
                        anchors.bottomMargin: 4
                        anchors.horizontalCenter: parent.horizontalCenter
                        color: "#E8F5E9"
                        radius: 4
                        border.color: "#A5D6A7"
                        border.width: 1

                        Column {
                            id: mirrorCol
                            anchors.centerIn: parent
                            spacing: 2

                            Repeater {
                                model: [
                                    { label: "关闭", mode: "none" },
                                    { label: "水平", mode: "horizontal" },
                                    { label: "垂直", mode: "vertical" }
                                ]

                                Rectangle {
                                    width: mirrorMenu.width - 8
                                    height: 28
                                    radius: 3
                                    color: mirrorItemArea.containsMouse ? "#C8E6C9"
                                         : (mainPage.videoMirrorMode === modelData.mode ? "#A5D6A7" : "transparent")

                                    Text {
                                        anchors.centerIn: parent
                                        text: modelData.label
                                        font.pixelSize: 12
                                        font.family: "PingFang HK"
                                        font.bold: mainPage.videoMirrorMode === modelData.mode
                                        color: "#263238"
                                    }

                                    MouseArea {
                                        id: mirrorItemArea
                                        anchors.fill: parent
                                        hoverEnabled: true
                                        cursorShape: Qt.PointingHandCursor
                                        onClicked: {
                                            mainPage.videoMirrorMode = modelData.mode
                                            mirrorMenu.visible = false
                                            console.log("🪞 镜像模式:", modelData.mode)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                
                // 弹性空间
                Item { Layout.fillWidth: true; width: 10 }
                
                // 本地缩放显示/重置按钮
                Rectangle {
                    width: 50
                    height: 32
                    radius: 4
                    color: zoomResetBtn.containsMouse ? "#C8E6C9" : "#80000000"
                    visible: mainPage.videoZoom > 1.0
                    
                    Text {
                        anchors.centerIn: parent
                        text: mainPage.videoZoom.toFixed(1) + "x"
                        font.pixelSize: 12
                        font.family: "PingFang HK"
                        font.bold: true
                        color: zoomResetBtn.containsMouse ? "#263238" : "#FFFFFF"
                    }
                    
                    MouseArea {
                        id: zoomResetBtn
                        anchors.fill: parent
                        hoverEnabled: true
                        cursorShape: Qt.PointingHandCursor
                        onClicked: {
                            mainPage.videoZoom = 1.0
                            mainPage.videoOffsetX = 0
                            mainPage.videoOffsetY = 0
                            // ⭐ 推送本地视觉效果到其他PC
                            sendLocalViewUpdate(1.0, 0, 0)
                        }
                    }
                }
                
                // 旋转按钮
                Rectangle {
                    width: 36
                    height: 32
                    radius: 4
                    color: rotateBtn.containsMouse ? "#C8E6C9" : "#80000000"
                    
                    Text {
                        anchors.centerIn: parent
                        text: mainPage.videoRotation + "°"
                        font.pixelSize: 12
                        font.family: "PingFang HK"
                        font.bold: true
                        color: rotateBtn.containsMouse ? "#263238" : "#FFFFFF"
                    }
                    
                    MouseArea {
                        id: rotateBtn
                        anchors.fill: parent
                        hoverEnabled: true
                        cursorShape: Qt.PointingHandCursor
                        onClicked: {
                            mainPage.videoRotation = (mainPage.videoRotation + 90) % 360
                        }
                        onWheel: function(wheel) {
                            if (wheel.angleDelta.y > 0) {
                                mainPage.videoRotation = (mainPage.videoRotation + 90) % 360
                            } else {
                                mainPage.videoRotation = (mainPage.videoRotation - 90 + 360) % 360
                            }
                        }
                    }
                }
                
                // 睡眠按钮
                Rectangle {
                    width: 50
                    height: 32
                    radius: 4
                    color: sleepBtnLive.containsMouse ? "#C8E6C9" : "#80000000"
                    
                    Text {
                        anchors.centerIn: parent
                        text: "睡眠"
                        font.pixelSize: 12
                        font.family: "PingFang HK"
                        font.bold: true
                        color: sleepBtnLive.containsMouse ? "#263238" : "#FFFFFF"
                    }
                    
                    MouseArea {
                        id: sleepBtnLive
                        anchors.fill: parent
                        hoverEnabled: true
                        cursorShape: Qt.PointingHandCursor
                        onClicked: {
                            console.log("📤 点击睡眠按钮")
                            mainPage.deviceStatus = "sleeping"
                            // ⭐ 主动停止拉流并重置状态，确保唤醒后能重新连接
                            if (publishState === 1) {
                                console.log("📤 睡眠：主动停止拉流，publishState 1 → 0")
                                stopAll()
                            }
                            publishState = 0
                            sendDeviceCommand("shuimian")
                        }
                    }
                }
                
                // 工作按钮
                Rectangle {
                    width: 50
                    height: 32
                    radius: 4
                    color: workBtnLive.containsMouse ? "#C8E6C9" : "#80000000"
                    
                    Text {
                        anchors.centerIn: parent
                        text: "工作"
                        font.pixelSize: 12
                        font.family: "PingFang HK"
                        font.bold: true
                        color: workBtnLive.containsMouse ? "#263238" : "#FFFFFF"
                    }
                    
                    MouseArea {
                        id: workBtnLive
                        anchors.fill: parent
                        hoverEnabled: true
                        cursorShape: Qt.PointingHandCursor
                        onClicked: {
                            console.log("📤 点击工作按钮")
                            mainPage.deviceStatus = "waking"
                            // ⭐ 主动停止旧连接并重置状态，确保收到 CONFIG_STATE(publishStatus=1) 时能触发 playWebRTC()
                            if (publishState === 1) {
                                console.log("📤 工作：主动停止旧拉流，publishState 1 → 0")
                                stopAll()
                            }
                            publishState = 0
                            isConnecting = false
                            sendDeviceCommand("gongzuo")
                        }
                    }
                }
            }
            
            // 全局 hover 检测层（放在最上层，不拦截点击）
            MouseArea {
                id: livePanelHover
                anchors.fill: parent
                hoverEnabled: true
                acceptedButtons: Qt.NoButton  // 不拦截任何点击
                z: 1000  // 最高层级
                onContainsMouseChanged: {
                    // console.log("🖱️ livePanelHover containsMouse:", containsMouse)
                }
            }
        }
    }

    // ============ 慢放内容（可交换）============
    Item {
        id: slowmoPanelContent
        // 根据布局模式选择父容器: 模式2时放左侧，否则放右中
        parent: windowLayoutMode === 2 ? leftHolder : rightMiddleHolder
        anchors.fill: parent
        
        // 阴影
        Rectangle {
            anchors.fill: slowmoPanel
            anchors.topMargin: 1
            anchors.bottomMargin: -1
            radius: 4
            color: "#1A000000"
        }
        
        Rectangle {
            id: slowmoPanel
            anchors.fill: parent
            color: mainPage.panelBgColor  // 面板背景色（滑块可调）
            radius: 4
            clip: true

                    // 慢放视频容器（用于旋转，与实时流一致）
                    Item {
                        id: slowmoVideoContainer
                        anchors.fill: parent
                        anchors.margins: 2
                        clip: true
                        
                        // 慢放视频输出（GPU 直接渲染，避免 QImage 内存开销）
                        VideoOutput {
                            id: slowmoVideoOutput
                            // 根据旋转角度调整宽高（与实时流一致）
                            width: (mainPage.videoRotation === 90 || mainPage.videoRotation === 270) 
                                   ? parent.height : parent.width
                            height: (mainPage.videoRotation === 90 || mainPage.videoRotation === 270) 
                                    ? parent.width : parent.height
                            fillMode: VideoOutput.Stretch
                            visible: slowMotionPlayer.hasContent
                            
                            // ⭐ 慢放独立缩放 — 用 slowmoZoom/slowmoOffsetX/Y, 不再继承实时流的 videoZoom
                            //    pcActivationLevel < 2 时强制 1.0 (老版逻辑保留: 低等级无局部放大)
                            x: parent.width / 2 - width / 2 + (mainPage.pcActivationLevel >= 2 ? mainPage.slowmoOffsetX : 0)
                            y: parent.height / 2 - height / 2 + (mainPage.pcActivationLevel >= 2 ? mainPage.slowmoOffsetY : 0)

                            transform: [
                                Rotation {
                                    origin.x: slowmoVideoOutput.width / 2
                                    origin.y: slowmoVideoOutput.height / 2
                                    angle: mainPage.videoRotation
                                },
                                Scale {
                                    origin.x: slowmoVideoOutput.width / 2
                                    origin.y: slowmoVideoOutput.height / 2
                                    property real baseZoom: mainPage.pcActivationLevel >= 2 ? mainPage.slowmoZoom : 1.0
                                    xScale: mainPage.videoMirrorMode === "horizontal" ? -baseZoom : baseZoom
                                    yScale: mainPage.videoMirrorMode === "vertical" ? -baseZoom : baseZoom
                                }
                            ]
                            
                            Component.onCompleted: {
                                slowMotionPlayer.videoSink = slowmoVideoOutput.videoSink
                            }
                            
                            // 色彩调节效果（与实时流一致）
                            layer.enabled: false  // 不再使用 shader，颜色调整由 GStreamer videobalance 和 gamma 处理
                        }
                        
                        // 滚轮切换帧 + 左键上一帧 + 右键下一帧（覆盖在视频上）
                        MouseArea {
                            anchors.fill: parent
                            acceptedButtons: Qt.LeftButton | Qt.RightButton
                            z: 10  // 确保在视频之上
                            
                            onClicked: function(mouse) {
                                if (!slowMotionPlayer.hasContent) return
                                // ⭐ 左键=上一帧，右键=下一帧
                                if (mouse.button === Qt.LeftButton) {
                                    slowMotionPlayer.prevFrame()
                                } else if (mouse.button === Qt.RightButton) {
                                    slowMotionPlayer.nextFrame()
                                }
                            }
                            
                            onWheel: function(wheel) {
                                wheel.accepted = true  // 阻止事件传播
                                if (!slowMotionPlayer.hasContent) return

                                // ⭐ S+滚轮: 慢放独立局部放大 (鼠标位置为中心)
                                //   - 跟实时流 videoZoom 解耦 — 实时流不会跟着缩放
                                //   - pcActivationLevel < 2 不允许局部放大 (与"慢放跟随实时流缩放"老约束一致)
                                if (mainPage.sKeyPressed) {
                                    if (mainPage.pcActivationLevel < 2) {
                                        console.log("🔒 慢放局部放大需要至尊版")
                                        return
                                    }
                                    var oldZoom = mainPage.slowmoZoom
                                    var delta = wheel.angleDelta.y > 0 ? 0.2 : -0.2
                                    var newZoom = Math.max(1.0, Math.min(5.0, oldZoom + delta))
                                    if (newZoom === oldZoom) return

                                    // 鼠标相对容器中心
                                    var containerCenterX = slowmoVideoContainer.width / 2
                                    var containerCenterY = slowmoVideoContainer.height / 2
                                    var mouseRelX = wheel.x - containerCenterX
                                    var mouseRelY = wheel.y - containerCenterY
                                    var zoomRatio = newZoom / oldZoom

                                    var newOffsetX = mouseRelX - (mouseRelX - mainPage.slowmoOffsetX) * zoomRatio
                                    var newOffsetY = mouseRelY - (mouseRelY - mainPage.slowmoOffsetY) * zoomRatio

                                    // 边界约束: ±(containerSize × (zoom-1) / 2)
                                    var maxOffsetX = slowmoVideoContainer.width  * (newZoom - 1) / 2
                                    var maxOffsetY = slowmoVideoContainer.height * (newZoom - 1) / 2
                                    mainPage.slowmoOffsetX = Math.max(-maxOffsetX, Math.min(maxOffsetX, newOffsetX))
                                    mainPage.slowmoOffsetY = Math.max(-maxOffsetY, Math.min(maxOffsetY, newOffsetY))
                                    mainPage.slowmoZoom = newZoom

                                    if (newZoom === 1.0) {
                                        mainPage.slowmoOffsetX = 0
                                        mainPage.slowmoOffsetY = 0
                                    }
                                    console.log("🔍 慢放局部缩放:", oldZoom.toFixed(1), "->", newZoom.toFixed(1))
                                    return
                                }

                                // 普通滚轮: 切换帧 (会自动暂停跟随实时流)
                                if (wheel.angleDelta.y > 0) {
                                    slowMotionPlayer.prevFrame()
                                } else {
                                    slowMotionPlayer.nextFrame()
                                }
                            }
                        }
                    }

                    // 暂无图片（居中显示）
                    Column {
                        anchors.centerIn: parent
                        spacing: 4
                        visible: !slowMotionPlayer.hasContent

                        Image {
                            source: "images/zwtp.png"
                            width: 108
                            height: 108
                            anchors.horizontalCenter: parent.horizontalCenter
                        }

                        Text {
                            text: "暂无图片"
                            font.family: "PingFang HK"
                            font.pixelSize: 14
                            color: "#90A4AE"
                            anchors.horizontalCenter: parent.horizontalCenter
                        }
                    }
                    
            // 状态覆盖层（右上角，录制时显示）
            Rectangle {
                anchors.top: parent.top
                anchors.right: parent.right
                anchors.margins: 8
                width: stateText.width + 16
                height: stateText.height + 8
                color: "#E53935"
                radius: 4
                visible: false  // 隐藏"录制中"文字

                Text {
                    id: stateText
                    anchors.centerIn: parent
                    text: "● 录制中"
                    color: "#ffffff"
                    font.pixelSize: 12
                    font.bold: true
                }
            }

            // 慢放进度条（贴在慢放view底部，跟随窗口切换）
            Rectangle {
                id: slowmoProgressBar
                anchors.left: parent.left
                anchors.right: parent.right
                anchors.bottom: parent.bottom
                height: 44
                color: "#80000000"
                visible: slowMotionPlayer.hasContent

                RowLayout {
                    anchors.fill: parent
                    anchors.leftMargin: 16
                    anchors.rightMargin: 16
                    spacing: 12

                    // 帧数显示: 当前播放帧/已录制帧数
                    Text {
                        text: (slowMotionPlayer.currentFrame + 1) + "/" + slowMotionPlayer.recordedFrames
                        font.family: "PingFang HK"
                        font.pixelSize: 14
                        color: "#FFFFFF"
                        Layout.minimumWidth: 70
                    }

                    // 进度条
                    Item {
                        id: frameSliderContainer
                        Layout.fillWidth: true
                        height: 16

                        MouseArea {
                            anchors.fill: parent
                            onWheel: function(wheel) {
                                wheel.accepted = true
                                if (!slowMotionPlayer.hasContent) return
                                if (wheel.angleDelta.y > 0) slowMotionPlayer.prevFrame()
                                else slowMotionPlayer.nextFrame()
                            }
                            onClicked: function(mouse) {
                                if (slowMotionPlayer.recordedFrames > 1) {
                                    var ratio = mouse.x / frameSliderContainer.width
                                    var frame = Math.round(ratio * (slowMotionPlayer.recordedFrames - 1))
                                    slowMotionPlayer.jumpToFrame(frame)
                                }
                            }
                        }

                        Rectangle {
                            anchors.centerIn: parent
                            width: parent.width
                            height: 4
                            radius: 999
                            color: "#C8E6C9"
                        }

                        Rectangle {
                            id: frameHandle
                            width: 16
                            height: 16
                            radius: 8
                            color: "#A5D6A7"
                            x: slowMotionPlayer.recordedFrames > 1 ?
                               slowMotionPlayer.currentFrame / (slowMotionPlayer.recordedFrames - 1) * (parent.width - 16) : 0
                            anchors.verticalCenter: parent.verticalCenter

                            MouseArea {
                                anchors.fill: parent
                                anchors.margins: -4
                                drag.target: parent
                                drag.axis: Drag.XAxis
                                drag.minimumX: 0
                                drag.maximumX: frameSliderContainer.width - 16

                                onWheel: function(wheel) {
                                    wheel.accepted = true
                                    if (!slowMotionPlayer.hasContent) return
                                    if (wheel.angleDelta.y > 0) slowMotionPlayer.prevFrame()
                                    else slowMotionPlayer.nextFrame()
                                }

                                onPositionChanged: {
                                    if (drag.active && slowMotionPlayer.recordedFrames > 1) {
                                        var ratio = frameHandle.x / (frameSliderContainer.width - 16)
                                        var frame = Math.round(ratio * (slowMotionPlayer.recordedFrames - 1))
                                        slowMotionPlayer.jumpToFrame(frame)
                                    }
                                }
                            }
                        }
                    }

                    // 播放/暂停按钮
                    Item {
                        width: 72
                        height: 32

                        Rectangle {
                            anchors.fill: parent
                            anchors.topMargin: 2
                            radius: 6
                            color: "#30000000"
                        }

                        Rectangle {
                            anchors.left: parent.left
                            anchors.right: parent.right
                            anchors.top: parent.top
                            height: 30
                            radius: 6
                            color: playBtnArea.containsMouse ? "#A5D6A7" : "#E8F5E9"

                            Text {
                                anchors.centerIn: parent
                                text: slowMotionPlayer.isPlaying ? "暂停(Q)" : "播放(Q)"
                                font.family: "PingFang HK"
                                font.weight: Font.Medium
                                font.pixelSize: 13
                                color: slowMotionPlayer.hasContent ? "#37474F" : "#90A4AE"
                            }

                            MouseArea {
                                id: playBtnArea
                                anchors.fill: parent
                                hoverEnabled: true
                                cursorShape: Qt.PointingHandCursor
                                onClicked: slowMotionPlayer.togglePlay()
                            }
                        }
                    }
                }
            }
        }
    }
    
    // ============ 第三部分控制面板（放在右侧底部）============
    Item {
        id: controlPanelContainer
        // 放在右侧底部容器中
        parent: rightBottomHolder
        anchors.fill: parent
        
        // 阴影
        Rectangle {
            anchors.fill: controlPanel
            anchors.topMargin: 1
            anchors.leftMargin: 0
            anchors.rightMargin: 0
            anchors.bottomMargin: -1
            radius: 4
            color: "#1A000000"
        }
        
        Rectangle {
            id: controlPanel
            anchors.fill: parent
            color: mainPage.panelBgColor  // 面板背景色（滑块可调）
            radius: 4
        }

                ColumnLayout {
                    anchors.fill: parent
                    anchors.margins: 20
                    spacing: 0

                    // 慢放播放控制（已移到慢放view底部 slowmoProgressBar，这里留空占位）
                    Item { Layout.fillHeight: true }

                    // 四个按钮
                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 12

                        // 开启慢放按钮
                        Item {
                            Layout.fillWidth: true
                            height: 36
                            
                            Rectangle {
                                anchors.fill: parent
                                anchors.topMargin: 2
                                radius: 6
                                color: "#30000000"
                            }
                            
                            Rectangle {
                                id: slowmoBtn
                                anchors.left: parent.left
                                anchors.right: parent.right
                                anchors.top: parent.top
                                height: 34
                                radius: 6
                                color: slowmoBtnArea.containsMouse ? "#A5D6A7" : 
                                       (slowMotionPlayer.isRecording ? "#E57373" : "#B2DFDB")
                                
                                Text {
                                    anchors.centerIn: parent
                                    text: slowMotionPlayer.isRecording ? "停止(W)" : "慢放(W)"
                                    font.family: "PingFang HK"
                                    font.weight: Font.Medium
                                    font.pixelSize: 14
                                    color: slowMotionPlayer.isRecording ? "#FFFFFF" : "#263238"
                                }
                                
                                MouseArea {
                                    id: slowmoBtnArea
                                    anchors.fill: parent
                                    hoverEnabled: true
                                    cursorShape: Qt.PointingHandCursor
                                    onClicked: {
                                        if (slowMotionPlayer.isRecording) {
                                            slowMotionPlayer.stopRecording()
                                        } else {
                                            slowMotionPlayer.startRecording()
                                            captureManager.slowMotionActive = true  // 开启慢放抓拍模式
                                        }
                                    }
                                }
                            }
                        }

                        // 慢放倍数下拉列表
                        Item {
                            Layout.preferredWidth: 60
                            height: 36
                            
                            Rectangle {
                                anchors.fill: parent
                                anchors.topMargin: 2
                                radius: 6
                                color: "#30000000"
                            }
                            
                            Rectangle {
                                id: multiplierDropdown
                                anchors.left: parent.left
                                anchors.right: parent.right
                                anchors.top: parent.top
                                height: 34
                                radius: 6
                                color: multiplierArea.containsMouse ? "#C8E6C9" : "#E8F5E9"
                                border.color: "#A5D6A7"
                                border.width: 1
                                
                                Text {
                                    anchors.centerIn: parent
                                    text: slowMotionPlayer.playbackMultiplier + "x"
                                    font.family: "PingFang HK"
                                    font.weight: Font.Medium
                                    font.pixelSize: 14
                                    color: "#263238"
                                }
                                
                                MouseArea {
                                    id: multiplierArea
                                    anchors.fill: parent
                                    hoverEnabled: true
                                    cursorShape: Qt.PointingHandCursor
                                    onClicked: multiplierPopup.open()
                                    onWheel: function(wheel) {
                                        var current = slowMotionPlayer.playbackMultiplier
                                        if (wheel.angleDelta.y > 0) {
                                            // 向上滚，减小倍数（步长0.1）
                                            current = Math.max(1, Math.round((current - 0.1) * 10) / 10)
                                        } else {
                                            // 向下滚，增大倍数（步长0.1）
                                            current = Math.min(10, Math.round((current + 0.1) * 10) / 10)
                                        }
                                        slowMotionPlayer.playbackMultiplier = current
                                    }
                                }
                                
                                // 下拉弹出菜单
                                Popup {
                                    id: multiplierPopup
                                    y: parent.height + 4
                                    width: parent.width
                                    height: Math.min(200, multiplierListView.contentHeight + 8)
                                    padding: 4
                                    
                                    background: Rectangle {
                                        color: "#F0FFF0"
                                        border.color: "#A5D6A7"
                                        border.width: 1
                                        radius: 6
                                    }
                                    
                                    ListView {
                                        id: multiplierListView
                                        anchors.fill: parent
                                        clip: true
                                        model: [1, 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7, 1.8, 1.9, 2, 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 2.8, 2.9, 3, 3.5, 4, 4.5, 5, 6, 7, 8, 9, 10]
                                        
                                        delegate: Rectangle {
                                            width: multiplierListView.width
                                            height: 28
                                            color: multiplierItemArea.containsMouse ? "#C8E6C9" : 
                                                   (slowMotionPlayer.playbackMultiplier === modelData ? "#B2DFDB" : "transparent")
                                            radius: 4
                                            
                                            Text {
                                                anchors.centerIn: parent
                                                text: modelData + "x"
                                                font.family: "PingFang HK"
                                                font.pixelSize: 13
                                                color: slowMotionPlayer.playbackMultiplier === modelData ? "#00796B" : "#37474F"
                                                font.weight: slowMotionPlayer.playbackMultiplier === modelData ? Font.Medium : Font.Normal
                                            }
                                            
                                            MouseArea {
                                                id: multiplierItemArea
                                                anchors.fill: parent
                                                hoverEnabled: true
                                                cursorShape: Qt.PointingHandCursor
                                                onClicked: {
                                                    slowMotionPlayer.playbackMultiplier = modelData
                                                    multiplierPopup.close()
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // 抓拍按钮（已隐藏，使用空格键触发）
                        Item {
                            visible: false
                            Layout.fillWidth: true
                            height: 36
                            
                            Rectangle {
                                anchors.fill: parent
                                anchors.topMargin: 2
                                radius: 6
                                color: "#30000000"
                            }
                            
                            Rectangle {
                                anchors.left: parent.left
                                anchors.right: parent.right
                                anchors.top: parent.top
                                height: 34
                                radius: 6
                                color: captureBtnArea.containsMouse ? "#A5D6A7" : "#B2DFDB"
                                
                                Text {
                                    anchors.centerIn: parent
                                    text: "抓拍(Space)"
                                    font.family: "PingFang HK"
                                    font.weight: Font.Medium
                                    font.pixelSize: 14
                                    color: "#37474F"
                                }
                                
                                MouseArea {
                                    id: captureBtnArea
                                    anchors.fill: parent
                                    hoverEnabled: true
                                    cursorShape: Qt.PointingHandCursor
                                    onClicked: EventBus.triggerCapture()
                                }
                            }
                        }

                        // 慢放清空按钮
                        Item {
                            Layout.fillWidth: true
                            height: 36
                            
                            Rectangle {
                                anchors.fill: parent
                                anchors.topMargin: 2
                                radius: 6
                                color: "#30000000"
                            }
                            
                            Rectangle {
                                id: slowmoClearBtn
                                anchors.left: parent.left
                                anchors.right: parent.right
                                anchors.top: parent.top
                                height: 34
                                radius: 6
                                color: slowmoClearBtnArea.containsMouse ? "#A5D6A7" : "#B2DFDB"
                                
                                Text {
                                    anchors.centerIn: parent
                                    text: "慢放清空(E)"
                                    font.family: "PingFang HK"
                                    font.weight: Font.Medium
                                    font.pixelSize: 14
                                    color: "#37474F"
                                }
                                
                                MouseArea {
                                    id: slowmoClearBtnArea
                                    anchors.fill: parent
                                    hoverEnabled: true
                                    cursorShape: Qt.PointingHandCursor
                                    onClicked: {
                                        slowMotionPlayer.clear()
                                        captureManager.slowMotionActive = false  // 关闭慢放抓拍模式
                                    }
                                }
                            }
                        }

                        // 抓拍清空按钮
                        Item {
                            Layout.fillWidth: true
                            height: 36
                            
                            Rectangle {
                                anchors.fill: parent
                                anchors.topMargin: 2
                                radius: 6
                                color: "#30000000"
                            }
                            
                            Rectangle {
                                anchors.left: parent.left
                                anchors.right: parent.right
                                anchors.top: parent.top
                                height: 34
                                radius: 6
                                color: captureClearBtnArea.containsMouse ? "#A5D6A7" : "#B2DFDB"
                                
                                Text {
                                    anchors.centerIn: parent
                                    text: "抓拍清空(C)"
                                    font.family: "PingFang HK"
                                    font.weight: Font.Medium
                                    font.pixelSize: 14
                                    color: "#37474F"
                                }
                                
                                MouseArea {
                                    id: captureClearBtnArea
                                    anchors.fill: parent
                                    hoverEnabled: true
                                    cursorShape: Qt.PointingHandCursor
                                    onClicked: EventBus.triggerClear()
                                }
                            }
                        }
                    }
                    
                    Item { Layout.fillHeight: true }

                    // 抓拍模式（行、列）
                    RowLayout {
                        Layout.fillWidth: true
                        Layout.leftMargin: 12
                        spacing: 40

                        Text {
                            text: "抓拍模式"
                            font.family: "PingFang HK"
                            font.pixelSize: 14
                            color: mainPage.panelTextColor  // 文字色随面板调整
                        }

                        RowLayout {
                            Layout.fillWidth: true
                            spacing: 24

                            // 行
                            RowLayout {
                                Layout.fillWidth: true
                                spacing: 24

                                Text {
                                    text: "行"
                                    font.family: "PingFang HK"
                                    font.pixelSize: 14
                                    color: mainPage.panelTextColor  // 文字色随面板调整
                                }

                                Item {
                                    Layout.fillWidth: true
                                    height: 22
                                    
                                    Text {
                                        id: rowsText
                                        anchors.left: parent.left
                                        anchors.verticalCenter: parent.verticalCenter
                                        text: captureManager.gridRows.toString()
                                        font.family: "PingFang HK"
                                        font.pixelSize: 16
                                        color: mainPage.panelTextColor  // 文字色随面板调整
                                    }
                                    
                                    // 下拉三角
                                    Item {
                                        anchors.right: parent.right
                                        anchors.verticalCenter: parent.verticalCenter
                                        width: 16
                                        height: 16
                                        
                                        Canvas {
                                            anchors.centerIn: parent
                                            width: 8
                                            height: 5
                                            onPaint: {
                                                var ctx = getContext("2d")
                                                ctx.fillStyle = "#FFFFFF"  // 白色三角（深灰面板）
                                                ctx.beginPath()
                                                ctx.moveTo(0, 0)
                                                ctx.lineTo(8, 0)
                                                ctx.lineTo(4, 5)
                                                ctx.closePath()
                                                ctx.fill()
                                            }
                                        }
                                    }
                                    
                                    Rectangle {
                                        anchors.left: parent.left
                                        anchors.right: parent.right
                                        anchors.bottom: parent.bottom
                                        height: 1.4
                                        color: "#A5D6A7"
                                    }
                                    
                                    MouseArea {
                                        anchors.fill: parent
                                        cursorShape: Qt.PointingHandCursor
                                        onClicked: rowsInput.popup.open()
                                        onWheel: function(wheel) {
                                            if (wheel.angleDelta.y > 0 && rowsInput.currentIndex > 0) {
                                                rowsInput.currentIndex--
                                            } else if (wheel.angleDelta.y < 0 && rowsInput.currentIndex < rowsInput.count - 1) {
                                                rowsInput.currentIndex++
                                            }
                                            pendingRows = parseInt(rowsInput.currentText)
                                            gridUpdateTimer.restart()
                                        }
                                    }
                                    
                                    ComboBox {
                                        id: rowsInput
                                        visible: false
                                        model: ["1", "2", "3", "4", "5", "6", "7", "8", "9", "10"]
                                        currentIndex: captureManager.gridRows - 1
                                        onActivated: captureManager.gridRows = parseInt(currentText)
                                    }
                                }
                            }

                            // 列
                            RowLayout {
                                Layout.fillWidth: true
                                spacing: 24

                                Text {
                                    text: "列"
                                    font.family: "PingFang HK"
                                    font.pixelSize: 14
                                    color: mainPage.panelTextColor  // 文字色随面板调整
                                }

                                Item {
                                    Layout.fillWidth: true
                                    height: 22
                                    
                                    Text {
                                        id: colsText
                                        anchors.left: parent.left
                                        anchors.verticalCenter: parent.verticalCenter
                                        text: captureManager.gridCols.toString()
                                        font.family: "PingFang HK"
                                        font.pixelSize: 16
                                        color: mainPage.panelTextColor  // 文字色随面板调整
                                    }
                                    
                                    // 下拉三角
                                    Item {
                                        anchors.right: parent.right
                                        anchors.verticalCenter: parent.verticalCenter
                                        width: 16
                                        height: 16
                                        
                                        Canvas {
                                            anchors.centerIn: parent
                                            width: 8
                                            height: 5
                                            onPaint: {
                                                var ctx = getContext("2d")
                                                ctx.fillStyle = "#FFFFFF"  // 白色三角（深灰面板）
                                                ctx.beginPath()
                                                ctx.moveTo(0, 0)
                                                ctx.lineTo(8, 0)
                                                ctx.lineTo(4, 5)
                                                ctx.closePath()
                                                ctx.fill()
                                            }
                                        }
                                    }
                                    
                                    Rectangle {
                                        anchors.left: parent.left
                                        anchors.right: parent.right
                                        anchors.bottom: parent.bottom
                                        height: 1.4
                                        color: "#A5D6A7"
                                    }
                                    
                                    MouseArea {
                                        anchors.fill: parent
                                        cursorShape: Qt.PointingHandCursor
                                        onClicked: colsInput.popup.open()
                                        onWheel: function(wheel) {
                                            if (wheel.angleDelta.y > 0 && colsInput.currentIndex > 0) {
                                                colsInput.currentIndex--
                                            } else if (wheel.angleDelta.y < 0 && colsInput.currentIndex < colsInput.count - 1) {
                                                colsInput.currentIndex++
                                            }
                                            pendingCols = parseInt(colsInput.currentText)
                                            gridUpdateTimer.restart()
                                        }
                                    }
                                    
                                    ComboBox {
                                        id: colsInput
                                        visible: false
                                        model: ["1", "2", "3", "4", "5", "6", "7", "8", "9", "10"]
                                        currentIndex: captureManager.gridCols - 1
                                        onActivated: captureManager.gridCols = parseInt(currentText)
                                    }
                                }
                            }
                        }
                    }
                    
                    Item { Layout.fillHeight: true }

                    // 预抓拍张数、后抓拍张数
                    RowLayout {
                        Layout.fillWidth: true
                        Layout.leftMargin: 12
                        spacing: 40

                        // 预抓拍张数
                        RowLayout {
                            Layout.fillWidth: true
                            spacing: 24

                            RowLayout {
                                spacing: 4
                                
                                Rectangle {
                                    width: 14
                                    height: 14
                                    radius: 4
                                    color: preCaptureCheck.checked ? "#B2DFDB" : "transparent"
                                    border.width: preCaptureCheck.checked ? 0 : 1
                                    border.color: "#A5D6A7"
                                    
                                    // 勾选图标
                                    Canvas {
                                        anchors.centerIn: parent
                                        width: 7
                                        height: 5
                                        visible: preCaptureCheck.checked
                                        onPaint: {
                                            var ctx = getContext("2d")
                                            ctx.strokeStyle = "#E8F5E9"
                                            ctx.lineWidth = 1.2
                                            ctx.beginPath()
                                            ctx.moveTo(0.5, 2.5)
                                            ctx.lineTo(2.5, 4.5)
                                            ctx.lineTo(6.5, 0.5)
                                            ctx.stroke()
                                        }
                                    }
                                    
                                    MouseArea {
                                        anchors.fill: parent
                                        cursorShape: Qt.PointingHandCursor
                                        onClicked: preCaptureCheck.checked = !preCaptureCheck.checked
                                    }
                                }
                                
                                Text {
                                    text: "预抓拍张数"
                                    font.family: "PingFang HK"
                                    font.pixelSize: 14
                                    color: mainPage.panelTextColor  // 文字色随面板调整
                                    
                                    MouseArea {
                                        anchors.fill: parent
                                        cursorShape: Qt.PointingHandCursor
                                        onClicked: preCaptureCheck.checked = !preCaptureCheck.checked
                                    }
                                }
                                
                                CheckBox { id: preCaptureCheck; visible: false; checked: true }
                            }

                            Item {
                                Layout.fillWidth: true
                                height: 22
                                
                                Text {
                                    anchors.left: parent.left
                                    anchors.verticalCenter: parent.verticalCenter
                                    text: captureManager.preFrameCount.toString()
                                    font.family: "PingFang HK"
                                    font.pixelSize: 16
                                    color: preCaptureCheck.checked ? "#FFFFFF" : "#B0B0B0"  // 白色文字（深灰面板）
                                }
                                
                                // 下拉三角
                                Item {
                                    anchors.right: parent.right
                                    anchors.verticalCenter: parent.verticalCenter
                                    width: 16
                                    height: 16
                                    
                                    Canvas {
                                        anchors.centerIn: parent
                                        width: 8
                                        height: 5
                                        onPaint: {
                                            var ctx = getContext("2d")
                                            ctx.fillStyle = "#FFFFFF"  // 白色三角（深灰面板）
                                            ctx.beginPath()
                                            ctx.moveTo(0, 0)
                                            ctx.lineTo(8, 0)
                                            ctx.lineTo(4, 5)
                                            ctx.closePath()
                                            ctx.fill()
                                        }
                                    }
                                }
                                
                                Rectangle {
                                    anchors.left: parent.left
                                    anchors.right: parent.right
                                    anchors.bottom: parent.bottom
                                    height: 1.4
                                    color: "#90A4AE"
                                }
                                
                                MouseArea {
                                    anchors.fill: parent
                                    enabled: preCaptureCheck.checked
                                    cursorShape: Qt.PointingHandCursor
                                    onClicked: preFramesInput.popup.open()
                                    onWheel: function(wheel) {
                                        if (!preCaptureCheck.checked) return
                                        if (wheel.angleDelta.y > 0 && preFramesInput.currentIndex > 0) {
                                            preFramesInput.currentIndex--
                                        } else if (wheel.angleDelta.y < 0 && preFramesInput.currentIndex < preFramesInput.count - 1) {
                                            preFramesInput.currentIndex++
                                        }
                                        captureManager.preFrameCount = parseInt(preFramesInput.currentText)
                                    }
                                }
                                
                                ComboBox {
                                    id: preFramesInput
                                    visible: false
                                    model: ["10", "15", "20", "30", "40", "50", "60", "80", "100", "120"]  // 最大120
                                    enabled: preCaptureCheck.checked
                                    onActivated: {
                                        captureManager.preFrameCount = parseInt(currentText)
                                    }
                                    
                                    function syncIndex() {
                                        var val = captureManager.preFrameCount
                                        if (val > 120) val = 120  // 限制最大120
                                        var valStr = val.toString()
                                        for (var i = 0; i < model.length; i++) {
                                            if (model[i] === valStr) { currentIndex = i; return; }
                                        }
                                        // 找最接近的值
                                        for (var j = model.length - 1; j >= 0; j--) {
                                            if (parseInt(model[j]) <= val) { currentIndex = j; return; }
                                        }
                                        currentIndex = 0
                                    }
                                    
                                    Component.onCompleted: syncIndex()
                                    
                                    Connections {
                                        target: captureManager
                                        function onPreFrameCountChanged() { preFramesInput.syncIndex() }
                                    }
                                }
                            }
                        }

                        // 后抓拍张数
                        RowLayout {
                            Layout.fillWidth: true
                            spacing: 24

                            Text {
                                text: "后抓拍张数"
                                font.family: "PingFang HK"
                                font.pixelSize: 14
                                color: mainPage.panelTextColor  // 文字色随面板调整
                            }

                            Item {
                                Layout.fillWidth: true
                                height: 22
                                
                                Text {
                                    anchors.left: parent.left
                                    anchors.verticalCenter: parent.verticalCenter
                                    text: captureManager.postFrameCount >= 1000 ? "无限" : captureManager.postFrameCount.toString()
                                    font.family: "PingFang HK"
                                    font.pixelSize: 16
                                    color: mainPage.panelTextColor  // 文字色随面板调整
                                }
                                
                                // 下拉三角
                                Item {
                                    anchors.right: parent.right
                                    anchors.verticalCenter: parent.verticalCenter
                                    width: 16
                                    height: 16
                                    
                                    Canvas {
                                        anchors.centerIn: parent
                                        width: 8
                                        height: 5
                                        onPaint: {
                                            var ctx = getContext("2d")
                                            ctx.fillStyle = "#FFFFFF"  // 白色三角（深灰面板）
                                            ctx.beginPath()
                                            ctx.moveTo(0, 0)
                                            ctx.lineTo(8, 0)
                                            ctx.lineTo(4, 5)
                                            ctx.closePath()
                                            ctx.fill()
                                        }
                                    }
                                }
                                
                                Rectangle {
                                    anchors.left: parent.left
                                    anchors.right: parent.right
                                    anchors.bottom: parent.bottom
                                    height: 1.4
                                    color: "#90A4AE"
                                }
                                
                                MouseArea {
                                    anchors.fill: parent
                                    cursorShape: Qt.PointingHandCursor
                                    onClicked: postFramesInput.popup.open()
                                    onWheel: function(wheel) {
                                        if (wheel.angleDelta.y > 0 && postFramesInput.currentIndex > 0) {
                                            postFramesInput.currentIndex--
                                        } else if (wheel.angleDelta.y < 0 && postFramesInput.currentIndex < postFramesInput.count - 1) {
                                            postFramesInput.currentIndex++
                                        }
                                        var text = postFramesInput.currentText
                                        captureManager.postFrameCount = (text === "无限") ? 1000 : parseInt(text)
                                    }
                                }
                                
                                ComboBox {
                                    id: postFramesInput
                                    visible: false
                                    model: ["10", "15", "20", "30", "40", "50", "60", "80", "100", "120", "150", "180", "200", "240", "无限"]
                                    onActivated: {
                                        var text = currentText
                                        captureManager.postFrameCount = (text === "无限") ? 1000 : parseInt(text)
                                    }
                                    
                                    function syncIndex() {
                                        var val = captureManager.postFrameCount
                                        if (val >= 1000) { currentIndex = model.length - 1; return; }
                                        var valStr = val.toString()
                                        for (var i = 0; i < model.length; i++) {
                                            if (model[i] === valStr) { currentIndex = i; return; }
                                        }
                                        // 找最接近的值
                                        for (var j = model.length - 2; j >= 0; j--) {
                                            if (parseInt(model[j]) <= val) { currentIndex = j; return; }
                                        }
                                        currentIndex = 0
                                    }
                                    
                                    Component.onCompleted: syncIndex()
                                    
                                    Connections {
                                        target: captureManager
                                        function onPostFrameCountChanged() { postFramesInput.syncIndex() }
                                    }
                                }
                            }
                        }
                    }
                }
        }

    // 底部状态栏已移除，中间内容区域扩展到底部
    // 保留隐藏的状态文本元素以保持代码引用有效
    Text { id: statusText; visible: false }
    Text { id: deviceStatusText; visible: false }

    // ============ 快捷键 ============
    Shortcut { 
        sequence: "Space"
        context: Qt.ApplicationShortcut
        onActivated: {
            // 如果清空确认对话框打开，空格键触发确认
            if (clearCaptureConfirmDialog.visible) {
                console.log("空格键确认清空")
                clearCaptureConfirmDialog.close()
                captureManager.clearAll()
            } else {
                console.log("空格键抓拍")
                EventBus.triggerCapture()
            }
        }
    }
    
    // W键：开启/停止慢放
    Shortcut { 
        sequence: "W"
        context: Qt.ApplicationShortcut
        onActivated: {
            if (slowMotionPlayer.isRecording) {
                slowMotionPlayer.stopRecording()
            } else {
                slowMotionPlayer.startRecording()
                captureManager.slowMotionActive = true  // 开启慢放抓拍模式
            }
        }
    }
    
    // Q键：慢放播放/暂停
    Shortcut {
        sequence: "Q"
        context: Qt.ApplicationShortcut
        onActivated: slowMotionPlayer.togglePlay()
    }
    
    // E键：慢放清空
    Shortcut { 
        sequence: "E"
        context: Qt.ApplicationShortcut
        onActivated: {
            slowMotionPlayer.clear()
            captureManager.slowMotionActive = false  // 关闭慢放抓拍模式
        }
    }
    
    // C键：抓拍清空
    Shortcut { 
        sequence: "C"
        context: Qt.ApplicationShortcut
        onActivated: EventBus.triggerClear()
    }
    
    // D键：删除最后一个抓拍item
    Shortcut {
        sequence: "D"
        context: Qt.ApplicationShortcut
        onActivated: {
            if (captureManager.count > 0) {
                captureManager.removeItem(captureManager.count - 1)
            }
        }
    }
    
    // F1键：行数增加
    Shortcut {
        sequence: "F1"
        context: Qt.ApplicationShortcut
        onActivated: {
            if (captureManager.gridRows < 10) {
                captureManager.gridRows = captureManager.gridRows + 1
                console.log("F1: 行数增加到", captureManager.gridRows)
            }
        }
    }
    
    // F2键：行数减少
    Shortcut {
        sequence: "F2"
        context: Qt.ApplicationShortcut
        onActivated: {
            if (captureManager.gridRows > 1) {
                captureManager.gridRows = captureManager.gridRows - 1
                console.log("F2: 行数减少到", captureManager.gridRows)
            }
        }
    }
    
    // F3键：列数增加
    Shortcut {
        sequence: "F3"
        context: Qt.ApplicationShortcut
        onActivated: {
            if (captureManager.gridCols < 10) {
                captureManager.gridCols = captureManager.gridCols + 1
                console.log("F3: 列数增加到", captureManager.gridCols)
            }
        }
    }
    
    // F4键：列数减少
    Shortcut {
        sequence: "F4"
        context: Qt.ApplicationShortcut
        onActivated: {
            if (captureManager.gridCols > 1) {
                captureManager.gridCols = captureManager.gridCols - 1
                console.log("F4: 列数减少到", captureManager.gridCols)
            }
        }
    }

    // ⭐ F5/F6/F7/F8: 设置上下帧跳跃步长 (1/2/3/4 帧). 影响单 item / Ctrl 同步 / 列预览 / 全屏, 不影响慢放
    Shortcut { sequence: "F5"; context: Qt.ApplicationShortcut; onActivated: { mainPage.frameStep = 1; console.log("F5: frameStep=1") } }
    Shortcut { sequence: "F6"; context: Qt.ApplicationShortcut; onActivated: { mainPage.frameStep = 2; console.log("F6: frameStep=2") } }
    Shortcut { sequence: "F7"; context: Qt.ApplicationShortcut; onActivated: { mainPage.frameStep = 3; console.log("F7: frameStep=3") } }
    Shortcut { sequence: "F8"; context: Qt.ApplicationShortcut; onActivated: { mainPage.frameStep = 4; console.log("F8: frameStep=4") } }

    // 数字键1-9, 0：列预览（显示对应列的所有截图，0代表第10列）
    Shortcut { sequence: "1"; context: Qt.ApplicationShortcut; onActivated: toggleColumnPreview(1) }
    Shortcut { sequence: "2"; context: Qt.ApplicationShortcut; onActivated: toggleColumnPreview(2) }
    Shortcut { sequence: "3"; context: Qt.ApplicationShortcut; onActivated: toggleColumnPreview(3) }
    Shortcut { sequence: "4"; context: Qt.ApplicationShortcut; onActivated: toggleColumnPreview(4) }
    Shortcut { sequence: "5"; context: Qt.ApplicationShortcut; onActivated: toggleColumnPreview(5) }
    Shortcut { sequence: "6"; context: Qt.ApplicationShortcut; onActivated: toggleColumnPreview(6) }
    Shortcut { sequence: "7"; context: Qt.ApplicationShortcut; onActivated: toggleColumnPreview(7) }
    Shortcut { sequence: "8"; context: Qt.ApplicationShortcut; onActivated: toggleColumnPreview(8) }
    Shortcut { sequence: "9"; context: Qt.ApplicationShortcut; onActivated: toggleColumnPreview(9) }
    Shortcut { sequence: "0"; context: Qt.ApplicationShortcut; onActivated: toggleColumnPreview(10) }  // 0键 = 第10列
    
    // Z/X键：列预览时切换上/下列
    Shortcut {
        sequence: "Z"; context: Qt.ApplicationShortcut
        onActivated: { if (columnPreviewVisible) columnPreviewPrevCol() }
    }
    Shortcut {
        sequence: "X"; context: Qt.ApplicationShortcut
        onActivated: { if (columnPreviewVisible) columnPreviewNextCol() }
    }
    
    // A键：列预览放大 > 全屏查看（谁在最上面服务于谁）
    // ⭐ 层级：列预览放大(z:1002) > 列预览(z:1001) > 全屏查看(z:1000) > 截图grid
    Shortcut {
        sequence: ShortcutStore.fullscreenViewerKey
        context: Qt.ApplicationShortcut
        onActivated: {
            console.log("🔑 A键按下, columnPreviewVisible:", columnPreviewVisible, "zoomIdx:", columnPreviewZoomItemIdx, "fullscreenVisible:", fullscreenViewerVisible)
            // ⭐ 列预览模式：A键服务于列查看器
            if (columnPreviewVisible) {
                if (columnPreviewZoomItemIdx >= 0) {
                    // 已在列预览放大 → 关闭放大
                    closeColumnPreviewZoom()
                } else if (columnPreviewHoveredIndex >= 0 && columnPreviewHoveredIndex < columnPreviewItems.length) {
                    // 打开列预览放大（悬停的元素）
                    openColumnPreviewZoom(columnPreviewHoveredIndex)
                }
                return
            }
            // ⭐ 原有逻辑：全屏查看
            if (fullscreenViewerVisible) {
                closeFullscreenViewer()
                console.log("🔑 关闭放大查看")
            } else if (mainPage.pcActivationLevel < 2) {
                console.log("🔒 全屏放大需要至尊版")
            } else if (captureManager.currentItemIndex >= 0 && captureManager.currentItemIndex < captureManager.count) {
                fullscreenViewerMode = appSettings.halfScreenViewMode ? 1 : 0
                console.log("🔑 打开抓拍放大查看, itemIndex:", captureManager.currentItemIndex, "模式:", appSettings.halfScreenViewMode ? "半屏" : "全屏")
                openFullscreenViewer(captureManager.currentItemIndex)
            }
        }
    }
    
    // ESC键：关闭（层级：列预览放大 > 列预览 > 全屏查看）
    Shortcut {
        sequence: "Escape"
        context: Qt.ApplicationShortcut
        onActivated: {
            console.log("🔑 ESC键按下")
            if (columnPreviewVisible && columnPreviewZoomItemIdx >= 0) {
                closeColumnPreviewZoom()  // 先关闭A键放大
            } else if (columnPreviewVisible) {
                closeColumnPreview()
            } else if (fullscreenViewerVisible) {
                closeFullscreenViewer()
            }
        }
    }
    
    // 左右键：帧切换（列预览放大 > 列预览 > 全屏查看 > 慢放 > 抓拍item）
    // 左键：上一帧
    Shortcut {
        sequence: "Left"
        context: Qt.ApplicationShortcut
        onActivated: {
            if (columnPreviewVisible && columnPreviewZoomItemIdx >= 0) {
                // A键放大模式：切换放大图的帧
                columnPreviewZoomPrevFrame()
            } else if (columnPreviewVisible) {
                // 列预览模式：所有图片同时上一帧
                columnPreviewPrevFrame()
            } else if (fullscreenViewerVisible) {
                // 全屏查看模式：切换帧 (frameStep)
                var totalFrames = captureManager.getTotalFrames(fullscreenItemIndex)
                console.log("⬅️ 全屏左键: totalFrames=" + totalFrames + " current=" + fullscreenFrameIndex + " step=" + mainPage.frameStep)
                if (totalFrames > 0 && fullscreenFrameIndex > 0) {
                    fullscreenFrameIndex = Math.max(0, fullscreenFrameIndex - mainPage.frameStep)
                    fullscreenRefreshToken = Date.now()
                    captureManager.gotoFrame(fullscreenItemIndex, fullscreenFrameIndex)
                }
            } else if (slowMotionPlayer.hasContent) {
                slowMotionPlayer.prevFrame()
            } else if (captureManager.currentItemIndex >= 0) {
                stepCaptureFrame(captureManager.currentItemIndex, "prev")
            }
        }
    }
    // 右键：下一帧
    Shortcut {
        sequence: "Right"
        context: Qt.ApplicationShortcut
        onActivated: {
            if (columnPreviewVisible && columnPreviewZoomItemIdx >= 0) {
                // A键放大模式：切换放大图的帧
                columnPreviewZoomNextFrame()
            } else if (columnPreviewVisible) {
                // 列预览模式：所有图片同时下一帧
                columnPreviewNextFrame()
            } else if (fullscreenViewerVisible) {
                var totalFrames = captureManager.getTotalFrames(fullscreenItemIndex)
                console.log("➡️ 全屏右键: totalFrames=" + totalFrames + " current=" + fullscreenFrameIndex + " step=" + mainPage.frameStep)
                if (totalFrames > 0 && fullscreenFrameIndex < totalFrames - 1) {
                    fullscreenFrameIndex = Math.min(totalFrames - 1, fullscreenFrameIndex + mainPage.frameStep)
                    fullscreenRefreshToken = Date.now()
                    captureManager.gotoFrame(fullscreenItemIndex, fullscreenFrameIndex)
                }
            } else if (slowMotionPlayer.hasContent) {
                slowMotionPlayer.nextFrame()
            } else if (captureManager.currentItemIndex >= 0) {
                stepCaptureFrame(captureManager.currentItemIndex, "next")
            }
        }
    }
    
    // P键：打开/关闭 iOS 滤镜弹框（替代旧的 PC 端曝光弹框）
    Shortcut {
        sequence: "P"
        context: Qt.ApplicationShortcut
        onActivated: {
            if (iosFilterPopup.visible) {
                iosFilterPopup.close()
            } else {
                iosFilterPopup.open()
            }
        }
    }
    
    // R键：打开/关闭相机设定弹窗（切换功能）
    Shortcut {
        sequence: "R"
        context: Qt.ApplicationShortcut
        onActivated: {
            if (iosCameraSettingsPopup.visible) {
                iosCameraSettingsPopup.close()
            } else {
                showIosCameraSettings()
            }
        }
    }

    // ============ 函数 ============
    function playWebRTC() {
        // 检查 streamKey 是否有效
        if (!currentStream || currentStream.length === 0) {
            console.log("⚠️ playWebRTC: currentStream 为空，跳过")
            return
        }
        
        // 🔥 v14: 设置连接中标志，防止 stopAll 期间的断开回调重置 publishState 导致死循环
        isConnecting = true
        
        stopAll()
        console.log("🎬 playWebRTC: currentStream=" + currentStream + " srsServer=" + srsServer)
        statusText.text = "正在连接 WebRTC..."
        
        console.log("🎬 playWebRTC: 重置 GstPlayer 状态...")
        gstPlayer.reset()
        
        console.log("🎬 playWebRTC: 连接 WebRTC...")
        webrtcClient.connect(srsServer, "tenantA", currentStream)
    }
    
    // P2P 直连模式拉流（不经过 SRS）
    function playP2P() {
        if (!pairedIosDeviceId || pairedIosDeviceId.length === 0) {
            console.log("❌ playP2P: pairedIosDeviceId 为空，无法建立 P2P 连接")
            statusText.text = "等待 iOS 设备连接..."
            return
        }
        
        isConnecting = true
        stopAll()
        
        var iceArray = iceServers.length > 0 ? iceServers : HttpClient.iceServers()
        console.log("🌐 playP2P: 配对设备=" + pairedIosDeviceId + " iceServers=" + iceArray.length + "个")
        statusText.text = "正在建立 P2P 直连..."
        
        gstPlayer.reset()
        
        console.log("🌐 playP2P: 启动 P2P 连接...")
        gstPlayer.connectP2P(pairedIosDeviceId, iceArray)
    }
    
    function stopAll() {
        console.log("🛑 stopAll: 停止所有流...")
        if (gstPlayer.isP2PMode()) {
            gstPlayer.disconnectP2P()
        } else {
            webrtcClient.disconnect()
        }
        gstPlayer.stop()
        slowMotionPlayer.clear()
        captureManager.slowMotionActive = false
        console.log("🛑 stopAll: 完成")
    }
    
    function toggleFullscreen() {
        // 保存当前比例
        var topH = rightTopHolder.height
        var middleH = rightMiddleHolder.height
        var total = topH + middleH
        if (total > 0) {
            savedHeightRatio = topH / total
        }
        
        // 使用 showMaximized 保留任务栏，而不是 showFullScreen
        if (mainWindow.visibility === Window.Maximized) {
            mainWindow.showNormal()
        } else {
            mainWindow.showMaximized()
        }
        
        // 延迟恢复比例
        windowFullscreenRestoreTimer.start()
    }
    
    Timer {
        id: windowFullscreenRestoreTimer
        interval: 50  // 减少延迟，快速恢复
        onTriggered: {
            // ⭐ 设置恢复标志，避免触发自动保存
            isRestoringRatio = true
            
            var topH = rightTopHolder.height
            var middleH = rightMiddleHolder.height
            var total = topH + middleH
            if (total > 0 && savedHeightRatio > 0) {
                rightTopHolder.SplitView.preferredHeight = total * savedHeightRatio
                rightMiddleHolder.SplitView.preferredHeight = total * (1 - savedHeightRatio)
                
                // 延迟清除标志
                Qt.callLater(function() {
                    isRestoringRatio = false
                })
            } else {
                isRestoringRatio = false
            }
        }
    }

    // ============ 对话框 ============
    
    Dialog {
        id: cameraSettingsDialog
        title: "相机设定"
        anchors.centerIn: parent
        width: 450
        modal: true
        standardButtons: Dialog.Ok | Dialog.Reset
        
        onReset: captureManager.resetCameraSettings()
        
        ColumnLayout {
            spacing: 16
            width: parent.width - 40
            
            ColumnLayout {
                spacing: 4
                Layout.fillWidth: true
                
                RowLayout {
                    Text { text: "曝光度"; font.pixelSize: 13; font.bold: true; color: "#37474F" }
                    Item { Layout.fillWidth: true }
                    Text { 
                        text: captureManager.exposure.toFixed(0) + "%"
                        font.pixelSize: 12
                        color: "#607D8B"
                    }
                }
                Slider {
                    id: exposureSlider
                    Layout.fillWidth: true
                    from: 0
                    to: 100
                    stepSize: 1
                    value: captureManager.exposure
                    onMoved: captureManager.exposure = value
                }
                Text {
                    text: "综合调节亮度、对比度、饱和度、色调、伽马"
                    font.pixelSize: 10
                    color: "#90A4AE"
                }
            }
            
            Rectangle { height: 1; Layout.fillWidth: true; color: "#C8E6C9" }
            
            ColumnLayout {
                spacing: 4
                Layout.fillWidth: true
                
                RowLayout {
                    Text { text: "亮度"; font.pixelSize: 13; color: "#37474F" }
                    Item { Layout.fillWidth: true }
                    Text { text: captureManager.brightness.toFixed(2); font.pixelSize: 12; color: "#607D8B" }
                }
                Slider {
                    Layout.fillWidth: true
                    from: -1.0; to: 1.0
                    value: captureManager.brightness
                    onMoved: captureManager.brightness = value
                }
            }
            
            ColumnLayout {
                spacing: 4
                Layout.fillWidth: true
                
                RowLayout {
                    Text { text: "对比度"; font.pixelSize: 13; color: "#37474F" }
                    Item { Layout.fillWidth: true }
                    Text { text: captureManager.contrast.toFixed(2); font.pixelSize: 12; color: "#607D8B" }
                }
                Slider {
                    Layout.fillWidth: true
                    from: 0.0; to: 2.0
                    value: captureManager.contrast
                    onMoved: captureManager.contrast = value
                }
            }
            
            ColumnLayout {
                spacing: 4
                Layout.fillWidth: true
                
                RowLayout {
                    Text { text: "饱和度"; font.pixelSize: 13; color: "#37474F" }
                    Item { Layout.fillWidth: true }
                    Text { text: captureManager.saturation.toFixed(2); font.pixelSize: 12; color: "#607D8B" }
                }
                Slider {
                    Layout.fillWidth: true
                    from: 0.0; to: 2.0
                    value: captureManager.saturation
                    onMoved: captureManager.saturation = value
                }
            }
            
            ColumnLayout {
                spacing: 4
                Layout.fillWidth: true
                
                RowLayout {
                    Text { text: "色调"; font.pixelSize: 13; color: "#37474F" }
                    Item { Layout.fillWidth: true }
                    Text { text: captureManager.hue.toFixed(2); font.pixelSize: 12; color: "#607D8B" }
                }
                Slider {
                    Layout.fillWidth: true
                    from: -1.0; to: 1.0
                    value: captureManager.hue
                    onMoved: captureManager.hue = value
                }
            }
            
            ColumnLayout {
                spacing: 4
                Layout.fillWidth: true
                
                RowLayout {
                    Text { text: "伽马"; font.pixelSize: 13; color: "#37474F" }
                    Item { Layout.fillWidth: true }
                    Text { text: captureManager.gamma.toFixed(2); font.pixelSize: 12; color: "#607D8B" }
                }
                Slider {
                    Layout.fillWidth: true
                    from: 0.01; to: 10.0
                    value: captureManager.gamma
                    onMoved: captureManager.gamma = value
                }
            }
        }
        
        Connections {
            target: captureManager
            function onCameraSettingsChanged() {
                exposureSlider.value = captureManager.exposure
            }
        }
    }
    
    // ============ iOS 相机设定 Window（独立窗口，可全屏拖动）============
    Window {
        id: iosCameraSettingsPopup
        width: 560
        height: 960
        flags: Qt.Tool | Qt.FramelessWindowHint | Qt.WindowStaysOnTopHint
        color: "transparent"
        visible: false
        
        // 兼容 Popup 的 open/close 方法
        function open() {
            // 综合亮度不从 captureManager 读取 — 使用 exposureValue 属性的当前值（跨 open/close 持久）
            // 只有重新登录时 onLoginSuccess 才会把它重置为 50
            // brightnessValue / saturationValue 是 legacy 字段，仍从 captureManager 读取（无副作用）
            brightnessValue = captureManager.brightness
            saturationValue = captureManager.saturation

            // ⭐ 显式设置滑块值（因为 onMoved 会打破绑定）
            exposureBiasSlider.value = exposureValue
            cameraBrightnessSlider.value = iosFilterPopup.fContrast        // 对比度滑块 → iOS 滤镜的 contrast
            cameraFakeExposureSlider.value = iosFilterPopup.fBrightness   // 曝光度滑块 → iOS 滤镜的 brightness
            cameraSaturationSlider.value = iosFilterPopup.fSaturation     // 红外模式滑块 → iOS 滤镜的 saturation

            visible = true
        }
        function close() { visible = false }
        
        // 拖动相关属性
        property point dragStart: Qt.point(0, 0)
        property bool dragging: false
        
        // 相机设定参数
        property double focusValue: 0.5
        property int exposureValue: 50   // ⭐ 综合亮度: 0..100, 中点 50 = iOS 滤镜全部 default
        property int flickerValue: Math.min(240, getMaxFlickerValue())  // 范围 60-400，直接下发60-400
        property int fpsValue: 30
        property int clarityValue: 50
        property double brightnessValue: 0.0  // (legacy, 现在不再使用; 保留兼容防止编译错)
        property double saturationValue: 1.10 // (legacy, 现在不再使用; 保留兼容)
        property double lensZoom: 1.0
        property string directionValue: "1"  // 摄像头方向：1=后置, 0=前置
        property string selectedButton: ""  // 睡眠/工作/刷新
        property string qualityType: "high" // 档位：low/standard/high/ultra/p4k
        property bool antiFlickerEnabled: false  // 抗频闪开关（默认关闭）
        property int antiFlickerFps: 80          // 抗频闪帧率档位（80/100/200）
        property bool testModeEnabled: false     // 测试模式：硬件EV/ISO 调亮度（玉麒麟方案对比）
        
        // 窗口内容背景
        Rectangle {
            anchors.fill: parent
            color: "#FFFFFF"
            radius: 4
            border.color: "#A5D6A7"
            border.width: 1
        
            ColumnLayout {
                spacing: 12
                anchors.fill: parent
                anchors.margins: 24
                
                // 拖动区域（标题栏）
                Rectangle {
                    Layout.fillWidth: true
                    Layout.preferredHeight: 40
                    color: "transparent"
                    
                    MouseArea {
                        anchors.fill: parent
                        cursorShape: Qt.ClosedHandCursor
                        propagateComposedEvents: false
                        
                        property point startPos: Qt.point(0, 0)
                        property point dragStartGlobal: Qt.point(0, 0)
                        
                        onPressed: function(mouse) {
                            startPos = Qt.point(iosCameraSettingsPopup.x, iosCameraSettingsPopup.y)
                            dragStartGlobal = mapToGlobal(mouse.x, mouse.y)
                            iosCameraSettingsPopup.dragging = true
                            mouse.accepted = true
                        }
                        
                        onPositionChanged: function(mouse) {
                            if (iosCameraSettingsPopup.dragging) {
                                var currentGlobal = mapToGlobal(mouse.x, mouse.y)
                                var deltaX = currentGlobal.x - dragStartGlobal.x
                                var deltaY = currentGlobal.y - dragStartGlobal.y
                                iosCameraSettingsPopup.x = startPos.x + deltaX
                                iosCameraSettingsPopup.y = startPos.y + deltaY
                            }
                        }
                        
                        onReleased: {
                            iosCameraSettingsPopup.dragging = false
                        }
                    }
                    
                    // 还原按钮（重置综合亮度为默认值20）
                    Rectangle {
                        anchors.left: parent.left
                        anchors.verticalCenter: parent.verticalCenter
                        width: resetBtnText.width + 20
                        height: 28
                        radius: 6
                        color: resetBtnArea.containsMouse ? "#C8E6C9" : "#E8F5E9"
                        border.color: "#A5D6A7"
                        border.width: 1
                        
                        Text {
                            id: resetBtnText
                            anchors.centerIn: parent
                            text: "还原"
                            font.family: "PingFang HK"
                            font.pixelSize: 14
                            color: "#263238"
                        }
                        
                        MouseArea {
                            id: resetBtnArea
                            anchors.fill: parent
                            hoverEnabled: true
                            cursorShape: Qt.PointingHandCursor
                            onClicked: {
                                // 对焦：0.6
                                iosCameraSettingsPopup.focusValue = 0.6
                                focusSlider.value = 0.6

                                // 综合亮度：回中点 50，参照登录逻辑（onLoginSuccess 也是这样做）
                                captureManager.exposure = 50
                                iosCameraSettingsPopup.exposureValue = 50
                                exposureBiasSlider.value = 50
                                iosFilterPopup.syncFromOverallBrightness(50)

                                // 对比度 / 曝光度 / 红外模式 → 重新拉服务器默认值（与登录后行为一致）
                                HttpClient.getIosFilterDefaults()

                                // 清晰度：50
                                iosCameraSettingsPopup.clarityValue = 50
                                claritySlider.value = 50

                                // 超级帧率：120
                                iosCameraSettingsPopup.flickerValue = 120
                                flickerSlider.value = 120

                                // 帧率：100
                                iosCameraSettingsPopup.fpsValue = 100
                                fpsSlider.value = 100

                                // 抗频闪：打开过就关闭
                                if (iosCameraSettingsPopup.antiFlickerEnabled) {
                                    iosCameraSettingsPopup.antiFlickerEnabled = false
                                    iosCameraSettingsPopup.antiFlickerFps = 80
                                    sendAntiFlickerConfig()
                                }

                                // 下发硬件配置
                                HttpClient.updateFocusDistance(0.6)
                                sendConfigUpdate("focus", {"focus": 0.6})
                                HttpClient.updateFlicker(120)
                                sendConfigUpdate("cjfps", {"cjfps": 120})
                                HttpClient.updateFps(100)
                                sendConfigUpdate("fps", {"fps": 100})

                                console.log("🔄 相机设定已还原（综合亮度=50, fps=100, 抗频闪关闭, 色彩参数走服务器默认值）")
                            }
                        }
                    }
                    
                    // 关闭按钮
                    Rectangle {
                        anchors.right: parent.right
                        anchors.verticalCenter: parent.verticalCenter
                        width: 24
                        height: 24
                        radius: 12
                        color: closeBtn.containsMouse ? "#C8E6C9" : "transparent"
                        
                        Text {
                            anchors.centerIn: parent
                            text: "✕"
                            font.pixelSize: 14
                            color: "#546E7A"
                        }
                        
                        MouseArea {
                            id: closeBtn
                            anchors.fill: parent
                            hoverEnabled: true
                            cursorShape: Qt.PointingHandCursor
                            onClicked: iosCameraSettingsPopup.close()
                        }
                    }
                }
            
            // ⭐ 红色提示文字
            Text {
                Layout.fillWidth: true
                text: "网络波动大请先降低：清晰度 → 分辨率 → 帧率"
                font.family: "PingFang HK"
                font.pixelSize: 24
                font.bold: true
                color: "#FF0000"
                horizontalAlignment: Text.AlignHCenter
            }
            
            // 第1行：对焦
            Column {
                Layout.fillWidth: true
                spacing: 2
                Text {
                    text: "数值越大远距离图像清楚，数值越小近距离图像清楚"
                    font.family: "PingFang HK"
                    font.pixelSize: 15
                    color: "#FF0000"
                    anchors.horizontalCenter: parent.horizontalCenter
                }
            RowLayout {
                width: parent.width
                spacing: 10
                
                Text {
                    text: "对焦"
                    font.family: "PingFang HK"
                    font.pixelSize: 16
                    color: "#263238"
                    Layout.preferredWidth: 60
                }
                
                Slider {
                    id: focusSlider
                    Layout.fillWidth: true
                    from: 0
                    to: 1
                    stepSize: 0.01
                    value: iosCameraSettingsPopup.focusValue
                    onMoved: iosCameraSettingsPopup.focusValue = value
                    onPressedChanged: if (!pressed) {
                        HttpClient.updateFocusDistance(value)
                        sendConfigUpdate("focus", {"focus": value})
                    }
                    
                    background: Rectangle {
                        x: focusSlider.leftPadding
                        y: focusSlider.topPadding + focusSlider.availableHeight / 2 - height / 2
                        implicitWidth: 200
                        implicitHeight: 4
                        width: focusSlider.availableWidth
                        height: 4
                        radius: 999
                        color: "#C8E6C9"
                        
                        Rectangle {
                            width: focusSlider.visualPosition * parent.width
                            height: parent.height
                            radius: 999
                            color: "#4DB6AC"
                        }
                    }
                    
                    handle: Rectangle {
                        x: focusSlider.leftPadding + focusSlider.visualPosition * (focusSlider.availableWidth - width)
                        y: focusSlider.topPadding + focusSlider.availableHeight / 2 - height / 2
                        implicitWidth: 14
                        implicitHeight: 14
                        width: 14
                        height: 14
                        radius: 7
                        color: "#4DB6AC"
                    }
                    
                    // ⭐ 鼠标滚轮支持
                    MouseArea {
                        anchors.fill: parent
                        acceptedButtons: Qt.NoButton
                        onWheel: function(wheel) {
                            var delta = wheel.angleDelta.y > 0 ? focusSlider.stepSize : -focusSlider.stepSize
                            var newValue = focusSlider.value + delta
                            newValue = Math.max(focusSlider.from, Math.min(focusSlider.to, newValue))
                            focusSlider.value = newValue
                            iosCameraSettingsPopup.focusValue = newValue
                            HttpClient.updateFocusDistance(newValue)
                            sendConfigUpdate("focus", {"focus": newValue})
                        }
                    }
                }
                
                Text {
                    text: iosCameraSettingsPopup.focusValue.toFixed(2)
                    font.family: "PingFang HK"
                    font.pixelSize: 16
                    color: "#263238"
                    Layout.preferredWidth: 40
                }
            }
            }
            
            // 第2行：清晰度
            Column {
                Layout.fillWidth: true
                spacing: 2
                Text {
                    text: "数值越高图片像素越高，对网速要求越高，建议远距离使用"
                    font.family: "PingFang HK"
                    font.pixelSize: 15
                    color: "#FF0000"
                    anchors.horizontalCenter: parent.horizontalCenter
                }
            RowLayout {
                width: parent.width
                spacing: 10
                
                Text {
                    text: "清晰度"
                    font.family: "PingFang HK"
                    font.pixelSize: 16
                    color: "#263238"
                    Layout.preferredWidth: 60
                }
                
                Slider {
                    id: claritySlider
                    Layout.fillWidth: true
                    from: 0
                    to: 100
                    stepSize: 1
                    value: iosCameraSettingsPopup.clarityValue
                    onMoved: iosCameraSettingsPopup.clarityValue = value
                    onPressedChanged: if (!pressed) {
                        HttpClient.updateClarity(value)
                        sendConfigUpdate("bitrate", {"bitrate": value})
                    }
                    
                    background: Rectangle {
                        x: claritySlider.leftPadding
                        y: claritySlider.topPadding + claritySlider.availableHeight / 2 - height / 2
                        implicitWidth: 200
                        implicitHeight: 4
                        width: claritySlider.availableWidth
                        height: 4
                        radius: 999
                        color: "#C8E6C9"
                        
                        Rectangle {
                            width: claritySlider.visualPosition * parent.width
                            height: parent.height
                            radius: 999
                            color: "#4DB6AC"
                        }
                    }
                    
                    handle: Rectangle {
                        x: claritySlider.leftPadding + claritySlider.visualPosition * (claritySlider.availableWidth - width)
                        y: claritySlider.topPadding + claritySlider.availableHeight / 2 - height / 2
                        implicitWidth: 14
                        implicitHeight: 14
                        width: 14
                        height: 14
                        radius: 7
                        color: "#4DB6AC"
                    }
                    
                    // ⭐ 鼠标滚轮支持
                    MouseArea {
                        anchors.fill: parent
                        acceptedButtons: Qt.NoButton
                        onWheel: function(wheel) {
                            var delta = wheel.angleDelta.y > 0 ? claritySlider.stepSize : -claritySlider.stepSize
                            var newValue = claritySlider.value + delta
                            newValue = Math.max(claritySlider.from, Math.min(claritySlider.to, newValue))
                            claritySlider.value = newValue
                            iosCameraSettingsPopup.clarityValue = newValue
                            HttpClient.updateClarity(newValue)
                            sendConfigUpdate("bitrate", {"bitrate": newValue})
                        }
                    }
                }
                
                Text {
                    text: iosCameraSettingsPopup.clarityValue
                    font.family: "PingFang HK"
                    font.pixelSize: 16
                    color: "#263238"
                    Layout.preferredWidth: 40
                }
            }
            }
            
            // 第3行：帧率（原第4行，顺序互换）
            Column {
                Layout.fillWidth: true
                spacing: 2
                
                // 说明文字（居中在滑块上方）
                Text {
                    text: "数值越高每秒钟看到图片张数越多，对网速要求越高"
                    font.family: "PingFang HK"
                    font.pixelSize: 15
                    color: "#FF0000"
                    anchors.horizontalCenter: parent.horizontalCenter
                }
                
                // 标签 + 滑块 + 数值
                RowLayout {
                    width: parent.width
                    spacing: 10
                    
                    Text {
                        text: "帧率"
                        font.family: "PingFang HK"
                        font.pixelSize: 16
                        color: "#263238"
                        Layout.preferredWidth: 60
                    }
                    
                    Slider {
                        id: fpsSlider
                        Layout.fillWidth: true
                        from: 0
                        to: 240
                        stepSize: 2  // 步长2，保证下发时为整数
                        value: iosCameraSettingsPopup.fpsValue
                        enabled: !iosCameraSettingsPopup.antiFlickerEnabled  // 抗频闪开启时禁用
                        opacity: iosCameraSettingsPopup.antiFlickerEnabled ? 0.4 : 1.0
                        
                        onMoved: {
                            // 使用全局分段函数获取上限（档位+会员等级）
                            var maxFps = getMaxFpsForQuality(iosCameraSettingsPopup.qualityType)
                            if (value > maxFps) {
                                value = maxFps  // 限制不能超过上限
                            }
                            iosCameraSettingsPopup.fpsValue = value
                        }
                        onPressedChanged: if (!pressed) {
                            // ⭐ fps 直接下发，不再除以2
                            var actualFps = Math.floor(value)
                            if (actualFps < 1) actualFps = 1
                            console.log("📤 帧率滑块松开: 滑块值=" + value + ", 实际发送=" + actualFps)
                            HttpClient.updateFps(actualFps)
                            sendConfigUpdate("fps", {"fps": actualFps})
                            // ⭐ v9.3: 同步帧率给 gstPlayer（用于网络质量检测）
                            gstPlayer.setConfigFps(actualFps / 4)  // 服务器fps转实际fps
                        }
                        
                        background: Rectangle {
                            x: fpsSlider.leftPadding
                            y: fpsSlider.topPadding + fpsSlider.availableHeight / 2 - height / 2
                            implicitWidth: 200
                            implicitHeight: 4
                            width: fpsSlider.availableWidth
                            height: 4
                            radius: 999
                            color: "#C8E6C9"
                            
                            Rectangle {
                                width: fpsSlider.visualPosition * parent.width
                                height: parent.height
                                radius: 999
                                color: "#4DB6AC"
                            }
                        }
                        
                        handle: Rectangle {
                            x: fpsSlider.leftPadding + fpsSlider.visualPosition * (fpsSlider.availableWidth - width)
                            y: fpsSlider.topPadding + fpsSlider.availableHeight / 2 - height / 2
                            implicitWidth: 14
                            implicitHeight: 14
                            width: 14
                            height: 14
                            radius: 7
                            color: "#4DB6AC"
                        }
                        
                        // ⭐ 鼠标滚轮支持
                        MouseArea {
                            anchors.fill: parent
                            acceptedButtons: Qt.NoButton
                            onWheel: function(wheel) {
                                var delta = wheel.angleDelta.y > 0 ? fpsSlider.stepSize : -fpsSlider.stepSize
                                var newValue = fpsSlider.value + delta
                                var maxFps = getMaxFpsForQuality(iosCameraSettingsPopup.qualityType)
                                newValue = Math.max(fpsSlider.from, Math.min(maxFps, newValue))
                                fpsSlider.value = newValue
                                iosCameraSettingsPopup.fpsValue = newValue
                                var actualFps = Math.floor(newValue)
                                if (actualFps < 1) actualFps = 1
                                HttpClient.updateFps(actualFps)
                                sendConfigUpdate("fps", {"fps": actualFps})
                            }
                        }
                    }
                    
                    Text {
                        text: iosCameraSettingsPopup.fpsValue
                        font.family: "PingFang HK"
                        font.pixelSize: 16
                        color: "#263238"
                        Layout.preferredWidth: 40
                    }
                }
            }
            
            // 第4行：超级帧率（原第3行，顺序互换）
            Column {
                Layout.fillWidth: true
                spacing: 2

                // 说明文字（2行，居中在滑块上方）
                Text {
                    width: parent.width
                    text: "不影响网速，数值越高每张图片拖影越小画面越暗\n超过光速会看到光闪，建议配合亮度使用"
                    font.family: "PingFang HK"
                    font.pixelSize: 15
                    color: "#FF0000"
                    horizontalAlignment: Text.AlignHCenter
                    wrapMode: Text.WordWrap
                    anchors.horizontalCenter: parent.horizontalCenter
                }
                
                // 标签 + 滑块 + 数值
                RowLayout {
                    width: parent.width
                    spacing: 10
                    
                    Text {
                        text: "超级帧率"
                        font.family: "PingFang HK"
                        font.pixelSize: 16
                        color: "#263238"
                        Layout.preferredWidth: 60
                    }
                    
                    Slider {
                        id: flickerSlider
                        Layout.fillWidth: true
                        from: 60
                        to: 600  // 滑块范围固定60-600，通过onMoved限制实际可拖动值
                        stepSize: 1
                        value: iosCameraSettingsPopup.flickerValue
                        onMoved: {
                            // 使用分段函数获取上限（档位+会员等级）
                            var maxFlicker = getMaxFlickerValue()
                            if (value > maxFlicker) {
                                value = maxFlicker  // 限制不能超过上限
                            }
                            iosCameraSettingsPopup.flickerValue = value
                        }
                        onPressedChanged: if (!pressed) {
                            // 直接下发 60-400
                            HttpClient.updateFlicker(value)
                            sendConfigUpdate("cjfps", {"cjfps": value})
                        }
                        
                        background: Rectangle {
                            x: flickerSlider.leftPadding
                            y: flickerSlider.topPadding + flickerSlider.availableHeight / 2 - height / 2
                            implicitWidth: 200
                            implicitHeight: 4
                            width: flickerSlider.availableWidth
                            height: 4
                            radius: 999
                            color: "#C8E6C9"
                            
                            Rectangle {
                                width: flickerSlider.visualPosition * parent.width
                                height: parent.height
                                radius: 999
                                color: "#4DB6AC"
                            }
                        }
                        
                        handle: Rectangle {
                            x: flickerSlider.leftPadding + flickerSlider.visualPosition * (flickerSlider.availableWidth - width)
                            y: flickerSlider.topPadding + flickerSlider.availableHeight / 2 - height / 2
                            implicitWidth: 14
                            implicitHeight: 14
                            width: 14
                            height: 14
                            radius: 7
                            color: "#4DB6AC"
                        }
                        
                        // ⭐ 鼠标滚轮支持
                        MouseArea {
                            anchors.fill: parent
                            acceptedButtons: Qt.NoButton
                        onWheel: function(wheel) {
                            var delta = wheel.angleDelta.y > 0 ? flickerSlider.stepSize : -flickerSlider.stepSize
                            var newValue = flickerSlider.value + delta
                            var maxFlicker = getMaxFlickerValue()
                            newValue = Math.max(flickerSlider.from, Math.min(maxFlicker, newValue))
                            flickerSlider.value = newValue
                            iosCameraSettingsPopup.flickerValue = newValue
                            HttpClient.updateFlicker(newValue)
                            sendConfigUpdate("cjfps", {"cjfps": newValue})
                        }
                        }
                    }
                    
                    Text {
                        // 直接显示滑块值 60-400
                        text: iosCameraSettingsPopup.flickerValue
                        font.family: "PingFang HK"
                        font.pixelSize: 16
                        color: "#263238"
                        Layout.preferredWidth: 40
                    }
                }
            }
            
            // 第5行：综合亮度
            RowLayout {
                Layout.fillWidth: true
                spacing: 10
                
                Text {
                    text: "综合亮度"
                    font.family: "PingFang HK"
                    font.pixelSize: 16
                    color: "#263238"
                    Layout.preferredWidth: 60
                }
                
                Slider {
                    id: exposureBiasSlider
                    Layout.fillWidth: true
                    from: 0
                    to: 100
                    stepSize: 1
                    value: iosCameraSettingsPopup.exposureValue
                    // ⭐ 综合亮度 = 联动驱动 iOS 滤镜的 brightness + gamma + exposure 三个滑块
                    //   不发 exposureBias 给硬件; exposureValue 属性持久化，关闭弹框再开不会复原
                    onMoved: {
                        iosCameraSettingsPopup.exposureValue = value
                        iosFilterPopup.syncFromOverallBrightness(value)
                    }
                    onPressedChanged: if (!pressed) {
                        iosFilterPopup.syncFromOverallBrightness(value)
                    }
                    
                    background: Rectangle {
                        x: exposureBiasSlider.leftPadding
                        y: exposureBiasSlider.topPadding + exposureBiasSlider.availableHeight / 2 - height / 2
                        implicitWidth: 200
                        implicitHeight: 4
                        width: exposureBiasSlider.availableWidth
                        height: 4
                        radius: 999
                        color: "#C8E6C9"
                        
                        Rectangle {
                            width: exposureBiasSlider.visualPosition * parent.width
                            height: parent.height
                            radius: 999
                            color: "#4DB6AC"
                        }
                    }
                    
                    handle: Rectangle {
                        x: exposureBiasSlider.leftPadding + exposureBiasSlider.visualPosition * (exposureBiasSlider.availableWidth - width)
                        y: exposureBiasSlider.topPadding + exposureBiasSlider.availableHeight / 2 - height / 2
                        implicitWidth: 14
                        implicitHeight: 14
                        width: 14
                        height: 14
                        radius: 7
                        color: "#4DB6AC"
                    }
                    
                    // ⭐ 鼠标滚轮支持 — 综合亮度
                    MouseArea {
                        anchors.fill: parent
                        acceptedButtons: Qt.NoButton
                        onWheel: function(wheel) {
                            var delta = wheel.angleDelta.y > 0 ? exposureBiasSlider.stepSize : -exposureBiasSlider.stepSize
                            var newValue = exposureBiasSlider.value + delta
                            newValue = Math.max(exposureBiasSlider.from, Math.min(exposureBiasSlider.to, newValue))
                            exposureBiasSlider.value = newValue
                            iosCameraSettingsPopup.exposureValue = newValue
                            iosFilterPopup.syncFromOverallBrightness(newValue)
                        }
                    }
                }
                
                Text {
                    // ⚠️ 综合亮度显示值 ×100 (内部 exposureValue 0..100 → 显示 0..10000)
                    //   故意放大量级, 使显示数字大于任何底层 iOS 滤镜参数 (brightness/gamma/contrast 都 0..2,
                    //   exposure 0.6..1.6, fps 60..240 等), 观察者看到大数字也无法反推到具体底层值, 起迷惑作用.
                    //   ⚠️ 别改回 ×10 — 那个量级跟 fps 等参数撞数, 容易被识破.
                    text: iosCameraSettingsPopup.exposureValue * 100
                    font.family: "PingFang HK"
                    font.pixelSize: 16
                    color: "#263238"
                    Layout.preferredWidth: 60
                }
            }

            // 第6行：对比度（⭐ 改成真正的对比度, 驱动 iOS 滤镜的 contrast, 双向同步 iosFilterPopup.fContrast）
            RowLayout {
                Layout.fillWidth: true
                spacing: 10

                Text {
                    text: "对比度"
                    font.family: "PingFang HK"
                    font.pixelSize: 16
                    color: "#263238"
                    Layout.preferredWidth: 60
                }

                Slider {
                    id: cameraBrightnessSlider
                    Layout.fillWidth: true
                    from: iosFilterPopup.contrastFrom
                    to: iosFilterPopup.contrastTo
                    stepSize: iosFilterPopup.contrastStep
                    value: iosFilterPopup.fContrast
                    onMoved: {
                        iosFilterPopup.syncSingle("contrast", value)
                    }
                    onPressedChanged: if (!pressed) {
                        iosFilterPopup.syncSingle("contrast", value)
                    }
                    
                    background: Rectangle {
                        x: cameraBrightnessSlider.leftPadding
                        y: cameraBrightnessSlider.topPadding + cameraBrightnessSlider.availableHeight / 2 - height / 2
                        implicitWidth: 200
                        implicitHeight: 4
                        width: cameraBrightnessSlider.availableWidth
                        height: 4
                        radius: 999
                        color: "#C8E6C9"
                        
                        Rectangle {
                            width: cameraBrightnessSlider.visualPosition * parent.width
                            height: parent.height
                            radius: 999
                            color: "#4DB6AC"
                        }
                    }
                    
                    handle: Rectangle {
                        x: cameraBrightnessSlider.leftPadding + cameraBrightnessSlider.visualPosition * (cameraBrightnessSlider.availableWidth - width)
                        y: cameraBrightnessSlider.topPadding + cameraBrightnessSlider.availableHeight / 2 - height / 2
                        implicitWidth: 14
                        implicitHeight: 14
                        width: 14
                        height: 14
                        radius: 7
                        color: "#4DB6AC"
                    }
                    
                    // ⭐ 鼠标滚轮支持 — 对比度
                    MouseArea {
                        anchors.fill: parent
                        acceptedButtons: Qt.NoButton
                        onWheel: function(wheel) {
                            var delta = wheel.angleDelta.y > 0 ? cameraBrightnessSlider.stepSize : -cameraBrightnessSlider.stepSize
                            var newValue = cameraBrightnessSlider.value + delta
                            newValue = Math.max(cameraBrightnessSlider.from, Math.min(cameraBrightnessSlider.to, newValue))
                            iosFilterPopup.syncSingle("contrast", newValue)
                        }
                    }
                }

                Text {
                    text: iosFilterPopup.fContrast.toFixed(2)
                    font.family: "PingFang HK"
                    font.pixelSize: 16
                    color: "#263238"
                    Layout.preferredWidth: 40
                }
            }

            // ====================================================================================
            // 第6.5行：⚠️ "曝光度" — 这是个迷惑名字!
            //
            //   显示标签:   "曝光度"
            //   实际驱动:   iOS 滤镜的 BRIGHTNESS (亮度) — 跟 iosFilterPopup 里那个红色"亮度"滑块完全等价,
            //               双向同步同一个底层属性 iosFilterPopup.fBrightness
            //
            //   为什么叫"曝光度": 用户/同行截图时不希望暴露真实参数名 — 故意用 iOS 滤镜里另一个真实存在的
            //   字段名 "曝光度(exposure)" 来命名这个 UI 行, 让人误以为它驱动 exposure. 实际上它就是 brightness.
            //
            //   如果勾选了 iosFilterPopup.linkBrightness, 拖动它会触发联动 (亮度方向反相: 拖高这个滑块,
            //   gamma/对比度/饱和度/曝光等其他勾选项会反向减少). 这一切跟 iosFilterPopup 那个"亮度"滑块行为一致.
            //
            //   ⚠️ 维护提醒: 不要把这里改成真正驱动 exposure! 若真要加 exposure 行, 另起一行另命名.
            // ====================================================================================
            RowLayout {
                Layout.fillWidth: true
                spacing: 10

                Text {
                    text: "曝光度"   // ⚠️ 迷惑名字, 实际驱动 brightness
                    font.family: "PingFang HK"
                    font.pixelSize: 16
                    color: "#263238"
                    Layout.preferredWidth: 60
                }

                Slider {
                    id: cameraFakeExposureSlider   // ⚠️ 名字带 fake — 实际是 brightness
                    Layout.fillWidth: true
                    from: iosFilterPopup.brightnessFrom
                    to:   iosFilterPopup.brightnessTo
                    stepSize: iosFilterPopup.brightnessStep
                    value: iosFilterPopup.fBrightness     // 双向同步同一底层

                    // ⚠️ 拖动只动 brightness 一个值 — 不级联其他联动参数
                    //    syncSingle("brightness", v) 会:
                    //      • 写 fBrightness / prevBrightness (所有绑定 fBrightness 的 UI 自动刷新:
                    //        iOS 滤镜弹框里的"亮度"滑块、本身这个"曝光度"滑块的右侧数字)
                    //      • 显式 ifMasterSlider.value = v 修复绑定被打断的情况
                    //      • pushParam → STOMP 推给 iOS
                    //    ⚠️ 不调 applyLinkedDelta — 即便勾了 linkGamma/linkContrast 等也不会跟动.
                    //    只有 综合亮度 滑块才会走多参数级联.
                    onMoved: iosFilterPopup.syncSingle("brightness", value)
                    onPressedChanged: if (!pressed) iosFilterPopup.syncSingle("brightness", value)

                    background: Rectangle {
                        x: cameraFakeExposureSlider.leftPadding
                        y: cameraFakeExposureSlider.topPadding + cameraFakeExposureSlider.availableHeight / 2 - height / 2
                        implicitWidth: 200
                        implicitHeight: 4
                        width: cameraFakeExposureSlider.availableWidth
                        height: 4
                        radius: 999
                        color: "#C8E6C9"

                        Rectangle {
                            width: cameraFakeExposureSlider.visualPosition * parent.width
                            height: parent.height
                            radius: 999
                            color: "#4DB6AC"
                        }
                    }

                    handle: Rectangle {
                        x: cameraFakeExposureSlider.leftPadding + cameraFakeExposureSlider.visualPosition * (cameraFakeExposureSlider.availableWidth - width)
                        y: cameraFakeExposureSlider.topPadding + cameraFakeExposureSlider.availableHeight / 2 - height / 2
                        implicitWidth: 14
                        implicitHeight: 14
                        width: 14
                        height: 14
                        radius: 7
                        color: "#4DB6AC"
                    }

                    // ⭐ 鼠标滚轮 — 走 syncSingle, 跟 onMoved 一样不级联其他联动参数
                    MouseArea {
                        anchors.fill: parent
                        acceptedButtons: Qt.NoButton
                        onWheel: function(wheel) {
                            if (wheel.angleDelta.y === 0) return
                            var dir = wheel.angleDelta.y > 0 ? 1 : -1
                            var nv = cameraFakeExposureSlider.value + dir * cameraFakeExposureSlider.stepSize
                            iosFilterPopup.syncSingle("brightness", nv)   // ⚠️ 单值同步, 不级联
                        }
                    }
                }

                Text {
                    // 显示真实的 brightness 数值 (即便标签叫"曝光度")
                    text: iosFilterPopup.fBrightness.toFixed(2)
                    font.family: "PingFang HK"
                    font.pixelSize: 16
                    color: "#263238"
                    Layout.preferredWidth: 40
                }
            }

            // 第7行：红外模式（独立调节，不受综合亮度联动影响）
            RowLayout {
                Layout.fillWidth: true
                spacing: 10
                
                Text {
                    text: "红外模式"
                    font.family: "PingFang HK"
                    font.pixelSize: 16
                    color: "#263238"
                    Layout.preferredWidth: 60
                }
                
                // ⭐ 红外模式: 直接驱动 iOS 滤镜的 saturation, 双向同步 iosFilterPopup.fSaturation
                Slider {
                    id: cameraSaturationSlider
                    Layout.fillWidth: true
                    from: iosFilterPopup.saturationFrom
                    to: iosFilterPopup.saturationTo
                    stepSize: iosFilterPopup.saturationStep
                    value: iosFilterPopup.fSaturation
                    onMoved: {
                        iosFilterPopup.syncSingle("saturation", value)
                    }
                    onPressedChanged: if (!pressed) {
                        iosFilterPopup.syncSingle("saturation", value)
                    }
                    
                    background: Rectangle {
                        x: cameraSaturationSlider.leftPadding
                        y: cameraSaturationSlider.topPadding + cameraSaturationSlider.availableHeight / 2 - height / 2
                        implicitWidth: 200
                        implicitHeight: 4
                        width: cameraSaturationSlider.availableWidth
                        height: 4
                        radius: 999
                        color: "#C8E6C9"
                        
                        Rectangle {
                            width: cameraSaturationSlider.visualPosition * parent.width
                            height: parent.height
                            radius: 999
                            color: "#4DB6AC"
                        }
                    }
                    
                    handle: Rectangle {
                        x: cameraSaturationSlider.leftPadding + cameraSaturationSlider.visualPosition * (cameraSaturationSlider.availableWidth - width)
                        y: cameraSaturationSlider.topPadding + cameraSaturationSlider.availableHeight / 2 - height / 2
                        implicitWidth: 14
                        implicitHeight: 14
                        width: 14
                        height: 14
                        radius: 7
                        color: "#4DB6AC"
                    }
                    
                    // ⭐ 鼠标滚轮支持 — 红外模式
                    MouseArea {
                        anchors.fill: parent
                        acceptedButtons: Qt.NoButton
                        onWheel: function(wheel) {
                            var delta = wheel.angleDelta.y > 0 ? cameraSaturationSlider.stepSize : -cameraSaturationSlider.stepSize
                            var newValue = cameraSaturationSlider.value + delta
                            newValue = Math.max(cameraSaturationSlider.from, Math.min(cameraSaturationSlider.to, newValue))
                            iosFilterPopup.syncSingle("saturation", newValue)
                        }
                    }
                }

                Text {
                    text: iosFilterPopup.fSaturation.toFixed(2)
                    font.family: "PingFang HK"
                    font.pixelSize: 16
                    color: "#263238"
                    Layout.preferredWidth: 40
                }
            }
            
            // 第8行：档位选择（单选按钮，居中，带会员等级限制）
            Item {
                Layout.fillWidth: true
                height: 36
                
                Row {
                    anchors.horizontalCenter: parent.horizontalCenter
                    spacing: 10
                    
                    // "分辨率" 提示文字
                    Text {
                        text: "分辨率"
                        font.family: "PingFang HK"
                        font.pixelSize: 13
                        color: "#78909C"
                        anchors.verticalCenter: parent.verticalCenter
                    }
                    
                    // 超低网（等级1可用，逻辑与高清相同）
                    Rectangle {
                        property bool accessible: isQualityAccessible("超低网")
                        width: 60
                        height: 32
                        radius: 16
                        color: !accessible ? "#E8E8E8" : (iosCameraSettingsPopup.qualityType === "low" ? "#4DB6AC" : "#E8F5E9")
                        border.color: !accessible ? "#C0C0C0" : (iosCameraSettingsPopup.qualityType === "low" ? "#4DB6AC" : "#A5D6A7")
                        
                        Text {
                            anchors.centerIn: parent
                            text: "超低网"
                            font.family: "PingFang HK"
                            font.pixelSize: 13
                            color: !parent.accessible ? "#999999" : (iosCameraSettingsPopup.qualityType === "low" ? "#FFFFFF" : "#333333")
                        }
                        
                        MouseArea {
                            anchors.fill: parent
                            cursorShape: parent.accessible ? Qt.PointingHandCursor : Qt.ForbiddenCursor
                            onClicked: {
                                if (parent.accessible) {
                                    switchQuality("low", "超低网")
                                } else {
                                    showQualityAccessDeniedTip("超低网")
                                }
                            }
                        }
                    }
                    
                    // 高清（所有会员可用）
                    Rectangle {
                        property bool accessible: isQualityAccessible("高清")
                        width: 60
                        height: 32
                        radius: 16
                        color: !accessible ? "#E8E8E8" : (iosCameraSettingsPopup.qualityType === "standard" ? "#4DB6AC" : "#E8F5E9")
                        border.color: !accessible ? "#C0C0C0" : (iosCameraSettingsPopup.qualityType === "standard" ? "#4DB6AC" : "#A5D6A7")
                        
                        Text {
                            anchors.centerIn: parent
                            text: "高清"
                            font.family: "PingFang HK"
                            font.pixelSize: 13
                            color: !parent.accessible ? "#999999" : (iosCameraSettingsPopup.qualityType === "standard" ? "#FFFFFF" : "#333333")
                        }
                        
                        MouseArea {
                            anchors.fill: parent
                            cursorShape: parent.accessible ? Qt.PointingHandCursor : Qt.ForbiddenCursor
                            onClicked: {
                                if (parent.accessible) {
                                    switchQuality("standard", "高清")
                                } else {
                                    showQualityAccessDeniedTip("高清")
                                }
                            }
                        }
                    }
                    
                    // 超清（所有会员可用）
                    Rectangle {
                        property bool accessible: isQualityAccessible("超清")
                        width: 60
                        height: 32
                        radius: 16
                        color: !accessible ? "#E8E8E8" : (iosCameraSettingsPopup.qualityType === "high" ? "#4DB6AC" : "#E8F5E9")
                        border.color: !accessible ? "#C0C0C0" : (iosCameraSettingsPopup.qualityType === "high" ? "#4DB6AC" : "#A5D6A7")
                        
                        Text {
                            anchors.centerIn: parent
                            text: "超清"
                            font.family: "PingFang HK"
                            font.pixelSize: 13
                            color: !parent.accessible ? "#999999" : (iosCameraSettingsPopup.qualityType === "high" ? "#FFFFFF" : "#333333")
                        }
                        
                        MouseArea {
                            anchors.fill: parent
                            cursorShape: parent.accessible ? Qt.PointingHandCursor : Qt.ForbiddenCursor
                            onClicked: {
                                if (parent.accessible) {
                                    switchQuality("high", "超清")
                                } else {
                                    showQualityAccessDeniedTip("超清")
                                }
                            }
                        }
                    }
                    
                    // 超高清（黄金会员可用）
                    Rectangle {
                        property bool accessible: isQualityAccessible("超高清")
                        width: 60
                        height: 32
                        radius: 16
                        color: !accessible ? "#E8E8E8" : (iosCameraSettingsPopup.qualityType === "p4k" ? "#4DB6AC" : "#E8F5E9")
                        border.color: !accessible ? "#C0C0C0" : (iosCameraSettingsPopup.qualityType === "p4k" ? "#4DB6AC" : "#A5D6A7")
                        
                        Text {
                            anchors.centerIn: parent
                            text: "超高清"
                            font.family: "PingFang HK"
                            font.pixelSize: 13
                            color: !parent.accessible ? "#999999" : (iosCameraSettingsPopup.qualityType === "p4k" ? "#FFFFFF" : "#333333")
                        }
                        
                        MouseArea {
                            anchors.fill: parent
                            cursorShape: parent.accessible ? Qt.PointingHandCursor : Qt.ForbiddenCursor
                            onClicked: {
                                if (parent.accessible) {
                                    switchQuality("p4k", "超高清")
                                } else {
                                    showQualityAccessDeniedTip("超高清")
                                }
                            }
                        }
                    }
                    
                    // 超高帧（黄金会员可用）
                    Rectangle {
                        property bool accessible: isQualityAccessible("超高帧")
                        width: 60
                        height: 32
                        radius: 16
                        color: !accessible ? "#E8E8E8" : (iosCameraSettingsPopup.qualityType === "ultra" ? "#4DB6AC" : "#E8F5E9")
                        border.color: !accessible ? "#C0C0C0" : (iosCameraSettingsPopup.qualityType === "ultra" ? "#4DB6AC" : "#A5D6A7")
                        
                        Text {
                            anchors.centerIn: parent
                            text: "超高帧"
                            font.family: "PingFang HK"
                            font.pixelSize: 13
                            color: !parent.accessible ? "#999999" : (iosCameraSettingsPopup.qualityType === "ultra" ? "#FFFFFF" : "#333333")
                        }
                        
                        MouseArea {
                            anchors.fill: parent
                            cursorShape: parent.accessible ? Qt.PointingHandCursor : Qt.ForbiddenCursor
                            onClicked: {
                                if (parent.accessible) {
                                    switchQuality("ultra", "超高帧")
                                } else {
                                    showQualityAccessDeniedTip("超高帧")
                                }
                            }
                        }
                    }

                    // 超快帧（等级4+后端开关，240fps高速模式）
                    Rectangle {
                        property bool accessible: isQualityAccessible("超快帧")
                        width: 60
                        height: 32
                        radius: 16
                        color: !accessible ? "#E8E8E8" : (mainPage.highSpeed240Enabled ? "#4DB6AC" : "#E8F5E9")
                        border.color: !accessible ? "#C0C0C0" : (mainPage.highSpeed240Enabled ? "#4DB6AC" : "#A5D6A7")

                        Text {
                            anchors.centerIn: parent
                            text: "超快帧"
                            font.family: "PingFang HK"
                            font.pixelSize: 13
                            color: !parent.accessible ? "#999999" : (mainPage.highSpeed240Enabled ? "#FFFFFF" : "#333333")
                        }

                        MouseArea {
                            anchors.fill: parent
                            cursorShape: Qt.PointingHandCursor
                            onClicked: {
                                showQualityAccessDeniedTip("超快帧（开发中）")
                            }
                        }
                    }
                }
            }

            // 第9行：抗频闪（开关 + 3档按钮，默认关闭）
            Item {
                Layout.fillWidth: true
                height: 36

                Row {
                    anchors.horizontalCenter: parent.horizontalCenter
                    spacing: 10

                    Text {
                        text: "抗频闪"
                        font.family: "PingFang HK"
                        font.pixelSize: 13
                        font.bold: true
                        color: "#E53935"
                        anchors.verticalCenter: parent.verticalCenter
                    }

                    // 开关
                    Rectangle {
                        width: 44; height: 24; radius: 12
                        color: iosCameraSettingsPopup.antiFlickerEnabled ? "#4DB6AC" : "#E0E0E0"
                        anchors.verticalCenter: parent.verticalCenter

                        Rectangle {
                            width: 20; height: 20; radius: 10
                            color: "#FFFFFF"
                            x: iosCameraSettingsPopup.antiFlickerEnabled ? 22 : 2
                            anchors.verticalCenter: parent.verticalCenter
                            Behavior on x { NumberAnimation { duration: 150 } }
                        }

                        MouseArea {
                            anchors.fill: parent
                            cursorShape: Qt.PointingHandCursor
                            onClicked: {
                                iosCameraSettingsPopup.antiFlickerEnabled = !iosCameraSettingsPopup.antiFlickerEnabled
                                if (iosCameraSettingsPopup.antiFlickerEnabled) {
                                    // 打开时强制同步到默认80档
                                    iosCameraSettingsPopup.antiFlickerFps = 80
                                    iosCameraSettingsPopup.fpsValue = 80
                                    fpsSlider.value = 80
                                    gstPlayer.setConfigFps(20)
                                }
                                sendAntiFlickerConfig()
                            }
                        }
                    }

                    // 80 档（20fps）
                    Rectangle {
                        width: 50; height: 32; radius: 16
                        property bool active: iosCameraSettingsPopup.antiFlickerEnabled && iosCameraSettingsPopup.antiFlickerFps === 80
                        color: !iosCameraSettingsPopup.antiFlickerEnabled ? "#E8E8E8" : (active ? "#4DB6AC" : "#E8F5E9")
                        border.color: !iosCameraSettingsPopup.antiFlickerEnabled ? "#C0C0C0" : (active ? "#4DB6AC" : "#A5D6A7")
                        Text { anchors.centerIn: parent; text: "80"; font.pixelSize: 13; font.family: "PingFang HK"; color: !iosCameraSettingsPopup.antiFlickerEnabled ? "#999" : (parent.active ? "#FFF" : "#333") }
                        MouseArea {
                            anchors.fill: parent
                            cursorShape: iosCameraSettingsPopup.antiFlickerEnabled ? Qt.PointingHandCursor : Qt.ForbiddenCursor
                            onClicked: {
                                if (!iosCameraSettingsPopup.antiFlickerEnabled) return
                                iosCameraSettingsPopup.antiFlickerFps = 80
                                iosCameraSettingsPopup.fpsValue = 80
                                fpsSlider.value = 80
                                gstPlayer.setConfigFps(20)
                                sendAntiFlickerConfig()
                            }
                        }
                    }

                    // 100 档（25fps）
                    Rectangle {
                        width: 50; height: 32; radius: 16
                        property bool active: iosCameraSettingsPopup.antiFlickerEnabled && iosCameraSettingsPopup.antiFlickerFps === 100
                        color: !iosCameraSettingsPopup.antiFlickerEnabled ? "#E8E8E8" : (active ? "#4DB6AC" : "#E8F5E9")
                        border.color: !iosCameraSettingsPopup.antiFlickerEnabled ? "#C0C0C0" : (active ? "#4DB6AC" : "#A5D6A7")
                        Text { anchors.centerIn: parent; text: "100"; font.pixelSize: 13; font.family: "PingFang HK"; color: !iosCameraSettingsPopup.antiFlickerEnabled ? "#999" : (parent.active ? "#FFF" : "#333") }
                        MouseArea {
                            anchors.fill: parent
                            cursorShape: iosCameraSettingsPopup.antiFlickerEnabled ? Qt.PointingHandCursor : Qt.ForbiddenCursor
                            onClicked: {
                                if (!iosCameraSettingsPopup.antiFlickerEnabled) return
                                iosCameraSettingsPopup.antiFlickerFps = 100
                                iosCameraSettingsPopup.fpsValue = 100
                                fpsSlider.value = 100
                                gstPlayer.setConfigFps(25)
                                sendAntiFlickerConfig()
                            }
                        }
                    }

                    // 200 档（50fps，仅 deviceLevel>=4 可用）
                    Rectangle {
                        property bool accessible: HttpClient.deviceLevel() >= 4
                        width: 50; height: 32; radius: 16
                        property bool active: iosCameraSettingsPopup.antiFlickerEnabled && iosCameraSettingsPopup.antiFlickerFps === 200
                        color: !accessible ? "#E8E8E8" : (!iosCameraSettingsPopup.antiFlickerEnabled ? "#E8E8E8" : (active ? "#4DB6AC" : "#E8F5E9"))
                        border.color: !accessible ? "#C0C0C0" : (!iosCameraSettingsPopup.antiFlickerEnabled ? "#C0C0C0" : (active ? "#4DB6AC" : "#A5D6A7"))
                        Text { anchors.centerIn: parent; text: "200"; font.pixelSize: 13; font.family: "PingFang HK"; color: !parent.accessible || !iosCameraSettingsPopup.antiFlickerEnabled ? "#999" : (parent.active ? "#FFF" : "#333") }
                        MouseArea {
                            anchors.fill: parent
                            cursorShape: (parent.accessible && iosCameraSettingsPopup.antiFlickerEnabled) ? Qt.PointingHandCursor : Qt.ForbiddenCursor
                            onClicked: {
                                if (!parent.accessible || !iosCameraSettingsPopup.antiFlickerEnabled) return
                                iosCameraSettingsPopup.antiFlickerFps = 200
                                iosCameraSettingsPopup.fpsValue = 200
                                fpsSlider.value = 200
                                gstPlayer.setConfigFps(50)
                                // 200档自动切超高帧
                                if (iosCameraSettingsPopup.qualityType !== "ultra") {
                                    switchQuality("ultra", "超高帧")
                                }
                                sendAntiFlickerConfig()
                            }
                        }
                    }
                }
            }

            // 测试模式（硬件 EV/ISO 调亮度，对比玉麒麟方案）
            Item {
                Layout.fillWidth: true
                height: 36

                Row {
                    anchors.horizontalCenter: parent.horizontalCenter
                    spacing: 10

                    Text {
                        text: "测试模式"
                        font.family: "PingFang HK"
                        font.pixelSize: 13
                        font.bold: true
                        color: "#1976D2"
                        anchors.verticalCenter: parent.verticalCenter
                    }

                    Rectangle {
                        width: 44; height: 24; radius: 12
                        color: iosCameraSettingsPopup.testModeEnabled ? "#1976D2" : "#E0E0E0"
                        anchors.verticalCenter: parent.verticalCenter

                        Rectangle {
                            width: 20; height: 20; radius: 10
                            color: "#FFFFFF"
                            x: iosCameraSettingsPopup.testModeEnabled ? 22 : 2
                            anchors.verticalCenter: parent.verticalCenter
                            Behavior on x { NumberAnimation { duration: 150 } }
                        }

                        MouseArea {
                            anchors.fill: parent
                            cursorShape: Qt.PointingHandCursor
                            onClicked: {
                                iosCameraSettingsPopup.testModeEnabled = !iosCameraSettingsPopup.testModeEnabled
                                sendTestModeConfig()
                            }
                        }
                    }

                    Text {
                        text: iosCameraSettingsPopup.testModeEnabled ? "硬件调节(玉麒麟)" : "后处理(原方案)"
                        font.family: "PingFang HK"
                        font.pixelSize: 11
                        color: "#78909C"
                        anchors.verticalCenter: parent.verticalCenter
                    }
                }
            }

            // 测试亮度滑块（仅测试模式开启时生效，独立于综合亮度）
            RowLayout {
                Layout.fillWidth: true
                Layout.leftMargin: 18
                Layout.rightMargin: 18
                spacing: 12

                Text {
                    text: "测试亮度"
                    font.family: "PingFang HK"
                    font.pixelSize: 13
                    font.bold: true
                    color: iosCameraSettingsPopup.testModeEnabled ? "#1976D2" : "#B0BEC5"
                    Layout.preferredWidth: 70
                }

                Slider {
                    id: testBrightnessSlider
                    Layout.fillWidth: true
                    from: 0
                    to: 100
                    stepSize: 1
                    value: 50
                    enabled: iosCameraSettingsPopup.testModeEnabled
                    opacity: iosCameraSettingsPopup.testModeEnabled ? 1.0 : 0.4

                    onMoved: {
                        sendTestBrightnessConfig(value)
                    }
                }

                Text {
                    text: Math.round(testBrightnessSlider.value)
                    font.family: "PingFang HK"
                    font.pixelSize: 13
                    color: iosCameraSettingsPopup.testModeEnabled ? "#1976D2" : "#B0BEC5"
                    Layout.preferredWidth: 28
                    horizontalAlignment: Text.AlignRight
                }
            }
            }  // 关闭 ColumnLayout
        }  // 关闭 Rectangle
    }  // 关闭 Window (相机设定)
    
    // 显示 iOS 相机设定
    function showIosCameraSettings() {
        // ⭐ 不再每次打开都从服务器获取配置，使用本地缓存值
        // 用户修改后的值保持在 iosCameraSettingsPopup 的属性中
        // 登录时已通过 getThinConfig() 获取过初始值

        // 设置位置：使用屏幕绝对坐标（Window 组件）
        var globalPos = cameraSettingText.mapToGlobal(0, cameraSettingText.height + 5)
        iosCameraSettingsPopup.x = globalPos.x
        iosCameraSettingsPopup.y = globalPos.y

        iosCameraSettingsPopup.open()
    }

    // 测试模式：通知 iOS 切换到硬件 EV/ISO 调亮度（玉麒麟方案对比）
    function sendTestModeConfig() {
        var enabled = iosCameraSettingsPopup.testModeEnabled
        var payload = { "cmd": "test_mode", "enabled": enabled }
        console.log("🧪 测试模式:", enabled ? "开启(硬件EV/ISO)" : "关闭(后处理)")
        sendConfigUpdate("test_mode", payload)
    }

    // 测试亮度：独立滑块（仅测试模式生效，不影响综合亮度后处理）
    function sendTestBrightnessConfig(value) {
        if (!iosCameraSettingsPopup.testModeEnabled) return
        var payload = { "cmd": "test_brightness", "value": Math.round(value) }
        sendConfigUpdate("test_brightness", payload)
    }

    // 抗频闪：发送开关和帧率档位到 iOS
    function sendAntiFlickerConfig() {
        var enabled = iosCameraSettingsPopup.antiFlickerEnabled
        var fps = iosCameraSettingsPopup.antiFlickerFps  // 80/100/200（服务器格式）
        var payload = {
            "cmd": "anti_flicker",
            "enabled": enabled,
            "fps": enabled ? fps : 0
        }
        console.log("🔦 抗频闪:", enabled ? "开启 fps=" + fps : "关闭")
        sendConfigUpdate("anti_flicker", payload)

        if (enabled) {
            // 同步 FPS 滑块 UI
            iosCameraSettingsPopup.fpsValue = fps
            fpsSlider.value = fps

            // 200 档需要超高帧档位
            if (fps === 200 && iosCameraSettingsPopup.qualityType !== "ultra") {
                switchQuality("ultra", "超高帧")
            }

            // 同步帧率给 gstPlayer
            gstPlayer.setConfigFps(fps / 4)
        }
    }
    
    // ============ 曝光值设定 Window（独立窗口，可全屏拖动）============
    Window {
        id: exposureSettingsPopup
        width: 560
        height: 360
        flags: Qt.Tool | Qt.FramelessWindowHint | Qt.WindowStaysOnTopHint
        color: "transparent"
        visible: false
        
        // 兼容 Popup 的 open/close 方法
        function open() { 
            // 打开时同步当前值
            exposureValue = captureManager.exposure
            brightnessValue = captureManager.brightness
            contrastValue = captureManager.contrast
            saturationValue = captureManager.saturation
            hueValue = captureManager.hue
            gammaValue = captureManager.gamma
            
            // 如果首次打开，居中显示
            if (!positionInitialized) {
                x = (Screen.width - width) / 2
                y = (Screen.height - height) / 2
                positionInitialized = true
            }
            visible = true
        }
        function close() { visible = false }
        
        // 拖动相关属性
        property point dragStart: Qt.point(0, 0)
        property bool dragging: false
        property bool positionInitialized: false
        
        // 曝光参数（默认值：饱和度、对比度1.10）
        property int exposureValue: 20
        property double brightnessValue: -0.02
        property double contrastValue: 1.10
        property double saturationValue: 1.10
        property double hueValue: -0.02
        property double gammaValue: 1.08
        
        // 窗口内容背景
        Rectangle {
            anchors.fill: parent
            color: "#FFFFFF"
            radius: 4
            border.color: "#A5D6A7"
            border.width: 1
        
            ColumnLayout {
                spacing: 16
                anchors.fill: parent
                anchors.margins: 24
                
                // 拖动区域（标题栏）
                Rectangle {
                    Layout.fillWidth: true
                    Layout.preferredHeight: 40
                    color: "transparent"
                    
                    MouseArea {
                        anchors.fill: parent
                        cursorShape: Qt.ClosedHandCursor
                        propagateComposedEvents: false
                        
                        property point startPos: Qt.point(0, 0)
                        property point dragStartGlobal: Qt.point(0, 0)
                        
                        onPressed: function(mouse) {
                            startPos = Qt.point(exposureSettingsPopup.x, exposureSettingsPopup.y)
                            dragStartGlobal = mapToGlobal(mouse.x, mouse.y)
                            exposureSettingsPopup.dragging = true
                            mouse.accepted = true
                        }
                        
                        onPositionChanged: function(mouse) {
                            if (exposureSettingsPopup.dragging) {
                                var currentGlobal = mapToGlobal(mouse.x, mouse.y)
                                var deltaX = currentGlobal.x - dragStartGlobal.x
                                var deltaY = currentGlobal.y - dragStartGlobal.y
                                exposureSettingsPopup.x = startPos.x + deltaX
                                exposureSettingsPopup.y = startPos.y + deltaY
                            }
                        }
                        
                        onReleased: {
                            exposureSettingsPopup.dragging = false
                        }
                    }
                    
                    Text {
                        anchors.left: parent.left
                        anchors.verticalCenter: parent.verticalCenter
                        text: "📷 曝光值设定"
                        font.family: "PingFang HK"
                        font.pixelSize: 18
                        font.bold: true
                        color: "#263238"
                    }
                    
                    // 关闭按钮
                    Rectangle {
                        anchors.right: parent.right
                        anchors.verticalCenter: parent.verticalCenter
                        width: 24
                        height: 24
                        radius: 12
                        color: closeExpBtn.containsMouse ? "#C8E6C9" : "transparent"
                        
                        Text {
                            anchors.centerIn: parent
                            text: "✕"
                            font.pixelSize: 14
                            color: "#546E7A"
                        }
                        
                        MouseArea {
                            id: closeExpBtn
                            anchors.fill: parent
                            hoverEnabled: true
                            cursorShape: Qt.PointingHandCursor
                            onClicked: exposureSettingsPopup.close()
                        }
                    }
                }
            
            // 综合亮度
            RowLayout {
                Layout.fillWidth: true
                spacing: 10
                Text { text: "综合亮度"; font.pixelSize: 16; color: "#263238"; Layout.preferredWidth: 70 }
                Slider {
                    id: expSlider
                    Layout.fillWidth: true
                    from: 0; to: 100; stepSize: 1
                    value: exposureSettingsPopup.exposureValue
                    onMoved: {
                        exposureSettingsPopup.exposureValue = value
                        iosCameraSettingsPopup.exposureValue = value  // 同步到相机设定弹框
                        captureManager.applyExposurePreview(value)
                        syncExposureParamsFromCaptureManager()
                    }
                    onPressedChanged: if (!pressed) {
                        captureManager.exposure = value
                        sendConfigUpdate("exposureBias", {"exposureBias": value})
                    }
                    background: Rectangle {
                        x: expSlider.leftPadding; y: expSlider.topPadding + expSlider.availableHeight / 2 - 2
                        implicitWidth: 200; implicitHeight: 4; width: expSlider.availableWidth; height: 4; radius: 999; color: "#C8E6C9"
                        Rectangle { width: expSlider.visualPosition * parent.width; height: parent.height; radius: 999; color: "#4DB6AC" }
                    }
                    handle: Rectangle {
                        x: expSlider.leftPadding + expSlider.visualPosition * (expSlider.availableWidth - width)
                        y: expSlider.topPadding + expSlider.availableHeight / 2 - height / 2
                        implicitWidth: 14; implicitHeight: 14; width: 14; height: 14; radius: 7; color: "#4DB6AC"
                    }
                    // ⭐ 鼠标滚轮支持
                    MouseArea {
                        anchors.fill: parent
                        acceptedButtons: Qt.NoButton
                        onWheel: function(wheel) {
                            var delta = wheel.angleDelta.y > 0 ? expSlider.stepSize : -expSlider.stepSize
                            var newValue = expSlider.value + delta
                            newValue = Math.max(expSlider.from, Math.min(expSlider.to, newValue))
                            expSlider.value = newValue
                            exposureSettingsPopup.exposureValue = newValue
                            iosCameraSettingsPopup.exposureValue = newValue
                            captureManager.exposure = newValue
                            sendConfigUpdate("exposureBias", {"exposureBias": newValue})
                        }
                    }
                }
                Text { text: exposureSettingsPopup.exposureValue * 100; font.pixelSize: 16; color: "#263238"; Layout.preferredWidth: 60 }  // ⚠️ ×100 迷惑量级, 跟相机设定弹框一致 — 别改回 ×10
            }
            
            // 亮度
            RowLayout {
                Layout.fillWidth: true
                spacing: 10
                Text { text: "亮度"; font.pixelSize: 16; color: "#263238"; Layout.preferredWidth: 70 }
                Slider {
                    id: brightSlider
                    Layout.fillWidth: true
                    from: -1.0; to: 1.0; stepSize: 0.01
                    value: exposureSettingsPopup.brightnessValue
                    onMoved: {
                        exposureSettingsPopup.brightnessValue = value
                        // 同步到相机设定弹框（限制在其范围内 -0.2 ~ 0.3）
                        iosCameraSettingsPopup.brightnessValue = Math.max(-0.2, Math.min(0.3, value))
                        // ⭐ PC 端色彩调整已禁用 (看 iOS 原画)
                        // captureManager.brightness = value
                    }
                    onPressedChanged: if (!pressed) { /* sendRenderParamUpdate("brightness", value) */ }
                    background: Rectangle {
                        x: brightSlider.leftPadding; y: brightSlider.topPadding + brightSlider.availableHeight / 2 - 2
                        implicitWidth: 200; implicitHeight: 4; width: brightSlider.availableWidth; height: 4; radius: 999; color: "#C8E6C9"
                        Rectangle { width: brightSlider.visualPosition * parent.width; height: parent.height; radius: 999; color: "#4DB6AC" }
                    }
                    handle: Rectangle {
                        x: brightSlider.leftPadding + brightSlider.visualPosition * (brightSlider.availableWidth - width)
                        y: brightSlider.topPadding + brightSlider.availableHeight / 2 - height / 2
                        implicitWidth: 14; implicitHeight: 14; width: 14; height: 14; radius: 7; color: "#4DB6AC"
                    }
                    // ⭐ 鼠标滚轮支持
                    MouseArea {
                        anchors.fill: parent
                        acceptedButtons: Qt.NoButton
                        onWheel: function(wheel) {
                            var delta = wheel.angleDelta.y > 0 ? brightSlider.stepSize : -brightSlider.stepSize
                            var newValue = brightSlider.value + delta
                            newValue = Math.max(brightSlider.from, Math.min(brightSlider.to, newValue))
                            brightSlider.value = newValue
                            exposureSettingsPopup.brightnessValue = newValue
                            iosCameraSettingsPopup.brightnessValue = Math.max(-0.2, Math.min(0.3, newValue))
                            // ⭐ PC 端色彩调整已禁用 (看 iOS 原画)
                            // captureManager.brightness = newValue
                            // sendRenderParamUpdate("brightness", newValue)
                        }
                    }
                }
                Text { text: exposureSettingsPopup.brightnessValue.toFixed(2); font.pixelSize: 16; color: "#263238"; Layout.preferredWidth: 40 }
            }
            
            // 对比度
            RowLayout {
                Layout.fillWidth: true
                spacing: 10
                Text { text: "对比度"; font.pixelSize: 16; color: "#263238"; Layout.preferredWidth: 70 }
                Slider {
                    id: contrastSlider
                    Layout.fillWidth: true
                    from: 0.0; to: 2.0; stepSize: 0.01
                    value: exposureSettingsPopup.contrastValue
                    onMoved: { exposureSettingsPopup.contrastValue = value; /* captureManager.contrast = value */ }
                    onPressedChanged: if (!pressed) { /* sendRenderParamUpdate("contrast", value) */ }
                    background: Rectangle {
                        x: contrastSlider.leftPadding; y: contrastSlider.topPadding + contrastSlider.availableHeight / 2 - 2
                        implicitWidth: 200; implicitHeight: 4; width: contrastSlider.availableWidth; height: 4; radius: 999; color: "#C8E6C9"
                        Rectangle { width: contrastSlider.visualPosition * parent.width; height: parent.height; radius: 999; color: "#4DB6AC" }
                    }
                    handle: Rectangle {
                        x: contrastSlider.leftPadding + contrastSlider.visualPosition * (contrastSlider.availableWidth - width)
                        y: contrastSlider.topPadding + contrastSlider.availableHeight / 2 - height / 2
                        implicitWidth: 14; implicitHeight: 14; width: 14; height: 14; radius: 7; color: "#4DB6AC"
                    }
                    // ⭐ 鼠标滚轮支持
                    MouseArea {
                        anchors.fill: parent
                        acceptedButtons: Qt.NoButton
                        onWheel: function(wheel) {
                            var delta = wheel.angleDelta.y > 0 ? contrastSlider.stepSize : -contrastSlider.stepSize
                            var newValue = contrastSlider.value + delta
                            newValue = Math.max(contrastSlider.from, Math.min(contrastSlider.to, newValue))
                            contrastSlider.value = newValue
                            exposureSettingsPopup.contrastValue = newValue
                            // ⭐ PC 端色彩调整已禁用 (看 iOS 原画)
                            // captureManager.contrast = newValue
                            // sendRenderParamUpdate("contrast", newValue)
                        }
                    }
                }
                Text { text: exposureSettingsPopup.contrastValue.toFixed(2); font.pixelSize: 16; color: "#263238"; Layout.preferredWidth: 40 }
            }
            
            // 饱和度
            RowLayout {
                Layout.fillWidth: true
                spacing: 10
                Text { text: "饱和度"; font.pixelSize: 16; color: "#263238"; Layout.preferredWidth: 70 }
                Slider {
                    id: satSlider
                    Layout.fillWidth: true
                    from: 0.0; to: 2.0; stepSize: 0.01
                    value: exposureSettingsPopup.saturationValue
                    onMoved: {
                        exposureSettingsPopup.saturationValue = value
                        iosCameraSettingsPopup.saturationValue = value  // 同步到相机设定弹框
                        // ⭐ PC 端色彩调整已禁用 (看 iOS 原画)
                        // captureManager.saturation = value
                    }
                    onPressedChanged: if (!pressed) { /* sendRenderParamUpdate("saturation", value) */ }
                    background: Rectangle {
                        x: satSlider.leftPadding; y: satSlider.topPadding + satSlider.availableHeight / 2 - 2
                        implicitWidth: 200; implicitHeight: 4; width: satSlider.availableWidth; height: 4; radius: 999; color: "#C8E6C9"
                        Rectangle { width: satSlider.visualPosition * parent.width; height: parent.height; radius: 999; color: "#4DB6AC" }
                    }
                    handle: Rectangle {
                        x: satSlider.leftPadding + satSlider.visualPosition * (satSlider.availableWidth - width)
                        y: satSlider.topPadding + satSlider.availableHeight / 2 - height / 2
                        implicitWidth: 14; implicitHeight: 14; width: 14; height: 14; radius: 7; color: "#4DB6AC"
                    }
                    // ⭐ 鼠标滚轮支持
                    MouseArea {
                        anchors.fill: parent
                        acceptedButtons: Qt.NoButton
                        onWheel: function(wheel) {
                            var delta = wheel.angleDelta.y > 0 ? satSlider.stepSize : -satSlider.stepSize
                            var newValue = satSlider.value + delta
                            newValue = Math.max(satSlider.from, Math.min(satSlider.to, newValue))
                            satSlider.value = newValue
                            exposureSettingsPopup.saturationValue = newValue
                            iosCameraSettingsPopup.saturationValue = newValue
                            // ⭐ PC 端色彩调整已禁用 (看 iOS 原画)
                            // captureManager.saturation = newValue
                            // sendRenderParamUpdate("saturation", newValue)
                        }
                    }
                }
                Text { text: exposureSettingsPopup.saturationValue.toFixed(2); font.pixelSize: 16; color: "#263238"; Layout.preferredWidth: 40 }
            }
            
            // 色调
            RowLayout {
                Layout.fillWidth: true
                spacing: 10
                Text { text: "色调"; font.pixelSize: 16; color: "#263238"; Layout.preferredWidth: 70 }
                Slider {
                    id: hueSlider
                    Layout.fillWidth: true
                    from: -1.0; to: 1.0; stepSize: 0.01
                    value: exposureSettingsPopup.hueValue
                    onMoved: { exposureSettingsPopup.hueValue = value; /* captureManager.hue = value */ }
                    onPressedChanged: if (!pressed) { /* sendRenderParamUpdate("hue", value) */ }
                    background: Rectangle {
                        x: hueSlider.leftPadding; y: hueSlider.topPadding + hueSlider.availableHeight / 2 - 2
                        implicitWidth: 200; implicitHeight: 4; width: hueSlider.availableWidth; height: 4; radius: 999; color: "#C8E6C9"
                        Rectangle { width: hueSlider.visualPosition * parent.width; height: parent.height; radius: 999; color: "#4DB6AC" }
                    }
                    handle: Rectangle {
                        x: hueSlider.leftPadding + hueSlider.visualPosition * (hueSlider.availableWidth - width)
                        y: hueSlider.topPadding + hueSlider.availableHeight / 2 - height / 2
                        implicitWidth: 14; implicitHeight: 14; width: 14; height: 14; radius: 7; color: "#4DB6AC"
                    }
                    // ⭐ 鼠标滚轮支持
                    MouseArea {
                        anchors.fill: parent
                        acceptedButtons: Qt.NoButton
                        onWheel: function(wheel) {
                            var delta = wheel.angleDelta.y > 0 ? hueSlider.stepSize : -hueSlider.stepSize
                            var newValue = hueSlider.value + delta
                            newValue = Math.max(hueSlider.from, Math.min(hueSlider.to, newValue))
                            hueSlider.value = newValue
                            exposureSettingsPopup.hueValue = newValue
                            // ⭐ PC 端色彩调整已禁用 (看 iOS 原画)
                            // captureManager.hue = newValue
                            // sendRenderParamUpdate("hue", newValue)
                        }
                    }
                }
                Text { text: exposureSettingsPopup.hueValue.toFixed(2); font.pixelSize: 16; color: "#263238"; Layout.preferredWidth: 40 }
            }
            
            // 伽马
            RowLayout {
                Layout.fillWidth: true
                spacing: 10
                Text { text: "伽马"; font.pixelSize: 16; color: "#263238"; Layout.preferredWidth: 70 }
                Slider {
                    id: gammaSlider
                    Layout.fillWidth: true
                    from: 0.01; to: 10.0; stepSize: 0.01
                    value: exposureSettingsPopup.gammaValue
                    onMoved: { exposureSettingsPopup.gammaValue = value; /* captureManager.gamma = value */ }
                    onPressedChanged: if (!pressed) { /* sendRenderParamUpdate("gamma", value) */ }
                    background: Rectangle {
                        x: gammaSlider.leftPadding; y: gammaSlider.topPadding + gammaSlider.availableHeight / 2 - 2
                        implicitWidth: 200; implicitHeight: 4; width: gammaSlider.availableWidth; height: 4; radius: 999; color: "#C8E6C9"
                        Rectangle { width: gammaSlider.visualPosition * parent.width; height: parent.height; radius: 999; color: "#4DB6AC" }
                    }
                    handle: Rectangle {
                        x: gammaSlider.leftPadding + gammaSlider.visualPosition * (gammaSlider.availableWidth - width)
                        y: gammaSlider.topPadding + gammaSlider.availableHeight / 2 - height / 2
                        implicitWidth: 14; implicitHeight: 14; width: 14; height: 14; radius: 7; color: "#4DB6AC"
                    }
                    // ⭐ 鼠标滚轮支持
                    MouseArea {
                        anchors.fill: parent
                        acceptedButtons: Qt.NoButton
                        onWheel: function(wheel) {
                            var delta = wheel.angleDelta.y > 0 ? gammaSlider.stepSize : -gammaSlider.stepSize
                            var newValue = gammaSlider.value + delta
                            newValue = Math.max(gammaSlider.from, Math.min(gammaSlider.to, newValue))
                            gammaSlider.value = newValue
                            exposureSettingsPopup.gammaValue = newValue
                            // ⭐ PC 端色彩调整已禁用 (看 iOS 原画)
                            // captureManager.gamma = newValue
                            // sendRenderParamUpdate("gamma", newValue)
                        }
                    }
                }
                Text { text: exposureSettingsPopup.gammaValue.toFixed(2); font.pixelSize: 16; color: "#263238"; Layout.preferredWidth: 40 }
            }
            
            // 还原按钮
            Rectangle {
                Layout.alignment: Qt.AlignHCenter
                width: 80; height: 32; radius: 4
                color: resetExpArea.containsMouse ? "#C8E6C9" : "#E8F5E9"
                border.color: "#A5D6A7"
                
                Text { anchors.centerIn: parent; text: "还原"; font.pixelSize: 14; color: "#333333" }
                
                MouseArea {
                    id: resetExpArea
                    anchors.fill: parent
                    hoverEnabled: true
                    cursorShape: Qt.PointingHandCursor
                    onClicked: {
                        // 还原到默认值（饱和度、对比度默认1.10）
                        exposureSettingsPopup.exposureValue = 20
                        exposureSettingsPopup.brightnessValue = -0.02
                        exposureSettingsPopup.contrastValue = 1.10
                        exposureSettingsPopup.saturationValue = 1.10
                        exposureSettingsPopup.hueValue = -0.02
                        exposureSettingsPopup.gammaValue = 1.08
                        captureManager.resetCameraSettings()
                        captureManager.exposure = 20
                        sendConfigUpdate("exposureBias", {"exposureBias": 20})
                    }
                }
            }
            }  // 关闭 ColumnLayout
        }  // 关闭 Rectangle
    }  // 关闭 Window
    
    // ============ PS 风格颜色选择器 Popup ============
    Popup {
        id: colorPickerPopup
        width: 320
        height: 280
        modal: true
        closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside
        anchors.centerIn: parent
        
        // 临时 HSV 值
        property real tempH: appSettings.panelColorH
        property real tempS: appSettings.panelColorS
        property real tempV: appSettings.panelColorV
        
        onOpened: {
            tempH = appSettings.panelColorH
            tempS = appSettings.panelColorS
            tempV = appSettings.panelColorV
        }
        
        background: Rectangle {
            color: "#2d2d2d"
            radius: 8
            border.color: "#555555"
            border.width: 1
        }
        
        contentItem: Column {
            spacing: 12
            padding: 16
            
            // 标题
            Text {
                text: "选择面板颜色"
                font.family: "PingFang HK"
                font.pixelSize: 16
                font.bold: true
                color: "#FFFFFF"
            }
            
            Row {
                spacing: 12
                
                // 左侧：饱和度-明度面板 (SV)
                Item {
                    width: 200
                    height: 150
                    
                    // 底层：白色到纯色的水平渐变
                    Rectangle {
                        id: svPanel
                        anchors.fill: parent
                        radius: 4
                        
                        gradient: Gradient {
                            orientation: Gradient.Horizontal
                            GradientStop { position: 0.0; color: "#FFFFFF" }
                            GradientStop { position: 1.0; color: Qt.hsva(colorPickerPopup.tempH, 1, 1, 1) }
                        }
                        
                        // 上层：透明到黑色的垂直渐变
                        Rectangle {
                            anchors.fill: parent
                            radius: 4
                            gradient: Gradient {
                                orientation: Gradient.Vertical
                                GradientStop { position: 0.0; color: "transparent" }
                                GradientStop { position: 1.0; color: "#000000" }
                            }
                        }
                        
                        // SV 选择指示器
                        Rectangle {
                            x: colorPickerPopup.tempS * parent.width - 6
                            y: (1 - colorPickerPopup.tempV) * parent.height - 6
                            width: 12
                            height: 12
                            radius: 6
                            color: "transparent"
                            border.color: "#FFFFFF"
                            border.width: 2
                            
                            Rectangle {
                                anchors.centerIn: parent
                                width: 8
                                height: 8
                                radius: 4
                                color: "transparent"
                                border.color: "#000000"
                                border.width: 1
                            }
                        }
                        
                        MouseArea {
                            anchors.fill: parent
                            
                            function updateSV(mouse) {
                                colorPickerPopup.tempS = Math.max(0, Math.min(1, mouse.x / width))
                                colorPickerPopup.tempV = Math.max(0, Math.min(1, 1 - mouse.y / height))
                            }
                            
                            onPressed: updateSV(mouse)
                            onPositionChanged: if (pressed) updateSV(mouse)
                        }
                    }
                }
                
                // 右侧：色相条 (H)
                Item {
                    width: 24
                    height: 150
                    
                    Rectangle {
                        id: hueBar
                        anchors.fill: parent
                        radius: 4
                        
                        gradient: Gradient {
                            orientation: Gradient.Vertical
                            GradientStop { position: 0.00; color: Qt.hsva(0.00, 1, 1, 1) }
                            GradientStop { position: 0.17; color: Qt.hsva(0.17, 1, 1, 1) }
                            GradientStop { position: 0.33; color: Qt.hsva(0.33, 1, 1, 1) }
                            GradientStop { position: 0.50; color: Qt.hsva(0.50, 1, 1, 1) }
                            GradientStop { position: 0.67; color: Qt.hsva(0.67, 1, 1, 1) }
                            GradientStop { position: 0.83; color: Qt.hsva(0.83, 1, 1, 1) }
                            GradientStop { position: 1.00; color: Qt.hsva(1.00, 1, 1, 1) }
                        }
                        
                        // 色相选择指示器
                        Rectangle {
                            x: -2
                            y: colorPickerPopup.tempH * parent.height - 3
                            width: parent.width + 4
                            height: 6
                            radius: 2
                            color: "transparent"
                            border.color: "#FFFFFF"
                            border.width: 2
                        }
                        
                        MouseArea {
                            anchors.fill: parent
                            
                            function updateH(mouse) {
                                colorPickerPopup.tempH = Math.max(0, Math.min(1, mouse.y / height))
                            }
                            
                            onPressed: updateH(mouse)
                            onPositionChanged: if (pressed) updateH(mouse)
                        }
                    }
                }
                
                // 预览
                Column {
                    spacing: 8
                    
                    Text {
                        text: "预览"
                        font.family: "PingFang HK"
                        font.pixelSize: 12
                        color: "#AAAAAA"
                    }
                    
                    Rectangle {
                        width: 40
                        height: 40
                        radius: 4
                        color: Qt.hsva(colorPickerPopup.tempH, colorPickerPopup.tempS, colorPickerPopup.tempV, 1)
                        border.color: "#555555"
                        border.width: 1
                    }
                    
                    Text {
                        text: "当前"
                        font.family: "PingFang HK"
                        font.pixelSize: 12
                        color: "#AAAAAA"
                    }
                    
                    Rectangle {
                        width: 40
                        height: 40
                        radius: 4
                        color: panelBgColor
                        border.color: "#555555"
                        border.width: 1
                    }
                }
            }
            
            // 按钮行
            Row {
                spacing: 12
                anchors.horizontalCenter: parent.horizontalCenter
                
                Rectangle {
                    width: 70
                    height: 32
                    radius: 4
                    color: cancelColorArea.containsMouse ? "#4a4a4a" : "#3c3c3c"
                    
                    Text {
                        anchors.centerIn: parent
                        text: "取消"
                        font.family: "PingFang HK"
                        font.pixelSize: 14
                        color: "#CCCCCC"
                    }
                    
                    MouseArea {
                        id: cancelColorArea
                        anchors.fill: parent
                        hoverEnabled: true
                        cursorShape: Qt.PointingHandCursor
                        onClicked: colorPickerPopup.close()
                    }
                }
                
                // 还原默认颜色按钮
                Rectangle {
                    width: 70
                    height: 32
                    radius: 4
                    color: resetColorArea.containsMouse ? "#5d8a5e" : "#4CAF50"
                    
                    Text {
                        anchors.centerIn: parent
                        text: "还原"
                        font.family: "PingFang HK"
                        font.pixelSize: 14
                        color: "#FFFFFF"
                    }
                    
                    MouseArea {
                        id: resetColorArea
                        anchors.fill: parent
                        hoverEnabled: true
                        cursorShape: Qt.PointingHandCursor
                        onClicked: {
                            // 还原到默认颜色 (淡绿色)
                            colorPickerPopup.tempH = 0
                            colorPickerPopup.tempS = 0
                            colorPickerPopup.tempV = 0.9
                        }
                        
                        ToolTip.visible: containsMouse
                        ToolTip.text: "还原默认淡绿色"
                        ToolTip.delay: 300
                    }
                }
                
                Rectangle {
                    width: 70
                    height: 32
                    radius: 4
                    color: confirmColorArea.containsMouse ? "#4a90d9" : "#3993D2"
                    
                    Text {
                        anchors.centerIn: parent
                        text: "确定"
                        font.family: "PingFang HK"
                        font.pixelSize: 14
                        color: "#FFFFFF"
                    }
                    
                    MouseArea {
                        id: confirmColorArea
                        anchors.fill: parent
                        hoverEnabled: true
                        cursorShape: Qt.PointingHandCursor
                        onClicked: {
                            appSettings.panelColorH = colorPickerPopup.tempH
                            appSettings.panelColorS = colorPickerPopup.tempS
                            appSettings.panelColorV = colorPickerPopup.tempV
                            colorPickerPopup.close()
                        }
                    }
                }
            }
        }
    }
    
    // ============ 快捷键说明 Popup ============
    Popup {
        id: shortcutHelpPopup
        width: 500
        height: 820  // 增加高度以容纳新增的说明
        modal: false  // 去掉灰蒙蒙的背景遮罩
        closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside
        anchors.centerIn: parent
        
        // 拖动相关属性
        property point dragStart: Qt.point(0, 0)
        property bool dragging: false
        
        background: Rectangle {
            color: "#FFFFFF"
            radius: 8
            border.color: "#A5D6A7"
            border.width: 1
        }
        
        contentItem: ColumnLayout {
            spacing: 16
            anchors.fill: parent
            anchors.margins: 24
            
            // 拖动区域（标题栏）
            Rectangle {
                Layout.fillWidth: true
                Layout.preferredHeight: 40
                color: "transparent"
                
                MouseArea {
                    anchors.fill: parent
                    cursorShape: Qt.ClosedHandCursor
                    propagateComposedEvents: false
                    
                    property point startPos: Qt.point(0, 0)
                    
                    onPressed: function(mouse) {
                        startPos = Qt.point(shortcutHelpPopup.x, shortcutHelpPopup.y)
                        dragStartGlobal = mapToGlobal(mouse.x, mouse.y)
                        shortcutHelpPopup.dragging = true
                        mouse.accepted = true
                    }
                    
                    property point dragStartGlobal: Qt.point(0, 0)
                    
                    onPositionChanged: function(mouse) {
                        if (shortcutHelpPopup.dragging) {
                            var currentGlobal = mapToGlobal(mouse.x, mouse.y)
                            var deltaX = currentGlobal.x - dragStartGlobal.x
                            var deltaY = currentGlobal.y - dragStartGlobal.y
                            shortcutHelpPopup.x = startPos.x + deltaX
                            shortcutHelpPopup.y = startPos.y + deltaY
                        }
                    }
                    
                    onReleased: {
                        shortcutHelpPopup.dragging = false
                    }
                }
                
                Text {
                    anchors.centerIn: parent
                    text: "快捷键说明"
                    font.family: "PingFang HK"
                    font.pixelSize: 18
                    font.bold: true
                    color: "#263238"
                }
            }
            
            // 分隔线
            Rectangle {
                Layout.fillWidth: true
                height: 1
                color: "#C8E6C9"
            }
            
            // 快捷键列表（两列布局）
            GridLayout {
                Layout.fillWidth: true
                Layout.fillHeight: true
                columns: 2
                columnSpacing: 40
                rowSpacing: 12
                
                // 第一列
                ShortcutItem { key: "Space"; desc: "抓拍" }
                ShortcutItem { key: "左键"; desc: "上一帧(实时流=抓拍)" }
                ShortcutItem { key: "右键"; desc: "下一帧" }
                ShortcutItem { key: "F"; desc: "全屏切换" }
                ShortcutItem { 
                    key: ShortcutStore.gridFullscreenKey
                    desc: "抓拍全屏(仅至尊版)" 
                }
                ShortcutItem { key: "G"; desc: "实时窗口切换" }
                ShortcutItem { key: "H"; desc: "慢放窗口切换" }
                ShortcutItem { key: "W"; desc: "开启/停止慢放" }
                ShortcutItem { key: "Q"; desc: "慢放播放/暂停" }
                ShortcutItem { key: "E"; desc: "慢放清空" }
                ShortcutItem { key: "C"; desc: "抓拍清空" }
                ShortcutItem { key: "D"; desc: "删除最后抓拍" }
                ShortcutItem { key: "R"; desc: "相机设定" }
                ShortcutItem { key: "P"; desc: "相机参数精调" }
                ShortcutItem { key: "F1"; desc: "行数增加" }
                ShortcutItem { key: "F2"; desc: "行数减少" }
                ShortcutItem { key: "F3"; desc: "列数增加" }
                ShortcutItem { key: "F4"; desc: "列数减少" }
                ShortcutItem { key: "F5/F6/F7/F8"; desc: "上下帧步长 1/2/3/4 (滚轮/左右键, 不影响慢放)" }
                ShortcutItem { key: "Esc"; desc: "退出全屏/关闭弹框" }
                ShortcutItem { key: "S+滚轮"; desc: "镜头变倍/缩放" }
                ShortcutItem { key: "滚轮"; desc: "本地缩放/切帧" }
                ShortcutItem { key: "A"; desc: "放大查看(列预览/截图)" }
                ShortcutItem { key: "0-9"; desc: "列预览(2-5张,0=第10列)" }
                ShortcutItem { key: "Shift+点击"; desc: "列预览(点击item所在列)" }
                ShortcutItem { key: "Z/X"; desc: "列预览:上/下列切换" }
                ShortcutItem { key: "Ctrl+滚轮"; desc: "全grid/列预览同步切帧" }
                ShortcutItem { key: "Ctrl+S+滚轮"; desc: "全grid/列预览同步缩放" }
                ShortcutItem { key: "Ctrl+左/右键"; desc: "全grid/列预览同步上/下一帧" }
            }
            
            // ⭐ 抓拍全屏功能说明
            Rectangle {
                Layout.fillWidth: true
                Layout.topMargin: 8
                Layout.bottomMargin: 8
                height: 1
                color: "#E0E0E0"
            }
            
            ColumnLayout {
                Layout.fillWidth: true
                spacing: 8
                
                Text {
                    text: "抓拍全屏功能说明"
                    font.family: "PingFang HK"
                    font.pixelSize: 14
                    font.bold: true
                    color: "#263238"
                }
                
                Text {
                    Layout.fillWidth: true
                    text: "• 豪华版(pc=1)：不支持手动打开抓拍全屏，不支持自动触发\n• 至尊版(pc=2)：支持手动打开/关闭抓拍全屏，支持自动触发（当截图个数达到行×列时）"
                    font.family: "PingFang HK"
                    font.pixelSize: 12
                    color: "#666666"
                    wrapMode: Text.Wrap
                    lineHeight: 1.5
                }
            }
            
            // 关闭按钮
            Rectangle {
                Layout.alignment: Qt.AlignHCenter
                width: 80; height: 32; radius: 4
                color: closeShortcutArea.containsMouse ? "#C8E6C9" : "#E8F5E9"
                border.color: "#A5D6A7"
                
                Text { anchors.centerIn: parent; text: "关闭"; font.pixelSize: 14; color: "#333333" }
                
                MouseArea {
                    id: closeShortcutArea
                    anchors.fill: parent
                    hoverEnabled: true
                    cursorShape: Qt.PointingHandCursor
                    onClicked: shortcutHelpPopup.close()
                }
            }
        }
    }
    
    // 快捷键项组件
    component ShortcutItem: RowLayout {
        property string key: ""
        property string desc: ""
        spacing: 12
        
        Rectangle {
            width: 60; height: 28; radius: 4
            color: "#E8F5E9"
            border.color: "#A5D6A7"
            Text {
                anchors.centerIn: parent
                text: key
                font.family: "Consolas"
                font.pixelSize: 13
                font.bold: true
                color: "#333333"
            }
        }
        Text {
            text: desc
            font.family: "PingFang HK"
            font.pixelSize: 14
            color: "#666666"
        }
    }
    
    // ===== 版本区别说明弹窗 =====
    Popup {
        id: versionCompareDialog
        width: 480
        height: 460  // 增加高度以容纳新增的抓拍全屏说明
        x: (mainPage.width - width) / 2
        y: (mainPage.height - height) / 2
        modal: true
        closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside
        
        background: Rectangle {
            color: "#FAFAFA"
            radius: 12
            border.color: "#E0E0E0"
            border.width: 1
            
            // 阴影
            Rectangle {
                anchors.fill: parent
                anchors.margins: -2
                z: -1
                radius: 14
                color: "#20000000"
            }
        }
        
        contentItem: Item {
            anchors.fill: parent
            
            // 右上角关闭按钮 ✕
            Rectangle {
                id: versionCloseBtn
                anchors.right: parent.right
                anchors.top: parent.top
                anchors.rightMargin: 8
                anchors.topMargin: 8
                width: 28
                height: 28
                radius: 14
                color: versionCloseArea.containsMouse ? "#E0E0E0" : "transparent"
                z: 10
                
                Text {
                    anchors.centerIn: parent
                    text: "✕"
                    font.pixelSize: 14
                    color: versionCloseArea.containsMouse ? "#333333" : "#9E9E9E"
                }
                
                MouseArea {
                    id: versionCloseArea
                    anchors.fill: parent
                    hoverEnabled: true
                    cursorShape: Qt.PointingHandCursor
                    onClicked: versionCompareDialog.close()
                }
            }
            
            ColumnLayout {
                anchors.fill: parent
                anchors.margins: 24
                spacing: 16
                
                // 标题（含当前版本）
                Text {
                    text: {
                        var name = mainPage.pcLevelName || (mainPage.pcActivationLevel >= 2 ? "至尊版" : "豪华版")
                        return "版本功能对比（当前：" + name + "）"
                    }
                    font.family: "PingFang HK"
                    font.pixelSize: 18
                    font.weight: Font.Bold
                    color: "#263238"
                    Layout.fillWidth: true
                    horizontalAlignment: Text.AlignHCenter
                }
                
                // 分隔线
                Rectangle { Layout.fillWidth: true; height: 1; color: "#E0E0E0" }
                
                // 表头
                RowLayout {
                    Layout.fillWidth: true
                    spacing: 0
                    
                    Text {
                        Layout.preferredWidth: 180
                        text: "功能"
                        font.family: "PingFang HK"
                        font.pixelSize: 13
                        font.weight: Font.Bold
                        color: "#546E7A"
                    }
                    Text {
                        Layout.fillWidth: true
                        text: "豪华版"
                        font.family: "PingFang HK"
                        font.pixelSize: 13
                        font.weight: Font.Bold
                        color: "#3993D2"
                        horizontalAlignment: Text.AlignHCenter
                    }
                    Text {
                        Layout.fillWidth: true
                        text: "至尊版"
                        font.family: "PingFang HK"
                        font.pixelSize: 13
                        font.weight: Font.Bold
                        color: "#C49000"
                        horizontalAlignment: Text.AlignHCenter
                    }
                }
                
                Rectangle { Layout.fillWidth: true; height: 1; color: "#EEEEEE" }
                
                // 对比列表
                Repeater {
                    model: [
                        { feature: "截图质量", level1: "60（固定）", level2: "60 ~ 100（可调）" },
                        { feature: "帧率上限", level1: "≤ 120 fps", level2: "不限制" },
                        { feature: "超级帧率上限", level1: "≤ 240", level2: "不限制" },
                        { feature: "实时流局部放大", level1: "✅ 支持", level2: "✅ 支持" },
                        { feature: "截图/慢放继承放大", level1: "❌ 不继承", level2: "✅ 继承" },
                        { feature: "截图项S+滚轮缩放", level1: "✅ 支持", level2: "✅ 支持" },
                        { feature: "全屏放大查看", level1: "❌ 不支持", level2: "✅ 支持" },
                        { feature: "抓拍全屏", level1: "❌ 不支持", level2: "✅ 支持（手动+自动）" },
                        { feature: "导航栏默认颜色", level1: "蓝色系", level2: "绿色系" }
                    ]
                    
                    delegate: RowLayout {
                        Layout.fillWidth: true
                        spacing: 0
                        
                        Text {
                            Layout.preferredWidth: 180
                            text: modelData.feature
                            font.family: "PingFang HK"
                            font.pixelSize: 13
                            color: "#37474F"
                        }
                        Text {
                            Layout.fillWidth: true
                            text: modelData.level1
                            font.family: "PingFang HK"
                            font.pixelSize: 12
                            color: "#607D8B"
                            horizontalAlignment: Text.AlignHCenter
                        }
                        Text {
                            Layout.fillWidth: true
                            text: modelData.level2
                            font.family: "PingFang HK"
                            font.pixelSize: 12
                            color: "#607D8B"
                            horizontalAlignment: Text.AlignHCenter
                        }
                    }
                }
                
                Item { Layout.fillHeight: true }
            }
        }
    }
    
    // 同步曝光值关联的参数（色调不再联动，保持用户独立设置）
    function syncExposureParamsFromCaptureManager() {
        exposureSettingsPopup.brightnessValue = captureManager.brightness
        exposureSettingsPopup.contrastValue = captureManager.contrast
        exposureSettingsPopup.saturationValue = captureManager.saturation
        // hueValue 不再联动，保持独立
        exposureSettingsPopup.gammaValue = captureManager.gamma
    }
    
    // 发送设备命令
    function sendDeviceCommand(cmdType) {
        console.log("📤 sendDeviceCommand 被调用, cmdType:", cmdType)
        
        var deviceId = HttpClient.currentDeviceId()
        console.log("📤 deviceId:", deviceId)
        
        if (!deviceId) {
            console.log("📤 无设备ID，显示Toast")
            showToast("未连接设备")
            return
        }
        
        var destination = "/topic/device/" + deviceId + "/config"
        var notification = {
            "type": cmdType,
            "deviceId": deviceId,
            "timestamp": Date.now()
        }
        
        // RESET_SHENGDIANG 需要额外的 reason 字段
        if (cmdType === "RESET_SHENGDIANG") {
            notification["reason"] = "后台管理员操作"
        }
        
        var jsonStr = JSON.stringify(notification)
        console.log("📤 发送设备命令:")
        console.log("📤   目标:", destination)
        console.log("📤   消息:", jsonStr)
        
        // 使用 sendMessageJson 直接发送 JSON 字符串
        WebSocketClient.sendMessageJson(destination, jsonStr)
    }
    
    // ⭐ 帧率限制 Timer（切换档位后 1 秒推送帧率）
    Timer {
        id: fpsLimitPushTimer
        interval: 1000
        onTriggered: {
            // ⭐ fps 直接下发，不再除以2
            var actualFps = Math.floor(iosCameraSettingsPopup.fpsValue)
            if (actualFps < 1) actualFps = 1
            console.log("📤 帧率限制推送: 滑块值=" + iosCameraSettingsPopup.fpsValue + ", 实际发送=" + actualFps)
            // HTTP 接口
            HttpClient.updateFps(actualFps)
            // WebSocket 推送
            sendConfigUpdate("fps", {"fps": actualFps})
        }
    }
    
    // ⭐ 档位切换防抖（1秒内不能切换两次）
    property bool qualitySwitchLocked: false
    Timer {
        id: qualitySwitchLockTimer
        interval: 1000
        onTriggered: {
            qualitySwitchLocked = false
        }
    }
    
    // ⭐ 档位切换后 PLI 请求定时器（防止绿幕）
    Timer {
        id: pliAfterQualitySwitchTimer
        interval: 300  // 300ms 后发送 PLI
        onTriggered: {
            console.log("📨 [PLI-1] 档位切换后 300ms，发送 PLI...")
            gstPlayer.requestKeyFrame()
            pliAfterQualitySwitchTimer2.restart()
        }
    }
    Timer {
        id: pliAfterQualitySwitchTimer2
        interval: 200  // 500ms 时发送
        onTriggered: {
            console.log("📨 [PLI-2] 档位切换后 500ms，发送 PLI...")
            gstPlayer.requestKeyFrame()
            pliAfterQualitySwitchTimer3.restart()
        }
    }
    Timer {
        id: pliAfterQualitySwitchTimer3
        interval: 500  // 1000ms 时发送
        onTriggered: {
            console.log("📨 [PLI-3] 档位切换后 1000ms，发送 PLI...")
            gstPlayer.requestKeyFrame()
            pliAfterQualitySwitchTimer4.restart()
        }
    }
    Timer {
        id: pliAfterQualitySwitchTimer4
        interval: 500  // 1500ms 时发送
        onTriggered: {
            console.log("📨 [PLI-4] 档位切换后 1500ms，发送 PLI...")
            gstPlayer.requestKeyFrame()
        }
    }
    
    
    // ⭐ 获取帧率(fps)上限 - 全部从登录接口 levelFps 动态获取
    // levelFps[0]=试用, [1]=高清, [2]=超清, [3]=超高清, [4]=超高帧
    // 最终上限 = min(levelFps[等级], PC端限制)
    // PC端等级额外限制：至尊版(2)不限制，豪华版(1)及以下最大120
    function getMaxFpsForQuality(qualityType) {
        // ⭐ 从 levelFps 数组读取 iOS 会员等级的帧率上限
        var level = mainPage.memberActivationLevel
        var fps = mainPage.levelFps  // [试用, 高清, 超清, 超高清, 超高帧]
        var iosMaxFps = 240  // 默认
        
        // 试用(等级0) 或 日试用：取 levelFps[0]
        if (!mainPage.memberActivated || level === 0 || mainPage.isDailyTrial) {
            iosMaxFps = (fps && fps.length > 0) ? fps[0] : 240
        } else if (level >= fps.length) {
            // 等级超出数组范围，取最后一个
            iosMaxFps = (fps && fps.length > 0) ? fps[fps.length - 1] : 240
        } else {
            iosMaxFps = (fps && level < fps.length) ? fps[level] : 240
        }
        
        console.log("📊 getMaxFpsForQuality: type=" + qualityType + " level=" + level + " levelFps=" + JSON.stringify(fps) + " iosMaxFps=" + iosMaxFps)
        
        // ⭐ PC端等级额外限制：豪华版(1)及以下最大120，至尊版(2)不限制
        var pcMaxFps = (mainPage.pcActivationLevel >= 2) ? 999 : 120
        
        // 取两者较小值
        return Math.min(iosMaxFps, pcMaxFps)
    }
    
    // ⭐ 获取超级帧率上限 - 全部从登录接口 levelExposureFps 动态获取
    // levelExposureFps[0]=试用, [1]=高清, [2]=超清, [3]=超高清, [4]=超高帧
    // 最终上限 = min(levelExposureFps[等级], PC端限制)
    // PC端等级额外限制：豪华版(1)最大240，至尊版(2)不限制
    function getMaxFlickerForQuality(qualityType) {
        // ⭐ 从 levelExposureFps 数组读取 iOS 会员等级的超级帧率上限
        var level = mainPage.memberActivationLevel
        var efps = mainPage.levelExposureFps  // [试用, 高清, 超清, 超高清, 超高帧]
        var iosMaxFlicker = 600  // 默认
        
        // 试用(等级0) 或 日试用：取 levelExposureFps[0]
        if (!mainPage.memberActivated || level === 0 || mainPage.isDailyTrial) {
            iosMaxFlicker = (efps && efps.length > 0) ? efps[0] : 600
        } else if (level >= efps.length) {
            // 等级超出数组范围，取最后一个
            iosMaxFlicker = (efps && efps.length > 0) ? efps[efps.length - 1] : 600
        } else {
            iosMaxFlicker = (efps && level < efps.length) ? efps[level] : 600
        }
        
        console.log("📊 getMaxFlickerForQuality: type=" + qualityType + " level=" + level + " levelExposureFps=" + JSON.stringify(efps) + " iosMaxFlicker=" + iosMaxFlicker)
        
        // ⭐ PC端等级额外限制：豪华版(1)及以下最大240，至尊版(2)不限制
        var pcMaxFlicker = (mainPage.pcActivationLevel >= 2) ? 999 : 240
        
        // 取两者较小值
        return Math.min(iosMaxFlicker, pcMaxFlicker)
    }
    
    // ⭐ 获取超级帧滑块最大值（只看会员等级）
    // 范围：60-600，根据会员等级返回上限
    function getMaxFlickerValue() {
        return getMaxFlickerForQuality(iosCameraSettingsPopup.qualityType)
    }
    
    // ⭐ UI显示名转换为服务器名称
    function uiQualityToServerName(uiName) {
        // UI名 → 服务器type
        switch (uiName) {
            case "超低网": return "low"
            case "高清": return "standard"
            case "超清": return "high"
            case "超高清": return "p4k"
            case "超高帧": return "ultra"
            default: return uiName
        }
    }
    
    // ⭐ 检查指定画质是否可用（会员等级限制）
    // 等级规则：0=试用全开放, 1=高清/超低网, 2=超清, 3=超高帧, 4=超超清
    // 高等级自动拥有低等级权限
    function isQualityAccessible(qualityName) {
        // 超快帧特殊处理：需要等级4(超高帧)
        if (qualityName === "超快帧") {
            return mainPage.memberActivationLevel >= 4
        }

        // 未激活（试用）：全部可用
        if (!mainPage.memberActivated || mainPage.memberActivationLevel === 0) {
            return true
        }
        
        var level = mainPage.memberActivationLevel
        
        // 等级4（超超清/p4k）：全部可用（包括超高帧）
        if (level >= 4) {
            return true
        }
        
        // 等级3（超高帧/ultra）：可用超低网、高清、超清、超超清（不含超高帧）
        if (level === 3) {
            return qualityName === "超低网" || qualityName === "高清" || qualityName === "超清" || qualityName === "超高清"
        }
        
        // 等级2（超清/high）：可用超低网、超清、高清
        if (level === 2) {
            return qualityName === "超低网" || qualityName === "高清" || qualityName === "超清"
        }
        
        // 等级1（高清/standard）：可用超低网、高清
        if (level === 1) {
            return qualityName === "超低网" || qualityName === "高清"
        }
        
        // 默认全部可用
        return true
    }
    
    // ⭐ 显示画质不可用提示
    function showQualityAccessDeniedTip(qualityName) {
        var levelName = mainPage.memberActivationLevelName || "当前"
        var message = levelName + "会员不支持" + qualityName + "画质，请升级会员"
        console.log("💡 " + message)
        // 显示提示
        statusText.text = message
        statusText.color = "#ff9800"  // 橙色警告
    }
    
    // ⭐ 根据档位获取默认综合亮度（内部值，UI显示值 = 内部值 ×100, 迷惑量级）
    // 高清(standard): 20 (UI显示2000)
    // 超清(high): 40 (UI显示4000)
    // 超高清(p4k): 60 (UI显示6000)
    // 超高帧(ultra): 80 (UI显示8000)
    function getDefaultExposureForQuality(qualityType) {
        // 所有档位综合亮度默认值都是 20
        return 20
    }
    
    // ⭐ 根据档位获取默认超级帧率
    // 所有档位默认值都是 120
    function getDefaultFlickerForQuality(qualityType) {
        // 所有档位超级帧率默认值都是 120
        return 120
    }
    
    // ⭐ 设置综合亮度（同步更新所有相关组件）
    function setExposureValue(value) {
        iosCameraSettingsPopup.exposureValue = value
        exposureSettingsPopup.exposureValue = value
        exposureBiasSlider.value = value
        captureManager.exposure = value
        console.log("📊 综合亮度设置为: " + value + " (UI显示: " + (value * 100) + ")")
    }
    
    // ⭐ 设置超级帧率（同步更新所有相关组件）
    function setFlickerValue(value) {
        iosCameraSettingsPopup.flickerValue = value
        flickerSlider.value = value
        console.log("📊 超级帧率设置为: " + value)
    }
    
    // ⭐ 切换档位（带防抖 + 会员等级限制）
    function switchQuality(qualityType, qualityName) {
        // 1秒内不能切换两次
        if (qualitySwitchLocked) {
            console.log("⚠️ 档位切换锁定中，请稍后再试")
            return false
        }
        
        // ⭐ 检查会员等级是否允许该画质
        if (!isQualityAccessible(qualityName)) {
            console.log("⚠️ 当前会员等级不支持该画质: " + qualityName)
            showQualityAccessDeniedTip(qualityName)
            return false
        }
        
        // 锁定切换
        qualitySwitchLocked = true
        qualitySwitchLockTimer.restart()
        
        // 更新档位
        iosCameraSettingsPopup.qualityType = qualityType
        qualityButtonText.text = qualityName
        
        // 发送档位切换
        HttpClient.updateQualityType(qualityType)
        sendConfigUpdate("type", {"type": qualityType})
        
        // ⭐ 切换档位时保持当前参数不变（亮度、对比度、综合亮度、帧率、超级帧率）
        // 只检查帧率和超级帧率是否超过新档位的上限，如果超过则限制到上限
        var maxFps = getMaxFpsForQuality(qualityType)
        var maxFlicker = getMaxFlickerValue()
        
        if (iosCameraSettingsPopup.fpsValue > maxFps) {
            console.log("⚠️ 帧率超限，限制到新档位最大值: " + iosCameraSettingsPopup.fpsValue + " → " + maxFps)
            iosCameraSettingsPopup.fpsValue = maxFps
            fpsSlider.value = maxFps
            HttpClient.updateFps(maxFps)
            sendConfigUpdate("fps", {"fps": maxFps})
        }
        
        if (iosCameraSettingsPopup.flickerValue > maxFlicker) {
            console.log("⚠️ 超级帧率超限，限制到新档位最大值: " + iosCameraSettingsPopup.flickerValue + " → " + maxFlicker)
            setFlickerValue(maxFlicker)
            HttpClient.updateFlicker(maxFlicker)
            sendConfigUpdate("cjfps", {"cjfps": maxFlicker})
        }
        
        console.log("📊 档位切换: " + qualityName + " (参数保持不变)")
        
        // ⭐⭐⭐ 档位切换后发送 PLI 请求关键帧（防止绿幕）
        // 延迟 300ms 发送，等待 iOS 完成分辨率切换
        pliAfterQualitySwitchTimer.restart()
        
        return true
    }
    
    // ⭐ 切换档位时检查帧率限制（兼容旧调用）
    function checkFpsLimitOnQualityChange(qualityType) {
        var maxFps = getMaxFpsForQuality(qualityType)
        
        if (iosCameraSettingsPopup.fpsValue > maxFps) {
            console.log("⚠️ 帧率超限: 当前=" + iosCameraSettingsPopup.fpsValue + " 最大=" + maxFps + " 档位=" + qualityType)
            iosCameraSettingsPopup.fpsValue = maxFps
            // ⭐ 同时更新滑块 UI（修复绑定被破坏后不更新的问题）
            fpsSlider.value = maxFps
            // 1秒后推送新帧率
            fpsLimitPushTimer.restart()
        }
    }
    
    // 发送配置更新（通知其他PC）- 格式与 Java 保持一致
    function sendConfigUpdate(ptype, config) {
        var deviceId = HttpClient.currentDeviceId()
        var operator = HttpClient.loggedInUsername() || ""  // ⭐ 添加操作者
        console.log("📤 sendConfigUpdate 调用, ptype:", ptype, "deviceId:", deviceId, "operator:", operator)
        
        if (!deviceId) {
            console.log("sendConfigUpdate: no deviceId")
            return
        }
        
        // 构建完整的 config 对象（与 Java ThinRemoteConfig 格式一致）
        var fullConfig = config
        fullConfig["device_id"] = deviceId
        fullConfig["ptype"] = ptype
        
        var notification = {
            "type": "CONFIG_UPDATE",
            "deviceId": deviceId,
            "config": fullConfig,
            "operator": operator,  // ⭐ 添加操作者
            "timestamp": Date.now()
        }
        
        var jsonStr = JSON.stringify(notification)
        var destination = "/topic/device/" + deviceId + "/config"
        
        console.log("📤 发送配置更新:")
        console.log("📤   目标:", destination)
        console.log("📤   操作者:", operator)
        console.log("📤   消息:", jsonStr)
        
        // 使用 sendMessageJson 直接发送 JSON 字符串
        WebSocketClient.sendMessageJson(destination, jsonStr)
    }
    
    // 发送渲染参数更新（曝光及关联的5个值）- 格式与 Java 保持一致
    function sendRenderParamUpdate(paramName, value) {
        var deviceId = HttpClient.currentDeviceId()
        var operator = HttpClient.loggedInUsername() || ""  // ⭐ 添加操作者
        if (!deviceId) return
        
        // 构建完整的 config 对象
        var fullConfig = {
            "device_id": deviceId,
            "ptype": paramName
        }
        fullConfig[paramName] = value
        
        var notification = {
            "type": "CONFIG_UPDATE",
            "deviceId": deviceId,
            "config": fullConfig,
            "operator": operator,  // ⭐ 添加操作者
            "timestamp": Date.now()
        }
        
        var jsonStr = JSON.stringify(notification)
        var destination = "/topic/device/" + deviceId + "/config"
        
        console.log("📤 发送渲染参数:", paramName, "=", value, "operator:", operator)
        console.log("📤   消息:", jsonStr)
        
        // 使用 sendMessageJson 直接发送 JSON 字符串
        WebSocketClient.sendMessageJson(destination, jsonStr)
    }
    
    // ⭐ 发送本地视觉效果更新（时时流局部缩放）
    function sendLocalViewUpdate(zoom, offsetX, offsetY) {
        var deviceId = HttpClient.currentDeviceId()
        var operator = HttpClient.loggedInUsername() || ""
        if (!deviceId) return
        
        var fullConfig = {
            "device_id": deviceId,
            "ptype": "localView",
            "videoZoom": zoom,
            "videoOffsetX": offsetX,
            "videoOffsetY": offsetY
        }
        
        var notification = {
            "type": "CONFIG_UPDATE",
            "deviceId": deviceId,
            "config": fullConfig,
            "operator": operator,
            "timestamp": Date.now()
        }
        
        var jsonStr = JSON.stringify(notification)
        var destination = "/topic/device/" + deviceId + "/config"
        
        console.log("📤 发送本地视觉:", "zoom=", zoom, "offsetX=", offsetX, "offsetY=", offsetY, "operator:", operator)
        
        WebSocketClient.sendMessageJson(destination, jsonStr)
    }
    
    // 监听相机配置接收
    Connections {
        target: HttpClient
        
        function onThinConfigReceived(focus, exposureBias, cjfps, fps, bitrate, direction, type, zoom) {
            console.log("📥 ThinConfig received: focus=", focus, "exposureBias=", exposureBias, "cjfps=", cjfps, 
                        "fps=", fps, "bitrate=", bitrate, "direction=", direction, "type=", type, "zoom=", zoom)
            
            // 更新相机设定弹窗的值
            iosCameraSettingsPopup.focusValue = focus
            // 综合亮度：使用本地保存的值，不从后端同步（避免覆盖用户设置）
            // iosCameraSettingsPopup.exposureValue 在 open() 时从 captureManager.exposure 读取
            // 超级帧：范围60-400，限制在会员等级+挡位允许的范围内
            var maxFlicker = getMaxFlickerValue()
            iosCameraSettingsPopup.flickerValue = Math.max(60, Math.min(cjfps, maxFlicker))
            // ⭐ fps 不再 *2，后端值直接显示在滑块上
            iosCameraSettingsPopup.fpsValue = fps
            // ⭐ 同时更新滑块 UI（确保绑定被破坏后也能正确显示）
            fpsSlider.value = fps
            iosCameraSettingsPopup.clarityValue = bitrate
            iosCameraSettingsPopup.lensZoom = zoom
            iosCameraSettingsPopup.directionValue = direction
            
            // 初始化底部档位按钮显示（支持多种格式）
            var typeMap = {"low": "超低网", "standard": "高清", "high": "超清", "ultra": "超高帧", "p4k": "超高清", "4k": "超高清"}
            var normalizedType = type.toLowerCase()
            if (normalizedType === "4k") normalizedType = "p4k"
            qualityButtonText.text = typeMap[type] || typeMap[normalizedType] || "超清"
            iosCameraSettingsPopup.qualityType = normalizedType || "high"
            console.log("📥 初始化档位: type='" + type + "' -> '" + qualityButtonText.text + "'")
            
            // ⭐ 根据档位设置默认综合亮度
            var defaultExposure = getDefaultExposureForQuality(normalizedType || "high")
            setExposureValue(defaultExposure)
            sendConfigUpdate("exposureBias", {"exposureBias": defaultExposure})
        }
    }

    Component.onCompleted: {
        console.log("📦 MainPage.qml: Component.onCompleted 开始")
        console.log("MainPage loaded, currentStream=" + currentStream)
        // 不在这里调用 playWebRTC()，等待 CONFIG_STATE 消息
        // playWebRTC() 会在收到 publishStatus=1 时自动调用
        
        // ⭐ 面板色迁移：旧默认值(H=0.35,S=0.25,V=0.85)重置为新默认90%白色
        if (Math.abs(appSettings.panelColorH - 0.35) < 0.01 &&
            Math.abs(appSettings.panelColorS - 0.25) < 0.01 &&
            Math.abs(appSettings.panelColorV - 0.85) < 0.01) {
            appSettings.panelColorH = 0
            appSettings.panelColorS = 0
            appSettings.panelColorV = 0.9
            console.log("🎨 面板色迁移：旧默认值 → 90%白色")
        }
        
        // ⭐ 从 HttpClient 读取PC端激活等级（登录信号在MainPage加载前已发出，这里补读）
        var pcLevel = HttpClient.pcActivationLevel()
        console.log("[抓拍全屏] Component.onCompleted: 从 HttpClient 读取 pcActivationLevel=" + pcLevel)
        mainPage.pcActivationLevel = pcLevel
        mainPage.pcLevelName = HttpClient.pcLevelName()
        mainPage.pcExpireAt = HttpClient.pcExpireAt()
        console.log("[抓拍全屏] Component.onCompleted: 设置后 mainPage.pcActivationLevel=" + mainPage.pcActivationLevel)
        console.log("[抓拍全屏] Component.onCompleted: 抓拍全屏菜单项应该显示:", mainPage.pcActivationLevel >= 2)
        console.log("📋 Component.onCompleted: PC端等级=" + mainPage.pcActivationLevel + " (" + mainPage.pcLevelName + ") 到期=" + mainPage.pcExpireAt)
        
        // ⭐ 从 HttpClient 读取登录时获取的 levelFps 和 levelExposureFps（登录信号在MainPage加载前已发出，这里补读）
        var serverLevelFps = HttpClient.levelFps()
        if (serverLevelFps && serverLevelFps.length > 0) {
            mainPage.levelFps = serverLevelFps
            console.log("📊 Component.onCompleted: levelFps from HttpClient=" + JSON.stringify(serverLevelFps))
        } else {
            console.log("📊 Component.onCompleted: levelFps using defaults=" + JSON.stringify(mainPage.levelFps))
        }
        var serverLevelExposureFps = HttpClient.levelExposureFps()
        if (serverLevelExposureFps && serverLevelExposureFps.length > 0) {
            mainPage.levelExposureFps = serverLevelExposureFps
            console.log("📊 Component.onCompleted: levelExposureFps from HttpClient=" + JSON.stringify(serverLevelExposureFps))
        } else {
            console.log("📊 Component.onCompleted: levelExposureFps using defaults=" + JSON.stringify(mainPage.levelExposureFps))
        }
        
        // ⭐ 从 HttpClient 读取 iOS 设备等级（登录信号在MainPage加载前已发出，这里补读）
        var serverDeviceLevel = HttpClient.deviceLevel()
        if (serverDeviceLevel > 0) {
            mainPage.memberActivationLevel = serverDeviceLevel
            mainPage.memberActivated = true
            console.log("📱 Component.onCompleted: deviceLevel=" + serverDeviceLevel + " → memberActivationLevel=" + mainPage.memberActivationLevel)
        }
        
        // 初始化 WebSocket 连接
        initWebSocket()
        
        // 从缓存初始化 zoom、fps、档位显示（登录成功时已获取 ThinConfig）
        iosCameraSettingsPopup.lensZoom = HttpClient.getCachedZoom()
        iosCameraSettingsPopup.directionValue = HttpClient.getCachedDirection()
        // ⭐ 从缓存读取帧率
        var cachedFps = HttpClient.getCachedFps()
        if (cachedFps > 0) {
            iosCameraSettingsPopup.fpsValue = cachedFps
            fpsSlider.value = cachedFps
            console.log("⭐ Component.onCompleted: 从缓存读取帧率=" + cachedFps)
        }
        var cachedType = HttpClient.getCachedQualityType()
        console.log("⭐ Component.onCompleted: 读取缓存 cachedType='" + cachedType + "'")
        var typeMap = {"low": "超低网", "standard": "高清", "high": "超清", "ultra": "超高帧", "p4k": "超高清", "4k": "超高清"}
        var mappedType = typeMap[cachedType]
        console.log("⭐ Component.onCompleted: typeMap[cachedType]='" + mappedType + "'")
        if (!mappedType && cachedType) {
            mappedType = typeMap[cachedType.toLowerCase()]
            console.log("⭐ Component.onCompleted: typeMap[toLowerCase]='" + mappedType + "'")
        }
        qualityButtonText.text = mappedType || "超清"
        // 同步到相机设定
        var normalizedType = cachedType ? cachedType.toLowerCase() : "high"
        if (normalizedType === "4k") normalizedType = "p4k"
        iosCameraSettingsPopup.qualityType = normalizedType || "high"
        console.log("⭐ Component.onCompleted: 最终显示='" + qualityButtonText.text + "'")
    }
    
    // ============ WebSocket 连接 ============
    
    function initWebSocket() {
        var wsUrl = HttpClient.websocketUrl()
        var token = HttpClient.authToken()
        var username = HttpClient.loggedInUsername()
        var pcDevId = HttpClient.pcDeviceId()
        var deviceId = HttpClient.currentDeviceId()
        
        // 确保 pairedIosDeviceId 有值（自动登录时不经过 onLoginSuccess）
        if (deviceId && deviceId.length > 0 && (!mainPage.pairedIosDeviceId || mainPage.pairedIosDeviceId.length === 0)) {
            mainPage.pairedIosDeviceId = deviceId
            console.log("📱 initWebSocket: 从缓存设置 pairedIosDeviceId=" + deviceId)
        }
        
        WebSocketClient.setConnectionParams(wsUrl, token, username, pcDevId)
        WebSocketClient.connectToServer()
    }
    
    // WebSocket 状态监听
    Connections {
        target: WebSocketClient
        
        function onStompConnected() {
            statusText.text = "STOMP 已连接"
            
            // 1. 始终订阅绑定消息频道（等待 iOS 扫码绑定）
            WebSocketClient.subscribe("/user/queue/binding")
            
            // 2. 只有有设备时才订阅设备配置频道
            var deviceId = HttpClient.currentDeviceId()
            if (deviceId && deviceId !== "") {
                WebSocketClient.subscribe("/topic/device/" + deviceId + "/config")
                
                // 确保 pairedIosDeviceId 有值
                if (!mainPage.pairedIosDeviceId || mainPage.pairedIosDeviceId.length === 0) {
                    mainPage.pairedIosDeviceId = deviceId
                    console.log("📱 onStompConnected: 设置 pairedIosDeviceId=" + deviceId)
                }
            } else {
                statusText.text = "等待绑定设备..."
            }
            
            // 3. 订阅 WebRTC 信令频道（P2P 模式需要）
            WebSocketClient.subscribeWebRTCSignaling()

            // ⭐ 4. STOMP 连上后立即把当前 iOS 滤镜参数推给 iOS — 不再依赖按 P
            //    场景: 客户开机 → 自动登录 → STOMP 连上 → 此处一发, iOS 立刻应用滤镜默认值.
            //    备份保险: 如果 applyServerDefaults 在 STOMP 连上之前就完成 (HTTP 比 WS 快),
            //    那次 push 因 STOMP 未连而失败, 此处 onStompConnected 兜底再发一次.
            //    多发无副作用 (iOS 收到相同值不会有视觉抖动).
            if (deviceId && deviceId !== "") {
                iosFilterPopup.pushAllStomp()
            }
        }
        
        // 收到 WebRTC 信令消息 → 转发给 GstPlayer
        function onWebrtcSignalingReceived(message) {
            if (gstPlayer.isP2PMode()) {
                console.log("[P2P-QML] 收到 WebRTC 信令: " + message.type)
                gstPlayer.handleWebRTCSignaling(message)
            }
        }
        
        function onStompDisconnected(reason) {
            statusText.text = "STOMP 断开: " + reason
            
            // WebSocket 断线时，停止拉流
            if (publishState === 1) {
                stopAll()
            }
            publishState = 0
            
            // ⭐ 重置右上角状态显示（码率、电量、网络质量）
            // FPS 自动从 gstPlayer.receiveFps 获取（stop 时自动归零）
            mainPage.deviceKbps = 0
            mainPage.deviceBattery = -1
            mainPage.deviceNetworkQuality = ""
            mainPage.deviceNetworkType = ""
        }
        
        function onStompError(error) {
            statusText.text = "STOMP 错误: " + error
        }
        
        // 收到绑定消息（iOS 设备扫码绑定成功）
        function onBindingMessageReceived(message) {
            handleBindingMessage(message)
        }
        
        // 收到设备配置消息
        function onDeviceConfigReceived(message) {
            handleDeviceConfigMessage(message)
        }
    }
    
    // 处理绑定消息（iOS 设备扫码绑定成功）
    function handleBindingMessage(message) {
        console.log("📩 收到绑定消息:", JSON.stringify(message))
        
        var msgType = message.type || ""
        var state = message.state || ""
        var newDeviceId = message.deviceId || ""
        var iosUsername = message.iosusername || ""
        var controlUsername = message.controlUsername || ""
        var controlNickname = message.controlNickname || ""
        
        console.log("📩 绑定消息解析: type=" + msgType + ", state=" + state + ", deviceId=" + newDeviceId + ", iosUsername=" + iosUsername)
        
        // 只处理 IOSBD 类型且状态为 ACTIVE 的消息
        if (msgType !== "IOSBD" || state !== "ACTIVE") {
            console.log("⚠️ 非绑定成功消息，忽略: type=" + msgType + ", state=" + state)
            return
        }
        
        console.log("📱 iOS 设备绑定成功: deviceId=" + newDeviceId + ", iosUsername=" + iosUsername)
        
        // 关闭扫码绑定弹窗
        scanBindPopup.close()
        
        var currentDeviceId = HttpClient.currentDeviceId()
        var hasExistingDevice = currentDeviceId && currentDeviceId !== ""
        
        console.log("📩 hasExistingDevice=" + hasExistingDevice + ", currentDeviceId=" + currentDeviceId)
        
        if (hasExistingDevice) {
            // 已有设备：只提示绑定成功，不切换设备
            statusText.text = "绑定成功"
            showToast("绑定成功")
        } else {
            // 首次绑定设备：重新登录获取设备信息
            statusText.text = "绑定成功"
            showToast("绑定成功")
            reLoginAndInitDevice(newDeviceId, iosUsername)
        }
    }
    
    // 首次绑定后重新登录
    property bool isBindingReLogin: false  // 标记是否为绑定后的重新登录
    
    // ⭐ 切换设备时的临时数据
    property bool isSwitchingDevice: false  // 标记是否正在切换设备
    property string switchingUsername: ""   // 切换目标的账号
    property string switchingPassword: ""   // 切换目标的密码
    property string switchingDeviceUsername: ""  // 切换目标的设备
    property string switchingDeviceDisplay: ""   // 切换目标的设备显示名
    
    function reLoginAndInitDevice(newDeviceId, iosUsername) {
        var username = HttpClient.getSavedUsername()
        var password = HttpClient.getSavedPassword()
        
        if (!username || !password) {
            console.log("⚠️ 无法重新登录：用户名或密码为空")
            // 直接使用当前信息初始化
            initAfterDeviceBinding(newDeviceId)
            return
        }
        
        console.log("🔄 重新登录: username=" + username + ", iosUsername=" + iosUsername)
        isBindingReLogin = true
        HttpClient.login(username, password, mainPage.pcActivationLevel || 1, iosUsername)
    }
    
    // 绑定后初始化设备（订阅频道等）
    function initAfterDeviceBinding(deviceId) {
        if (!deviceId || deviceId === "") {
            console.log("⚠️ initAfterDeviceBinding: deviceId 为空")
            return
        }
        
        console.log("📱 initAfterDeviceBinding: 订阅设备频道 deviceId=" + deviceId)
        
        // 订阅设备配置频道
        WebSocketClient.subscribe("/topic/device/" + deviceId + "/config")
        
        statusText.text = "绑定成功"
        showToast("绑定成功")
    }
    
    // 监听登录成功信号（用于绑定后重新登录 / 切换设备）
    Connections {
        target: HttpClient
        
        function onLoginSuccess(token, deviceId, deviceUsername, bindingList, pcActivationLevel, pcLevelName, pcExpireAt, deviceLevel, levelFps, levelExposureFps, iceServersFromLogin) {
            // ⭐ 切换账号 / 登录成功 → 重新拉取 iOS 滤镜后端默认值 (含 from/to/step/default/linkDefault)
            //    applyServerDefaults 会把所有 fXxx / prevXxx / linkXxx / 上下限/步进 全部覆盖为后端值.
            //    同时把"综合亮度"回到中点 50 (= 全部 iOS 滤镜参数都到 default), 保持 UI 与底层一致.
            //    captureManager.exposure 也同步重置 — 因为 iosCameraSettingsPopup.open() 会从这里读初值,
            //    不写它的话, 下次打开相机设定时仍会显示账号切换前的旧值.
            //    注意: 启动时 iosFilterPopup.Component.onCompleted 已经拉过一次, 此处覆盖账号切换场景.
            HttpClient.getIosFilterDefaults()
            captureManager.exposure = 50
            iosCameraSettingsPopup.exposureValue = 50

            // ⭐ 保存 ICE 服务器列表（P2P STUN/TURN 配置）
            if (iceServersFromLogin && iceServersFromLogin.length > 0) {
                mainPage.iceServers = iceServersFromLogin
                console.log("🌐 onLoginSuccess: iceServers=" + iceServersFromLogin.length + "个")
            } else {
                mainPage.iceServers = [
                    {"urls": ["stun:stun.miwifi.com:3478"]},
                    {"urls": ["stun:stun.qq.com:3478"]},
                    {"urls": ["stun:stun.l.google.com:19302"]}
                ]
                console.log("⚠️ onLoginSuccess: 服务器未返回 iceServers, 使用默认公共 STUN")
            }
            
            // 设置配对的 iOS 设备 ID（用于 P2P 信令发送）
            if (deviceId && deviceId.length > 0) {
                mainPage.pairedIosDeviceId = deviceId
                console.log("📱 onLoginSuccess: pairedIosDeviceId=" + deviceId)
            }
            
            // ⭐ 保存PC端激活等级和到期信息
            console.log("[抓拍全屏] onLoginSuccess: 收到 pcActivationLevel=" + pcActivationLevel + ", pcLevelName=" + pcLevelName)
            mainPage.pcActivationLevel = pcActivationLevel
            mainPage.pcLevelName = pcLevelName || ""
            mainPage.pcExpireAt = pcExpireAt || ""
            console.log("📋 PC端等级:", pcActivationLevel, "(" + pcLevelName + ") 到期:", pcExpireAt)
            console.log("[抓拍全屏] onLoginSuccess: 抓拍全屏菜单项应该显示:", pcActivationLevel >= 2)
            
            // ⭐ 从登录接口获取各等级FPS上限数组
            if (levelFps && levelFps.length > 0) {
                mainPage.levelFps = levelFps
                console.log("📊 onLoginSuccess: levelFps from server=" + JSON.stringify(levelFps))
            } else {
                mainPage.levelFps = [240, 120, 180, 180, 240]  // 默认值
                console.log("📊 onLoginSuccess: levelFps using defaults")
            }
            
            // ⭐ 从登录接口获取各等级超级帧率上限数组
            if (levelExposureFps && levelExposureFps.length > 0) {
                mainPage.levelExposureFps = levelExposureFps
                console.log("📊 onLoginSuccess: levelExposureFps from server=" + JSON.stringify(levelExposureFps))
            } else {
                mainPage.levelExposureFps = [600, 120, 180, 240, 600]  // 默认值
                console.log("📊 onLoginSuccess: levelExposureFps using defaults")
            }
            
            // ⭐ 从登录接口获取 iOS 设备等级，设置 memberActivationLevel
            // deviceLevel: 0=试用, 1=标清, 2=高清, 3=超清, 4=4K
            var dl = (deviceLevel !== undefined && deviceLevel !== null) ? deviceLevel : 1
            mainPage.memberActivationLevel = dl
            // 设备等级 > 0 表示已激活
            mainPage.memberActivated = (dl > 0)
            console.log("📱 onLoginSuccess: deviceLevel=" + dl + " → memberActivationLevel=" + mainPage.memberActivationLevel + " memberActivated=" + mainPage.memberActivated)
            
            // ⭐ 设备等级更新后，重新检查超级帧滑块上限和帧率上限
            var maxFlicker = getMaxFlickerValue()
            console.log("📊 登录设备等级: level=" + dl + " 超级帧上限=" + maxFlicker + " 当前值=" + iosCameraSettingsPopup.flickerValue)
            var newFlickerValue = Math.max(60, Math.min(iosCameraSettingsPopup.flickerValue, maxFlicker))
            if (iosCameraSettingsPopup.flickerValue !== newFlickerValue) {
                iosCameraSettingsPopup.flickerValue = newFlickerValue
                console.log("⚠️ 超级帧调整到: " + newFlickerValue)
            }
            
            // ⭐ 切换设备时保存新的设备信息
            if (isSwitchingDevice) {
                isSwitchingDevice = false
                console.log("✅ 切换设备登录成功，保存设备信息: username=" + switchingUsername + " device=" + switchingDeviceUsername)

                // 保存新的设备信息到本地存储
                HttpClient.saveAccount(
                    switchingUsername,
                    switchingPassword,
                    switchingDeviceUsername,
                    switchingDeviceDisplay
                )

                // 清空临时数据
                switchingUsername = ""
                switchingPassword = ""
                switchingDeviceUsername = ""
                switchingDeviceDisplay = ""

                // 重新初始化 WebSocket
                initWebSocket()

                // ⭐ 重新拉取相机配置，避免档位/清晰度残留上一账号的值
                HttpClient.getThinConfig()
                return
            }
            
            // 只在绑定重新登录时处理
            if (isBindingReLogin) {
                isBindingReLogin = false
                console.log("✅ 绑定后重新登录成功，deviceId=" + deviceId)
                initAfterDeviceBinding(deviceId)
            }
        }
        
        function onLoginFailed(code, message) {
            if (isBindingReLogin) {
                isBindingReLogin = false
                console.log("❌ 绑定后重新登录失败: " + message)
                // 即使登录失败，也尝试初始化
                var deviceId = HttpClient.currentDeviceId()
                if (deviceId && deviceId !== "") {
                    initAfterDeviceBinding(deviceId)
                } else {
                    showToast("登录失败: " + message)
                }
            }
        }
    }
    
    // ============ 推流状态管理 ============
    property int publishState: 0  // 0=未推流, 1=推流中
    property bool isConnecting: false  // 🔥 v14: 正在连接中标志（防止stopAll期间的断开回调重置publishState导致死循环）
    property string lastStreamKey: ""
    property string lastStreamPushIp: ""
    property string deviceStatus: ""  // 设备状态：""=无, "sleeping"=睡眠中, "waking"=唤醒中
    
    // 处理设备配置消息
    function handleDeviceConfigMessage(message) {
        var msgType = message.type || ""
        var msgDeviceId = message.deviceId || ""
        var expectedDeviceId = HttpClient.currentDeviceId()
        
        // ============ CONFIG_STATE：设备状态消息 ============
        if (msgType === "CONFIG_STATE") {
            // 验证 deviceId
            if (!expectedDeviceId || expectedDeviceId !== msgDeviceId) {
                return
            }
            
            var state = message.state || {}
            var publishStatus = state.publishStatus !== undefined ? state.publishStatus : 0
            var streamKey = state.streamKey || ""
            var streamPushIp = state.streamPushIp || ""
            var networkType = state.networkType || ""
            var battery = state.battery !== undefined ? state.battery : -1
            var fps = state.fps || 0
            var sendFps = state.sendFps || 0
            var kbps = state.kbps || 0
            var networkQuality = state.networkQuality || ""
            
            // ⭐ 解析会员等级信息
            // 等级规则：0=试用全开放, 1=高清, 2=超清, 3=超高帧, 4=超超清
            // 高等级自动拥有低等级权限
            var memberLevelChanged = false
            if (state.activated !== undefined) {
                mainPage.memberActivated = state.activated
                memberLevelChanged = true
            }
            if (state.activationLevel !== undefined) {
                mainPage.memberActivationLevel = state.activationLevel
                memberLevelChanged = true
            }
            if (state.activationLevelName !== undefined) {
                mainPage.memberActivationLevelName = state.activationLevelName
            }
            if (state.qualityAccess !== undefined && Array.isArray(state.qualityAccess)) {
                mainPage.memberQualityAccess = state.qualityAccess
                memberLevelChanged = true
            }
            // 🆕 日试用标记
            if (state.isDailyTrial !== undefined) {
                mainPage.isDailyTrial = state.isDailyTrial
            }
            // 🆕 剩余有效秒数
            if (state.activationRemainingSeconds !== undefined) {
                mainPage.activationRemainingSeconds = state.activationRemainingSeconds
            }
            
            // ⭐ 会员等级更新后，重新检查超级帧滑块上限（范围60-400）
            if (memberLevelChanged) {
                var maxFlicker = getMaxFlickerValue()
                console.log("📊 会员等级更新: activated=" + mainPage.memberActivated + " level=" + mainPage.memberActivationLevel + " levelName=" + mainPage.memberActivationLevelName + " isDailyTrial=" + mainPage.isDailyTrial + " remainingSeconds=" + mainPage.activationRemainingSeconds)
                console.log("📊 超级帧上限=" + maxFlicker + " 当前值=" + iosCameraSettingsPopup.flickerValue)
                // 确保值在 60-maxFlicker 范围内
                var newValue = Math.max(60, Math.min(iosCameraSettingsPopup.flickerValue, maxFlicker))
                if (iosCameraSettingsPopup.flickerValue !== newValue) {
                    iosCameraSettingsPopup.flickerValue = newValue
                    console.log("⚠️ 超级帧调整到: " + newValue)
                }
            }
            
            // ⭐ 更新设备状态属性（供顶部状态栏显示）
            mainPage.deviceKbps = kbps
            mainPage.deviceBattery = battery
            mainPage.deviceNetworkQuality = networkQuality
            mainPage.deviceNetworkType = networkType
            // FPS 现在从 gstPlayer.receiveFps 自动获取（绑定）
            
            // ⭐ 更新拉流 IP
            if (streamPushIp && streamPushIp.length > 0) {
                lastStreamPushIp = streamPushIp
                srsServer = streamPushIp  // 更新拉流服务器地址
            }
            
            // 解析连接类型: 0 或未传 = SRS 模式, 1 = P2P 直连模式
            var connectstype = state.connectstype !== undefined ? state.connectstype : 0
            if (connectstype !== mainPage.connectMode) {
                console.log("🔄 连接模式变更: " + mainPage.connectMode + " → " + connectstype)
                mainPage.connectMode = connectstype
            }
            
            // ⭐ 推流状态处理
            if (publishStatus === 1 && streamKey && streamKey.length > 0) {
                // 开始推流
                if (publishState === 0) {
                    console.log("📥 设备开始推流，启动拉流...")
                    publishState = 1
                    mainPage.deviceStatus = ""
                    lastStreamKey = streamKey
                    currentStream = streamKey
                    statusText.text = "正在连接视频流..."
                    
                    // 根据 connectstype 选择拉流模式
                    if (mainPage.connectMode === 1) {
                        console.log("🌐 使用 P2P 直连模式拉流")
                        playP2P()
                    } else {
                        console.log("🎬 使用 SRS 模式拉流")
                        playWebRTC()
                    }
                }
            } else {
                // iOS 停止推流（publishStatus = 0 或没有 streamKey）
                if (publishState === 1) {
                    console.log("📥 设备停止推流，停止拉流...")
                    stopAll()
                    statusText.text = "设备未上线"
                }
                publishState = 0
                // 重置设备状态
                // FPS 自动从 gstPlayer.receiveFps 获取
                mainPage.deviceKbps = 0
                mainPage.deviceBattery = -1
                mainPage.deviceNetworkQuality = ""
                mainPage.deviceNetworkType = ""
            }
            
            // 更新状态栏信息
            if (publishStatus === 1) {
                liveInfoFps.text = "FPS: " + fps + " | " + kbps + "kbps"
                deviceStatusText.text = "📱 FPS: " + fps + " | " + kbps + "kbps | 电量: " + battery + "%"
                deviceStatusText.color = "#4caf50"  // 绿色表示在线
                
                // ⭐ 设置慢放播放帧率（fps / 2）
                if (fps > 0) {
                    var realFps = Math.floor(fps / 2)
                    if (realFps > 0 && realFps !== slowMotionPlayer.maxFrameRate) {
                        slowMotionPlayer.maxFrameRate = realFps
                        console.log("SlowMotionPlayer: maxFrameRate set to", realFps, "from device fps", fps)
                    }
                }
            } else {
                liveInfoFps.text = "FPS: --"
                deviceStatusText.text = "📱 设备未上线"
                deviceStatusText.color = "#ff9800"  // 橙色表示离线
            }
        }
        // ============ CONFIG_UPDATE：配置更新 ============
        else if (msgType === "CONFIG_UPDATE") {
            var ptype = message.ptype || ""
            var config = message.config || {}
            var operator = message.operator || ""
            var myUsername = HttpClient.loggedInUsername() || ""
            
            // 如果是自己发送的，不处理（避免循环）
            if (operator && operator === myUsername) {
                console.log("📥 CONFIG_UPDATE 忽略自己的消息:", ptype)
                return
            }
            
            // 如果顶层 ptype 为空，尝试从 config 中获取
            if (!ptype && config.ptype) {
                ptype = config.ptype
            }
            
            console.log("📥 CONFIG_UPDATE ptype:", ptype, "operator:", operator, "config:", JSON.stringify(config))
            
            // 根据 ptype 处理不同配置，或者当 ptype 为空时更新所有可用配置
            var shouldUpdateAll = (!ptype || ptype === "all")
            
            if (ptype === "focus" || (shouldUpdateAll && config.focus !== undefined)) {
                if (config.focus !== undefined) iosCameraSettingsPopup.focusValue = config.focus
            }
            // ⭐ 综合亮度（exposureBias）- 其他PC操作时需要同步并应用视觉效果
            if (ptype === "exposureBias" || (shouldUpdateAll && config.exposureBias !== undefined)) {
                if (config.exposureBias !== undefined) {
                    var expBiasVal = config.exposureBias
                    console.log("📥 同步综合亮度:", expBiasVal)
                    iosCameraSettingsPopup.exposureValue = expBiasVal
                    exposureSettingsPopup.exposureValue = expBiasVal
                    captureManager.exposure = expBiasVal
                    captureManager.applyExposurePreview(expBiasVal)  // ⭐ 应用视觉效果
                    syncExposureParamsFromCaptureManager()
                }
            }
            if (ptype === "cjfps" || (shouldUpdateAll && config.cjfps !== undefined)) {
                if (config.cjfps !== undefined) {
                    // 超级帧：范围60-400，限制在会员等级+挡位允许的范围内
                    var maxFlicker = getMaxFlickerValue()
                    iosCameraSettingsPopup.flickerValue = Math.max(60, Math.min(config.cjfps, maxFlicker))
                }
            }
            // ⭐ 帧率不再从 WebSocket 同步（初始化和用户拖动不变）
            // if (ptype === "fps" || (shouldUpdateAll && config.fps !== undefined)) {
            //     if (config.fps !== undefined) {
            //         iosCameraSettingsPopup.fpsValue = config.fps
            //         fpsSlider.value = config.fps
            //     }
            // }
            if (ptype === "bitrate" || (shouldUpdateAll && config.bitrate !== undefined)) {
                if (config.bitrate !== undefined) iosCameraSettingsPopup.clarityValue = config.bitrate
            }
            if (ptype === "zoom" || (shouldUpdateAll && config.zoom !== undefined)) {
                if (config.zoom !== undefined) iosCameraSettingsPopup.lensZoom = config.zoom
            }
            if (ptype === "direction" || (shouldUpdateAll && config.direction !== undefined)) {
                if (config.direction !== undefined) iosCameraSettingsPopup.directionValue = config.direction
            }
            if (ptype === "type" || (shouldUpdateAll && config.type !== undefined)) {
                // 更新画质类型
                if (config.type !== undefined) {
                    var typeMap = {"low": "超低网", "standard": "高清", "high": "超清", "ultra": "超高帧", "p4k": "超高清", "4k": "超高清"}
                    var normalizedQType = config.type.toLowerCase()
                    if (normalizedQType === "4k") normalizedQType = "p4k"
                    qualityButtonText.text = typeMap[config.type] || typeMap[normalizedQType] || "超清"
                    // ⭐ 同步更新 qualityType，避免底部按钮与设定弹窗不一致
                    iosCameraSettingsPopup.qualityType = normalizedQType || "high"
                }
            }
            
            // 渲染参数（曝光、亮度、对比度、饱和度、色调、伽马）
            // ptype 直接就是参数名
            if (ptype === "exposure" || (shouldUpdateAll && config.exposure !== undefined)) {
                if (config.exposure !== undefined) {
                    var expVal = config.exposure
                    iosCameraSettingsPopup.exposureValue = expVal
                    exposureSettingsPopup.exposureValue = expVal
                    captureManager.applyExposurePreview(expVal)
                    syncExposureParamsFromCaptureManager()
                }
            }
            // ⭐ PC 端色彩调整已禁用 (看 iOS 原画) — 不再接收其他 PC 同步过来的色彩参数
            //   popup 显示值仍同步, 但不写 captureManager (不会触发 GstPlayer videobalance/gamma)
            if (ptype === "brightness" || (shouldUpdateAll && config.brightness !== undefined)) {
                if (config.brightness !== undefined) {
                    exposureSettingsPopup.brightnessValue = config.brightness
                    // captureManager.brightness = config.brightness
                }
            }
            if (ptype === "contrast" || (shouldUpdateAll && config.contrast !== undefined)) {
                if (config.contrast !== undefined) {
                    exposureSettingsPopup.contrastValue = config.contrast
                    // captureManager.contrast = config.contrast
                }
            }
            if (ptype === "saturation" || (shouldUpdateAll && config.saturation !== undefined)) {
                if (config.saturation !== undefined) {
                    exposureSettingsPopup.saturationValue = config.saturation
                    // captureManager.saturation = config.saturation
                }
            }
            if (ptype === "hue" || (shouldUpdateAll && config.hue !== undefined)) {
                if (config.hue !== undefined) {
                    exposureSettingsPopup.hueValue = config.hue
                    // captureManager.hue = config.hue
                }
            }
            if (ptype === "gamma" || (shouldUpdateAll && config.gamma !== undefined)) {
                if (config.gamma !== undefined) {
                    exposureSettingsPopup.gammaValue = config.gamma
                    // captureManager.gamma = config.gamma
                }
            }
            
            // ⭐ 本地视觉效果（时时流局部缩放）- 其他PC操作时需要同步
            if (ptype === "localView") {
                if (config.videoZoom !== undefined) {
                    var newZoom = config.videoZoom
                    var newOffsetX = config.videoOffsetX || 0
                    var newOffsetY = config.videoOffsetY || 0
                    
                    // ⭐ 边界约束：根据本地容器大小重新计算有效偏移范围
                    var maxOffsetX = videoContainer.width * (newZoom - 1) / 2
                    var maxOffsetY = videoContainer.height * (newZoom - 1) / 2
                    mainPage.videoZoom = newZoom
                    mainPage.videoOffsetX = Math.max(-maxOffsetX, Math.min(maxOffsetX, newOffsetX))
                    mainPage.videoOffsetY = Math.max(-maxOffsetY, Math.min(maxOffsetY, newOffsetY))
                    
                    console.log("📥 同步本地视觉: zoom=", newZoom, "offsetX=", mainPage.videoOffsetX.toFixed(1), "offsetY=", mainPage.videoOffsetY.toFixed(1), "maxOffset=", maxOffsetX.toFixed(1))
                }
            }
        }
        // ============ CONFIG_ERROR：iOS 设备断线 ============
        else if (msgType === "CONFIG_ERROR") {
            var iosDeviceUsername = message.error || ""
            var currentDeviceUsername = HttpClient.getSavedDeviceUsername()
            
            console.log("📥 CONFIG_ERROR: iOS断线消息，iosDeviceUsername:", iosDeviceUsername, "currentDeviceUsername:", currentDeviceUsername)
            
            // 验证是否为当前绑定的设备
            if (currentDeviceUsername && currentDeviceUsername === iosDeviceUsername) {
                console.log("📥 CONFIG_ERROR: 设备匹配，正在停止拉流并重置状态...")
                
                // 停止拉流
                if (publishState === 1) {
                    stopAll()
                }
                publishState = 0
                statusText.text = "iOS 设备已断线"
                deviceStatusText.text = "📱 设备已断线"
                deviceStatusText.color = "#f44336"  // 红色
                liveInfoFps.text = "FPS: --"
                
                // ⭐ 重置右上角状态（码率、电量、网络质量）
                // FPS 自动从 gstPlayer.receiveFps 获取
                mainPage.deviceKbps = 0
                mainPage.deviceBattery = -1
                mainPage.deviceNetworkQuality = ""
                mainPage.deviceNetworkType = ""
                
                // ⭐ 如果切换账号弹框正在显示，刷新设备在线状态
                if (switchAccountDialog.visible) {
                    console.log("📥 CONFIG_ERROR: 切换账号弹框已打开，刷新在线状态...")
                    refreshOnlineStatus()
                }
            }
        }
        // ============ UNBIND / ACCOUNT_CLEARED：设备解绑 ============
        else if (msgType === "UNBIND" || msgType === "ACCOUNT_CLEARED") {
            var controlUsername = message.controlUsername || ""
            var loggedInUsername = HttpClient.loggedInUsername()
            
            // 验证 deviceId 和 controlUsername
            if (expectedDeviceId === msgDeviceId && loggedInUsername === controlUsername) {
                // 1. 停止拉流
                if (publishState === 1) {
                    stopAll()
                }
                publishState = 0
                
                // 2. 取消订阅旧设备频道
                WebSocketClient.unsubscribe("/topic/device/" + msgDeviceId + "/config")
                
                // 3. 更新状态
                statusText.text = "设备已解绑，等待绑定新设备..."
                deviceStatusText.text = "📱 设备已解绑"
                deviceStatusText.color = "#f44336"  // 红色
                liveInfoFps.text = "FPS: --"
                
                // 4. 弹出提示
                unbindDialog.open()
            }
        }
        // ============ ACCOUNT_UPDATEPASSWORD：设备端密码已更新，需要重新登录 ============
        else if (msgType === "ACCOUNT_UPDATEPASSWORD") {
            var controlUsername = message.controlUsername || ""
            var loggedInUsername = HttpClient.loggedInUsername()
            
            console.log("📌 收到 ACCOUNT_UPDATEPASSWORD 消息: controlUsername=" + controlUsername + " loggedInUsername=" + loggedInUsername)
            
            // 验证 controlUsername 和当前登录账号是否一致
            if (loggedInUsername === controlUsername) {
                console.log("🚪 设备端密码已更新，需要断开推流并重新登录...")
                
                // 1. 停止拉流
                stopAll()
                publishState = 0
                currentStream = ""  // 清空 streamKey
                
                // 2. 清理 frames 目录和抓拍列表
                gstPlayer.clearJpegFiles()
                captureManager.clearAll()
                
                // 3. 断开 WebSocket
                WebSocketClient.disconnectFromServer()
                
                // 4. 退出登录（只清除token，保留账号列表）
                HttpClient.logout()
                
                // 5. 弹出提示并返回登录页
                showToast("设备端账号密码已更新，请重新登录")
                logoutRequested()
            }
        }
        // ============ RESET_PUBLISH / TryDisconnect：忽略 ============
        else if (msgType === "RESET_PUBLISH" || msgType === "TryDisconnect") {
            // 忽略
        }
    }
    
    // ============ 解绑提示对话框 ============
    Dialog {
        id: unbindDialog
        title: "设备已解绑"
        modal: true
        anchors.centerIn: parent
        standardButtons: Dialog.Ok
        
        Label {
            text: "iOS 设备已解除绑定\n\n如需继续使用请重新绑定设备"
            wrapMode: Text.WordWrap
            width: 300
        }
    }
    
    // ============ 扫码绑定 Popup ============
    Popup {
        id: scanBindPopup
        parent: Overlay.overlay
        width: 224
        height: 224
        modal: false
        closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside
        padding: 12
        
        property string qrCodeContent: ""
        
        // 动态计算位置
        onAboutToShow: {
            var pos = deviceBindText.mapToGlobal(0, deviceBindText.height + 8)
            var windowPos = mainWindow.contentItem.mapFromGlobal(pos.x, pos.y)
            scanBindPopup.x = windowPos.x - scanBindPopup.width / 2 + deviceBindText.width / 2
            scanBindPopup.y = windowPos.y
        }
        
        background: Rectangle {
            color: "#FFFFFF"
            radius: 4
            border.color: "#A5D6A7"
            border.width: 1
        }
        
        contentItem: Rectangle {
            width: 200
            height: 200
            color: "#FFFFFF"
            
            QRCodeGenerator {
                id: qrCode
                anchors.fill: parent
                text: scanBindPopup.qrCodeContent
                foreground: "#000000"
                background: "#FFFFFF"
                margin: 2
            }
            
            // 加载中
            Text {
                anchors.centerIn: parent
                text: "加载中..."
                font.pixelSize: 14
                color: "#666666"
                visible: scanBindPopup.qrCodeContent === ""
            }
        }
    }
    
    // ============ 手动绑定对话框 ============
    Dialog {
        id: manualBindDialog
        title: "手动绑定设备"
        modal: true
        anchors.centerIn: parent
        width: 360
        height: 420
        padding: 20
        
        property string errorText: ""
        property bool isBinding: false
        
        background: Rectangle {
            color: "#FFFFFF"
            radius: 8
            border.color: "#A5D6A7"
            border.width: 1
        }
        
        header: Item {
            height: 44
            
            Text {
                anchors.left: parent.left
                anchors.leftMargin: 20
                anchors.verticalCenter: parent.verticalCenter
                text: "手动绑定设备"
                font.family: "PingFang HK"
                font.pixelSize: 16
                font.bold: true
                color: "#263238"
            }
            
            Rectangle {
                anchors.left: parent.left
                anchors.right: parent.right
                anchors.bottom: parent.bottom
                height: 1
                color: "#C8E6C9"
            }
        }
        
        contentItem: Column {
            spacing: 16
            topPadding: 8
            
            // 提示
            Text {
                text: "请输入设备端的账号前8位和密码进行绑定"
                font.family: "PingFang HK"
                font.pixelSize: 13
                color: "#666666"
            }
            
            // 设备账号前8位
            Column {
                spacing: 6
                width: parent.width
                
                Text {
                    text: "设备端账号前8位"
                    font.family: "PingFang HK"
                    font.pixelSize: 14
                    color: "#333333"
                }
                
                TextField {
                    id: deviceNicknameField
                    width: parent.width
                    height: 40
                    placeholderText: "请输入账号前8位"
                    color: "#263238"
                    placeholderTextColor: "#999999"
                    background: Rectangle {
                        color: "#E8F5E9"
                        radius: 6
                        border.color: deviceNicknameField.activeFocus ? "#607AFB" : "#E0E0E0"
                        border.width: 1
                    }
                    leftPadding: 12
                    rightPadding: 12
                    verticalAlignment: TextInput.AlignVCenter
                }
            }
            
            // 绑定密码
            Column {
                spacing: 6
                width: parent.width

                Text {
                    text: "绑定密码"
                    font.family: "PingFang HK"
                    font.pixelSize: 14
                    color: "#333333"
                }

                TextField {
                    id: secondaryPasswordField
                    width: parent.width
                    height: 40
                    placeholderText: "请输入绑定密码"
                    echoMode: TextInput.Password
                    color: "#263238"
                    placeholderTextColor: "#999999"
                    background: Rectangle {
                        color: "#E8F5E9"
                        radius: 6
                        border.color: secondaryPasswordField.activeFocus ? "#607AFB" : "#E0E0E0"
                        border.width: 1
                    }
                    leftPadding: 12
                    rightPadding: 12
                    verticalAlignment: TextInput.AlignVCenter
                }
            }
            
            // 错误提示
            Text {
                text: manualBindDialog.errorText
                font.family: "PingFang HK"
                font.pixelSize: 13
                color: "#ef4444"
                visible: manualBindDialog.errorText !== ""
            }
        }
        
        footer: Item {
            height: 56
            
            Rectangle {
                anchors.left: parent.left
                anchors.right: parent.right
                anchors.top: parent.top
                height: 1
                color: "#C8E6C9"
            }
            
            Row {
                anchors.right: parent.right
                anchors.rightMargin: 20
                anchors.verticalCenter: parent.verticalCenter
                spacing: 12
                
                // 取消按钮
                Rectangle {
                    width: 72
                    height: 32
                    color: cancelBtnMouse.containsMouse ? "#F0F0F0" : "#FFFFFF"
                    radius: 6
                    border.color: "#A5D6A7"
                    border.width: 1
                    
                    Text {
                        anchors.centerIn: parent
                        text: "取消"
                        font.family: "PingFang HK"
                        font.pixelSize: 14
                        color: "#263238"
                    }
                    
                    MouseArea {
                        id: cancelBtnMouse
                        anchors.fill: parent
                        hoverEnabled: true
                        cursorShape: Qt.PointingHandCursor
                        onClicked: manualBindDialog.close()
                    }
                }
                
                // 绑定按钮
                Rectangle {
                    width: 72
                    height: 32
                    color: manualBindDialog.isBinding ? "#A0B0FF" : (bindBtnMouse.containsMouse ? "#4f6af0" : "#607AFB")
                    radius: 6
                    
                    Text {
                        anchors.centerIn: parent
                        text: manualBindDialog.isBinding ? "绑定中..." : "绑定"
                        font.family: "PingFang HK"
                        font.pixelSize: 14
                        color: "#FFFFFF"
                    }
                    
                    MouseArea {
                        id: bindBtnMouse
                        anchors.fill: parent
                        hoverEnabled: true
                        cursorShape: Qt.PointingHandCursor
                        enabled: !manualBindDialog.isBinding
                        onClicked: executeManualBind()
                    }
                }
            }
        }
        
        onClosed: {
            // 清空表单
            deviceNicknameField.text = ""
            devicePasswordField.text = ""
            secondaryPasswordField.text = ""
            errorText = ""
            isBinding = false
        }
    }
    
    // ============ 绑定成功对话框 ============
    Dialog {
        id: bindSuccessDialog
        title: "绑定成功"
        modal: true
        anchors.centerIn: parent
        standardButtons: Dialog.Ok
        
        Label {
            text: "设备绑定成功！"
            wrapMode: Text.WordWrap
            width: 200
        }
    }
    
    // ============ 设备绑定函数 ============
    function showScanBindPopup() {
        scanBindPopup.qrCodeContent = ""
        scanBindPopup.open()
        HttpClient.getQRCodeData()
    }
    
    function executeManualBind() {
        var nickname = deviceNicknameField.text.trim()
        var secondaryPassword = secondaryPasswordField.text

        // 验证
        if (nickname === "") {
            manualBindDialog.errorText = "请输入设备端账号前8位"
            return
        }
        if (secondaryPassword === "") {
            manualBindDialog.errorText = "请输入绑定密码"
            return
        }

        manualBindDialog.isBinding = true
        manualBindDialog.errorText = ""
        HttpClient.manualBind(nickname, "", secondaryPassword)
    }
    
    // HttpClient 绑定信号处理
    Connections {
        target: HttpClient
        
        function onQrCodeDataReceived(controlUsername) {
            scanBindPopup.qrCodeContent = controlUsername
        }
        
        function onQrCodeDataFailed(code, message) {
            console.log("获取二维码失败:", message)
            scanBindPopup.close()
            statusText.text = "获取二维码失败: " + message
        }
        
        function onManualBindSuccess(deviceId, deviceUsername) {
            manualBindDialog.isBinding = false
            manualBindDialog.close()
            
            // 检查是否已有设备
            var currentDeviceId = HttpClient.currentDeviceId()
            var hasExistingDevice = currentDeviceId && currentDeviceId !== ""
            
            console.log("📱 手动绑定成功: deviceId=" + deviceId + ", deviceUsername=" + deviceUsername + ", hasExistingDevice=" + hasExistingDevice)
            
            if (hasExistingDevice) {
                // 已有设备：只提示绑定成功，不切换设备
                statusText.text = "绑定成功"
                showToast("绑定成功")
            } else {
                // 首次绑定设备：重新登录获取设备信息并订阅频道
                statusText.text = "绑定成功"
                showToast("绑定成功")
                reLoginAndInitDevice(deviceId, deviceUsername)
            }
        }
        
        function onManualBindFailed(code, message) {
            manualBindDialog.isBinding = false
            manualBindDialog.errorText = message
        }
    }
    
    // ============ 列预览函数（数字键0-9，0代表第10列） ============
    function toggleColumnPreview(colNumber) {
        // colNumber: 1-10 (用户按键1-9对应1-9列, 0对应第10列), 列索引 0-based
        var colIndex = colNumber - 1
        var cols = captureManager.gridCols
        var rows = captureManager.gridRows
        
        if (colIndex >= cols) {
            console.log("⚠️ 列预览: 按键" + colNumber + "超出列数" + cols)
            return
        }
        
        // 再次按同一数字键 → 关闭预览
        if (columnPreviewVisible && columnPreviewCol === colIndex) {
            closeColumnPreview()
            return
        }
        
        // 收集该列所有有数据的 dataIndex
        var items = []
        var frames = []
        var zooms = []
        var offX = []
        var offY = []
        for (var row = 0; row < rows; row++) {
            var dataIdx
            if (captureManager.isHorizontalLayout) {
                dataIdx = row * cols + colIndex
            } else {
                dataIdx = colIndex * rows + row
            }
            if (dataIdx < captureManager.count) {
                items.push(dataIdx)
                frames.push(captureManager.getCurrentOffset(dataIdx))  // 当前帧
                zooms.push(1.0)
                offX.push(0)
                offY.push(0)
            }
        }
        
        if (items.length === 0) {
            console.log("⚠️ 列预览: 第" + colNumber + "列没有截图数据")
            return
        }
        
        // ⭐ 仅支持 2-5 个元素
        if (items.length < 2 || items.length > 5) {
            console.log("⚠️ 列预览: 第" + colNumber + "列有" + items.length + "个元素，仅支持2-5个")
            return
        }
        
        console.log("📸 列预览: 列" + colNumber + " 共" + items.length + "张 dataIndices=" + JSON.stringify(items))
        columnPreviewItems = items
        columnPreviewFrames = frames
        columnPreviewZooms = zooms
        columnPreviewOffsetX = offX
        columnPreviewOffsetY = offY
        columnPreviewCol = colIndex
        columnPreviewRefreshToken = Date.now()
        columnPreviewVisible = true
    }
    
    function closeColumnPreview() {
        // 先关闭A键放大
        closeColumnPreviewZoom()
        // 关闭前同步帧到captureManager
        if (columnPreviewItems && columnPreviewFrames) {
            for (var i = 0; i < columnPreviewItems.length; i++) {
                captureManager.gotoFrame(columnPreviewItems[i], columnPreviewFrames[i])
            }
        }
        columnPreviewVisible = false
        columnPreviewCol = -1
        columnPreviewItems = []
        columnPreviewFrames = []
        columnPreviewZooms = []
        columnPreviewOffsetX = []
        columnPreviewOffsetY = []
        columnPreviewHoveredIndex = -1
    }
    
    // ⭐ 列预览A键放大相关函数
    function openColumnPreviewZoom(previewIdx) {
        if (previewIdx < 0 || previewIdx >= columnPreviewItems.length) return
        columnPreviewZoomItemIdx = previewIdx
        columnPreviewZoomFrame = columnPreviewFrames[previewIdx] || 0
        columnPreviewZoomScale = 1.0
        columnPreviewZoomOffX = 0
        columnPreviewZoomOffY = 0
        columnPreviewRefreshToken = Date.now()
        console.log("🔍 列预览放大: 索引=" + previewIdx + " dataIdx=" + columnPreviewItems[previewIdx])
    }
    
    function closeColumnPreviewZoom() {
        if (columnPreviewZoomItemIdx >= 0 && columnPreviewZoomItemIdx < columnPreviewFrames.length) {
            // 同步帧回列预览
            var frames = columnPreviewFrames.slice()
            frames[columnPreviewZoomItemIdx] = columnPreviewZoomFrame
            columnPreviewFrames = frames
            columnPreviewRefreshToken = Date.now()
        }
        columnPreviewZoomItemIdx = -1
        columnPreviewZoomScale = 1.0
        columnPreviewZoomOffX = 0
        columnPreviewZoomOffY = 0
    }
    
    function columnPreviewZoomPrevFrame() {
        if (columnPreviewZoomItemIdx < 0) return
        var newF = Math.max(0, columnPreviewZoomFrame - mainPage.frameStep)
        if (newF !== columnPreviewZoomFrame) {
            columnPreviewZoomFrame = newF
            columnPreviewRefreshToken = Date.now()
        }
    }

    function columnPreviewZoomNextFrame() {
        if (columnPreviewZoomItemIdx < 0) return
        var dataIdx = columnPreviewItems[columnPreviewZoomItemIdx]
        var total = captureManager.getTotalFrames(dataIdx)
        var newF = Math.min(total - 1, columnPreviewZoomFrame + mainPage.frameStep)
        if (newF !== columnPreviewZoomFrame && newF >= 0) {
            columnPreviewZoomFrame = newF
            columnPreviewRefreshToken = Date.now()
        }
    }

    // ⭐ 单 item 步进上/下一帧 frameStep 次 (C++ side stepwise)
    function stepCaptureFrame(dataIndex, direction) {
        var n = mainPage.frameStep
        for (var i = 0; i < n; i++) {
            if (direction === "prev") captureManager.prevFrame(dataIndex)
            else captureManager.nextFrame(dataIndex)
        }
    }

    // 列预览：所有图片同时切换上一帧
    function columnPreviewPrevFrame() {
        if (!columnPreviewVisible || columnPreviewItems.length === 0) return
        var step = mainPage.frameStep
        var frames = columnPreviewFrames.slice()  // 拷贝数组
        var changed = false
        for (var i = 0; i < columnPreviewItems.length; i++) {
            var newF = Math.max(0, (frames[i] || 0) - step)
            if (newF !== frames[i]) {
                frames[i] = newF
                changed = true
            }
        }
        if (changed) {
            columnPreviewFrames = frames
            columnPreviewRefreshToken = Date.now()
        }
    }

    // 列预览：所有图片同时切换下一帧
    function columnPreviewNextFrame() {
        if (!columnPreviewVisible || columnPreviewItems.length === 0) return
        var step = mainPage.frameStep
        var frames = columnPreviewFrames.slice()
        var changed = false
        for (var i = 0; i < columnPreviewItems.length; i++) {
            var total = captureManager.getTotalFrames(columnPreviewItems[i])
            var newF = Math.min(total - 1, (frames[i] || 0) + step)
            if (newF !== frames[i] && newF >= 0) {
                frames[i] = newF
                changed = true
            }
        }
        if (changed) {
            columnPreviewFrames = frames
            columnPreviewRefreshToken = Date.now()
        }
    }

    // 列预览：所有图片同时按各自中心缩放 (Ctrl+S+滚轮)
    function columnPreviewSyncZoomDelta(delta) {
        if (!columnPreviewVisible || columnPreviewItems.length === 0) return
        var zooms  = columnPreviewZooms.slice()
        var offX   = columnPreviewOffsetX.slice()
        var offY   = columnPreviewOffsetY.slice()
        for (var i = 0; i < columnPreviewItems.length; i++) {
            var oldZoom = zooms[i] || 1.0
            var newZoom = Math.max(1.0, Math.min(5.0, oldZoom + delta))
            if (newZoom === oldZoom) continue
            // 以容器中心为原点缩放：mouseRel = 0 → newOff = oldOff * (newZoom/oldZoom)
            var ratio = newZoom / oldZoom
            offX[i] = (offX[i] || 0) * ratio
            offY[i] = (offY[i] || 0) * ratio
            zooms[i] = newZoom
            if (newZoom === 1.0) { offX[i] = 0; offY[i] = 0 }
        }
        columnPreviewZooms = zooms
        columnPreviewOffsetX = offX
        columnPreviewOffsetY = offY
    }

    // 列预览：切换到上一列
    function columnPreviewPrevCol() {
        if (!columnPreviewVisible) return
        var newCol = columnPreviewCol  // 0-based
        if (newCol <= 0) return  // 已在第一列
        closeColumnPreview()
        toggleColumnPreview(newCol)  // newCol is 0-based current-1, which equals colNumber for prev
    }
    
    // 列预览：切换到下一列
    function columnPreviewNextCol() {
        if (!columnPreviewVisible) return
        var cols = captureManager.gridCols
        var newColIndex = columnPreviewCol + 1  // 0-based
        if (newColIndex >= cols) return  // 已在最后一列
        closeColumnPreview()
        toggleColumnPreview(newColIndex + 1)  // +1 because toggleColumnPreview expects 1-based colNumber
    }
    
    // ============ 全屏查看函数 ============
    function openFullscreenViewer(itemIndex) {
        if (itemIndex < 0 || itemIndex >= captureManager.count) return
        
        fullscreenItemIndex = itemIndex
        fullscreenFrameIndex = captureManager.getCurrentOffset(itemIndex)
        fullscreenZoom = 1.0
        fullscreenOffsetX = 0  // ⭐ 重置偏移
        fullscreenOffsetY = 0
        fullscreenRefreshToken = Date.now()  // ⭐ 强制刷新图片
        fullscreenViewerVisible = true
    }
    
    function closeFullscreenViewer() {
        if (fullscreenItemIndex >= 0 && fullscreenItemIndex < captureManager.count) {
            // 同步帧 index 到 item
            captureManager.gotoFrame(fullscreenItemIndex, fullscreenFrameIndex)
        }
        fullscreenViewerVisible = false
        fullscreenItemIndex = -1
    }
    
    // ============ 全屏查看弹窗 ============
    // fullscreenViewerMode: 0=全屏, 1=半屏（只覆盖截图区域）
    Rectangle {
        id: fullscreenViewer
        // ⭐ 根据模式选择覆盖区域
        x: fullscreenViewerMode === 0 ? 0 : captureGridContent.mapToItem(mainPage, 0, 0).x
        y: fullscreenViewerMode === 0 ? 0 : captureGridContent.mapToItem(mainPage, 0, 0).y
        width: fullscreenViewerMode === 0 ? parent.width : captureGridContent.width
        height: fullscreenViewerMode === 0 ? parent.height : captureGridContent.height
        color: "#000000"
        visible: fullscreenViewerVisible
        z: 1000
        
        // 点击背景关闭
        MouseArea {
            anchors.fill: parent
            onClicked: closeFullscreenViewer()
            
            onWheel: function(wheel) {
                if (mainPage.sKeyPressed) {
                    // ⭐ PC等级1(豪华版)：禁用全屏查看局部放大
                    if (mainPage.pcActivationLevel < 2) {
                        console.log("🔒 全屏局部放大需要至尊版")
                        return
                    }
                    // ⭐ S + 滚轮：以鼠标位置为中心缩放
                    var oldZoom = fullscreenZoom
                    var delta = wheel.angleDelta.y > 0 ? 0.2 : -0.2
                    var newZoom = Math.max(1.0, Math.min(5.0, fullscreenZoom + delta))
                    
                    if (newZoom !== oldZoom) {
                        // 计算鼠标相对于容器中心的位置
                        var containerCenterX = fullscreenImageContainer.width / 2
                        var containerCenterY = fullscreenImageContainer.height / 2
                        var mouseRelX = wheel.x - containerCenterX
                        var mouseRelY = wheel.y - containerCenterY
                        
                        // 计算缩放比例变化
                        var zoomRatio = newZoom / oldZoom
                        
                        // 调整偏移量，使鼠标位置保持不变
                        fullscreenOffsetX = mouseRelX - (mouseRelX - fullscreenOffsetX) * zoomRatio
                        fullscreenOffsetY = mouseRelY - (mouseRelY - fullscreenOffsetY) * zoomRatio
                        
                        fullscreenZoom = newZoom
                        
                        // 缩放回1.0时重置偏移
                        if (newZoom === 1.0) {
                            fullscreenOffsetX = 0
                            fullscreenOffsetY = 0
                        }
                    }
                } else {
                    // 普通滚轮：切换帧 (受 frameStep 影响)
                    var totalFrames = captureManager.getTotalFrames(fullscreenItemIndex)
                    if (totalFrames > 0) {
                        var step = mainPage.frameStep
                        if (wheel.angleDelta.y > 0) {
                            fullscreenFrameIndex = Math.max(0, fullscreenFrameIndex - step)
                        } else {
                            fullscreenFrameIndex = Math.min(totalFrames - 1, fullscreenFrameIndex + step)
                        }
                        fullscreenRefreshToken = Date.now()  // ⭐ 强制刷新图片
                    }
                }
            }
        }

        // 图片容器
        Item {
            id: fullscreenImageContainer
            anchors.centerIn: parent
            width: parent.width
            height: parent.height
            clip: true
            
            Image {
                id: fullscreenImage
                // ⭐ 完全铺满容器（拉伸填充）
                // 使用手动居中 + 偏移量实现缩放拖动
                x: parent.width / 2 - width / 2 + fullscreenOffsetX
                y: parent.height / 2 - height / 2 + fullscreenOffsetY
                width: parent.width * fullscreenZoom
                height: parent.height * fullscreenZoom
                // ⭐ 使用刷新令牌强制重新加载图片，解决图片不同步问题
                source: fullscreenViewerVisible && fullscreenItemIndex >= 0 
                        ? "image://capture/frame/" + fullscreenItemIndex + "/" + fullscreenFrameIndex + "?t=" + fullscreenRefreshToken
                        : ""
                fillMode: Image.Stretch  // 拉伸铺满，完全填充容器
                cache: false
                asynchronous: false
                mirror: mainPage.videoMirrorMode === "horizontal"
                mirrorVertically: mainPage.videoMirrorMode === "vertical"
                
                layer.enabled: false  // 不再使用 shader，颜色调整由 GStreamer videobalance 和 gamma 处理
                
                // ⭐ 左键=上一帧，右键=下一帧，滚轮=切帧/缩放
                MouseArea {
                    anchors.fill: parent
                    acceptedButtons: Qt.LeftButton | Qt.RightButton
                    
                    onClicked: function(mouse) {
                        var totalFrames = captureManager.getTotalFrames(fullscreenItemIndex)
                        if (totalFrames > 0) {
                            var step = mainPage.frameStep
                            if (mouse.button === Qt.LeftButton) {
                                fullscreenFrameIndex = Math.max(0, fullscreenFrameIndex - step)
                                fullscreenRefreshToken = Date.now()
                            } else if (mouse.button === Qt.RightButton) {
                                fullscreenFrameIndex = Math.min(totalFrames - 1, fullscreenFrameIndex + step)
                                fullscreenRefreshToken = Date.now()
                            }
                        }
                    }
                    
                    onWheel: function(wheel) {
                        if (mainPage.sKeyPressed) {
                            // ⭐ S + 滚轮：以鼠标位置为中心缩放
                            var oldZoom = fullscreenZoom
                            var delta = wheel.angleDelta.y > 0 ? 0.2 : -0.2
                            var newZoom = Math.max(1.0, Math.min(5.0, fullscreenZoom + delta))
                            
                            if (newZoom !== oldZoom) {
                                // 鼠标在图片上的位置
                                var mouseInImageX = wheel.x
                                var mouseInImageY = wheel.y
                                
                                // 鼠标相对于容器中心的位置
                                var containerCenterX = fullscreenImageContainer.width / 2
                                var containerCenterY = fullscreenImageContainer.height / 2
                                var mouseRelX = fullscreenImage.x + mouseInImageX - containerCenterX
                                var mouseRelY = fullscreenImage.y + mouseInImageY - containerCenterY
                                
                                // 计算缩放比例变化
                                var zoomRatio = newZoom / oldZoom
                                
                                // 调整偏移量
                                fullscreenOffsetX = mouseRelX - (mouseRelX - fullscreenOffsetX) * zoomRatio
                                fullscreenOffsetY = mouseRelY - (mouseRelY - fullscreenOffsetY) * zoomRatio
                                
                                fullscreenZoom = newZoom
                                
                                // 缩放回1.0时重置偏移
                                if (newZoom === 1.0) {
                                    fullscreenOffsetX = 0
                                    fullscreenOffsetY = 0
                                }
                            }
                        } else {
                            var totalFrames = captureManager.getTotalFrames(fullscreenItemIndex)
                            if (totalFrames > 0) {
                                var step = mainPage.frameStep
                                if (wheel.angleDelta.y > 0) {
                                    fullscreenFrameIndex = Math.max(0, fullscreenFrameIndex - step)
                                } else {
                                    fullscreenFrameIndex = Math.min(totalFrames - 1, fullscreenFrameIndex + step)
                                }
                                fullscreenRefreshToken = Date.now()  // ⭐ 强制刷新图片
                            }
                        }
                    }
                }
            }
        }
        
        // 帧信息显示（左上角，无背景，白色文字70%透明度）
        Text {
            id: fullscreenFrameText
            anchors.left: parent.left
            anchors.top: parent.top
            anchors.margins: 20
            text: (fullscreenFrameIndex + 1)  // 只显示当前帧数
            font.pixelSize: 12  // 字体增加4px（8 -> 12）
            font.family: "PingFang HK"
            color: "#B3FFFFFF"  // 白色，透明度70%
        }
        
        // 关闭按钮（右上角，透明度与抓拍item一致）
        Rectangle {
            anchors.right: parent.right
            anchors.top: parent.top
            anchors.margins: 20
            width: 40
            height: 40
            color: fullscreenCloseBtn.containsMouse ? "#E53935" : "#40000000"  // 25% 透明度
            radius: 20
            
            Text {
                anchors.centerIn: parent
                text: "✕"
                font.pixelSize: 20
                color: "#33FFFFFF"  // 白色 20% 透明度
            }
            
            MouseArea {
                id: fullscreenCloseBtn
                anchors.fill: parent
                hoverEnabled: true
                cursorShape: Qt.PointingHandCursor
                onClicked: closeFullscreenViewer()
            }
        }
        
        // 操作提示（底部，透明度与抓拍item一致）
        Rectangle {
            anchors.horizontalCenter: parent.horizontalCenter
            anchors.bottom: parent.bottom
            anchors.bottomMargin: 30
            width: hintText.width + 32
            height: 36
            color: "#40000000"  // 25% 透明度
            radius: 18
            
            Text {
                id: hintText
                anchors.centerIn: parent
                text: "左键/滚轮↑: 上一帧 | 右键/滚轮↓: 下一帧 | S+滚轮: 缩放 | " + ShortcutStore.fullscreenViewerKey + ": 全屏/半屏 | Esc: 关闭"
                font.pixelSize: 13
                font.family: "PingFang HK"
                color: "#33FFFFFF"  // 白色 20% 透明度
            }
        }
    }
    
    // ============ 列预览覆盖层（数字键0-9触发，仅2-5张）============
    Rectangle {
        id: columnPreviewOverlay
        anchors.fill: parent
        color: "#CC000000"  // 半透明黑色背景
        visible: columnPreviewVisible
        z: 1001  // 在全屏查看之上
        
        // 点击背景关闭
        MouseArea {
            anchors.fill: parent
            onClicked: closeColumnPreview()
        }
        
        // ===== 顶部栏：Z按钮 + 列号 + X按钮 + 拉伸开关 =====
        Row {
            id: colPreviewTopBar
            anchors.top: parent.top
            anchors.horizontalCenter: parent.horizontalCenter
            anchors.topMargin: 12
            spacing: 16
            z: 2
            
            // Z按钮（上一列）
            Rectangle {
                width: 36; height: 28; radius: 4
                color: colPrevBtnArea.containsMouse ? "#4DB6AC" : "#40FFFFFF"
                Text { anchors.centerIn: parent; text: "Z ◀"; font.pixelSize: 13; font.family: "PingFang HK"; color: "#FFFFFF" }
                MouseArea {
                    id: colPrevBtnArea
                    anchors.fill: parent; hoverEnabled: true; cursorShape: Qt.PointingHandCursor
                    onClicked: columnPreviewPrevCol()
                }
            }
            
            // 列号提示
            Text {
                text: "第 " + (columnPreviewCol + 1) + " 列  （共 " + columnPreviewItems.length + " 张）"
                font.family: "PingFang HK"; font.pixelSize: 18; font.bold: true; color: "#FFFFFF"
                anchors.verticalCenter: parent.verticalCenter
            }
            
            // X按钮（下一列）
            Rectangle {
                width: 36; height: 28; radius: 4
                color: colNextBtnArea.containsMouse ? "#4DB6AC" : "#40FFFFFF"
                Text { anchors.centerIn: parent; text: "▶ X"; font.pixelSize: 13; font.family: "PingFang HK"; color: "#FFFFFF" }
                MouseArea {
                    id: colNextBtnArea
                    anchors.fill: parent; hoverEnabled: true; cursorShape: Qt.PointingHandCursor
                    onClicked: columnPreviewNextCol()
                }
            }
            
            // 分隔线
            Rectangle { width: 1; height: 24; color: "#40FFFFFF"; anchors.verticalCenter: parent.verticalCenter }
            
            // 拉伸开关
            Rectangle {
                width: stretchLabel.width + 16; height: 28; radius: 4
                color: stretchBtnArea.containsMouse ? "#4DB6AC" : (columnPreviewStretch ? "#66FFFFFF" : "#40FFFFFF")
                Text {
                    id: stretchLabel
                    anchors.centerIn: parent
                    text: columnPreviewStretch ? "拉伸:开" : "拉伸:关"
                    font.pixelSize: 13; font.family: "PingFang HK"; color: "#FFFFFF"
                }
                MouseArea {
                    id: stretchBtnArea
                    anchors.fill: parent; hoverEnabled: true; cursorShape: Qt.PointingHandCursor
                    onClicked: columnPreviewStretch = !columnPreviewStretch
                }
            }
        }
        
        // ===== 图片网格容器（2-3:1行, 4:2x2, 5:2x3）=====
        Item {
            id: columnPreviewGrid
            anchors.centerIn: parent
            
            property int imgCount: columnPreviewVisible ? columnPreviewItems.length : 0
            property int layoutCols: imgCount <= 3 ? imgCount : (imgCount === 4 ? 2 : 3)
            property int layoutRows: imgCount <= 3 ? 1 : 2
            property real gridSpacing: 8
            property real availH: columnPreviewOverlay.height - 110  // 上方顶部栏+下方提示栏
            property real availW: columnPreviewOverlay.width - 60    // 左右各留30边距
            property real cellW: layoutCols > 0 ? (availW - (layoutCols - 1) * gridSpacing) / layoutCols : 0
            property real cellH: layoutRows > 0 ? (availH - (layoutRows - 1) * gridSpacing) / layoutRows : 0
            
            width: layoutCols > 0 ? layoutCols * cellW + (layoutCols - 1) * gridSpacing : 0
            height: layoutRows > 0 ? layoutRows * cellH + (layoutRows - 1) * gridSpacing : 0
            
            Repeater {
                model: columnPreviewGrid.imgCount
                
                // 每张图片的容器
                Item {
                    id: colPreviewItem
                    property int myIndex: index
                    property int dataIdx: columnPreviewItems[index]
                    property int frameIdx: columnPreviewFrames.length > index ? columnPreviewFrames[index] : 0
                    property int totalFrames: captureManager.getTotalFrames(dataIdx)
                    property real itemZoom: columnPreviewZooms.length > index ? columnPreviewZooms[index] : 1.0
                    property real itemOffX: columnPreviewOffsetX.length > index ? columnPreviewOffsetX[index] : 0
                    property real itemOffY: columnPreviewOffsetY.length > index ? columnPreviewOffsetY[index] : 0
                    property bool isHovered: false
                    
                    x: (index % columnPreviewGrid.layoutCols) * (columnPreviewGrid.cellW + columnPreviewGrid.gridSpacing)
                    y: Math.floor(index / columnPreviewGrid.layoutCols) * (columnPreviewGrid.cellH + columnPreviewGrid.gridSpacing)
                    width: columnPreviewGrid.cellW
                    height: columnPreviewGrid.cellH
                    clip: true
                    
                    Image {
                        id: colPreviewImage
                        // 缩放 + 偏移定位
                        x: parent.width / 2 - width / 2 + colPreviewItem.itemOffX
                        y: parent.height / 2 - height / 2 + colPreviewItem.itemOffY
                        width: parent.width * colPreviewItem.itemZoom
                        height: parent.height * colPreviewItem.itemZoom
                        source: colPreviewItem.dataIdx >= 0
                            ? "image://capture/frame/" + colPreviewItem.dataIdx + "/" + colPreviewItem.frameIdx + "?t=" + columnPreviewRefreshToken
                            : ""
                        fillMode: columnPreviewStretch ? Image.Stretch : Image.PreserveAspectFit
                        cache: false
                        asynchronous: false
                        mirror: mainPage.videoMirrorMode === "horizontal"
                        mirrorVertically: mainPage.videoMirrorMode === "vertical"
                        
                        MouseArea {
                            anchors.fill: parent
                            hoverEnabled: true
                            acceptedButtons: Qt.LeftButton | Qt.RightButton
                            
                            onEntered: {
                                colPreviewItem.isHovered = true
                                columnPreviewHoveredIndex = colPreviewItem.myIndex
                            }
                            onExited: {
                                colPreviewItem.isHovered = false
                                if (columnPreviewHoveredIndex === colPreviewItem.myIndex)
                                    columnPreviewHoveredIndex = -1
                            }
                            
                            onClicked: function(mouse) {
                                // ⭐ Ctrl+点击：本列所有 item 同步上/下一帧
                                if (mouse.modifiers & Qt.ControlModifier) {
                                    if (mouse.button === Qt.LeftButton) columnPreviewPrevFrame()
                                    else if (mouse.button === Qt.RightButton) columnPreviewNextFrame()
                                    return
                                }
                                // ⭐ 左键=上一帧，右键=下一帧（单张, 受 frameStep 影响）
                                var idx = colPreviewItem.myIndex
                                var step = mainPage.frameStep
                                var frames = columnPreviewFrames.slice()
                                var total = captureManager.getTotalFrames(colPreviewItem.dataIdx)
                                if (total > 0) {
                                    if (mouse.button === Qt.LeftButton) {
                                        frames[idx] = Math.max(0, (frames[idx] || 0) - step)
                                    } else if (mouse.button === Qt.RightButton) {
                                        frames[idx] = Math.min(total - 1, (frames[idx] || 0) + step)
                                    }
                                    columnPreviewFrames = frames
                                    columnPreviewRefreshToken = Date.now()
                                }
                            }

                            onWheel: function(wheel) {
                                wheel.accepted = true
                                var idx = colPreviewItem.myIndex

                                // ⭐ Ctrl+滚轮：本列所有 item 同步切帧 / 同步缩放
                                if (wheel.modifiers & Qt.ControlModifier) {
                                    if (mainPage.sKeyPressed) {
                                        columnPreviewSyncZoomDelta(wheel.angleDelta.y > 0 ? 0.2 : -0.2)
                                    } else {
                                        if (wheel.angleDelta.y > 0) columnPreviewPrevFrame()
                                        else columnPreviewNextFrame()
                                    }
                                    return
                                }

                                if (mainPage.sKeyPressed) {
                                    // S + 滚轮：以鼠标为中心缩放（单张）
                                    var zooms = columnPreviewZooms.slice()
                                    var offXArr = columnPreviewOffsetX.slice()
                                    var offYArr = columnPreviewOffsetY.slice()
                                    var oldZoom = zooms[idx] || 1.0
                                    var delta = wheel.angleDelta.y > 0 ? 0.2 : -0.2
                                    var newZoom = Math.max(1.0, Math.min(5.0, oldZoom + delta))
                                    
                                    if (newZoom !== oldZoom) {
                                        var containerCenterX = colPreviewItem.width / 2
                                        var containerCenterY = colPreviewItem.height / 2
                                        var mouseRelX = colPreviewImage.x + wheel.x - containerCenterX
                                        var mouseRelY = colPreviewImage.y + wheel.y - containerCenterY
                                        var zoomRatio = newZoom / oldZoom
                                        
                                        offXArr[idx] = mouseRelX - (mouseRelX - (offXArr[idx] || 0)) * zoomRatio
                                        offYArr[idx] = mouseRelY - (mouseRelY - (offYArr[idx] || 0)) * zoomRatio
                                        zooms[idx] = newZoom
                                        
                                        if (newZoom === 1.0) {
                                            offXArr[idx] = 0
                                            offYArr[idx] = 0
                                        }
                                        
                                        columnPreviewZooms = zooms
                                        columnPreviewOffsetX = offXArr
                                        columnPreviewOffsetY = offYArr
                                    }
                                } else {
                                    // 普通滚轮：切换该张图的帧 (受 frameStep 影响)
                                    var step2 = mainPage.frameStep
                                    var frames = columnPreviewFrames.slice()
                                    var total = captureManager.getTotalFrames(colPreviewItem.dataIdx)
                                    if (total > 0) {
                                        if (wheel.angleDelta.y > 0) {
                                            frames[idx] = Math.max(0, (frames[idx] || 0) - step2)
                                        } else {
                                            frames[idx] = Math.min(total - 1, (frames[idx] || 0) + step2)
                                        }
                                        columnPreviewFrames = frames
                                        columnPreviewRefreshToken = Date.now()
                                    }
                                }
                            }
                        }
                    }
                    
                    // 帧信息（左上角）
                    Text {
                        anchors.left: parent.left
                        anchors.top: parent.top
                        anchors.margins: 6
                        text: (colPreviewItem.frameIdx + 1) + "/" + colPreviewItem.totalFrames
                        font.pixelSize: 12
                        font.family: "PingFang HK"
                        color: "#B3FFFFFF"
                        style: Text.Outline
                        styleColor: "#000000"
                    }
                    
                    // 编号（右上角，大号）
                    Rectangle {
                        anchors.right: parent.right
                        anchors.top: parent.top
                        anchors.margins: 6
                        width: 28; height: 28; radius: 14
                        color: "#60000000"
                        Text {
                            anchors.centerIn: parent
                            text: (colPreviewItem.myIndex + 1)
                            font.family: "PingFang HK"; font.pixelSize: 16; font.bold: true
                            color: "#FFFFFF"
                        }
                    }
                    
                    // ⭐ 悬停/选中边框
                    Rectangle {
                        anchors.fill: parent
                        color: "transparent"
                        border.color: colPreviewItem.isHovered ? "#4DB6AC" : "#40FFFFFF"
                        border.width: colPreviewItem.isHovered ? 3 : 1
                    }
                    
                    // 悬停提示：按A放大
                    Rectangle {
                        anchors.bottom: parent.bottom
                        anchors.horizontalCenter: parent.horizontalCenter
                        anchors.bottomMargin: 8
                        width: zoomHintText.width + 16; height: 24; radius: 12
                        color: "#80000000"
                        visible: colPreviewItem.isHovered
                        Text {
                            id: zoomHintText
                            anchors.centerIn: parent
                            text: "按 A 放大"
                            font.pixelSize: 11; font.family: "PingFang HK"; color: "#CCFFFFFF"
                        }
                    }
                }
            }
        }
        
        // ===== 底部操作提示 =====
        Rectangle {
            anchors.horizontalCenter: parent.horizontalCenter
            anchors.bottom: parent.bottom
            anchors.bottomMargin: 12
            width: colPreviewHintText.width + 32
            height: 36
            color: "#40000000"
            radius: 18
            
            Text {
                id: colPreviewHintText
                anchors.centerIn: parent
                text: "左键/滚轮↑: 上一帧 | 右键/滚轮↓: 下一帧 | ←→: 切帧(全部) | S+滚轮: 缩放 | A: 放大 | Z/X: 上/下列 | Esc: 关闭"
                font.pixelSize: 13
                font.family: "PingFang HK"
                color: "#33FFFFFF"
            }
        }
    }
    
    // ============ 列预览A键放大覆盖层（z:1002，在列预览之上）============
    Rectangle {
        id: columnPreviewZoomOverlay
        anchors.fill: parent
        color: "#EE000000"
        visible: columnPreviewVisible && columnPreviewZoomItemIdx >= 0
        z: 1002
        
        property int zoomDataIdx: columnPreviewZoomItemIdx >= 0 && columnPreviewZoomItemIdx < columnPreviewItems.length
            ? columnPreviewItems[columnPreviewZoomItemIdx] : -1
        property int zoomTotalFrames: zoomDataIdx >= 0 ? captureManager.getTotalFrames(zoomDataIdx) : 0
        
        // 点击背景关闭放大
        MouseArea {
            anchors.fill: parent
            onClicked: closeColumnPreviewZoom()
            
            onWheel: function(wheel) {
                if (mainPage.sKeyPressed) {
                    // S + 滚轮：缩放
                    var oldZoom = columnPreviewZoomScale
                    var delta = wheel.angleDelta.y > 0 ? 0.2 : -0.2
                    var newZoom = Math.max(1.0, Math.min(5.0, oldZoom + delta))
                    if (newZoom !== oldZoom) {
                        var cx = zoomImageContainer.width / 2
                        var cy = zoomImageContainer.height / 2
                        var mx = wheel.x - cx
                        var my = wheel.y - cy
                        var r = newZoom / oldZoom
                        columnPreviewZoomOffX = mx - (mx - columnPreviewZoomOffX) * r
                        columnPreviewZoomOffY = my - (my - columnPreviewZoomOffY) * r
                        columnPreviewZoomScale = newZoom
                        if (newZoom === 1.0) { columnPreviewZoomOffX = 0; columnPreviewZoomOffY = 0 }
                    }
                } else {
                    // 普通滚轮：切帧
                    if (columnPreviewZoomOverlay.zoomTotalFrames > 0) {
                        if (wheel.angleDelta.y > 0) {
                            columnPreviewZoomFrame = Math.max(0, columnPreviewZoomFrame - 1)
                        } else {
                            columnPreviewZoomFrame = Math.min(columnPreviewZoomOverlay.zoomTotalFrames - 1, columnPreviewZoomFrame + 1)
                        }
                        columnPreviewRefreshToken = Date.now()
                    }
                }
            }
        }
        
        // 图片容器
        Item {
            id: zoomImageContainer
            anchors.centerIn: parent
            width: parent.width
            height: parent.height
            clip: true
            
            Image {
                id: zoomImage
                x: parent.width / 2 - width / 2 + columnPreviewZoomOffX
                y: parent.height / 2 - height / 2 + columnPreviewZoomOffY
                width: parent.width * columnPreviewZoomScale
                height: parent.height * columnPreviewZoomScale
                source: columnPreviewZoomOverlay.zoomDataIdx >= 0
                    ? "image://capture/frame/" + columnPreviewZoomOverlay.zoomDataIdx + "/" + columnPreviewZoomFrame + "?t=" + columnPreviewRefreshToken
                    : ""
                fillMode: columnPreviewStretch ? Image.Stretch : Image.PreserveAspectFit
                cache: false
                asynchronous: false
                mirror: mainPage.videoMirrorMode === "horizontal"
                mirrorVertically: mainPage.videoMirrorMode === "vertical"
                
                MouseArea {
                    anchors.fill: parent
                    acceptedButtons: Qt.LeftButton | Qt.RightButton
                    onClicked: function(mouse) {
                        // ⭐ 左键=上一帧，右键=下一帧
                        if (columnPreviewZoomOverlay.zoomTotalFrames > 0) {
                            if (mouse.button === Qt.LeftButton) {
                                columnPreviewZoomFrame = Math.max(0, columnPreviewZoomFrame - 1)
                                columnPreviewRefreshToken = Date.now()
                            } else if (mouse.button === Qt.RightButton) {
                                columnPreviewZoomFrame = Math.min(columnPreviewZoomOverlay.zoomTotalFrames - 1, columnPreviewZoomFrame + 1)
                                columnPreviewRefreshToken = Date.now()
                            }
                        }
                    }
                    onWheel: function(wheel) {
                        if (mainPage.sKeyPressed) {
                            var oldZoom = columnPreviewZoomScale
                            var delta = wheel.angleDelta.y > 0 ? 0.2 : -0.2
                            var newZoom = Math.max(1.0, Math.min(5.0, oldZoom + delta))
                            if (newZoom !== oldZoom) {
                                var cx = zoomImageContainer.width / 2
                                var cy = zoomImageContainer.height / 2
                                var mx = zoomImage.x + wheel.x - cx
                                var my = zoomImage.y + wheel.y - cy
                                var r = newZoom / oldZoom
                                columnPreviewZoomOffX = mx - (mx - columnPreviewZoomOffX) * r
                                columnPreviewZoomOffY = my - (my - columnPreviewZoomOffY) * r
                                columnPreviewZoomScale = newZoom
                                if (newZoom === 1.0) { columnPreviewZoomOffX = 0; columnPreviewZoomOffY = 0 }
                            }
                        } else {
                            if (columnPreviewZoomOverlay.zoomTotalFrames > 0) {
                                if (wheel.angleDelta.y > 0) {
                                    columnPreviewZoomFrame = Math.max(0, columnPreviewZoomFrame - 1)
                                } else {
                                    columnPreviewZoomFrame = Math.min(columnPreviewZoomOverlay.zoomTotalFrames - 1, columnPreviewZoomFrame + 1)
                                }
                                columnPreviewRefreshToken = Date.now()
                            }
                        }
                    }
                }
            }
        }
        
        // 左上角帧信息
        Text {
            anchors.left: parent.left; anchors.top: parent.top; anchors.margins: 20
            text: (columnPreviewZoomFrame + 1) + "/" + columnPreviewZoomOverlay.zoomTotalFrames + "  [#" + (columnPreviewZoomItemIdx + 1) + "]"
            font.pixelSize: 14; font.family: "PingFang HK"; color: "#B3FFFFFF"
        }
        
        // 右上角关闭按钮
        Rectangle {
            anchors.right: parent.right; anchors.top: parent.top; anchors.margins: 20
            width: 40; height: 40; radius: 20
            color: zoomCloseArea.containsMouse ? "#E53935" : "#40000000"
            Text { anchors.centerIn: parent; text: "✕"; font.pixelSize: 20; color: "#33FFFFFF" }
            MouseArea {
                id: zoomCloseArea
                anchors.fill: parent; hoverEnabled: true; cursorShape: Qt.PointingHandCursor
                onClicked: closeColumnPreviewZoom()
            }
        }
        
        // 底部操作提示
        Rectangle {
            anchors.horizontalCenter: parent.horizontalCenter
            anchors.bottom: parent.bottom; anchors.bottomMargin: 20
            width: zoomHintBar.width + 32; height: 36; radius: 18
            color: "#40000000"
            Text {
                id: zoomHintBar
                anchors.centerIn: parent
                text: "左键/滚轮↑: 上一帧 | 右键/滚轮↓: 下一帧 | ←→: 切帧 | S+滚轮: 缩放 | A/Esc: 关闭"
                font.pixelSize: 13; font.family: "PingFang HK"; color: "#33FFFFFF"
            }
        }
    }
    
    // ============ 快捷键处理（使用 Shortcut 组件更可靠）============
    
    // Grid全屏快捷键
    Shortcut {
        sequence: ShortcutStore.gridFullscreenKey
        onActivated: toggleGridFullscreen()
    }
    
    // 实时窗口切换快捷键
    Shortcut {
        sequence: ShortcutStore.realtimeWindowKey
        onActivated: swapRealtimeWindow()
    }
    
    // 慢放窗口切换快捷键
    Shortcut {
        sequence: ShortcutStore.slowmoWindowKey
        onActivated: swapSlowmoWindow()
    }
    
    // 全屏查看关闭快捷键（A键和ESC已在上方统一定义）
    
    // ============ 窗口切换函数 ============
    
    // Grid全屏切换：左侧占满宽度，右侧隐藏
    function toggleGridFullscreen() {
        // ⭐ PC等级1(豪华版)：不允许手动打开抓拍全屏
        if (mainPage.pcActivationLevel < 2) {
            console.log("[抓拍全屏] PC等级1不允许手动打开抓拍全屏")
            return
        }
        
        // ⭐ 变化前的状态（在进入全屏前立即获取，确保是最新的）
        var beforeTop = rightTopHolder.height
        var beforeMiddle = rightMiddleHolder.height
        var beforeTotal = beforeTop + beforeMiddle
        var beforeHeightRatio = beforeTotal > 0 ? beforeTop / beforeTotal : 0
        
        // ⭐ 保存左右分割的宽度比例
        var beforeRightWidth = rightPanel.width
        var beforeTotalWidth = mainSplitView.width
        var beforeWidthRatio = beforeTotalWidth > 0 ? beforeRightWidth / beforeTotalWidth : 0.25
        
        console.log("[抓拍全屏] ====== 切换前 ======")
        console.log("[抓拍全屏] top高度:", beforeTop, "middle高度:", beforeMiddle, "总高度:", beforeTotal, "高度比例:", beforeHeightRatio)
        console.log("[抓拍全屏] rightPanel宽度:", beforeRightWidth, "总宽度:", beforeTotalWidth, "宽度比例:", beforeWidthRatio)
        console.log("[抓拍全屏] 当前 gridFullscreenMode:", gridFullscreenMode)
        
        if (!gridFullscreenMode) {
            // ⭐ 进入全屏前：先停止防抖 Timer，立即保存当前实际比例
            saveHeightRatioTimer.stop()
            saveWidthRatioTimer.stop()
            
            // ⭐ 立即保存当前高度比例（包括用户拖动后的最新比例）
            savedHeightRatio = beforeHeightRatio
            
            // ⭐ 立即保存当前宽度比例（包括用户拖动后的最新比例）
            savedWidthRatio = beforeWidthRatio
            
            console.log("[抓拍全屏] 进入全屏，立即保存高度比例:", savedHeightRatio, "top=", beforeTop, "middle=", beforeMiddle, "total=", beforeTotal)
            console.log("[抓拍全屏] 进入全屏，立即保存宽度比例:", savedWidthRatio, "rightPanel=", beforeRightWidth, "total=", beforeTotalWidth)
        }
        
        gridFullscreenMode = !gridFullscreenMode
        console.log("[抓拍全屏] Grid全屏模式:", gridFullscreenMode)
        
        // 延迟打印变化后的状态
        afterChangeTimer.start()
        
        if (!gridFullscreenMode) {
            // ⭐ 退出全屏后：延迟恢复比例（确保布局已经完成）
            console.log("[抓拍全屏] 退出全屏，准备恢复比例:", savedHeightRatio)
            ratioRestoreTimer.start()
        }
    }
    
    Timer {
        id: afterChangeTimer
        interval: 100
        onTriggered: {
            var afterTop = rightTopHolder.height
            var afterMiddle = rightMiddleHolder.height
            var afterTotal = afterTop + afterMiddle
            var afterRatio = afterTotal > 0 ? afterTop / afterTotal : 0
            console.log("====== 切换后(100ms) ======")
            console.log("top高度:", afterTop, "middle高度:", afterMiddle, "总高度:", afterTotal, "比例:", afterRatio)
        }
    }
    
    Timer {
        id: ratioRestoreTimer
        interval: 100  // 减少延迟，更快恢复
        onTriggered: {
            // ⭐ 设置恢复标志，避免触发自动保存
            isRestoringRatio = true
            isRestoringWidthRatio = true
            
            // ⭐ 先恢复宽度比例（左右分割）
            var totalWidth = mainSplitView.width
            if (totalWidth > 0 && savedWidthRatio > 0 && savedWidthRatio <= 1) {
                var newRightWidth = totalWidth * savedWidthRatio
                console.log("[抓拍全屏] 恢复宽度: rightPanel=", newRightWidth, "total=", totalWidth, "比例=", savedWidthRatio)
                rightPanel.SplitView.preferredWidth = newRightWidth
            }
            
            // ⭐ 再恢复高度比例（上下分割）
            var topHeight = rightTopHolder.height
            var middleHeight = rightMiddleHolder.height
            var totalHeight = topHeight + middleHeight
            var currentRatio = totalHeight > 0 ? topHeight / totalHeight : 0
            console.log("[抓拍全屏] ====== 恢复前(100ms) ======")
            console.log("[抓拍全屏] top高度:", topHeight, "middle高度:", middleHeight, "总高度:", totalHeight)
            console.log("[抓拍全屏] 当前高度比例:", currentRatio, "目标高度比例:", savedHeightRatio)
            
            if (totalHeight > 0 && savedHeightRatio > 0 && savedHeightRatio <= 1) {
                var newTopHeight = totalHeight * savedHeightRatio
                var newMiddleHeight = totalHeight * (1 - savedHeightRatio)
                console.log("[抓拍全屏] 设置高度: top=", newTopHeight, "middle=", newMiddleHeight, "total=", totalHeight)
                
                // ⭐ 直接设置 preferredHeight，SplitView 会自动调整
                rightTopHolder.SplitView.preferredHeight = newTopHeight
                rightMiddleHolder.SplitView.preferredHeight = newMiddleHeight
                
                // 再延迟打印恢复后的状态
                afterRestoreTimer.start()
            } else {
                console.log("[抓拍全屏] 无法恢复高度: totalHeight=", totalHeight, "savedHeightRatio=", savedHeightRatio)
                // 如果无法恢复，也要清除标志
                isRestoringRatio = false
                isRestoringWidthRatio = false
            }
        }
    }
    
    Timer {
        id: afterRestoreTimer
        interval: 150  // 增加延迟，确保 SplitView 完成调整
        onTriggered: {
            var afterTop = rightTopHolder.height
            var afterMiddle = rightMiddleHolder.height
            var afterTotal = afterTop + afterMiddle
            var afterHeightRatio = afterTotal > 0 ? afterTop / afterTotal : 0
            
            var afterRightWidth = rightPanel.width
            var afterTotalWidth = mainSplitView.width
            var afterWidthRatio = afterTotalWidth > 0 ? afterRightWidth / afterTotalWidth : 0
            
            console.log("[抓拍全屏] ====== 恢复后(250ms) ======")
            console.log("[抓拍全屏] top高度:", afterTop, "middle高度:", afterMiddle, "总高度:", afterTotal, "高度比例:", afterHeightRatio)
            console.log("[抓拍全屏] rightPanel宽度:", afterRightWidth, "总宽度:", afterTotalWidth, "宽度比例:", afterWidthRatio)
            console.log("[抓拍全屏] 目标高度比例:", savedHeightRatio, "误差:", Math.abs(afterHeightRatio - savedHeightRatio))
            console.log("[抓拍全屏] 目标宽度比例:", savedWidthRatio, "误差:", Math.abs(afterWidthRatio - savedWidthRatio))
            
            // ⭐ 如果高度恢复不准确，再次尝试恢复
            if (afterTotal > 0 && savedHeightRatio > 0 && Math.abs(afterHeightRatio - savedHeightRatio) > 0.01) {
                console.log("[抓拍全屏] 高度恢复不准确，再次尝试恢复")
                var newTopHeight2 = afterTotal * savedHeightRatio
                var newMiddleHeight2 = afterTotal * (1 - savedHeightRatio)
                rightTopHolder.SplitView.preferredHeight = newTopHeight2
                rightMiddleHolder.SplitView.preferredHeight = newMiddleHeight2
            }
            
            // ⭐ 如果宽度恢复不准确，再次尝试恢复
            if (afterTotalWidth > 0 && savedWidthRatio > 0 && Math.abs(afterWidthRatio - savedWidthRatio) > 0.01) {
                console.log("[抓拍全屏] 宽度恢复不准确，再次尝试恢复")
                var newRightWidth2 = afterTotalWidth * savedWidthRatio
                rightPanel.SplitView.preferredWidth = newRightWidth2
            }
            
            // ⭐ 恢复完成，清除恢复标志
            Qt.callLater(function() {
                isRestoringRatio = false
                isRestoringWidthRatio = false
                console.log("[抓拍全屏] 恢复完成")
            })
        }
    }
    
    // 实时窗口切换：抓拍grid <-> 实时流
    function swapRealtimeWindow() {
        if (windowLayoutMode === 1) {
            // 当前是实时窗口模式，切换回默认
            windowLayoutMode = 0
        } else {
            // 切换到实时窗口模式
            windowLayoutMode = 1
        }
        console.log("实时窗口切换，当前模式:", windowLayoutMode)
    }
    
    // 慢放窗口切换：抓拍grid <-> 慢放
    function swapSlowmoWindow() {
        if (windowLayoutMode === 2) {
            // 当前是慢放窗口模式，切换回默认
            windowLayoutMode = 0
        } else {
            // 切换到慢放窗口模式
            windowLayoutMode = 2
        }
        console.log("慢放窗口切换，当前模式:", windowLayoutMode)
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
        z: 10000
        
        Text {
            id: toastText
            anchors.centerIn: parent
            text: ""
            font.family: "PingFang HK"
            font.pixelSize: 14
            color: "#FFFFFF"
        }
        
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
        }
    }
    
    function showToast(message) {
        toastText.text = message
        toastContainer.visible = true
        toastContainer.opacity = 1
        toastTimer.restart()
    }
    
    // ============ 切换账号对话框 ============
    Dialog {
        id: switchAccountDialog
        anchors.centerIn: parent
        width: 500
        height: 500
        modal: true
        
        property var accountList: []
        property var deviceMap: ({})  // {username: [device1, device2, ...]}
        property string currentUsername: ""
        property string currentDeviceUsername: ""
        property bool isLoading: false
        property var currentDevices: deviceMap[currentUsername] || []
        
        background: Rectangle {
            color: "#FFFFFF"
            radius: 8
            border.color: "#A5D6A7"
            border.width: 1
        }
        
        header: Item {
            height: 50

            Row {
                anchors.left: parent.left
                anchors.leftMargin: 20
                anchors.verticalCenter: parent.verticalCenter
                spacing: 10

                Text {
                    text: "账号管理"
                    font.family: "PingFang HK"
                    font.pixelSize: 18
                    font.bold: true
                    color: "#263238"
                    anchors.verticalCenter: parent.verticalCenter
                }

                Text {
                    text: "设备号: " + HttpClient.pcDeviceId()
                    font.family: "PingFang HK"
                    font.pixelSize: 12
                    color: "#888888"
                    anchors.verticalCenter: parent.verticalCenter
                }
            }
            
            // 刷新按钮
            Rectangle {
                anchors.right: switchCloseBtn.left
                anchors.rightMargin: 12
                anchors.verticalCenter: parent.verticalCenter
                width: 60
                height: 28
                radius: 4
                color: switchRefreshBtnArea.containsMouse ? "#C8E6C9" : "#E8F5E9"
                
                Text {
                    anchors.centerIn: parent
                    text: switchAccountDialog.isLoading ? "加载中" : "🔄 刷新"
                    font.pixelSize: 12
                    color: "#666666"
                }
                
                MouseArea {
                    id: switchRefreshBtnArea
                    anchors.fill: parent
                    hoverEnabled: true
                    cursorShape: Qt.PointingHandCursor
                    onClicked: {
                        if (!switchAccountDialog.isLoading) {
                            refreshOnlineStatus()
                        }
                    }
                }
            }
            
            // 关闭按钮
            Rectangle {
                id: switchCloseBtn
                anchors.right: parent.right
                anchors.rightMargin: 12
                anchors.verticalCenter: parent.verticalCenter
                width: 28
                height: 28
                radius: 14
                color: switchCloseArea.containsMouse ? "#F0F0F0" : "transparent"
                
                Text {
                    anchors.centerIn: parent
                    text: "✕"
                    font.pixelSize: 14
                    color: "#666666"
                }
                
                MouseArea {
                    id: switchCloseArea
                    anchors.fill: parent
                    hoverEnabled: true
                    cursorShape: Qt.PointingHandCursor
                    onClicked: switchAccountDialog.close()
                }
            }
            
            Rectangle {
                anchors.bottom: parent.bottom
                width: parent.width
                height: 1
                color: "#C8E6C9"
            }
        }
        
        contentItem: Item {
            Column {
                anchors.fill: parent
                anchors.margins: 16
                spacing: 16
                
                // 当前账号信息（独立显示）
                Rectangle {
                    width: parent.width
                    height: 60
                    radius: 8
                    color: "#F0F5FA"
                    border.color: "#3993D2"
                    border.width: 2
                    
                    RowLayout {
                        anchors.fill: parent
                        anchors.margins: 12
                        spacing: 12
                        
                        // 头像
                        Rectangle {
                            width: 36
                            height: 36
                            radius: 18
                            color: "#F8F8F8"
                            clip: true
                            
                            Image {
                                anchors.fill: parent
                                source: "images/avatar.png"
                                fillMode: Image.PreserveAspectCrop
                            }
                        }
                        
                        // 账号信息
                        ColumnLayout {
                            Layout.fillWidth: true
                            spacing: 2
                            
                            Text {
                                text: switchAccountDialog.currentUsername || "未登录"
                                font.family: "PingFang HK"
                                font.pixelSize: 15
                                font.bold: true
                                color: "#263238"
                            }
                            
                            Text {
                                property int deviceCount: switchAccountDialog.currentDevices.length
                                property int onlineCount: {
                                    var count = 0
                                    for (var i = 0; i < switchAccountDialog.currentDevices.length; i++) {
                                        if (switchAccountDialog.currentDevices[i].online) count++
                                    }
                                    return count
                                }
                                text: deviceCount > 0 
                                    ? deviceCount + " 个设备，" + onlineCount + " 个在线" 
                                    : "未绑定设备"
                                font.family: "PingFang HK"
                                font.pixelSize: 12
                                color: "#546E7A"
                            }
                        }
                        
                        // 当前标记
                        Rectangle {
                            width: 50
                            height: 22
                            radius: 11
                            color: "#3993D2"
                            
                            Text {
                                anchors.centerIn: parent
                                text: "当前"
                                font.pixelSize: 11
                                color: "#FFFFFF"
                            }
                        }
                    }
                }
                
                // 绑定设备列表标题
                Text {
                    text: "绑定的设备"
                    font.family: "PingFang HK"
                    font.pixelSize: 14
                    font.bold: true
                    color: "#333333"
                    visible: switchAccountDialog.currentDevices.length > 0
                }
                
                // 设备列表（直接显示，不需要展开）
                ListView {
                    id: deviceListView
                    width: parent.width
                    height: parent.height - 120
                    clip: true
                    model: switchAccountDialog.currentDevices
                    spacing: 8
                    
                    delegate: Rectangle {
                        id: deviceItem
                        width: deviceListView.width
                        height: 48
                        radius: 6
                        color: deviceItemMouseArea.containsMouse ? "#F5F8FA" : "#FAFAFA"
                        // ⭐ 当前使用的判断：必须是当前登录账号 + 当前设备
                        // 注意：switchAccountDialog.currentUsername 是选中的标签页账号
                        // HttpClient.getSavedUsername() 是真正登录的账号
                        // switchAccountDialog.currentDeviceUsername 是打开对话框时获取的当前设备
                        property bool isCurrentDevice: {
                            var loggedInUsername = HttpClient.getSavedUsername() || ""
                            var loggedInDeviceUsername = HttpClient.getSavedDeviceUsername() || ""
                            return switchAccountDialog.currentUsername === loggedInUsername && 
                                   modelData.deviceUsername === loggedInDeviceUsername
                        }
                        border.color: isCurrentDevice ? "#3993D2" : "#E8E8E8"
                        border.width: isCurrentDevice ? 2 : 1
                        property string deviceDisplayName: {
                            var baseName = modelData.deviceNickname || modelData.deviceUsername || "未知设备"
                            // ⭐ 备注放在昵称后面
                            if (modelData.remark) {
                                return baseName + " (" + modelData.remark + ")"
                            }
                            return baseName
                        }
                        
                        RowLayout {
                            anchors.fill: parent
                            anchors.leftMargin: 12
                            anchors.rightMargin: 8
                            spacing: 8
                            
                            // 在线状态点
                            Rectangle {
                                width: 8
                                height: 8
                                radius: 4
                                color: modelData.online ? "#4CAF50" : "#CCCCCC"
                            }

                            // 设备名称
                            Text {
                                Layout.fillWidth: true
                                text: deviceItem.deviceDisplayName
                                font.family: "PingFang HK"
                                font.pixelSize: 13
                                color: "#263238"
                                elide: Text.ElideRight
                            }

                            // ⭐ 设备激活等级徽章 — 跟 PC 客户端的 quality_type / memberActivationLevel 口径对齐
                            //   注意: 后端 User.getActivationLevelName 给的是"标清/高清/超清/4K", 跟 PC 客户端不一致
                            //         所以这里 NOT 用 modelData.activationLevelName, 而是从 activationLevel 整数自己映射:
                            //         0=试用, 1=高清, 2=超清, 3=超高清, 4=超高帧
                            //         (跟 mainPage.memberActivationLevel 的注释、相机设定档位的 5 个按钮完全一致)
                            //   颜色按等级递进: 试用=灰, 高清=绿, 超清=蓝, 超高清=橙, 超高帧=红
                            Rectangle {
                                visible: modelData.activationLevel !== undefined && modelData.activationLevel !== null
                                width: levelBadgeText.implicitWidth + 12
                                height: 20
                                radius: 10
                                property int lvl: modelData.activationLevel || 0
                                property string lvlText: {
                                    switch (lvl) {
                                        case 1: return "高清"
                                        case 2: return "超清"
                                        case 3: return "超高清"
                                        case 4: return "超高帧"
                                        default: return "试用"
                                    }
                                }
                                color: {
                                    if (lvl >= 4) return "#FFEBEE"   // 超高帧 — 浅红
                                    if (lvl === 3) return "#FFF3E0"  // 超高清 — 浅橙
                                    if (lvl === 2) return "#E3F2FD"  // 超清 — 浅蓝
                                    if (lvl === 1) return "#E8F5E9"  // 高清 — 浅绿
                                    return "#F0F0F0"                  // 试用 — 灰
                                }
                                border.width: 1
                                border.color: {
                                    if (lvl >= 4) return "#E57373"
                                    if (lvl === 3) return "#FFB74D"
                                    if (lvl === 2) return "#64B5F6"
                                    if (lvl === 1) return "#81C784"
                                    return "#BDBDBD"
                                }

                                Text {
                                    id: levelBadgeText
                                    anchors.centerIn: parent
                                    text: parent.lvlText
                                    font.family: "PingFang HK"
                                    font.pixelSize: 10
                                    font.bold: true
                                    color: {
                                        // parent = 外层 Rectangle, 直接读它定义的 lvl
                                        if (parent.lvl >= 4) return "#C62828"
                                        if (parent.lvl === 3) return "#E65100"
                                        if (parent.lvl === 2) return "#1565C0"
                                        if (parent.lvl === 1) return "#2E7D32"
                                        return "#757575"
                                    }
                                }
                            }
                            
                            // 当前使用标记
                            Rectangle {
                                visible: deviceItem.isCurrentDevice
                                width: 60
                                height: 20
                                radius: 10
                                color: "#E8F4FD"
                                
                                Text {
                                    anchors.centerIn: parent
                                    text: "当前使用"
                                    font.pixelSize: 10
                                    color: "#3993D2"
                                }
                            }
                            
                            // ⭐ 移除在线状态文字（已有绿灯指示）
                            
                            // 备注按钮
                            Rectangle {
                                width: 36
                                height: 24
                                radius: 4
                                color: remarkBtnMouseArea.containsMouse ? "#E8E8E8" : "transparent"
                                
                                Text {
                                    anchors.centerIn: parent
                                    text: "备注"
                                    font.pixelSize: 11
                                    color: "#546E7A"
                                }
                                
                                MouseArea {
                                    id: remarkBtnMouseArea
                                    anchors.fill: parent
                                    hoverEnabled: true
                                    cursorShape: Qt.PointingHandCursor
                                    onClicked: {
                                        showRemarkDialog(
                                            switchAccountDialog.currentUsername,
                                            modelData.deviceUsername || "",
                                            modelData.remark || "",
                                            modelData.deviceNickname || modelData.deviceUsername || "未知设备"
                                        )
                                    }
                                }
                            }
                            
                            // 修改密码按钮
                            Rectangle {
                                width: 60
                                height: 24
                                radius: 4
                                color: securityBtnMouseArea.containsMouse ? "#E8F4FD" : "transparent"
                                
                                Text {
                                    anchors.centerIn: parent
                                    text: "修改密码"
                                    font.pixelSize: 11
                                    color: "#3993D2"
                                }
                                
                                MouseArea {
                                    id: securityBtnMouseArea
                                    anchors.fill: parent
                                    hoverEnabled: true
                                    cursorShape: Qt.PointingHandCursor
                                    onClicked: {
                                        showChangePasswordDialog(
                                            switchAccountDialog.currentUsername,
                                            modelData.deviceUsername || "",
                                            modelData.deviceNickname || modelData.deviceUsername || "未知设备"
                                        )
                                    }
                                }
                            }
                            
                            // 解绑按钮（x号）
                            Rectangle {
                                width: 24
                                height: 24
                                radius: 12
                                color: unbindBtnMouseArea.containsMouse ? "#FFEEEE" : "transparent"
                                
                                Text {
                                    anchors.centerIn: parent
                                    text: "✕"
                                    font.pixelSize: 14
                                    color: unbindBtnMouseArea.containsMouse ? "#CC0000" : "#999999"
                                }
                                
                                MouseArea {
                                    id: unbindBtnMouseArea
                                    anchors.fill: parent
                                    hoverEnabled: true
                                    cursorShape: Qt.PointingHandCursor
                                    onClicked: {
                                        console.log("📍 解绑点击 - modelData:", JSON.stringify(modelData))
                                        var bindingId = modelData.bindingId
                                        console.log("📍 解绑点击 - bindingId:", bindingId, "类型:", typeof bindingId)
                                        if (!bindingId && bindingId !== 0) {
                                            showToast("无法获取绑定信息，请刷新后重试")
                                            return
                                        }
                                        showUnbindConfirmDialog(
                                            bindingId,
                                            modelData,
                                            modelData.deviceNickname || modelData.deviceUsername || "未知设备"
                                        )
                                    }
                                }
                            }
                        }
                        
                        MouseArea {
                            id: deviceItemMouseArea
                            anchors.fill: parent
                            hoverEnabled: true
                            cursorShape: Qt.PointingHandCursor
                            z: -1
                            onClicked: {
                                if (!deviceItem.isCurrentDevice) {
                                    switchToAccountWithDevice(modelData, switchAccountDialog.currentUsername)
                                }
                            }
                        }
                    }
                }
                
                // 无设备提示
                Text {
                    anchors.horizontalCenter: parent.horizontalCenter
                    visible: switchAccountDialog.currentDevices.length === 0 && !switchAccountDialog.isLoading
                    text: "暂无绑定设备"
                    font.family: "PingFang HK"
                    font.pixelSize: 14
                    color: "#999999"
                }
            }
            
            // 加载中提示
            Rectangle {
                anchors.fill: parent
                color: "#80FFFFFF"
                visible: switchAccountDialog.isLoading
                
                Text {
                    anchors.centerIn: parent
                    text: "正在加载设备信息..."
                    font.family: "PingFang HK"
                    font.pixelSize: 14
                    color: "#666666"
                }
            }
        }
        
        footer: Item { height: 12 }
    }
    
    // 监听在线状态接口响应
    Connections {
        target: HttpClient
        
        function onOnlineStatusReceived(list) {
            console.log("📥 OnlineStatus received:", list.length, "devices")
            switchAccountDialog.isLoading = false
            
            // 按 controlUsername 分组
            var deviceMap = {}
            for (var i = 0; i < list.length; i++) {
                var item = list[i]
                console.log("📥 Device", i, "- bindingId:", item.bindingId, 
                            "deviceUsername:", item.deviceUsername, 
                            "online:", item.online)
                var controlUsername = item.controlUsername || ""
                if (!deviceMap[controlUsername]) {
                    deviceMap[controlUsername] = []
                }
                deviceMap[controlUsername].push(item)
            }
            
            // ⭐ 每个账号下的设备按在线状态排序：在线的排前面
            var keys = Object.keys(deviceMap)
            for (var k = 0; k < keys.length; k++) {
                deviceMap[keys[k]].sort(function(a, b) {
                    return (b.online ? 1 : 0) - (a.online ? 1 : 0)
                })
            }
            
            switchAccountDialog.deviceMap = deviceMap
        }
        
        function onOnlineStatusFailed(code, message) {
            console.log("❌ OnlineStatus failed:", code, message)
            switchAccountDialog.isLoading = false
            showToast("获取设备状态失败")
        }
        
        function onSetRemarkSuccess(controlUsername, deviceUsername, remark) {
            console.log("✅ SetRemark success:", controlUsername, deviceUsername, remark)
            showToast("备注设置成功")
            refreshOnlineStatus()
        }
        
        function onSetRemarkFailed(code, message) {
            console.log("❌ SetRemark failed:", code, message)
            showToast("设置备注失败: " + message)
        }
        
        function onUnbindSuccess(bindingId, message) {
            console.log("✅ Unbind success - bindingId:", bindingId, "message:", message)
            showToast("解绑成功: " + message)
            
            // ⭐ 检查被解绑的设备是否是当前正在拉流的设备
            var unbindedDeviceUsername = unbindConfirmDialog.deviceData ? unbindConfirmDialog.deviceData.deviceUsername : ""
            var currentDeviceUsername = HttpClient.getSavedDeviceUsername()
            
            console.log("📍 解绑检查 - 被解绑设备:", unbindedDeviceUsername, "当前拉流设备:", currentDeviceUsername)
            
            if (unbindedDeviceUsername && currentDeviceUsername && unbindedDeviceUsername === currentDeviceUsername) {
                console.log("⚠️ 解绑的是当前正在拉流的设备，停止拉流...")
                stopAll()
                publishState = 0
                mainPage.deviceKbps = 0
                mainPage.deviceBattery = -1
                mainPage.deviceNetworkQuality = ""
                mainPage.deviceNetworkType = ""
                // FPS 自动从 gstPlayer.receiveFps 获取
                statusText.text = "设备已解绑"
            }
            
            refreshOnlineStatus()
        }
        
        function onUnbindFailed(code, message) {
            console.log("❌ Unbind failed - code:", code, "message:", message)
            showToast("解绑失败: (" + code + ") " + message)
        }
        
        function onChangePasswordSuccess(deviceUsername, message, notifyCount, unbindCount) {
            console.log("✅ ChangePassword success:", deviceUsername, message, "notifyCount:", notifyCount, "unbindCount:", unbindCount)
            var toastMsg = message
            if (unbindCount > 0) {
                toastMsg += " (已解绑 " + unbindCount + " 个其他PC端)"
            }
            showToast(toastMsg)
        }
        
        function onChangePasswordFailed(code, message) {
            console.log("❌ ChangePassword failed:", code, message)
            showToast("修改密码失败: " + message)
        }
    }
    
    // ============ 设置备注对话框 ============
    Dialog {
        id: remarkDialog
        anchors.centerIn: parent
        width: 360
        height: 200
        modal: true
        
        property string controlUsername: ""
        property string deviceUsername: ""
        property string currentRemark: ""
        property string deviceName: ""
        
        background: Rectangle {
            color: "#FFFFFF"
            radius: 8
            border.color: "#A5D6A7"
            border.width: 1
        }
        
        header: Item {
            height: 50
            
            Text {
                anchors.left: parent.left
                anchors.leftMargin: 20
                anchors.verticalCenter: parent.verticalCenter
                text: "设置备注 - " + remarkDialog.deviceName
                font.family: "PingFang HK"
                font.pixelSize: 16
                font.bold: true
                color: "#263238"
                elide: Text.ElideRight
                width: parent.width - 60
            }
            
            Rectangle {
                anchors.right: parent.right
                anchors.rightMargin: 12
                anchors.verticalCenter: parent.verticalCenter
                width: 28
                height: 28
                radius: 14
                color: remarkCloseArea.containsMouse ? "#F0F0F0" : "transparent"
                
                Text {
                    anchors.centerIn: parent
                    text: "✕"
                    font.pixelSize: 14
                    color: "#666666"
                }
                
                MouseArea {
                    id: remarkCloseArea
                    anchors.fill: parent
                    hoverEnabled: true
                    cursorShape: Qt.PointingHandCursor
                    onClicked: remarkDialog.close()
                }
            }
            
            Rectangle {
                anchors.bottom: parent.bottom
                width: parent.width
                height: 1
                color: "#C8E6C9"
            }
        }
        
        contentItem: Column {
            spacing: 16
            padding: 20
            
            TextField {
                id: remarkInput
                width: parent.width - 40
                height: 40
                placeholderText: "请输入备注（可为空）"
                text: remarkDialog.currentRemark
                font.pixelSize: 14
                background: Rectangle {
                    color: "#E8F5E9"
                    radius: 4
                    border.color: remarkInput.activeFocus ? "#3993D2" : "#E0E0E0"
                    border.width: 1
                }
            }
            
            Row {
                spacing: 12
                anchors.horizontalCenter: parent.horizontalCenter
                
                Rectangle {
                    width: 100
                    height: 36
                    radius: 4
                    color: remarkCancelArea.containsMouse ? "#E8E8E8" : "#F0F0F0"
                    
                    Text {
                        anchors.centerIn: parent
                        text: "取消"
                        font.pixelSize: 14
                        color: "#546E7A"
                    }
                    
                    MouseArea {
                        id: remarkCancelArea
                        anchors.fill: parent
                        hoverEnabled: true
                        cursorShape: Qt.PointingHandCursor
                        onClicked: remarkDialog.close()
                    }
                }
                
                Rectangle {
                    width: 100
                    height: 36
                    radius: 4
                    color: remarkConfirmArea.containsMouse ? "#2E7AB8" : "#3993D2"
                    
                    Text {
                        anchors.centerIn: parent
                        text: "确定"
                        font.pixelSize: 14
                        color: "#FFFFFF"
                    }
                    
                    MouseArea {
                        id: remarkConfirmArea
                        anchors.fill: parent
                        hoverEnabled: true
                        cursorShape: Qt.PointingHandCursor
                        onClicked: {
                            var newRemark = remarkInput.text.trim()
                            HttpClient.setRemark(
                                remarkDialog.controlUsername,
                                remarkDialog.deviceUsername,
                                newRemark
                            )
                            remarkDialog.close()
                        }
                    }
                }
            }
        }
        
        footer: Item { height: 1 }
    }
    
    // 显示备注对话框
    function showRemarkDialog(controlUsername, deviceUsername, currentRemark, deviceName) {
        remarkDialog.controlUsername = typeof controlUsername === 'string' ? controlUsername : HttpClient.getSavedUsername()
        remarkDialog.deviceUsername = deviceUsername
        remarkDialog.currentRemark = currentRemark || ""
        remarkDialog.deviceName = deviceName
        remarkInput.text = currentRemark || ""
        remarkDialog.open()
    }
    
    // ============ 修改密码对话框 ============
    Dialog {
        id: changePasswordDialog
        anchors.centerIn: parent
        width: 360
        height: 420
        modal: true
        title: ""
        
        property string controlUsername: ""
        property string deviceUsername: ""
        property string deviceName: ""
        
        background: Rectangle {
            color: "#FFFFFF"
            radius: 12
            border.color: "#A5D6A7"
            border.width: 1
        }
        
        header: Item {
            width: parent.width
            height: 50
            
            Text {
                anchors.centerIn: parent
                text: "修改密码"
                font.family: "PingFang HK"
                font.pixelSize: 16
                font.bold: true
                color: "#263238"
            }
            
            Rectangle {
                width: parent.width
                height: 1
                anchors.bottom: parent.bottom
                color: "#E8F5E9"
            }
        }
        
        contentItem: Column {
            spacing: 12
            padding: 20
            
            // 设备名称
            Text {
                text: "设备: " + changePasswordDialog.deviceName
                font.family: "PingFang HK"
                font.pixelSize: 13
                color: "#666666"
            }
            
            // 当前绑定码
            Column {
                spacing: 6
                width: parent.width - 40
                
                Text {
                    text: "当前绑定码"
                    font.family: "PingFang HK"
                    font.pixelSize: 13
                    color: "#333333"
                }
                
                Rectangle {
                    width: parent.width
                    height: 40
                    radius: 6
                    border.color: currentPasswordInput.activeFocus ? "#3993D2" : "#E0E0E0"
                    border.width: currentPasswordInput.activeFocus ? 2 : 1
                    
                    TextInput {
                        id: currentPasswordInput
                        anchors.fill: parent
                        anchors.margins: 10
                        font.family: "PingFang HK"
                        font.pixelSize: 14
                        color: "#263238"
                        echoMode: TextInput.Password
                        clip: true
                        verticalAlignment: TextInput.AlignVCenter
                        
                        Text {
                            anchors.verticalCenter: parent.verticalCenter
                            text: "请输入当前绑定码"
                            color: "#AAAAAA"
                            font.family: "PingFang HK"
                            font.pixelSize: 14
                            visible: parent.text.length === 0 && !parent.activeFocus
                        }
                    }
                }
            }
            
            // 新登录密码
            Column {
                spacing: 6
                width: parent.width - 40
                
                Text {
                    text: "新登录密码 (1-20位)"
                    font.family: "PingFang HK"
                    font.pixelSize: 13
                    color: "#333333"
                }
                
                Rectangle {
                    width: parent.width
                    height: 40
                    radius: 6
                    border.color: newLoginPasswordInput.activeFocus ? "#3993D2" : "#E0E0E0"
                    border.width: newLoginPasswordInput.activeFocus ? 2 : 1
                    
                    TextInput {
                        id: newLoginPasswordInput
                        anchors.fill: parent
                        anchors.margins: 10
                        font.family: "PingFang HK"
                        font.pixelSize: 14
                        color: "#263238"
                        echoMode: TextInput.Password
                        clip: true
                        verticalAlignment: TextInput.AlignVCenter
                        
                        Text {
                            anchors.verticalCenter: parent.verticalCenter
                            text: "请输入新登录密码"
                            color: "#AAAAAA"
                            font.family: "PingFang HK"
                            font.pixelSize: 14
                            visible: parent.text.length === 0 && !parent.activeFocus
                        }
                    }
                }
            }
            
            // 新绑定码
            Column {
                spacing: 6
                width: parent.width - 40
                
                Text {
                    text: "新绑定码 (1-20位)"
                    font.family: "PingFang HK"
                    font.pixelSize: 13
                    color: "#333333"
                }
                
                Rectangle {
                    width: parent.width
                    height: 40
                    radius: 6
                    border.color: newPasswordInput.activeFocus ? "#3993D2" : "#E0E0E0"
                    border.width: newPasswordInput.activeFocus ? 2 : 1
                    
                    TextInput {
                        id: newPasswordInput
                        anchors.fill: parent
                        anchors.margins: 10
                        font.family: "PingFang HK"
                        font.pixelSize: 14
                        color: "#263238"
                        echoMode: TextInput.Password
                        clip: true
                        verticalAlignment: TextInput.AlignVCenter
                        
                        Text {
                            anchors.verticalCenter: parent.verticalCenter
                            text: "请输入新绑定码"
                            color: "#AAAAAA"
                            font.family: "PingFang HK"
                            font.pixelSize: 14
                            visible: parent.text.length === 0 && !parent.activeFocus
                        }
                    }
                }
            }
            
            // 按钮行
            Row {
                spacing: 12
                anchors.horizontalCenter: parent.horizontalCenter
                
                // 取消按钮
                Rectangle {
                    width: 100
                    height: 36
                    radius: 6
                    color: cancelPwdBtnArea.containsMouse ? "#F0F0F0" : "#FAFAFA"
                    border.color: "#A5D6A7"
                    border.width: 1
                    
                    Text {
                        anchors.centerIn: parent
                        text: "取消"
                        font.family: "PingFang HK"
                        font.pixelSize: 14
                        color: "#546E7A"
                    }
                    
                    MouseArea {
                        id: cancelPwdBtnArea
                        anchors.fill: parent
                        hoverEnabled: true
                        cursorShape: Qt.PointingHandCursor
                        onClicked: changePasswordDialog.close()
                    }
                }
                
                // 确认按钮
                Rectangle {
                    width: 100
                    height: 36
                    radius: 6
                    color: confirmPwdBtnArea.containsMouse ? "#2E7BB8" : "#3993D2"
                    
                    Text {
                        anchors.centerIn: parent
                        text: "确认修改"
                        font.family: "PingFang HK"
                        font.pixelSize: 14
                        color: "#FFFFFF"
                    }
                    
                    MouseArea {
                        id: confirmPwdBtnArea
                        anchors.fill: parent
                        hoverEnabled: true
                        cursorShape: Qt.PointingHandCursor
                        onClicked: {
                            var currentSecondaryPwd = currentPasswordInput.text.trim()
                            var newLoginPwd = newLoginPasswordInput.text.trim()
                            var newSecondaryPwd = newPasswordInput.text.trim()
                            
                            if (currentSecondaryPwd.length === 0) {
                                showToast("请输入当前绑定码")
                                return
                            }
                            
                            if (newLoginPwd.length < 1 || newLoginPwd.length > 20) {
                                showToast("新登录密码长度需为1-20位")
                                return
                            }
                            
                            if (newSecondaryPwd.length < 1 || newSecondaryPwd.length > 20) {
                                showToast("新绑定码长度需为1-20位")
                                return
                            }
                            
                            HttpClient.changeDevicePassword(
                                changePasswordDialog.controlUsername,
                                changePasswordDialog.deviceUsername,
                                currentSecondaryPwd,
                                newLoginPwd,
                                newSecondaryPwd
                            )
                            changePasswordDialog.close()
                            showToast("正在修改密码...")
                        }
                    }
                }
            }
        }
        
        footer: Item { height: 1 }
    }
    
    // 显示修改密码对话框
    function showChangePasswordDialog(controlUsername, deviceUsername, deviceName) {
        changePasswordDialog.controlUsername = controlUsername
        changePasswordDialog.deviceUsername = deviceUsername
        changePasswordDialog.deviceName = deviceName
        currentPasswordInput.text = ""
        newLoginPasswordInput.text = ""
        newPasswordInput.text = ""
        changePasswordDialog.open()
    }
    
    // ============ 抓拍清空确认对话框 ============
    Dialog {
        id: clearCaptureConfirmDialog
        anchors.centerIn: parent
        width: 320
        height: 180
        modal: true
        
        background: Rectangle {
            color: "#FFFFFF"
            radius: 8
            border.color: "#A5D6A7"
            border.width: 1
        }
        
        header: Item {
            height: 50
            
            Text {
                anchors.left: parent.left
                anchors.leftMargin: 20
                anchors.verticalCenter: parent.verticalCenter
                text: "确认清空"
                font.family: "PingFang HK"
                font.pixelSize: 16
                font.bold: true
                color: "#263238"
            }
            
            Rectangle {
                anchors.right: parent.right
                anchors.rightMargin: 12
                anchors.verticalCenter: parent.verticalCenter
                width: 28
                height: 28
                radius: 14
                color: clearCaptureCloseArea.containsMouse ? "#F0F0F0" : "transparent"
                
                Text {
                    anchors.centerIn: parent
                    text: "✕"
                    font.pixelSize: 14
                    color: "#666666"
                }
                
                MouseArea {
                    id: clearCaptureCloseArea
                    anchors.fill: parent
                    hoverEnabled: true
                    cursorShape: Qt.PointingHandCursor
                    onClicked: clearCaptureConfirmDialog.close()
                }
            }
            
            Rectangle {
                anchors.bottom: parent.bottom
                width: parent.width
                height: 1
                color: "#C8E6C9"
            }
        }
        
        contentItem: Column {
            spacing: 16
            padding: 20
            
            Text {
                width: parent.width - 40
                text: "确定要清空所有抓拍内容吗？"
                font.family: "PingFang HK"
                font.pixelSize: 14
                color: "#333333"
                wrapMode: Text.Wrap
            }
            
            Row {
                anchors.horizontalCenter: parent.horizontalCenter
                spacing: 16
                
                Rectangle {
                    width: 100
                    height: 36
                    radius: 4
                    color: clearCaptureCancelArea.containsMouse ? "#E8E8E8" : "#F0F0F0"
                    
                    Text {
                        anchors.centerIn: parent
                        text: "取消"
                        font.family: "PingFang HK"
                        font.pixelSize: 14
                        color: "#546E7A"
                    }
                    
                    MouseArea {
                        id: clearCaptureCancelArea
                        anchors.fill: parent
                        hoverEnabled: true
                        cursorShape: Qt.PointingHandCursor
                        onClicked: clearCaptureConfirmDialog.close()
                    }
                }
                
                Rectangle {
                    width: 100
                    height: 36
                    radius: 4
                    color: clearCaptureConfirmArea.containsMouse ? "#D32F2F" : "#E53935"
                    
                    Text {
                        anchors.centerIn: parent
                        text: "确认清空"
                        font.family: "PingFang HK"
                        font.pixelSize: 14
                        color: "#FFFFFF"
                    }
                    
                    MouseArea {
                        id: clearCaptureConfirmArea
                        anchors.fill: parent
                        hoverEnabled: true
                        cursorShape: Qt.PointingHandCursor
                        onClicked: {
                            // 清空抓拍
                            captureManager.clearAll()
                            clearCaptureConfirmDialog.close()
                            console.log("🗑️ 抓拍已清空")
                        }
                    }
                }
            }
        }
    }
    
    // ============ 解绑确认对话框 ============
    Dialog {
        id: unbindConfirmDialog
        anchors.centerIn: parent
        width: 360
        height: 200
        modal: true
        
        property var bindingId: 0  // 使用 var 以支持大整数（Java Long类型）
        property var deviceData: null
        property string deviceName: ""
        
        background: Rectangle {
            color: "#FFFFFF"
            radius: 8
            border.color: "#A5D6A7"
            border.width: 1
        }
        
        header: Item {
            height: 50
            
            Text {
                anchors.left: parent.left
                anchors.leftMargin: 20
                anchors.verticalCenter: parent.verticalCenter
                text: "确认解绑"
                font.family: "PingFang HK"
                font.pixelSize: 16
                font.bold: true
                color: "#263238"
            }
            
            Rectangle {
                anchors.right: parent.right
                anchors.rightMargin: 12
                anchors.verticalCenter: parent.verticalCenter
                width: 28
                height: 28
                radius: 14
                color: unbindCloseArea.containsMouse ? "#F0F0F0" : "transparent"
                
                Text {
                    anchors.centerIn: parent
                    text: "✕"
                    font.pixelSize: 14
                    color: "#666666"
                }
                
                MouseArea {
                    id: unbindCloseArea
                    anchors.fill: parent
                    hoverEnabled: true
                    cursorShape: Qt.PointingHandCursor
                    onClicked: unbindConfirmDialog.close()
                }
            }
            
            Rectangle {
                anchors.bottom: parent.bottom
                width: parent.width
                height: 1
                color: "#C8E6C9"
            }
        }
        
        contentItem: Column {
            spacing: 16
            padding: 20
            
            Text {
                width: parent.width - 40
                text: "确定要解绑设备「" + unbindConfirmDialog.deviceName + "」吗？\n解绑后需要重新在iOS端扫码或手动绑定。"
                font.family: "PingFang HK"
                font.pixelSize: 14
                color: "#333333"
                wrapMode: Text.WordWrap
            }
            
            Row {
                spacing: 12
                anchors.horizontalCenter: parent.horizontalCenter
                
                Rectangle {
                    width: 100
                    height: 36
                    radius: 4
                    color: unbindCancelArea.containsMouse ? "#E8E8E8" : "#F0F0F0"
                    
                    Text {
                        anchors.centerIn: parent
                        text: "取消"
                        font.pixelSize: 14
                        color: "#546E7A"
                    }
                    
                    MouseArea {
                        id: unbindCancelArea
                        anchors.fill: parent
                        hoverEnabled: true
                        cursorShape: Qt.PointingHandCursor
                        onClicked: unbindConfirmDialog.close()
                    }
                }
                
                Rectangle {
                    width: 100
                    height: 36
                    radius: 4
                    color: unbindConfirmArea.containsMouse ? "#CC0000" : "#E53935"
                    
                    Text {
                        anchors.centerIn: parent
                        text: "确认解绑"
                        font.pixelSize: 14
                        color: "#FFFFFF"
                    }
                    
                    MouseArea {
                        id: unbindConfirmArea
                        anchors.fill: parent
                        hoverEnabled: true
                        cursorShape: Qt.PointingHandCursor
                        onClicked: {
                            var username = HttpClient.getSavedUsername()
                            var password = HttpClient.getAccountPassword(username)
                            console.log("📍 确认解绑 - username:", username)
                            console.log("📍 确认解绑 - password长度:", password ? password.length : 0)
                            console.log("📍 确认解绑 - bindingId:", unbindConfirmDialog.bindingId)
                            if (!password) {
                                showToast("无法获取账号密码，请重新登录")
                                unbindConfirmDialog.close()
                                return
                            }
                            if (!unbindConfirmDialog.bindingId && unbindConfirmDialog.bindingId !== 0) {
                                showToast("绑定ID无效")
                                unbindConfirmDialog.close()
                                return
                            }
                            console.log("📍 发送解绑请求 - bindingId:", unbindConfirmDialog.bindingId, "passwordLen:", password.length)
                            HttpClient.windowsUnbind(unbindConfirmDialog.bindingId, password)
                            unbindConfirmDialog.close()
                            showToast("正在解绑...")
                        }
                    }
                }
            }
        }
        
        footer: Item { height: 1 }
    }
    
    // 显示解绑确认对话框
    function showUnbindConfirmDialog(bindingId, deviceData, deviceName) {
        unbindConfirmDialog.bindingId = bindingId
        unbindConfirmDialog.deviceData = deviceData
        unbindConfirmDialog.deviceName = deviceName
        unbindConfirmDialog.open()
    }
    
    // 刷新在线状态
    function refreshOnlineStatus() {
        var accounts = switchAccountDialog.accountList
        if (accounts.length === 0) return
        
        switchAccountDialog.isLoading = true
        HttpClient.getOnlineStatus(accounts)
    }
    
    // 显示切换账号对话框
    function showSwitchAccountDialog() {
        var accounts = HttpClient.getSavedAccounts()
        switchAccountDialog.accountList = accounts
        switchAccountDialog.currentUsername = HttpClient.getSavedUsername()
        switchAccountDialog.currentDeviceUsername = HttpClient.getSavedDeviceUsername()
        switchAccountDialog.deviceMap = {}
        switchAccountDialog.open()
        
        // 自动加载设备状态
        refreshOnlineStatus()
    }
    
    // 切换到指定账号（无设备时）
    function switchToAccount(username) {
        var password = HttpClient.getAccountPassword(username)
        if (!password) {
            showToast("账号密码已失效，请重新登录")
            return
        }
        
        switchAccountDialog.close()
        showToast("正在切换账号...")
        
        // 停止当前流
        stopAll()
        publishState = 0
        currentStream = ""  // 清空 streamKey
        
        // ⭐ 清理 frames 目录和抓拍列表
        gstPlayer.clearJpegFiles()
        captureManager.clearAll()
        
        // ⭐ 重置档位显示（避免残留上一账号的档位）
        iosCameraSettingsPopup.qualityType = "high"
        qualityButtonText.text = "超清"
        
        // 断开 WebSocket
        WebSocketClient.disconnectFromServer()
        
        // ⭐ 设置切换设备标志，以便登录成功后重连 WebSocket 并刷新配置
        isSwitchingDevice = true
        switchingUsername = username
        switchingPassword = password
        switchingDeviceUsername = ""
        switchingDeviceDisplay = ""
        
        // 重新登录（保持当前等级）
        HttpClient.login(username, password, mainPage.pcActivationLevel || 1)
    }
    
    // 切换到指定账号的指定设备
    function switchToAccountWithDevice(device, username) {
        var deviceUsername = device.deviceUsername || ""
        
        // 检查是否是同一账号同一设备，如果是则忽略
        var currentUsername = HttpClient.getSavedUsername()
        var currentDeviceUsername = HttpClient.getSavedDeviceUsername()
        if (username === currentUsername && deviceUsername === currentDeviceUsername) {
            console.log("📌 同一账号同一设备，忽略切换")
            switchAccountDialog.close()
            return
        }
        
        var password = HttpClient.getAccountPassword(username)
        if (!password) {
            showToast("账号密码已失效，请重新登录")
            return
        }
        
        switchAccountDialog.close()
        showToast("正在切换到 " + (device.deviceNickname || deviceUsername) + "...")
        
        console.log("🔄 切换账号: 停止当前流...")
        // 停止当前流、清空慢放、清空抓拍
        stopAll()
        publishState = 0
        currentStream = ""  // 清空 streamKey
        
        // ⭐ 清理 frames 目录和抓拍列表
        console.log("🔄 切换账号: 清理 frames 目录和抓拍列表...")
        gstPlayer.clearJpegFiles()
        captureManager.clearAll()
        
        // ⭐ 重置档位显示（避免残留上一账号的档位）
        iosCameraSettingsPopup.qualityType = "high"
        qualityButtonText.text = "超清"
        
        console.log("🔄 切换账号: 断开 WebSocket...")
        // 断开 WebSocket (STOMP)
        WebSocketClient.disconnectFromServer()
        
        // ⭐ 设置切换设备标志，以便登录成功后保存设备信息
        isSwitchingDevice = true
        switchingUsername = username
        switchingPassword = password
        switchingDeviceUsername = deviceUsername
        switchingDeviceDisplay = device.deviceNickname || deviceUsername
        
        console.log("🔄 切换账号: 重新登录 username=" + username + " device=" + deviceUsername)
        // 重新登录（传入设备账号，保持当前等级）
        HttpClient.login(username, password, mainPage.pcActivationLevel || 1, deviceUsername)
    }
    
    // 退出登录
    function handleLogout() {
        console.log("🚪 退出登录: 开始...")
        
        // 停止当前流
        stopAll()
        publishState = 0
        currentStream = ""  // 清空 streamKey
        
        // ⭐ 清理 frames 目录和抓拍列表
        console.log("🚪 退出登录: 清理 frames 目录和抓拍列表...")
        gstPlayer.clearJpegFiles()
        captureManager.clearAll()
        
        console.log("🚪 退出登录: 断开 WebSocket...")
        // 断开 WebSocket
        WebSocketClient.disconnectFromServer()
        
        // 退出登录（只清除token，保留账号列表）
        HttpClient.logout()
        
        console.log("🚪 退出登录: 完成，返回登录页")
        showToast("已退出登录")

        // 触发信号，返回登录页
        logoutRequested()
    }

    // ============ ⭐ iOS 滤镜设定 Window（独立窗口，可拖动）============
    //   STOMP 直推 iOS, 不绕后端 HTTP. 正式后端没有 IosFilterController,
    //   所以这里去掉了"保存为系统默认"按钮和登录默认值拉取.
    Window {
        id: iosFilterPopup
        width: 480
        height: 420
        flags: Qt.Tool | Qt.FramelessWindowHint | Qt.WindowStaysOnTopHint
        color: "transparent"
        visible: false

        // 兼容 Popup 的 open/close
        //   open 时把 PC 当前默认值同步到 iOS (确保 redBoost=0.02 等锁死值生效)
        function open()  {
            visible = true
            pushAllStomp()
        }
        function close() { visible = false }

        // 拖动状态
        property point dragStart: Qt.point(0, 0)
        property bool dragging: false

        // ⭐ 滤镜参数（默认值规格 — 启动时从总后台动态拉取覆盖）
        //   滑块值直接 = 发给 iOS 的值 (无派生公式)
        //   曝光: PC 端展示线性倍数, 发给 iOS 时 Math.log2() 转 EV stops
        //   红色增强: 锁死, 无滑块
        property double fBrightness: 1.10
        property double fGamma:      1.10
        property double fContrast:   1.10
        property double fSaturation: 1.10
        property double fExposure:   1.10
        property double fRedBoost:   0.02
        property double fBlackPoint: 0.10   // ⭐ 默认 0.10 压死 limited-range 伪黑 (黑色不再灰)
        property bool   fEnabled:    true

        // ⭐ 上下限 / 步进 / 出厂默认 — 跟默认值一样从后台动态拉取 (硬编码仅作 server fetch 失败时的 fallback)
        property double brightnessFrom: 0.8;   property double brightnessTo: 2.0;   property double brightnessStep: 0.02; property double brightnessDefault: 1.10
        property double gammaFrom:      0.8;   property double gammaTo:      2.0;   property double gammaStep:      0.01; property double gammaDefault:      1.10
        property double contrastFrom:   0.8;   property double contrastTo:   1.30;  property double contrastStep:   0.02; property double contrastDefault:   1.10
        property double saturationFrom: 0.0;   property double saturationTo: 2.0;   property double saturationStep: 0.02; property double saturationDefault: 1.10
        property double exposureFrom:   0.6;   property double exposureTo:   1.6;   property double exposureStep:   0.02; property double exposureDefault:   1.10
        property double redBoostDefault: 0.02
        property double blackPointDefault: 0.10   // ⭐ 后台可调; 压 H.264 limited-range 伪黑

        // ⭐ 综合亮度联动 — 每个选项前的复选框, 勾选的滑块组成联动组
        //   拖任一勾选滑块, 其他勾选滑块按各自 stepSize 走相同步数 (双向)
        //   默认: 亮度+伽马 联动, 其他独立 (后台可改 linkDefault)
        property bool linkBrightness: true
        property bool linkGamma:      true
        property bool linkContrast:   false
        property bool linkSaturation: false
        property bool linkExposure:   false

        // ⭐ 存储后台配置的 linkDefault 值（供"还原"按钮使用）
        property bool linkBrightnessDefault: true
        property bool linkGammaDefault:      true
        property bool linkContrastDefault:   false
        property bool linkSaturationDefault: false
        property bool linkExposureDefault:   false

        // ⭐ 应用从后台拉到的默认配置 JSON
        //   后端 GET /api/config/ios-filter-defaults 返回 { config: "<JSON>" }
        //   解析后覆盖 popup 上述属性, 滑块自动 rebind
        function applyServerDefaults(configJson) {
            var c
            try { c = JSON.parse(configJson) }
            catch (e) { console.warn("🎨 [iOS-Filter] 后台默认值 JSON 解析失败:", e); return }

            function applyOne(key, fromProp, toProp, stepProp, defaultProp, currProp, prevProp, linkProp, linkDefaultProp) {
                if (!c[key]) return
                var entry = c[key]
                if (entry.from      !== undefined) iosFilterPopup[fromProp]    = entry.from
                if (entry.to        !== undefined) iosFilterPopup[toProp]      = entry.to
                if (entry.stepSize  !== undefined) iosFilterPopup[stepProp]    = entry.stepSize
                if (entry.default   !== undefined) {
                    iosFilterPopup[defaultProp] = entry.default       // 出厂默认 (供"还原"按钮用)
                    iosFilterPopup[currProp]    = entry.default       // 当前值 (滑块绑定)
                    iosFilterPopup[prevProp]    = entry.default
                }
                if (entry.linkDefault !== undefined && linkProp) {
                    iosFilterPopup[linkProp] = entry.linkDefault
                    // ⭐ 同时保存到 linkDefaultProp（供"还原"按钮恢复用）
                    if (linkDefaultProp) iosFilterPopup[linkDefaultProp] = entry.linkDefault
                }
            }
            applyOne("brightness", "brightnessFrom", "brightnessTo", "brightnessStep", "brightnessDefault", "fBrightness", "prevBrightness", "linkBrightness", "linkBrightnessDefault")
            applyOne("gamma",      "gammaFrom",      "gammaTo",      "gammaStep",      "gammaDefault",      "fGamma",      "prevGamma",      "linkGamma",      "linkGammaDefault")
            applyOne("contrast",   "contrastFrom",   "contrastTo",   "contrastStep",   "contrastDefault",   "fContrast",   "prevContrast",   "linkContrast",   "linkContrastDefault")
            applyOne("saturation", "saturationFrom", "saturationTo", "saturationStep", "saturationDefault", "fSaturation", "prevSaturation", "linkSaturation", "linkSaturationDefault")
            applyOne("exposure",   "exposureFrom",   "exposureTo",   "exposureStep",   "exposureDefault",   "fExposure",   "prevExposure",   "linkExposure",   "linkExposureDefault")
            if (c.redBoost && c.redBoost.locked !== undefined) {
                iosFilterPopup.redBoostDefault = c.redBoost.locked
                iosFilterPopup.fRedBoost       = c.redBoost.locked
            }
            // ⭐ blackPoint (locked, 无滑块, 启动时由 pushAllStomp 推给 iOS)
            if (c.blackPoint && c.blackPoint.locked !== undefined) {
                iosFilterPopup.blackPointDefault = c.blackPoint.locked
                iosFilterPopup.fBlackPoint       = c.blackPoint.locked
            }
            // 手动同步滑块当前 value (绑定可能已被 onMoved 打断)
            if (typeof ifMasterSlider     !== 'undefined') ifMasterSlider.value     = iosFilterPopup.fBrightness
            if (typeof ifGammaSlider      !== 'undefined') ifGammaSlider.value      = iosFilterPopup.fGamma
            if (typeof ifContrastSlider   !== 'undefined') ifContrastSlider.value   = iosFilterPopup.fContrast
            if (typeof ifSaturationSlider !== 'undefined') ifSaturationSlider.value = iosFilterPopup.fSaturation
            if (typeof ifExposureSlider   !== 'undefined') ifExposureSlider.value   = iosFilterPopup.fExposure
            console.log("✅ [iOS-Filter] 已应用后台默认值")

            // ⭐ Bug2 修复：重置相机设定的"综合亮度"到中点 50（对应所有 iOS 滤镜值都在 default）
            iosCameraSettingsPopup.exposureValue = 50
            captureManager.exposure = 50

            // ⭐ 自动推送给 iOS — 不再依赖用户按 P 打开滤镜弹框才生效
            //   场景:
            //     • 启动时 Component.onCompleted 拉默认值 → 此时未登录, sendConfigUpdate 因无 deviceId 静默 noop, 无副作用
            //     • 登录/切换账号时 onLoginSuccess 重新拉 → 此时 deviceId 已设, STOMP 已连, iOS 收到全量参数, 滤镜立即生效
            //   ⚠️ 别移到 onLoginSuccess 直接调 — 那里 getIosFilterDefaults 是异步, 还没到 applyServerDefaults
            //   就 push 会推到旧值/默认值, 拿不到后端最新.
            iosFilterPopup.pushAllStomp()
        }

        Component.onCompleted: {
            // 启动时连接 HttpClient 的 iOS 滤镜默认值信号 + 主动拉取一次
            HttpClient.iosFilterDefaultsReceived.connect(applyServerDefaults)
            HttpClient.iosFilterDefaultsFailed.connect(function(code, msg) {
                console.warn("🎨 [iOS-Filter] 拉默认值失败 (用前端 fallback): code=" + code + ", msg=" + msg)
            })
            HttpClient.getIosFilterDefaults()
        }

        // 内部 prev 值 — 用于计算每次 onMoved 的 delta (slider 的 value 已经是新值)
        property double prevBrightness: 1.10
        property double prevGamma:      1.10
        property double prevContrast:   1.10
        property double prevSaturation: 1.10
        property double prevExposure:   1.10

        // ⭐ 联动 helper
        function clampVal(v, lo, hi) { return Math.max(lo, Math.min(hi, v)) }

        //   sourceId: 触发联动的源滑块 ("brightness" / "gamma" / ...), 不会再次驱动它自己
        //   stepCount: 源滑块走了多少个自己的 stepSize 单位 (整数或小数)
        //   每个勾选的目标滑块 = prev + stepCount × 自己的 stepSize, clamp 到自身范围
        function applyLinkedDelta(sourceId, stepCount) {
            // ⭐ 亮度方向相反: 亮度永远与其他联动参数反向
            //   - 亮度作为联动目标 (sourceId 不是 brightness)         → 走 -stepCount
            //   - 亮度作为源 (sourceId === "brightness")              → 其他联动目标走 -stepCount
            //   两种情况下"亮度增加=其他减少 / 其他增加=亮度减少".
            var otherSign = (sourceId === "brightness") ? -1 : 1   // 给非 brightness 目标用的符号

            if (sourceId !== "brightness" && linkBrightness) {
                var nv = clampVal(prevBrightness + (-stepCount) * ifMasterSlider.stepSize,
                                   ifMasterSlider.from, ifMasterSlider.to)
                prevBrightness = nv;  fBrightness = nv;  ifMasterSlider.value = nv
                pushParam("brightness", nv)
            }
            if (sourceId !== "gamma" && linkGamma) {
                var nv = clampVal(prevGamma + otherSign * stepCount * ifGammaSlider.stepSize,
                                   ifGammaSlider.from, ifGammaSlider.to)
                prevGamma = nv;  fGamma = nv;  ifGammaSlider.value = nv
                pushParam("gamma", nv)
            }
            if (sourceId !== "contrast" && linkContrast) {
                var nv = clampVal(prevContrast + otherSign * stepCount * ifContrastSlider.stepSize,
                                   ifContrastSlider.from, ifContrastSlider.to)
                prevContrast = nv;  fContrast = nv;  ifContrastSlider.value = nv
                pushParam("contrast", nv)
            }
            if (sourceId !== "saturation" && linkSaturation) {
                var nv = clampVal(prevSaturation + otherSign * stepCount * ifSaturationSlider.stepSize,
                                   ifSaturationSlider.from, ifSaturationSlider.to)
                prevSaturation = nv;  fSaturation = nv;  ifSaturationSlider.value = nv
                pushParam("saturation", nv)
            }
            if (sourceId !== "exposure" && linkExposure) {
                var nv = clampVal(prevExposure + otherSign * stepCount * ifExposureSlider.stepSize,
                                   ifExposureSlider.from, ifExposureSlider.to)
                prevExposure = nv;  fExposure = nv;  ifExposureSlider.value = nv
                pushParam("exposure", Math.log2(nv))
            }
        }

        // ⭐ v3 STOMP 直推: 单参数 → /topic/device/{id}/config
        function pushParam(ptype, val) {
            var c = {}
            c[ptype] = val
            sendConfigUpdate(ptype, c)
        }

        // ⭐ STOMP 全量推送 — 还原时用一次发齐 (filterEnabled 永远 true 不可关)
        function pushAllStomp() {
            pushParam("filterEnabled", true)
            pushParam("brightness",    iosFilterPopup.fBrightness)
            pushParam("contrast",      iosFilterPopup.fContrast)
            pushParam("saturation",    iosFilterPopup.fSaturation)
            pushParam("redBoost",      iosFilterPopup.fRedBoost)
            pushParam("gamma",         iosFilterPopup.fGamma)
            pushParam("exposure",      Math.log2(iosFilterPopup.fExposure))
            pushParam("blackPoint",    iosFilterPopup.fBlackPoint)   // ⭐ 压 limited-range 伪黑
        }

        // ⭐ 相机设定的"综合亮度"(0-100) → 同时驱动 brightness + gamma + exposure
        //   X=0   → 三个都到 from (最暗)
        //   X=50  → 三个都到 default (出厂默认)
        //   X=100 → 三个都到 to (最亮)
        //   按各自 default/from/to 非对称插值, 保证 X=50 时停在默认
        //   ⚠️ 亮度是反向的：X 增大时，brightness 减小（其他参数增大）
        function syncFromOverallBrightness(X) {
            var t = (X - 50) / 50   // -1 .. +1
            function setOne(currProp, prevProp, defProp, fromProp, toProp, ptype, isExposure, isInverted) {
                var def = iosFilterPopup[defProp]
                var lo  = iosFilterPopup[fromProp]
                var hi  = iosFilterPopup[toProp]
                // ⭐ 亮度反向：t 取反
                var actualT = isInverted ? -t : t
                var v   = actualT < 0 ? def + actualT * (def - lo) : def + actualT * (hi - def)
                v = clampVal(v, lo, hi)
                iosFilterPopup[currProp] = v
                iosFilterPopup[prevProp] = v
                if (isExposure) pushParam("exposure", Math.log2(v))
                else            pushParam(ptype, v)
            }
            // ⭐ 动态联动 — 不再写死 (brightness/gamma/exposure)
            //    根据 iOS 滤镜弹框里的勾选 (linkBrightness/linkGamma/...) 决定
            //    勾选状态启动时来自后端 /api/config/ios-filter-defaults 的 linkDefault,
            //    切换账号时会重新拉取覆盖, 用户在 iOS 滤镜弹框里手动勾/取消也会改变这里的联动集合.
            //    ⚠️ brightness 是反向的（isInverted = true）
            if (linkBrightness) setOne("fBrightness", "prevBrightness", "brightnessDefault", "brightnessFrom", "brightnessTo", "brightness", false, true)
            if (linkGamma)      setOne("fGamma",      "prevGamma",      "gammaDefault",      "gammaFrom",      "gammaTo",      "gamma",      false, false)
            if (linkContrast)   setOne("fContrast",   "prevContrast",   "contrastDefault",   "contrastFrom",   "contrastTo",   "contrast",   false, false)
            if (linkSaturation) setOne("fSaturation", "prevSaturation", "saturationDefault", "saturationFrom", "saturationTo", "saturation", false, false)
            if (linkExposure)   setOne("fExposure",   "prevExposure",   "exposureDefault",   "exposureFrom",   "exposureTo",   "exposure",   true,  false)
            // ⭐ Bug3 修复：同步更新 iOS 滤镜弹框的滑块
            if (typeof ifMasterSlider     !== 'undefined') ifMasterSlider.value     = iosFilterPopup.fBrightness
            if (typeof ifGammaSlider      !== 'undefined') ifGammaSlider.value      = iosFilterPopup.fGamma
            if (typeof ifContrastSlider   !== 'undefined') ifContrastSlider.value   = iosFilterPopup.fContrast
            if (typeof ifSaturationSlider !== 'undefined') ifSaturationSlider.value = iosFilterPopup.fSaturation
            if (typeof ifExposureSlider   !== 'undefined') ifExposureSlider.value   = iosFilterPopup.fExposure
            // ⭐ Bug2 修复：同步更新相机设定弹框的滑块
            if (typeof cameraFakeExposureSlider !== 'undefined') cameraFakeExposureSlider.value = iosFilterPopup.fBrightness
            if (typeof cameraBrightnessSlider   !== 'undefined') cameraBrightnessSlider.value   = iosFilterPopup.fContrast
            if (typeof cameraSaturationSlider   !== 'undefined') cameraSaturationSlider.value   = iosFilterPopup.fSaturation
        }

        // ⭐ 相机设定弹框里 对比度 / 曝光度(实际驱动 brightness) / 红外模式(saturation) 滑块用这个
        //    特点: 只设自己一个底层值 + 推 iOS + 同步刷新 iOS 滤镜弹框对应滑块的 value (绑定可能被打断)
        //    ⚠️ 不调 applyLinkedDelta — 即不触发"亮度联动" 级联
        //       (区别: iOS 滤镜弹框里的滑块 onMoved 会调 applyLinkedDelta; 相机设定里的不调.
        //        只有 综合亮度 滑块才走多参数级联.)
        function syncSingle(ptype, v) {
            if (ptype === "brightness") {
                v = clampVal(v, brightnessFrom, brightnessTo)
                fBrightness = v;  prevBrightness = v
                if (typeof ifMasterSlider !== 'undefined') ifMasterSlider.value = v
                // ⭐ Bug3 修复：同步更新相机设定的"曝光度"滑块（实际驱动 brightness）
                if (typeof cameraFakeExposureSlider !== 'undefined') cameraFakeExposureSlider.value = v
                pushParam("brightness", v)
            } else if (ptype === "contrast") {
                v = clampVal(v, contrastFrom, contrastTo)
                fContrast = v;   prevContrast = v
                if (typeof ifContrastSlider !== 'undefined') ifContrastSlider.value = v
                // ⭐ Bug3 修复：同步更新相机设定的"对比度"滑块
                if (typeof cameraBrightnessSlider !== 'undefined') cameraBrightnessSlider.value = v
                pushParam("contrast", v)
            } else if (ptype === "saturation") {
                v = clampVal(v, saturationFrom, saturationTo)
                fSaturation = v;  prevSaturation = v
                if (typeof ifSaturationSlider !== 'undefined') ifSaturationSlider.value = v
                // ⭐ Bug3 修复：同步更新相机设定的"红外模式"滑块
                if (typeof cameraSaturationSlider !== 'undefined') cameraSaturationSlider.value = v
                pushParam("saturation", v)
            }
        }

        // ⭐ 滚轮调节滑块: 一格 = 2 × stepSize (0.02), 直接 STOMP, 无防抖
        function adjustSliderByWheel(slider, propName, ptype, angleDeltaY) {
            if (angleDeltaY === 0) return
            var dir = angleDeltaY > 0 ? 1 : -1
            var step = slider.stepSize > 0 ? slider.stepSize * 2 : (slider.to - slider.from) / 100
            var newVal = slider.value + dir * step
            newVal = Math.max(slider.from, Math.min(slider.to, newVal))
            slider.value = newVal
            iosFilterPopup[propName] = newVal
            pushParam(ptype, newVal)
        }

        // 窗口背景（白底+绿描边，跟相机设定一致）
        Rectangle {
            anchors.fill: parent
            color: "#FFFFFF"
            radius: 4
            border.color: "#A5D6A7"
            border.width: 1

            ColumnLayout {
                spacing: 12
                anchors.fill: parent
                anchors.margins: 24

                // ===== 标题栏（拖动区 + 还原 + ✕）=====
                Rectangle {
                    Layout.fillWidth: true
                    Layout.preferredHeight: 40
                    color: "transparent"

                    MouseArea {
                        anchors.fill: parent
                        cursorShape: Qt.ClosedHandCursor
                        propagateComposedEvents: false
                        property point startPos: Qt.point(0, 0)
                        property point dragStartGlobal: Qt.point(0, 0)
                        onPressed: function(mouse) {
                            startPos = Qt.point(iosFilterPopup.x, iosFilterPopup.y)
                            dragStartGlobal = mapToGlobal(mouse.x, mouse.y)
                            iosFilterPopup.dragging = true
                            mouse.accepted = true
                        }
                        onPositionChanged: function(mouse) {
                            if (iosFilterPopup.dragging) {
                                var g = mapToGlobal(mouse.x, mouse.y)
                                iosFilterPopup.x = startPos.x + (g.x - dragStartGlobal.x)
                                iosFilterPopup.y = startPos.y + (g.y - dragStartGlobal.y)
                            }
                        }
                        onReleased: iosFilterPopup.dragging = false
                    }

                    // 还原按钮
                    Rectangle {
                        anchors.left: parent.left
                        anchors.verticalCenter: parent.verticalCenter
                        width: filterResetText.width + 20
                        height: 28
                        radius: 6
                        color: filterResetArea.containsMouse ? "#C8E6C9" : "#E8F5E9"
                        border.color: "#A5D6A7"
                        border.width: 1
                        Text {
                            id: filterResetText
                            anchors.centerIn: parent
                            text: "还原"
                            font.family: "PingFang HK"
                            font.pixelSize: 14
                            color: "#263238"
                        }
                        MouseArea {
                            id: filterResetArea
                            anchors.fill: parent
                            hoverEnabled: true
                            cursorShape: Qt.PointingHandCursor
                            onClicked: {
                                // ⭐ Bug1 修复：还原 — 用从后台拉到的"出厂默认"(brightnessDefault 等) + linkDefault
                                iosFilterPopup.fBrightness = iosFilterPopup.brightnessDefault
                                iosFilterPopup.fGamma      = iosFilterPopup.gammaDefault
                                iosFilterPopup.fContrast   = iosFilterPopup.contrastDefault
                                iosFilterPopup.fSaturation = iosFilterPopup.saturationDefault
                                iosFilterPopup.fExposure   = iosFilterPopup.exposureDefault
                                iosFilterPopup.fRedBoost   = iosFilterPopup.redBoostDefault
                                iosFilterPopup.fEnabled    = true
                                iosFilterPopup.prevBrightness = iosFilterPopup.brightnessDefault
                                iosFilterPopup.prevGamma      = iosFilterPopup.gammaDefault
                                iosFilterPopup.prevContrast   = iosFilterPopup.contrastDefault
                                iosFilterPopup.prevSaturation = iosFilterPopup.saturationDefault
                                iosFilterPopup.prevExposure   = iosFilterPopup.exposureDefault
                                // ⭐ 使用后台配置的 linkDefault 值（而非硬编码）
                                iosFilterPopup.linkBrightness = iosFilterPopup.linkBrightnessDefault
                                iosFilterPopup.linkGamma      = iosFilterPopup.linkGammaDefault
                                iosFilterPopup.linkContrast   = iosFilterPopup.linkContrastDefault
                                iosFilterPopup.linkSaturation = iosFilterPopup.linkSaturationDefault
                                iosFilterPopup.linkExposure   = iosFilterPopup.linkExposureDefault
                                if (typeof ifMasterSlider     !== 'undefined') ifMasterSlider.value     = iosFilterPopup.brightnessDefault
                                if (typeof ifGammaSlider      !== 'undefined') ifGammaSlider.value      = iosFilterPopup.gammaDefault
                                if (typeof ifContrastSlider   !== 'undefined') ifContrastSlider.value   = iosFilterPopup.contrastDefault
                                if (typeof ifSaturationSlider !== 'undefined') ifSaturationSlider.value = iosFilterPopup.saturationDefault
                                if (typeof ifExposureSlider   !== 'undefined') ifExposureSlider.value   = iosFilterPopup.exposureDefault
                                // ⭐ 同时重置相机设定的"综合亮度"到中点 50（对应所有 iOS 滤镜值都在 default）
                                iosCameraSettingsPopup.exposureValue = 50
                                iosFilterPopup.pushAllStomp()
                            }
                        }
                    }

                    // 标题
                    Text {
                        anchors.centerIn: parent
                        text: "iOS 视频滤镜"
                        font.family: "PingFang HK"
                        font.pixelSize: 16
                        font.bold: true
                        color: "#263238"
                    }

                    // ✕ 关闭按钮
                    Rectangle {
                        anchors.right: parent.right
                        anchors.verticalCenter: parent.verticalCenter
                        width: 24
                        height: 24
                        radius: 12
                        color: filterCloseBtn.containsMouse ? "#C8E6C9" : "transparent"
                        Text {
                            anchors.centerIn: parent
                            text: "✕"
                            font.pixelSize: 14
                            color: "#546E7A"
                        }
                        MouseArea {
                            id: filterCloseBtn
                            anchors.fill: parent
                            hoverEnabled: true
                            cursorShape: Qt.PointingHandCursor
                            onClicked: iosFilterPopup.close()
                        }
                    }
                }

                // ===== 启用滤镜 永远 true, UI 不再显示 (用户需求) =====
                // ===== "GPU 后处理" 提示文字已移除 =====

                // ===== 综合亮度联动 提示文字 =====
                //   每行最前的 ☑ 复选框 = 是否加入"综合亮度联动组"
                //   勾选 ≥ 2 项时, 拖动其中任一会按各自 stepSize 同步驱动其他勾选项 (双向)
                Text {
                    Layout.fillWidth: true
                    horizontalAlignment: Text.AlignHCenter
                    text: "勾选 ☑ 加入'综合亮度联动'(双向): 拖任一勾选项, 其他勾选项按各自步进同步走"
                    font.family: "PingFang HK"
                    font.pixelSize: 12
                    color: "#90A4AE"
                    wrapMode: Text.WordWrap
                }

                // ===== 亮度滑块 (range 0.8/1.10/2.0, stepSize 0.02) =====
                RowLayout {
                    Layout.fillWidth: true
                    spacing: 8
                    CheckBox {
                        checked: iosFilterPopup.linkBrightness
                        onToggled: iosFilterPopup.linkBrightness = checked
                    }
                    Text { text: "亮度"; font.family: "PingFang HK"; font.pixelSize: 20; font.bold: true; color: "#E53935"; Layout.preferredWidth: 70 }
                    Slider {
                        id: ifMasterSlider
                        Layout.fillWidth: true
                        from: iosFilterPopup.brightnessFrom; to: iosFilterPopup.brightnessTo; stepSize: iosFilterPopup.brightnessStep
                        value: iosFilterPopup.fBrightness
                        onMoved: {
                            var delta = value - iosFilterPopup.prevBrightness
                            iosFilterPopup.prevBrightness = value
                            iosFilterPopup.fBrightness = value
                            iosFilterPopup.pushParam("brightness", value)
                            if (iosFilterPopup.linkBrightness) {
                                iosFilterPopup.applyLinkedDelta("brightness", delta / stepSize)
                            }
                        }
                        onPressedChanged: if (!pressed) iosFilterPopup.pushParam("brightness", value)
                        background: Rectangle {
                            x: ifMasterSlider.leftPadding
                            y: ifMasterSlider.topPadding + ifMasterSlider.availableHeight / 2 - height / 2
                            implicitWidth: 200; implicitHeight: 4
                            width: ifMasterSlider.availableWidth; height: 4
                            radius: 999; color: "#C8E6C9"
                            Rectangle {
                                width: ifMasterSlider.visualPosition * parent.width
                                height: parent.height; radius: 999; color: "#4DB6AC"
                            }
                        }
                        handle: Rectangle {
                            x: ifMasterSlider.leftPadding + ifMasterSlider.visualPosition * (ifMasterSlider.availableWidth - width)
                            y: ifMasterSlider.topPadding + ifMasterSlider.availableHeight / 2 - height / 2
                            implicitWidth: 14; implicitHeight: 14
                            width: 14; height: 14; radius: 7; color: "#4DB6AC"
                        }
                        WheelHandler {
                            onWheel: function(event) {
                                if (event.angleDelta.y === 0) return
                                var dir = event.angleDelta.y > 0 ? 1 : -1
                                var nv = iosFilterPopup.clampVal(ifMasterSlider.value + dir * ifMasterSlider.stepSize, ifMasterSlider.from, ifMasterSlider.to)
                                var delta = nv - iosFilterPopup.prevBrightness
                                ifMasterSlider.value = nv
                                iosFilterPopup.prevBrightness = nv
                                iosFilterPopup.fBrightness = nv
                                iosFilterPopup.pushParam("brightness", nv)
                                if (iosFilterPopup.linkBrightness) {
                                    iosFilterPopup.applyLinkedDelta("brightness", delta / ifMasterSlider.stepSize)
                                }
                            }
                        }
                    }
                    Text { text: iosFilterPopup.fBrightness.toFixed(2); font.family: "PingFang HK"; font.pixelSize: 16; color: "#263238"; Layout.preferredWidth: 50 }
                }

                // ===== 伽马滑块 (range 0.8/1.10/2.0, stepSize 0.01 ⭐ 比亮度细一倍) =====
                //   联动时双向 — 拖伽马也会驱动亮度等其他勾选项
                RowLayout {
                    Layout.fillWidth: true
                    spacing: 8
                    CheckBox {
                        checked: iosFilterPopup.linkGamma
                        onToggled: iosFilterPopup.linkGamma = checked
                    }
                    Text { text: "伽马"; font.family: "PingFang HK"; font.pixelSize: 20; font.bold: true; color: "#E53935"; Layout.preferredWidth: 70 }
                    Slider {
                        id: ifGammaSlider
                        Layout.fillWidth: true
                        from: iosFilterPopup.gammaFrom; to: iosFilterPopup.gammaTo; stepSize: iosFilterPopup.gammaStep
                        value: iosFilterPopup.fGamma
                        onMoved: {
                            var delta = value - iosFilterPopup.prevGamma
                            iosFilterPopup.prevGamma = value
                            iosFilterPopup.fGamma = value
                            iosFilterPopup.pushParam("gamma", value)
                            if (iosFilterPopup.linkGamma) {
                                iosFilterPopup.applyLinkedDelta("gamma", delta / stepSize)
                            }
                        }
                        onPressedChanged: if (!pressed) iosFilterPopup.pushParam("gamma", value)
                        background: Rectangle {
                            x: ifGammaSlider.leftPadding
                            y: ifGammaSlider.topPadding + ifGammaSlider.availableHeight / 2 - height / 2
                            implicitWidth: 200; implicitHeight: 4
                            width: ifGammaSlider.availableWidth; height: 4
                            radius: 999; color: "#C8E6C9"
                            Rectangle {
                                width: ifGammaSlider.visualPosition * parent.width
                                height: parent.height; radius: 999; color: "#4DB6AC"
                            }
                        }
                        handle: Rectangle {
                            x: ifGammaSlider.leftPadding + ifGammaSlider.visualPosition * (ifGammaSlider.availableWidth - width)
                            y: ifGammaSlider.topPadding + ifGammaSlider.availableHeight / 2 - height / 2
                            implicitWidth: 14; implicitHeight: 14
                            width: 14; height: 14; radius: 7; color: "#4DB6AC"
                        }
                        WheelHandler {
                            onWheel: function(event) {
                                if (event.angleDelta.y === 0) return
                                var dir = event.angleDelta.y > 0 ? 1 : -1
                                var nv = iosFilterPopup.clampVal(ifGammaSlider.value + dir * ifGammaSlider.stepSize, ifGammaSlider.from, ifGammaSlider.to)
                                var delta = nv - iosFilterPopup.prevGamma
                                ifGammaSlider.value = nv
                                iosFilterPopup.prevGamma = nv
                                iosFilterPopup.fGamma = nv
                                iosFilterPopup.pushParam("gamma", nv)
                                if (iosFilterPopup.linkGamma) {
                                    iosFilterPopup.applyLinkedDelta("gamma", delta / ifGammaSlider.stepSize)
                                }
                            }
                        }
                    }
                    Text { text: iosFilterPopup.fGamma.toFixed(2); font.family: "PingFang HK"; font.pixelSize: 16; color: "#263238"; Layout.preferredWidth: 50 }
                }

                // ===== 对比度滑块 (0.8/1.10/1.30, stepSize 0.02) =====
                RowLayout {
                    Layout.fillWidth: true
                    spacing: 8
                    CheckBox {
                        checked: iosFilterPopup.linkContrast
                        onToggled: iosFilterPopup.linkContrast = checked
                    }
                    Text { text: "对比度"; font.family: "PingFang HK"; font.pixelSize: 20; font.bold: true; color: "#E53935"; Layout.preferredWidth: 70 }
                    Slider {
                        id: ifContrastSlider
                        Layout.fillWidth: true
                        from: iosFilterPopup.contrastFrom; to: iosFilterPopup.contrastTo; stepSize: iosFilterPopup.contrastStep
                        value: iosFilterPopup.fContrast
                        onMoved: {
                            var delta = value - iosFilterPopup.prevContrast
                            iosFilterPopup.prevContrast = value
                            iosFilterPopup.fContrast = value
                            iosFilterPopup.pushParam("contrast", value)
                            if (iosFilterPopup.linkContrast) {
                                iosFilterPopup.applyLinkedDelta("contrast", delta / stepSize)
                            }
                        }
                        onPressedChanged: if (!pressed) iosFilterPopup.pushParam("contrast", value)
                        background: Rectangle {
                            x: ifContrastSlider.leftPadding
                            y: ifContrastSlider.topPadding + ifContrastSlider.availableHeight / 2 - height / 2
                            implicitWidth: 200; implicitHeight: 4
                            width: ifContrastSlider.availableWidth; height: 4
                            radius: 999; color: "#C8E6C9"
                            Rectangle {
                                width: ifContrastSlider.visualPosition * parent.width
                                height: parent.height; radius: 999; color: "#4DB6AC"
                            }
                        }
                        handle: Rectangle {
                            x: ifContrastSlider.leftPadding + ifContrastSlider.visualPosition * (ifContrastSlider.availableWidth - width)
                            y: ifContrastSlider.topPadding + ifContrastSlider.availableHeight / 2 - height / 2
                            implicitWidth: 14; implicitHeight: 14
                            width: 14; height: 14; radius: 7; color: "#4DB6AC"
                        }
                        WheelHandler {
                            onWheel: function(event) {
                                if (event.angleDelta.y === 0) return
                                var dir = event.angleDelta.y > 0 ? 1 : -1
                                var nv = iosFilterPopup.clampVal(ifContrastSlider.value + dir * ifContrastSlider.stepSize, ifContrastSlider.from, ifContrastSlider.to)
                                var delta = nv - iosFilterPopup.prevContrast
                                ifContrastSlider.value = nv
                                iosFilterPopup.prevContrast = nv
                                iosFilterPopup.fContrast = nv
                                iosFilterPopup.pushParam("contrast", nv)
                                if (iosFilterPopup.linkContrast) {
                                    iosFilterPopup.applyLinkedDelta("contrast", delta / ifContrastSlider.stepSize)
                                }
                            }
                        }
                    }
                    Text { text: iosFilterPopup.fContrast.toFixed(2); font.family: "PingFang HK"; font.pixelSize: 16; color: "#263238"; Layout.preferredWidth: 50 }
                }

                // ===== 红外模式 (饱和度) (0.0/1.10/2.0, stepSize 0.02, 0=黑白) =====
                RowLayout {
                    Layout.fillWidth: true
                    spacing: 8
                    CheckBox {
                        checked: iosFilterPopup.linkSaturation
                        onToggled: iosFilterPopup.linkSaturation = checked
                    }
                    Text { text: "红外模式"; font.family: "PingFang HK"; font.pixelSize: 20; font.bold: true; color: "#E53935"; Layout.preferredWidth: 90 }
                    Slider {
                        id: ifSaturationSlider
                        Layout.fillWidth: true
                        from: iosFilterPopup.saturationFrom; to: iosFilterPopup.saturationTo; stepSize: iosFilterPopup.saturationStep
                        value: iosFilterPopup.fSaturation
                        onMoved: {
                            var delta = value - iosFilterPopup.prevSaturation
                            iosFilterPopup.prevSaturation = value
                            iosFilterPopup.fSaturation = value
                            iosFilterPopup.pushParam("saturation", value)
                            if (iosFilterPopup.linkSaturation) {
                                iosFilterPopup.applyLinkedDelta("saturation", delta / stepSize)
                            }
                        }
                        onPressedChanged: if (!pressed) iosFilterPopup.pushParam("saturation", value)
                        background: Rectangle {
                            x: ifSaturationSlider.leftPadding
                            y: ifSaturationSlider.topPadding + ifSaturationSlider.availableHeight / 2 - height / 2
                            implicitWidth: 200; implicitHeight: 4
                            width: ifSaturationSlider.availableWidth; height: 4
                            radius: 999; color: "#C8E6C9"
                            Rectangle {
                                width: ifSaturationSlider.visualPosition * parent.width
                                height: parent.height; radius: 999; color: "#4DB6AC"
                            }
                        }
                        handle: Rectangle {
                            x: ifSaturationSlider.leftPadding + ifSaturationSlider.visualPosition * (ifSaturationSlider.availableWidth - width)
                            y: ifSaturationSlider.topPadding + ifSaturationSlider.availableHeight / 2 - height / 2
                            implicitWidth: 14; implicitHeight: 14
                            width: 14; height: 14; radius: 7; color: "#4DB6AC"
                        }
                        WheelHandler {
                            onWheel: function(event) {
                                if (event.angleDelta.y === 0) return
                                var dir = event.angleDelta.y > 0 ? 1 : -1
                                var nv = iosFilterPopup.clampVal(ifSaturationSlider.value + dir * ifSaturationSlider.stepSize, ifSaturationSlider.from, ifSaturationSlider.to)
                                var delta = nv - iosFilterPopup.prevSaturation
                                ifSaturationSlider.value = nv
                                iosFilterPopup.prevSaturation = nv
                                iosFilterPopup.fSaturation = nv
                                iosFilterPopup.pushParam("saturation", nv)
                                if (iosFilterPopup.linkSaturation) {
                                    iosFilterPopup.applyLinkedDelta("saturation", delta / ifSaturationSlider.stepSize)
                                }
                            }
                        }
                    }
                    Text { text: iosFilterPopup.fSaturation.toFixed(2); font.family: "PingFang HK"; font.pixelSize: 16; color: "#263238"; Layout.preferredWidth: 50 }
                }

                // ===== 曝光度滑块 (0.6/1.10/1.6, stepSize 0.02, PC 端线性倍数, 发送 log2) =====
                RowLayout {
                    Layout.fillWidth: true
                    spacing: 8
                    CheckBox {
                        checked: iosFilterPopup.linkExposure
                        onToggled: iosFilterPopup.linkExposure = checked
                    }
                    Text { text: "曝光度"; font.family: "PingFang HK"; font.pixelSize: 20; font.bold: true; color: "#E53935"; Layout.preferredWidth: 70 }
                    Slider {
                        id: ifExposureSlider
                        Layout.fillWidth: true
                        from: iosFilterPopup.exposureFrom; to: iosFilterPopup.exposureTo; stepSize: iosFilterPopup.exposureStep
                        value: iosFilterPopup.fExposure
                        onMoved: {
                            var delta = value - iosFilterPopup.prevExposure
                            iosFilterPopup.prevExposure = value
                            iosFilterPopup.fExposure = value
                            iosFilterPopup.pushParam("exposure", Math.log2(value))
                            if (iosFilterPopup.linkExposure) {
                                iosFilterPopup.applyLinkedDelta("exposure", delta / stepSize)
                            }
                        }
                        onPressedChanged: if (!pressed) iosFilterPopup.pushParam("exposure", Math.log2(value))
                        background: Rectangle {
                            x: ifExposureSlider.leftPadding
                            y: ifExposureSlider.topPadding + ifExposureSlider.availableHeight / 2 - height / 2
                            implicitWidth: 200; implicitHeight: 4
                            width: ifExposureSlider.availableWidth; height: 4
                            radius: 999; color: "#C8E6C9"
                            Rectangle {
                                width: ifExposureSlider.visualPosition * parent.width
                                height: parent.height; radius: 999; color: "#4DB6AC"
                            }
                        }
                        handle: Rectangle {
                            x: ifExposureSlider.leftPadding + ifExposureSlider.visualPosition * (ifExposureSlider.availableWidth - width)
                            y: ifExposureSlider.topPadding + ifExposureSlider.availableHeight / 2 - height / 2
                            implicitWidth: 14; implicitHeight: 14
                            width: 14; height: 14; radius: 7; color: "#4DB6AC"
                        }
                        WheelHandler {
                            onWheel: function(event) {
                                if (event.angleDelta.y === 0) return
                                var dir = event.angleDelta.y > 0 ? 1 : -1
                                var nv = iosFilterPopup.clampVal(ifExposureSlider.value + dir * ifExposureSlider.stepSize, ifExposureSlider.from, ifExposureSlider.to)
                                var delta = nv - iosFilterPopup.prevExposure
                                ifExposureSlider.value = nv
                                iosFilterPopup.prevExposure = nv
                                iosFilterPopup.fExposure = nv
                                iosFilterPopup.pushParam("exposure", Math.log2(nv))
                                if (iosFilterPopup.linkExposure) {
                                    iosFilterPopup.applyLinkedDelta("exposure", delta / ifExposureSlider.stepSize)
                                }
                            }
                        }
                    }
                    Text { text: iosFilterPopup.fExposure.toFixed(2); font.family: "PingFang HK"; font.pixelSize: 16; color: "#263238"; Layout.preferredWidth: 50 }
                }

                // ===== 红色增强已锁死 0.02 (无滑块, 启动时由 pushAllStomp 推) =====

                // 底部留空 (正式后端没有 IosFilterController, "保存为系统默认"按钮已去掉)
                Item { Layout.fillWidth: true; Layout.fillHeight: true }
            }
        }
    }
}

