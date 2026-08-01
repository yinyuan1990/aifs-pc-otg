pragma Singleton
import QtQuick

// ⭐ 第五十章：设备相机能力仓库（OTG 自适配的唯一数据源）
//
// 为什么单独一个文件：MainPage.qml 已 800KB+，新东西一律不许再往里塞。
// 这里只负责「存 + 解析 + 判断」，不画任何界面；OtgCameraPanel.qml 按它渲染，
// MainPage.qml 只读 isOtg / needsCaps 两个标志做最小接线。
//
// 数据来自 Android（第五十章 Android 侧）：
//   · CONFIG_STATE.state.cameraMode / otgCapsVersion —— 每秒心跳常带，几个字节
//   · OTG_CAPS.caps —— 完整能力快照，设备开流枚举完主动推一次，PC 也可发 otg_get_caps 索要
QtObject {
    id: store

    // ===== 对外状态 =====
    property bool   isOtg: false          // 当前连接的设备是不是外接 OTG 摄像头
    property bool   capsReady: false      // 是否已拿到完整能力快照
    property string deviceName: ""        // UVC 设备名（如 "USB 2.0 Camera"）
    property int    curWidth: 0           // 设备当前协商的分辨率
    property int    curHeight: 0
    property real   version: 0            // 能力版本号（Android 侧枚举时间戳）

    // 档位列表：[{width,height,maxFps,maxKbps,encodable}, ...] —— 设备枚举出几档就是几档，
    // 不是自带摄像头那套固定 5 档。
    property var sizes: []
    // 硬件可调项：[{key,label,type,supported,cur,min,max,options}, ...]
    property var controls: []

    // 设备当前生效的推送帧率 / 码率百分比（真值来自设备，面板别自己猜缺省）
    property int devicePushFps: 0
    property int deviceBitratePct: 0

    // 当前实际采集格式（"MJPEG"/"YUYV"）——让"选了格式没生效"看得见
    property string activeFormat: ""

    // 手机当前的热控推流上限（0=无限制）。"fps 拖了没反应"十有八九是它摁的
    property int deviceThermalCap: 0

    // 已向设备索要过能力快照的版本（防重复索要）
    property real requestedVersion: -1

    signal capsUpdated()

    // ===== 查询 =====

    // 该硬件项是否可用（PC 面板只渲染 true 的，不渲染成灰按钮）
    function supports(key) {
        for (var i = 0; i < controls.length; i++)
            if (controls[i].key === key && controls[i].supported) return true
        return false
    }

    function control(key) {
        for (var i = 0; i < controls.length; i++)
            if (controls[i].key === key) return controls[i]
        return null
    }

    // 支持的硬件项（面板 Repeater 的 model）
    function supportedControls() {
        var out = []
        for (var i = 0; i < controls.length; i++)
            if (controls[i].supported) out.push(controls[i])
        return out
    }

    // 可用档位 = 硬件编码器吃得下的那些。
    // 编码器吃不下的尺寸（如 HEVC 下的 160x120）采集完全正常但编码器一帧不出，
    // 选了必黑，所以直接不给选，而不是让用户点一个黑屏档位。
    function usableSizes() {
        var out = []
        for (var i = 0; i < sizes.length; i++)
            if (sizes[i].encodable !== false) out.push(sizes[i])
        return out
    }

    function blockedSizeCount() {
        return sizes.length - usableSizes().length
    }

    // 当前分辨率这一档的 fps 上限。
    // 设备声明了就用声明值；没声明的（不少 UVC 设备/库版本都不填）设备会用**实测值**回填，
    // 两者都没有才退回 30 兜底。
    function maxFpsOfCurrentSize() {
        for (var i = 0; i < sizes.length; i++)
            if (sizes[i].width === curWidth && sizes[i].height === curHeight)
                return sizes[i].maxFps > 0 ? sizes[i].maxFps : 30
        return 30
    }

    // 这一档的 fps 是设备声明/实测出来的真值，还是兜底猜的 30
    function fpsIsKnown() {
        for (var i = 0; i < sizes.length; i++)
            if (sizes[i].width === curWidth && sizes[i].height === curHeight)
                return sizes[i].maxFps > 0
        return false
    }

    // 推流 fps 的真实上限 = 手机编码器在当前尺寸下能编多快（Android 用 MediaCodec 查出来随快照上报）。
    // 以前写死 60——那是自带摄像头 ladder 的拍脑袋值；查不到时兜底 120。
    function pushFpsCapOfCurrentSize() {
        for (var i = 0; i < sizes.length; i++)
            if (sizes[i].width === curWidth && sizes[i].height === curHeight)
                return sizes[i].encMaxFps > 0 ? sizes[i].encMaxFps : 120
        return 120
    }

    function sizeLabel(s) {
        return s.maxFps > 0 ? (s.width + "x" + s.height + "@" + s.maxFps)
                            : (s.width + "x" + s.height)
    }

    // 当前分辨率这一档的码率上限（Android 侧 OtgBitratePlan 按像素率等比算好、随能力上报，
    // PC 不重复实现公式，只负责显示）
    function maxKbpsOfCurrentSize() {
        for (var i = 0; i < sizes.length; i++)
            if (sizes[i].width === curWidth && sizes[i].height === curHeight)
                return sizes[i].maxKbps > 0 ? sizes[i].maxKbps : 0
        return 0
    }

    // ===== 消息入口（MainPage 的消息分发里各调一行）=====

    // CONFIG_STATE 心跳：只看 cameraMode 与 capsVersion。
    // @return true = 需要向设备发 otg_get_caps 拉一次完整能力
    function onConfigState(state) {
        if (!state) return false
        var mode = state.cameraMode || "builtin"
        var otg = (mode === "otg")
        if (otg !== isOtg) {
            isOtg = otg
            if (!otg) clear()          // 切回自带摄像头：能力作废
            console.log("📷 [能力] 摄像头模式 → " + mode)
        }
        if (!otg) return false

        var v = state.otgCapsVersion || 0
        if (v <= 0) return false                  // 设备还没开流枚举出能力
        if (v === version && capsReady) return false   // 已是最新
        if (v === requestedVersion) return false       // 已经要过这一版，等它推过来
        requestedVersion = v
        console.log("📷 [能力] 检测到新能力版本 " + v + "（当前 " + version + "）→ 索要完整快照")
        return true
    }

    // OTG_CAPS 完整快照
    function applyCaps(caps) {
        if (!caps) return
        isOtg = true
        deviceName = caps.deviceName || ""
        curWidth   = caps.width  || 0
        curHeight  = caps.height || 0
        version    = caps.version || 0
        sizes      = caps.sizes    || []
        controls   = caps.controls || []
        devicePushFps    = caps.pushFps    || 0
        deviceBitratePct = caps.bitratePct || 0
        activeFormat     = caps.format     || ""
        deviceThermalCap = caps.thermalCapFps || 0
        capsReady  = sizes.length > 0 || controls.length > 0
        requestedVersion = version
        console.log("📷 [能力] 已接收 OTG 能力快照: " + deviceName
                    + " 当前" + curWidth + "x" + curHeight
                    + " 档位" + sizes.length + "个"
                    + " 可调项" + supportedControls().length + "项 ver=" + version)
        capsUpdated()
    }

    // 本地记下某个硬件项的新值（面板拖完滑条即时回写，免得重开面板弹回旧值）
    function setLocalValue(key, value) {
        var arr = controls
        for (var i = 0; i < arr.length; i++) {
            if (arr[i].key === key) {
                arr[i].cur = value
                controls = arr      // 触发绑定刷新
                return
            }
        }
    }

    function setLocalSize(w, h) {
        curWidth = w
        curHeight = h
    }

    // 切换账号 / 切设备 / 退出登录：能力跟着设备走，一律清掉重新拉
    function clear() {
        isOtg = false
        capsReady = false
        deviceName = ""
        curWidth = 0
        curHeight = 0
        version = 0
        requestedVersion = -1
        sizes = []
        controls = []
        console.log("📷 [能力] 已清空（切换账号/设备）")
    }
}
