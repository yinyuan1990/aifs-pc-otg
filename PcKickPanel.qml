import QtQuick
import QtQuick.Controls
import Aifs.Components 1.0

// 登录时这台新电脑超出账号电脑数上限（后端 1009 带 pcKick）：列出登录过的电脑，输对二级密码移除一台后按原参数重登。
//   账号没开二级密码时只能看，不能移除（只凭账号密码不能踢别的电脑）；禁止登录的电脑不能移除。
//   show(提示, pcKick, 成功回调(二级密码), 取消回调)
Popup {
    id: root
    anchors.centerIn: Overlay.overlay
    width: 640
    height: 540
    modal: true
    focus: true
    closePolicy: Popup.CloseOnEscape
    padding: 0

    property string message: ""
    property string ticket: ""
    property bool allowed: false
    property var devices: []
    property int maxDevices: 10
    property string secName: "二级密码"
    property bool busy: false
    property string status: ""
    property bool statusOk: true
    property bool succeeded: false
    property string usedPw: ""
    property var doneCallback: null
    property var cancelCallback: null

    function show(msg, data, onDone, onCancel) {
        message = msg || ""
        ticket = data.ticket || ""
        allowed = !!data.allowed && ticket.length > 0
        devices = data.devices || []
        maxDevices = data.maxDevices || 10
        secName = data.secondaryName || HttpClient.pcSecondaryName()
        doneCallback = onDone || null
        cancelCallback = onCancel || null
        busy = false
        succeeded = false
        usedPw = ""
        status = ""
        pwInput.text = ""
        open()
        if (allowed) pwInput.forceActiveFocus()
    }
    function setStatus(ok, msg) { statusOk = ok; status = msg || "" }
    function fmtTime(t) { return t ? String(t).replace("T", " ").substring(0, 16) : "-" }

    function askRemove(d) {
        if (busy) return
        var pw = pwInput.text.trim()
        if (pw.length === 0) {
            setStatus(false, "请先输入" + secName)
            pwInput.forceActiveFocus()
            return
        }
        confirmPopup.dev = d
        confirmPopup.open()
    }

    onClosed: {
        var ok = succeeded, pw = usedPw
        var done = doneCallback, cancel = cancelCallback
        doneCallback = null
        cancelCallback = null
        if (ok) { if (done) done(pw) }
        else if (cancel) cancel()
    }

    Connections {
        target: HttpClient
        enabled: root.visible
        function onPcKickDone(ok, message) {
            root.busy = false
            if (ok) {
                root.setStatus(true, message + "，正在重新登录…")
                root.succeeded = true
                kickCloseTimer.start()
            } else {
                root.setStatus(false, message)
            }
        }
    }

    Timer {
        id: kickCloseTimer
        interval: 600
        onTriggered: root.close()
    }

    Popup {
        id: confirmPopup
        anchors.centerIn: Overlay.overlay
        width: 400; height: 180; modal: true; padding: 0
        property var dev: ({})
        background: Rectangle { color: "#FFFFFF"; radius: 8; border.color: "#A5D6A7" }
        contentItem: Column {
            padding: 20; spacing: 12
            Text { text: "移除这台电脑？"; font.family: "PingFang HK"; font.pixelSize: 16; font.bold: true; color: "#263238" }
            Text {
                width: 360; wrapMode: Text.WrapAnywhere; font.pixelSize: 12; color: "#666666"
                text: (confirmPopup.dev.pcDeviceId || "") + (confirmPopup.dev.remark ? "（" + confirmPopup.dev.remark + "）" : "")
                      + "\n移除后它下次登录按新电脑处理"
                      + (confirmPopup.dev.online ? "；它现在在线，正在看的画面不会马上断" : "")
            }
            Row {
                anchors.right: parent.right; anchors.rightMargin: 20; spacing: 10
                Button { text: "取消"; width: 80; onClicked: confirmPopup.close() }
                Button {
                    text: "移除"; width: 80; highlighted: true
                    onClicked: {
                        var id = confirmPopup.dev.pcDeviceId
                        var pw = pwInput.text.trim()
                        confirmPopup.close()
                        root.busy = true
                        root.usedPw = pw
                        root.setStatus(true, "正在移除…")
                        HttpClient.pcKickDevice(root.ticket, id, pw)
                    }
                }
            }
        }
    }

    background: Rectangle {
        color: "#FFFFFF"
        radius: 8
        border.color: "#A5D6A7"
        border.width: 1
    }

    contentItem: Item {
        Item {
            id: head
            width: parent.width
            height: 50
            Text {
                id: titleText
                anchors.left: parent.left; anchors.leftMargin: 20; anchors.verticalCenter: parent.verticalCenter
                text: "电脑数已满"
                font.family: "PingFang HK"; font.pixelSize: 18; font.bold: true; color: "#263238"
            }
            Text {
                anchors.left: titleText.right; anchors.leftMargin: 12; anchors.verticalCenter: parent.verticalCenter
                text: root.devices.length + " / " + root.maxDevices + " 台电脑"
                font.pixelSize: 12; color: "#888888"
            }
            Button {
                anchors.right: parent.right; anchors.rightMargin: 12; anchors.verticalCenter: parent.verticalCenter
                text: "✕"; width: 32; height: 28; flat: true
                onClicked: root.close()
            }
            Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: 1; color: "#C8E6C9" }
        }

        Column {
            id: topArea
            anchors.top: head.bottom; anchors.topMargin: 10
            anchors.left: parent.left; anchors.leftMargin: 20
            width: parent.width - 40
            spacing: 8

            Text {
                width: parent.width
                wrapMode: Text.WordWrap
                font.pixelSize: 12
                color: "#E53935"
                text: root.message
                visible: text.length > 0
            }
            Text {
                width: parent.width
                wrapMode: Text.WordWrap
                font.pixelSize: 12
                color: "#666666"
                text: root.allowed
                      ? "输入" + root.secName + "，选一台不用的电脑点「移除」，移除后自动重新登录。"
                      : "这个账号没开启" + root.secName + "，这里不能移除。请在已登录的电脑上「切换账户 → 设备列表」移除不用的电脑"
                        + "（或在那里开启" + root.secName + "后回来移除），也可以联系管理员。"
            }
            TextField {
                id: pwInput
                visible: root.allowed
                width: 280
                height: 36
                echoMode: TextInput.Password
                maximumLength: 20
                enabled: !root.busy && !root.succeeded
                placeholderText: "请输入" + root.secName
            }
            Text {
                width: parent.width
                visible: root.status.length > 0
                text: root.status
                wrapMode: Text.WordWrap
                font.pixelSize: 12
                color: root.statusOk ? "#2E7D32" : "#E53935"
            }
        }

        ListView {
            anchors.top: topArea.bottom; anchors.topMargin: 10
            anchors.left: parent.left; anchors.right: parent.right
            anchors.bottom: footer.top; anchors.bottomMargin: 8
            anchors.leftMargin: 12; anchors.rightMargin: 12
            clip: true
            spacing: 6
            model: root.devices
            ScrollBar.vertical: ScrollBar {}
            delegate: Rectangle {
                width: ListView.view.width
                height: 64
                radius: 6
                color: "#FAFAFA"
                border.color: "#E0E0E0"
                Column {
                    anchors.left: parent.left; anchors.leftMargin: 12
                    anchors.right: kickBtn.left; anchors.rightMargin: 8
                    anchors.verticalCenter: parent.verticalCenter
                    spacing: 4
                    Row {
                        spacing: 8
                        Text { text: modelData.pcDeviceId; font.pixelSize: 13; font.bold: true; color: "#263238" }
                        Text {
                            text: modelData.online ? "● 在线" : "● 离线"
                            font.pixelSize: 12
                            color: modelData.online ? "#43A047" : "#9E9E9E"
                        }
                        Text {
                            visible: !modelData.kickable
                            text: "已禁止登录"
                            font.pixelSize: 12
                            color: "#E53935"
                        }
                    }
                    Text {
                        width: parent.width
                        elide: Text.ElideRight
                        font.pixelSize: 12
                        color: "#666666"
                        text: "备注：" + (modelData.remark || "-")
                              + (modelData.online && modelData.watchingUsername
                                 ? "    正在看：" + (modelData.watchingNickname || modelData.watchingUsername) : "")
                              + "    最后登录：" + root.fmtTime(modelData.lastLoginAt)
                    }
                }
                Button {
                    id: kickBtn
                    visible: root.allowed
                    anchors.right: parent.right; anchors.rightMargin: 10
                    anchors.verticalCenter: parent.verticalCenter
                    text: "移除"; height: 28; width: 56
                    enabled: modelData.kickable && !root.busy && !root.succeeded
                    onClicked: root.askRemove(modelData)
                }
            }
            Text {
                anchors.centerIn: parent
                visible: root.devices.length === 0
                text: "没有可显示的电脑，请联系管理员"
                color: "#9E9E9E"
            }
        }

        Item {
            id: footer
            anchors.bottom: parent.bottom
            width: parent.width
            height: 48
            Button {
                anchors.right: parent.right; anchors.rightMargin: 20
                anchors.verticalCenter: parent.verticalCenter
                text: "取消"; width: 80
                enabled: !root.succeeded
                onClicked: root.close()
            }
        }
    }
}
