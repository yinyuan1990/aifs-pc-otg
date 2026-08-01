import QtQuick

// ⭐ 第五十章：实时流底部按钮栏的**公用下拉菜单**（弹在按钮正上方）。
//
// 与 LiveBarButton 配套：底部原来的档位/镜像两个下拉是各抄一份的，这里抽成一个。
// options 元素形如 { label: "1280x720@30", value: {...} }，选中即发 picked(value)。
Rectangle {
    id: menu

    property var options: []
    property var currentValue: undefined
    // 判等函数：默认按值比；对象型 value（如分辨率）由调用方传入自定义比较
    property var isCurrent: function(v) { return v === menu.currentValue }
    property int itemWidth: 96

    signal picked(var value)

    visible: false
    width: itemWidth
    height: col.height + 8
    z: 200          // 弹在按钮上方，别被同排后面的按钮盖住
    color: "#E8F5E9"
    radius: 4
    border.color: "#A5D6A7"
    border.width: 1

    function toggle() { visible = !visible }

    Column {
        id: col
        anchors.centerIn: parent
        spacing: 2

        Repeater {
            model: menu.options

            Rectangle {
                property bool active: menu.isCurrent(modelData.value)
                width: menu.width - 8
                height: 28
                radius: 3
                color: itemArea.containsMouse ? "#C8E6C9" : (active ? "#A5D6A7" : "transparent")

                Text {
                    anchors.centerIn: parent
                    text: modelData.label
                    font.pixelSize: 12
                    font.family: "PingFang HK"
                    font.bold: parent.active
                    color: "#263238"
                }

                MouseArea {
                    id: itemArea
                    anchors.fill: parent
                    hoverEnabled: true
                    cursorShape: Qt.PointingHandCursor
                    onClicked: {
                        menu.picked(modelData.value)
                        menu.visible = false
                    }
                }
            }
        }
    }
}
