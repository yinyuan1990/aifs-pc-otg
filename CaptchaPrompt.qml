import QtQuick
import QtQuick.Controls
import Aifs.Components 1.0

// 登录图形验证码（登录页 / 主界面共用）。ask(说明, 回调(captchaId, track), 取消回调)：领一张图，按后端给的 type 显示：
//   WORD_IMAGE_CLICK 文字点选：按提示依次点大图里的字 → 确定；坐标按界面显示尺寸提交（imgW × imgH），后端按百分比比对
//   SLIDER 滑块：拖下面的滑块把拼图块移到缺口，松手就提交；trackList 记 down / move / up 的 x（拼图块左边移了多远）
//   MATH 数字计算：填图里算式的结果 → 确定；track = { answer }
Popup {
    id: root
    anchors.centerIn: Overlay.overlay
    width: 380
    height: 440
    modal: true
    focus: true
    closePolicy: Popup.CloseOnEscape
    padding: 0

    readonly property int imgW: 340
    readonly property int imgH: 204
    property string message: ""
    property string errorText: ""
    property var callback: null
    property var cancelCallback: null
    property string captchaId: ""
    property string ctype: ""
    property bool loading: false
    property var clicks: []
    property double startTime: 0
    // 滑块：拼图块（和背景同高的竖条）显示宽度、当前拖动距离、轨迹
    property real pieceW: 0
    property real slideX: 0
    property var slideTrack: []
    property bool sliding: false

    function ask(msg, cb, onCancel) {
        message = msg || ""
        callback = cb
        cancelCallback = onCancel || null
        open()
        refresh()
    }

    function refresh() {
        clicks = []
        slideTrack = []
        slideX = 0
        sliding = false
        captchaId = ""
        ctype = ""
        errorText = ""
        answerField.text = ""
        bgImage.source = ""
        tplImage.source = ""
        pieceImage.source = ""
        mathImage.source = ""
        loading = true
        HttpClient.fetchCaptcha()
    }

    function finish(track) {
        var cb = callback
        var id = captchaId
        callback = null
        cancelCallback = null
        close()
        if (cb) cb(id, track)
    }

    function submit() {
        if (captchaId === "") return
        if (ctype === "MATH") {
            var a = answerField.text.trim()
            if (a === "") {
                errorText = "请输入算式的结果"
                return
            }
            finish({ answer: a })
            return
        }
        if (clicks.length === 0) {
            errorText = "请按上面的顺序依次点击图中的文字"
            return
        }
        var list = []
        for (var i = 0; i < clicks.length; i++) {
            list.push({ x: clicks[i].x, y: clicks[i].y, t: clicks[i].t, type: "click" })
        }
        finish({ bgImageWidth: imgW, bgImageHeight: imgH,
                 startTime: startTime, stopTime: Date.now(), trackList: list })
    }

    function slidePoint(type, y) {
        slideTrack = slideTrack.concat([{ x: Math.round(slideX), y: Math.round(y), t: Date.now() - startTime, type: type }])
    }

    function submitSlide() {
        if (captchaId === "" || slideTrack.length < 2) return
        finish({ bgImageWidth: imgW, bgImageHeight: imgH,
                 templateImageWidth: Math.round(pieceW), templateImageHeight: imgH,
                 startTime: startTime, stopTime: Date.now(), trackList: slideTrack })
    }

    onClosed: {
        var c = cancelCallback
        cancelCallback = null
        callback = null
        if (c) c()
    }

    Connections {
        target: HttpClient
        enabled: root.visible
        function onCaptchaReady(c) {
            root.loading = false
            root.captchaId = c.captchaId || ""
            root.ctype = c.type || "WORD_IMAGE_CLICK"
            if (root.ctype === "MATH") {
                mathImage.source = c.backgroundImage || ""
                answerField.forceActiveFocus()
            } else if (root.ctype === "SLIDER") {
                bgImage.source = c.backgroundImage || ""
                pieceImage.source = c.templateImage || ""
                var bw = c.backgroundImageWidth || 600
                root.pieceW = (c.templateImageWidth || 110) * root.imgW / bw
            } else {
                bgImage.source = c.backgroundImage || ""
                tplImage.source = c.templateImage || ""
            }
            root.startTime = Date.now()
        }
        function onCaptchaFetchFailed(msg) {
            root.loading = false
            root.errorText = msg
        }
    }

    background: Rectangle {
        color: "#FFFFFF"
        radius: 8
        border.color: "#A5D6A7"
        border.width: 1
    }

    contentItem: Column {
        padding: 20
        spacing: 10

        Text {
            text: "安全验证"
            font.family: "PingFang HK"
            font.pixelSize: 16
            font.bold: true
            color: "#263238"
        }
        Text {
            width: root.width - 40
            text: root.errorText.length > 0 ? root.errorText : root.message
            visible: text.length > 0
            wrapMode: Text.WordWrap
            font.family: "PingFang HK"
            font.pixelSize: 12
            color: "#E53935"
        }
        Row {
            spacing: 8
            height: 34
            Text {
                anchors.verticalCenter: parent.verticalCenter
                text: root.ctype === "SLIDER" ? "拖动下方滑块，把拼图移到缺口处"
                    : root.ctype === "MATH" ? "请输入图中算式的结果" : "请依次点击："
                font.family: "PingFang HK"
                font.pixelSize: 13
                color: "#455A64"
            }
            Image {
                id: tplImage
                visible: root.ctype === "WORD_IMAGE_CLICK"
                height: 34
                fillMode: Image.PreserveAspectFit
                asynchronous: false
                cache: false
            }
        }

        // 文字点选 / 滑块的大图
        Rectangle {
            width: root.imgW
            height: root.imgH
            visible: root.ctype !== "MATH"
            color: "#ECEFF1"
            border.color: "#CFD8DC"
            clip: true
            Image {
                id: bgImage
                anchors.fill: parent
                fillMode: Image.Stretch
                cache: false
            }
            Image {
                id: pieceImage
                visible: root.ctype === "SLIDER"
                x: root.slideX
                y: 0
                width: root.pieceW
                height: root.imgH
                fillMode: Image.Stretch
                cache: false
            }
            Text {
                anchors.centerIn: parent
                visible: root.loading
                text: "加载中…"
                color: "#607D8B"
            }
            MouseArea {
                anchors.fill: parent
                enabled: root.ctype === "WORD_IMAGE_CLICK" && root.captchaId !== "" && !root.loading
                cursorShape: Qt.PointingHandCursor
                onClicked: function(mouse) {
                    root.errorText = ""
                    root.clicks = root.clicks.concat([{ x: mouse.x, y: mouse.y, t: Date.now() - root.startTime }])
                }
            }
            Repeater {
                model: root.ctype === "WORD_IMAGE_CLICK" ? root.clicks.length : 0
                Rectangle {
                    x: root.clicks[index].x - 11
                    y: root.clicks[index].y - 11
                    width: 22
                    height: 22
                    radius: 11
                    color: "#1E88E5"
                    border.color: "#FFFFFF"
                    border.width: 2
                    Text {
                        anchors.centerIn: parent
                        text: index + 1
                        color: "#FFFFFF"
                        font.pixelSize: 12
                        font.bold: true
                    }
                }
            }
        }

        // 滑块条
        Rectangle {
            id: slideBar
            visible: root.ctype === "SLIDER"
            width: root.imgW
            height: 40
            radius: 20
            color: "#ECEFF1"
            border.color: "#CFD8DC"
            Text {
                anchors.centerIn: parent
                visible: !root.sliding && root.slideX === 0
                text: "按住滑块向右拖动 →"
                font.family: "PingFang HK"
                font.pixelSize: 12
                color: "#90A4AE"
            }
            Rectangle {
                width: root.slideX + handle.width
                height: parent.height
                radius: 20
                color: "#C8E6C9"
                visible: root.slideX > 0
            }
            Rectangle {
                id: handle
                x: root.slideX
                width: 52
                height: parent.height
                radius: 20
                color: root.sliding ? "#43A047" : "#66BB6A"
                Text {
                    anchors.centerIn: parent
                    text: "→"
                    color: "#FFFFFF"
                    font.pixelSize: 20
                    font.bold: true
                }
                MouseArea {
                    anchors.fill: parent
                    enabled: root.ctype === "SLIDER" && root.captchaId !== "" && !root.loading
                    cursorShape: Qt.OpenHandCursor
                    property real pressX: 0
                    onPressed: function(mouse) {
                        root.errorText = ""
                        root.sliding = true
                        root.slideTrack = []
                        pressX = mouse.x
                        root.slideX = 0
                        root.slidePoint("down", mouse.y)
                    }
                    onPositionChanged: function(mouse) {
                        if (!root.sliding) return
                        var nx = handle.x + mouse.x - pressX
                        root.slideX = Math.max(0, Math.min(root.imgW - root.pieceW, nx))
                        root.slidePoint("move", mouse.y)
                    }
                    onReleased: function(mouse) {
                        if (!root.sliding) return
                        root.sliding = false
                        root.slidePoint("up", mouse.y)
                        if (root.slideX < 4) {
                            root.slideX = 0
                            return
                        }
                        root.submitSlide()
                    }
                }
            }
        }

        // 数字计算
        Column {
            visible: root.ctype === "MATH"
            spacing: 12
            Rectangle {
                width: root.imgW
                height: 110
                color: "#ECEFF1"
                border.color: "#CFD8DC"
                Image {
                    id: mathImage
                    anchors.centerIn: parent
                    width: 288
                    height: 108
                    fillMode: Image.PreserveAspectFit
                    cache: false
                }
                Text {
                    anchors.centerIn: parent
                    visible: root.loading
                    text: "加载中…"
                    color: "#607D8B"
                }
            }
            TextField {
                id: answerField
                width: root.imgW
                placeholderText: "输入结果（数字）"
                inputMethodHints: Qt.ImhDigitsOnly
                validator: RegularExpressionValidator { regularExpression: /^-?\d{0,4}$/ }
                font.pixelSize: 16
                onAccepted: root.submit()
                onTextChanged: root.errorText = ""
            }
        }

        Row {
            width: root.width - 40
            spacing: 10
            Button {
                text: "换一张"
                width: 70
                onClicked: root.refresh()
            }
            Button {
                text: "重点"
                width: 60
                visible: root.ctype === "WORD_IMAGE_CLICK"
                enabled: root.clicks.length > 0
                onClicked: root.clicks = []
            }
            // 行宽 340：点选 = 70+60+80+80+4 个间距；滑块没有「重点」「确定」；数字 / 加载中没有「重点」
            Item {
                width: root.ctype === "SLIDER" ? 170 : root.ctype === "WORD_IMAGE_CLICK" ? 10 : 80
                height: 1
            }
            Button {
                text: "取消"
                width: 80
                onClicked: root.close()
            }
            Button {
                text: "确定"
                width: 80
                visible: root.ctype !== "SLIDER"
                highlighted: true
                enabled: root.ctype === "MATH" ? answerField.text.trim().length > 0 : root.clicks.length > 0
                onClicked: root.submit()
            }
        }
    }
}
