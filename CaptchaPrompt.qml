import QtQuick
import QtQuick.Controls
import Aifs.Components 1.0

// 登录图形验证码（文字点选，登录页 / 主界面共用）。ask(说明, 回调(captchaId, track), 取消回调)：
// 领一张图 → 按上面的提示依次点击大图里的字 → 确定。坐标按界面显示尺寸提交（imgW × imgH），后端按百分比比对。
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
    property bool loading: false
    property var clicks: []
    property double startTime: 0

    function ask(msg, cb, onCancel) {
        message = msg || ""
        callback = cb
        cancelCallback = onCancel || null
        open()
        refresh()
    }

    function refresh() {
        clicks = []
        captchaId = ""
        errorText = ""
        bgImage.source = ""
        tplImage.source = ""
        loading = true
        HttpClient.fetchCaptcha()
    }

    function submit() {
        if (captchaId === "" || clicks.length === 0) {
            errorText = "请按上面的顺序依次点击图中的文字"
            return
        }
        var list = []
        for (var i = 0; i < clicks.length; i++) {
            list.push({ x: clicks[i].x, y: clicks[i].y, t: clicks[i].t, type: "click" })
        }
        var track = { bgImageWidth: imgW, bgImageHeight: imgH,
                      startTime: startTime, stopTime: Date.now(), trackList: list }
        var cb = callback
        var id = captchaId
        callback = null
        cancelCallback = null
        close()
        if (cb) cb(id, track)
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
            bgImage.source = c.backgroundImage || ""
            tplImage.source = c.templateImage || ""
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
            Text {
                anchors.verticalCenter: parent.verticalCenter
                text: "请依次点击："
                font.family: "PingFang HK"
                font.pixelSize: 13
                color: "#455A64"
            }
            Image {
                id: tplImage
                height: 34
                fillMode: Image.PreserveAspectFit
                asynchronous: false
                cache: false
            }
        }
        Rectangle {
            width: root.imgW
            height: root.imgH
            color: "#ECEFF1"
            border.color: "#CFD8DC"
            Image {
                id: bgImage
                anchors.fill: parent
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
                enabled: root.captchaId !== "" && !root.loading
                cursorShape: Qt.PointingHandCursor
                onClicked: function(mouse) {
                    root.errorText = ""
                    root.clicks = root.clicks.concat([{ x: mouse.x, y: mouse.y, t: Date.now() - root.startTime }])
                }
            }
            Repeater {
                model: root.clicks.length
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
                enabled: root.clicks.length > 0
                onClicked: root.clicks = []
            }
            Item { width: root.width - 40 - 70 - 60 - 80 - 80 - 40; height: 1 }
            Button {
                text: "取消"
                width: 80
                onClicked: root.close()
            }
            Button {
                text: "确定"
                width: 80
                highlighted: true
                enabled: root.clicks.length > 0
                onClicked: root.submit()
            }
        }
    }
}
