import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

// ⭐ 第五十章：相机调节面板的**公用行控件**（"提取公用的"就是这里）。
//
// 一行 = 标签 + 控件 + 当前值。按 ctrlType 三选一渲染：
//   · "pct"  0~100 百分比滑条（UVC 硬件项统一口径）
//   · "bool" 开关
//   · "enum" 分段选择（options 文本数组）
//
// 只管交互与外观，不知道任何协议：值变了就发信号，接的人自己决定怎么下发。
// OtgCameraPanel 在用；自带摄像头面板后续要重构也可以直接复用同一个控件。
Item {
    id: row

    property string label: ""
    property string ctrlType: "pct"     // pct / bool / enum
    property int    value: 0
    property int    minValue: 0
    property int    maxValue: 100
    property var    options: []         // enum 用
    property string unit: ""            // pct 用，如 "%"

    // 拖动过程中连续变化（用于实时预览，调用方自行节流）
    signal moved(int v)
    // 松手/点击后的最终值（真正下发用这个）
    signal committed(int v)

    // ⭐ 右侧数字显示的值：pct 类**跟着滑条走**，不是跟着 value 走。
    //   之前直接显示 value，而 value 只在外部（能力快照/调用方）更新时才变，
    //   硬件项那几行又没接 onMoved —— 于是拖动时数字纹丝不动、松手才跳一下，
    //   看起来就是"百分比显示不对"。
    readonly property int displayValue: ctrlType === "pct" ? Math.round(pctSlider.value) : value

    // 外部把 value 改了（切设备/刷新能力/还原）要把滑条拉回去。
    // 用户拖过一次后 Slider.value 的绑定就断了，必须显式赋值。
    onValueChanged: {
        if (ctrlType === "pct" && Math.round(pctSlider.value) !== value) {
            pctSlider.value = value
        }
    }

    implicitHeight: 40
    Layout.fillWidth: true

    RowLayout {
        anchors.fill: parent
        spacing: 10

        Text {
            text: row.label
            font.family: "PingFang HK"
            font.pixelSize: 13
            color: "#263238"
            Layout.preferredWidth: 84
            elide: Text.ElideRight
        }

        // ===== 百分比滑条 =====
        Slider {
            id: pctSlider
            visible: row.ctrlType === "pct"
            Layout.fillWidth: true
            from: row.minValue
            to: row.maxValue
            stepSize: 1
            Component.onCompleted: value = row.value
            onMoved: row.moved(Math.round(value))
            onPressedChanged: if (!pressed) row.committed(Math.round(value))
        }

        // ===== 开关 =====
        Switch {
            id: boolSwitch
            visible: row.ctrlType === "bool"
            Layout.fillWidth: true
            checked: row.value !== 0
            onToggled: {
                var v = checked ? 1 : 0
                row.moved(v)
                row.committed(v)
            }
        }

        // ===== 分段选择 =====
        Row {
            visible: row.ctrlType === "enum"
            Layout.fillWidth: true
            spacing: 6

            Repeater {
                model: row.options

                Rectangle {
                    property bool active: index === row.value
                    width: Math.max(48, optText.implicitWidth + 16)
                    height: 26
                    radius: 4
                    color: active ? "#A5D6A7" : (optArea.containsMouse ? "#E8F5E9" : "#FFFFFF")
                    border.color: active ? "#4CAF50" : "#E0E0E0"
                    border.width: 1

                    Text {
                        id: optText
                        anchors.centerIn: parent
                        text: modelData
                        font.family: "PingFang HK"
                        font.pixelSize: 12
                        color: "#263238"
                    }

                    MouseArea {
                        id: optArea
                        anchors.fill: parent
                        hoverEnabled: true
                        cursorShape: Qt.PointingHandCursor
                        onClicked: {
                            // 链路日志：分段按钮的点击本身（qlgx 里没这行 = 点击根本没进来）
                            console.log("🔗 [OTG链路|点击] " + row.label + " → " + modelData + "(index=" + index + ")")
                            row.moved(index)
                            row.committed(index)
                        }
                    }
                }
            }
        }

        // ===== 当前值（跟着滑条实时走）=====
        Text {
            visible: row.ctrlType === "pct"
            text: row.displayValue + row.unit
            font.family: "PingFang HK"
            font.pixelSize: 12
            color: "#546E7A"
            horizontalAlignment: Text.AlignRight
            Layout.preferredWidth: 44
        }
    }
}
