import QtQuick
import QtQuick.Controls
import Aifs.Components 1.0

// 输入 PC 二级密码的小弹框（登录页 / 主界面共用）。ask(标题, 说明, 回调) → 用户点确定时回调(密码)
Popup {
    id: root
    anchors.centerIn: Overlay.overlay
    width: 360
    height: 210
    modal: true
    focus: true
    closePolicy: Popup.CloseOnEscape
    padding: 0

    property string title: ""
    property string message: ""
    property var callback: null
    property var cancelCallback: null

    function ask(t, msg, cb, onCancel) {
        title = t
        message = msg || ""
        callback = cb
        cancelCallback = onCancel || null
        pwInput.text = ""
        open()
        pwInput.forceActiveFocus()
    }

    function submit() {
        var pw = pwInput.text.trim()
        if (pw.length === 0) {
            pwInput.forceActiveFocus()
            return
        }
        var cb = callback
        callback = null
        cancelCallback = null
        close()
        if (cb) cb(pw)
    }

    onClosed: {
        var c = cancelCallback
        cancelCallback = null
        callback = null
        if (c) c()
    }

    background: Rectangle {
        color: "#FFFFFF"
        radius: 8
        border.color: "#A5D6A7"
        border.width: 1
    }

    contentItem: Column {
        padding: 20
        spacing: 12

        Text {
            text: root.title
            font.family: "PingFang HK"
            font.pixelSize: 16
            font.bold: true
            color: "#263238"
        }
        Text {
            width: root.width - 40
            text: root.message
            visible: text.length > 0
            wrapMode: Text.WordWrap
            font.family: "PingFang HK"
            font.pixelSize: 12
            color: "#E53935"
        }
        TextField {
            id: pwInput
            width: root.width - 40
            height: 36
            echoMode: TextInput.Password
            placeholderText: "请输入" + HttpClient.pcSecondaryName()
            font.pixelSize: 14
            maximumLength: 20
            onAccepted: root.submit()
        }
        Row {
            anchors.right: parent.right
            anchors.rightMargin: 20
            spacing: 10
            Button {
                text: "取消"
                width: 80
                onClicked: root.close()
            }
            Button {
                text: "确定"
                width: 80
                highlighted: true
                onClicked: root.submit()
            }
        }
    }
}
