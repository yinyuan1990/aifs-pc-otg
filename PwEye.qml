import QtQuick

// 密码框里的明 / 密文切换眼睛（§56.8 默认明文）：直接写在 TextField 里，输入框要留 rightPadding
Text {
    id: eye
    property Item target: parent
    anchors.right: parent.right
    anchors.rightMargin: 8
    anchors.verticalCenter: parent.verticalCenter
    text: target && target.echoMode === TextInput.Password ? "🙈" : "👁"
    font.pixelSize: 15
    MouseArea {
        anchors.fill: parent
        anchors.margins: -4
        cursorShape: Qt.PointingHandCursor
        onClicked: eye.target.echoMode = eye.target.echoMode === TextInput.Password ? TextInput.Normal : TextInput.Password
    }
}
