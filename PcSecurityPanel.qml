import QtQuick
import QtQuick.Controls
import Aifs.Components 1.0

// 「切换账户」里的两个弹框：mode = "devices"（这个 PC 账号登录过的电脑）/ "secondary"（二级密码开关 / 修改）。
//   电脑列表不含总后台屏蔽的；开了二级密码时改备注 / 移除要输。
Popup {
    id: root
    anchors.centerIn: Overlay.overlay
    width: mode === "devices" ? 640 : 420
    height: mode === "devices" ? 520 : 460
    modal: true
    focus: true
    closePolicy: Popup.CloseOnEscape
    padding: 0

    property string mode: "devices"
    property bool loading: false
    property var devices: []
    property int maxDevices: 10
    property bool enabledState: HttpClient.pcSecondaryEnabled()
    property string secName: HttpClient.pcSecondaryName()
    property string status: ""
    property bool statusOk: true

    function show(m) {
        mode = m
        status = ""
        devices = []
        clearInputs()
        open()
        reload()
    }
    function reload() {
        loading = true
        HttpClient.fetchPcSecurity()
    }
    function clearInputs() {
        newPw1.text = ""; newPw2.text = ""
        oldPw.text = ""; chPw1.text = ""; chPw2.text = ""
        offPw.text = ""
    }
    function setStatus(ok, msg) { statusOk = ok; status = msg || "" }
    // 开了二级密码：先弹框，输完把它挂到下一个敏感请求上
    function requireSecondary(title, cb) {
        if (!HttpClient.pcSecondaryEnabled()) { cb(); return }
        prompt.ask(title, "", function(pw) { HttpClient.setNextPcSecondary(pw); cb() })
    }
    function fmtTime(t) { return t ? String(t).replace("T", " ").substring(0, 16) : "-" }
    function okPw(p) { return /^[A-Za-z0-9]{4,20}$/.test(p) }

    Connections {
        target: HttpClient
        enabled: root.visible
        function onPcSecurityReceived(data) {
            root.loading = false
            if (data.error) { root.setStatus(false, data.error); return }
            root.enabledState = !!data.pcSecondaryEnabled
            root.secName = data.pcSecondaryName || "二级密码"
            root.maxDevices = data.pcMaxDevices || 10
            root.devices = data.devices || []
        }
        function onPcSecurityDone(action, ok, message) {
            root.setStatus(ok, message)
            if (ok) { root.clearInputs(); root.reload() }
        }
    }

    PcSecondaryPrompt { id: prompt }

    // 备注输入
    Popup {
        id: remarkPopup
        anchors.centerIn: Overlay.overlay
        width: 360; height: 180; modal: true; padding: 0
        property string pcDeviceId: ""
        background: Rectangle { color: "#FFFFFF"; radius: 8; border.color: "#A5D6A7" }
        contentItem: Column {
            padding: 20; spacing: 12
            Text { text: "电脑备注"; font.family: "PingFang HK"; font.pixelSize: 16; font.bold: true; color: "#263238" }
            TextField { id: remarkInput; width: 320; height: 36; maximumLength: 100; placeholderText: "比如：家里的电脑" }
            Row {
                anchors.right: parent.right; anchors.rightMargin: 20; spacing: 10
                Button { text: "取消"; width: 80; onClicked: remarkPopup.close() }
                Button {
                    text: "保存"; width: 80; highlighted: true
                    onClicked: {
                        var id = remarkPopup.pcDeviceId, r = remarkInput.text.trim()
                        remarkPopup.close()
                        root.requireSecondary("修改备注需要" + root.secName, function() {
                            HttpClient.pcSecurityPost("device-remark", { pcDeviceId: id, remark: r })
                        })
                    }
                }
            }
        }
    }

    // 移除确认
    Popup {
        id: removePopup
        anchors.centerIn: Overlay.overlay
        width: 380; height: 170; modal: true; padding: 0
        property string pcDeviceId: ""
        background: Rectangle { color: "#FFFFFF"; radius: 8; border.color: "#A5D6A7" }
        contentItem: Column {
            padding: 20; spacing: 12
            Text { text: "移除这台电脑？"; font.family: "PingFang HK"; font.pixelSize: 16; font.bold: true; color: "#263238" }
            Text {
                width: 340; wrapMode: Text.WrapAnywhere; font.pixelSize: 12; color: "#666666"
                text: removePopup.pcDeviceId + "\n移除后它下次登录按新电脑处理" + (root.enabledState ? "，要输" + root.secName : "")
            }
            Row {
                anchors.right: parent.right; anchors.rightMargin: 20; spacing: 10
                Button { text: "取消"; width: 80; onClicked: removePopup.close() }
                Button {
                    text: "移除"; width: 80; highlighted: true
                    onClicked: {
                        var id = removePopup.pcDeviceId
                        removePopup.close()
                        root.requireSecondary("移除电脑需要" + root.secName, function() {
                            HttpClient.pcSecurityPost("device-remove", { pcDeviceId: id })
                        })
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
        // 标题栏
        Item {
            id: head
            width: parent.width
            height: 50
            Text {
                anchors.left: parent.left; anchors.leftMargin: 20; anchors.verticalCenter: parent.verticalCenter
                text: root.mode === "devices" ? "设备列表" : root.secName
                font.family: "PingFang HK"; font.pixelSize: 18; font.bold: true; color: "#263238"
            }
            Text {
                visible: root.mode === "devices"
                anchors.left: parent.left; anchors.leftMargin: 110; anchors.verticalCenter: parent.verticalCenter
                text: root.devices.length + " / " + root.maxDevices + " 台电脑"
                font.pixelSize: 12; color: "#888888"
            }
            Button {
                visible: root.mode === "devices"
                anchors.right: closeBtn.left; anchors.rightMargin: 10; anchors.verticalCenter: parent.verticalCenter
                text: root.loading ? "加载中" : "刷新"
                height: 28
                onClicked: if (!root.loading) root.reload()
            }
            Button {
                id: closeBtn
                anchors.right: parent.right; anchors.rightMargin: 12; anchors.verticalCenter: parent.verticalCenter
                text: "✕"; width: 32; height: 28; flat: true
                onClicked: root.close()
            }
            Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: 1; color: "#C8E6C9" }
        }

        Text {
            id: statusLine
            anchors.top: head.bottom; anchors.topMargin: 8
            anchors.left: parent.left; anchors.leftMargin: 20
            width: parent.width - 40
            visible: root.status.length > 0
            text: root.status
            wrapMode: Text.WordWrap
            font.pixelSize: 12
            color: root.statusOk ? "#2E7D32" : "#E53935"
        }

        // ===== 设备列表 =====
        ListView {
            visible: root.mode === "devices"
            anchors.top: statusLine.visible ? statusLine.bottom : head.bottom
            anchors.topMargin: 8
            anchors.left: parent.left; anchors.right: parent.right
            anchors.bottom: parent.bottom; anchors.bottomMargin: 12
            anchors.leftMargin: 12; anchors.rightMargin: 12
            clip: true
            spacing: 6
            model: root.devices
            ScrollBar.vertical: ScrollBar {}
            delegate: Rectangle {
                width: ListView.view.width
                height: 64
                radius: 6
                color: modelData.pcDeviceId === HttpClient.pcDeviceId() ? "#F1F8E9" : "#FAFAFA"
                border.color: "#E0E0E0"
                Column {
                    anchors.left: parent.left; anchors.leftMargin: 12
                    anchors.right: btnRow.left; anchors.rightMargin: 8
                    anchors.verticalCenter: parent.verticalCenter
                    spacing: 4
                    Row {
                        spacing: 8
                        Text { text: modelData.pcDeviceId; font.pixelSize: 13; font.bold: true; color: "#263238" }
                        Text {
                            visible: modelData.pcDeviceId === HttpClient.pcDeviceId()
                            text: "本机"; font.pixelSize: 11; color: "#FFFFFF"
                            leftPadding: 5; rightPadding: 5
                            Rectangle { anchors.fill: parent; z: -1; radius: 3; color: "#66BB6A" }
                        }
                        Text {
                            text: modelData.online ? "● 在线" : "● 离线"
                            font.pixelSize: 12
                            color: modelData.online ? "#43A047" : "#9E9E9E"
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
                Row {
                    id: btnRow
                    anchors.right: parent.right; anchors.rightMargin: 10
                    anchors.verticalCenter: parent.verticalCenter
                    spacing: 6
                    Button {
                        text: "备注"; height: 28; width: 56
                        onClicked: {
                            remarkPopup.pcDeviceId = modelData.pcDeviceId
                            remarkInput.text = modelData.remark || ""
                            remarkPopup.open()
                        }
                    }
                    Button {
                        text: "移除"; height: 28; width: 56
                        onClicked: { removePopup.pcDeviceId = modelData.pcDeviceId; removePopup.open() }
                    }
                }
            }
            Text {
                anchors.centerIn: parent
                visible: !root.loading && root.devices.length === 0 && root.status.length === 0
                text: "还没有电脑记录"
                color: "#9E9E9E"
            }
        }

        // ===== 二级密码 =====
        Column {
            visible: root.mode === "secondary"
            anchors.top: statusLine.visible ? statusLine.bottom : head.bottom
            anchors.topMargin: 12
            anchors.left: parent.left; anchors.leftMargin: 20
            width: parent.width - 40
            spacing: 10

            Text {
                width: parent.width
                wrapMode: Text.WordWrap
                font.pixelSize: 12
                color: "#666666"
                text: root.enabledState
                      ? "已开启。改 PC 密码、解绑、备注、设备列表改动、新电脑第一次登录都要输" + root.secName + "。"
                      : "未开启。开启后改 PC 密码、解绑、备注、设备列表改动、新电脑第一次登录都要输" + root.secName + "（已登录过的电脑不再问）。"
            }

            // 未开启：设置并开启
            Column {
                visible: !root.enabledState
                width: parent.width
                spacing: 8
                TextField { id: newPw1; width: parent.width; height: 36; echoMode: TextInput.Password; maximumLength: 20; placeholderText: "设置" + root.secName + "（4~20 位字母或数字）" }
                TextField { id: newPw2; width: parent.width; height: 36; echoMode: TextInput.Password; maximumLength: 20; placeholderText: "再输一次" }
                Button {
                    text: "开启" + root.secName
                    highlighted: true
                    onClicked: {
                        var a = newPw1.text.trim(), b = newPw2.text.trim()
                        if (!root.okPw(a)) { root.setStatus(false, "只能是 4~20 位字母或数字"); return }
                        if (a !== b) { root.setStatus(false, "两次输入不一致"); return }
                        HttpClient.pcSecurityPost("enable", { password: a })
                    }
                }
            }

            // 已开启：修改 / 关闭
            Column {
                visible: root.enabledState
                width: parent.width
                spacing: 8
                Text { text: "修改" + root.secName; font.pixelSize: 13; font.bold: true; color: "#263238" }
                TextField { id: oldPw; width: parent.width; height: 34; echoMode: TextInput.Password; maximumLength: 20; placeholderText: "原" + root.secName }
                TextField { id: chPw1; width: parent.width; height: 34; echoMode: TextInput.Password; maximumLength: 20; placeholderText: "新" + root.secName + "（4~20 位字母或数字）" }
                TextField { id: chPw2; width: parent.width; height: 34; echoMode: TextInput.Password; maximumLength: 20; placeholderText: "再输一次新" + root.secName }
                Button {
                    text: "修改"
                    highlighted: true
                    onClicked: {
                        var o = oldPw.text.trim(), a = chPw1.text.trim(), b = chPw2.text.trim()
                        if (o.length === 0) { root.setStatus(false, "请输入原" + root.secName); return }
                        if (!root.okPw(a)) { root.setStatus(false, "只能是 4~20 位字母或数字"); return }
                        if (a !== b) { root.setStatus(false, "两次输入不一致"); return }
                        HttpClient.pcSecurityPost("change", { oldPassword: o, newPassword: a })
                    }
                }
                Rectangle { width: parent.width; height: 1; color: "#EEEEEE" }
                Text { text: "关闭" + root.secName; font.pixelSize: 13; font.bold: true; color: "#263238" }
                Row {
                    spacing: 8
                    TextField { id: offPw; width: 240; height: 34; echoMode: TextInput.Password; maximumLength: 20; placeholderText: "当前" + root.secName }
                    Button {
                        text: "关闭"
                        onClicked: {
                            var p = offPw.text.trim()
                            if (p.length === 0) { root.setStatus(false, "请输入当前" + root.secName); return }
                            HttpClient.pcSecurityPost("disable", { password: p })
                        }
                    }
                }
            }
        }
    }
}
