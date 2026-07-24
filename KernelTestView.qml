import QtQuick
import QtWebEngine
import QtWebChannel

// ⭐ 内核测试视图：用 Qt WebEngine（Chromium 内核）加载 webplayer_test.html。
//   SRS 走 WHEP；P2P 通过 QWebChannel 把 kernelBridge（C++ P2P 信令）暴露给 JS。
//   单独成文件，把 QtWebEngine/QtWebChannel 依赖隔离，未装时不影响 MainPage。
Item {
    id: root

    // ⭐ 作为 Loader 的根项：显式铺满父节点。Loader 默认只在「根项无尺寸」时才把它
    //   resize 到 Loader 大小，一旦内部任何绑定/时序让根项拿到非零尺寸，自动 resize 就会
    //   失效，导致 WebEngineView 不铺满父容器（用户现象：网页没填满父节点）。这里直接
    //   anchors.fill 父项，杜绝时序竞态，始终占满 videoContainer。
    anchors.fill: parent

    property string testMode: "srs"     // "srs" | "p2p" | "srt"
    property string pendingHost: ""
    property string pendingApp: "tenantA"
    property string pendingStream: ""
    property string pendingVhost: "vid-7gg4748"
    property bool pageReady: false

    // ⭐ 顶部留白：作为「对比浮窗」时给标题栏留 34px；作为主播放器全屏时设 0。
    property int topInset: 34

    // ⭐ H265（第四十九章）：会话编码 "h264"/"h265"。SRS 拉流时传给页面，
    //   startSRS 据此在 play API 上追加 ?codec=hevc（SRS 6.0 的 RTC H265 协商开关）。
    property string pendingCodec: "h264"

    // SRS 用：startTest(mode, host, app, stream, vhost, codec)
    // SRT（网页内核不支持）：startTest("srt") → 显示提示、不播
    function startTest(mode, host, app, stream, vhost, codec) {
        testMode = mode || "srs"
        pendingHost = host || ""
        pendingApp = app || "tenantA"
        pendingStream = stream || ""
        pendingVhost = vhost || "vid-7gg4748"
        pendingCodec = codec || "h264"
        if (pageReady) _injectAndPlay()
    }

    function stopTest() {
        if (pageReady) webView.runJavaScript("if (window.stopPlay) window.stopPlay();")
    }

    // ⭐ Android 本地滤镜（网页内核 sink）：把亮度/对比度/饱和度 CSS 乘数注入页面 <video>。
    //   iOS 不调用此函数（iOS 走设备端 STOMP 滤镜）。runJavaScript 异步，CSS filter 由
    //   Chromium 合成器处理，不卡 Qt/JS 主线程。参数 1.0=中性。
    function applyColorFilter(brightness, contrast, saturate) {
        if (!pageReady) return
        var js = "if (window.applyColorFilter) window.applyColorFilter(" +
                 (brightness || 1.0) + ", " + (contrast || 1.0) + ", " + (saturate || 1.0) + ");"
        webView.runJavaScript(js)
    }

    // ⭐ 本地画面变换（缩放/镜像/旋转/偏移）转发给页面 JS（CSS transform）。
    //   网页内核作主播放器时，MainPage 的 videoZoom/videoMirrorMode/videoRotation/offset 变化调用。
    function applyTransform(zoom, mirror, rotation, offsetX, offsetY) {
        if (!pageReady) return
        var js = "if (window.applyTransform) window.applyTransform(" +
                 (zoom || 1.0) + ", '" + (mirror || "none") + "', " +
                 (rotation || 0) + ", " + (offsetX || 0) + ", " + (offsetY || 0) + ");"
        webView.runJavaScript(js)
    }

    // ⭐ 2026-07-15：鼠标进入/移出实时流画面 → 转发给页面，控制右上角「信息」开关按钮的显隐
    //   （按钮本身只是入口，点击按钮才真正展开统计面板，逻辑在页面 JS 里，这里只管按钮显隐）。
    //   根因：MainPage 里 livePanelHover（z:1000，铺满整个实时流面板，用于驱动底部控制栏的 hover
    //   显隐）盖在 WebEngineView 之上，会拦掉真正的鼠标移动(hover)事件，导致页面收不到
    //   mousemove/mouseenter，只有点击才能透传下来。QML 端 livePanelHover 本身能可靠拿到 hover
    //   状态，这里直接把它转发给页面即可，不用等页面自己收事件。
    function setStatsHover(visible) {
        if (!pageReady) return
        webView.runJavaScript("if (window.setStatsHoverFromQml) window.setStatsHoverFromQml(" + (visible ? "true" : "false") + ");")
    }

    function _injectAndPlay() {
        var js
        if (testMode === "p2p") {
            js = "if (window.showSrtUnsupported) window.showSrtUnsupported(false);" +
                 "if (window.startPlay) window.startPlay({mode:'p2p'});"
        } else if (testMode === "srt") {
            // 网页内核无法播 SRT：显示提示、不发起播放
            js = "if (window.showSrtUnsupported) window.showSrtUnsupported(true);"
        } else {
            js = "if (window.showSrtUnsupported) window.showSrtUnsupported(false);" +
                 "if (window.startPlay) window.startPlay({" +
                 "mode:'srs', host:'" + pendingHost + "', app:'" + pendingApp +
                 "', stream:'" + pendingStream + "', vhost:'" + pendingVhost +
                 "', codec:'" + pendingCodec + "'});"
        }
        webView.runJavaScript(js)
        // 🔥 2026-07-02: 卡顿根因已定位（发送端周期 IDR 攒帧，见手册第二十一章），
        //    删除第二十章的临时"播放即自动 A/B 诊断" Timer；需要时仍可手动 window.setAbDiag(true)。
    }

    // ⭐ QWebChannel：把 C++ 的 kernelBridge 暴露给页面 JS（P2P 信令）。
    //   kernelBridge 是 main.cpp 在 HAVE_WEBENGINE 时注册的 context property；
    //   本文件仅 HAVE_KERNEL_TEST 时编译，故此处 kernelBridge 必然存在，可直接引用。
    //
    //   ⚠ 关键：QML 的 WebChannel.registeredObjects 要求每个对象都设了【WebChannel.id 附加属性】，
    //   它【不会】回退用 objectName！kernelBridge 是 C++ context property，无法在 QML 里给它挂
    //   WebChannel.id，所以放进 registeredObjects 会以空 id 注册 → JS 侧 channel.objects.kernelBridge
    //   永远是 undefined（实测自检：transport=true 但 channel.objects 里无 kernelBridge）。
    //   正解：用命令式 registerObject("kernelBridge", kernelBridge) 显式指定 JS 侧标识，
    //   在 Component.onCompleted（webChannel 已建立、url 尚未加载前）注册。
    WebChannel {
        id: kernelChannel
        Component.onCompleted: kernelChannel.registerObject("kernelBridge", kernelBridge)
    }

    WebEngineView {
        id: webView
        anchors.fill: parent
        anchors.topMargin: root.topInset
        // ⭐ 关键：webChannel 必须在 url 加载“之前”绑定，否则 Qt 不会把 qwebchannel.js 的
        //   传输脚本（qt.webChannelTransport）注入到页面，JS 侧就会一直报「transport 未注入 /
        //   QWebChannel 未就绪」。QML 同一对象内属性按声明顺序赋值，若 url 写在 webChannel 之前，
        //   会先触发导航再绑定 channel → 注入丢失。这里改为：先绑 webChannel，url 留空，
        //   等 Component.onCompleted 里确认 channel 已就绪后再赋 url，杜绝时序竞态。
        webChannel: kernelChannel

        settings.playbackRequiresUserGesture: false
        settings.javascriptEnabled: true
        settings.localContentCanAccessRemoteUrls: true
        settings.allowRunningInsecureContent: true
        settings.screenCaptureEnabled: true

        // ⭐⭐ 真正的根因修复：Qt6 QML WebEngineView 仅设 webChannel 并不可靠地把
        //   qwebchannel.js + qt.webChannelTransport 注入到页面主世界（MainWorld），
        //   常见表现就是 JS 里 typeof QWebChannel==='undefined' / qt.webChannelTransport 缺失，
        //   于是一直报「QWebChannel 未就绪」。官方/社区一致做法：用 WebEngineScript 在
        //   DocumentCreation + MainWorld 显式注入 qwebchannel.js（Qt6.10 用 userScripts.collection
        //   赋值 JS 字典）。注入完成后再赋 url 发起导航，确保页面脚本运行时 QWebChannel 必然就绪。
        Component.onCompleted: {
            webView.userScripts.collection = [{
                name: "QWebChannelTransport",
                sourceUrl: "qrc:///qtwebchannel/qwebchannel.js",
                injectionPoint: WebEngineScript.DocumentCreation,
                worldId: WebEngineScript.MainWorld
            }]
            webView.url = "qrc:/qt/qml/Aifs/webplayer_test.html"
        }

        onLoadingChanged: function(loadRequest) {
            if (loadRequest.status === WebEngineView.LoadSucceededStatus) {
                root.pageReady = true
                // ⭐ 运行期自检：确定到底哪一环断了（脚本是否加载 / transport 是否注入 / bridge 是否可见）。
                //   结果回传 C++ 日志，排障时不用猜。
                webView.runJavaScript(
                    "JSON.stringify({" +
                    "  qwebchannelJs: (typeof QWebChannel !== 'undefined')," +
                    "  transport: (typeof qt !== 'undefined' && !!qt.webChannelTransport)," +
                    "  href: location.href" +
                    "})",
                    function(r) { console.log("[KernelTest 自检] " + r) }
                )
                _injectAndPlay()
            }
        }
        onJavaScriptConsoleMessage: function(level, message, lineNumber, sourceID) {
            console.log("[KernelTest JS] " + message)
        }
    }
}
