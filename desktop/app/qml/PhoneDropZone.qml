// SPDX-License-Identifier: GPL-3.0-or-later
import QtQuick
import QtQuick.Window
import app.nectarlink

// The "Phone" drop zone: appears at the left edge of the screen while a PC
// window is being dragged, and lights up when the pointer is over it. Letting go
// of the window there sends that window to the phone (it stays where it is on
// the PC and keeps its size; the phone shows it in an overlay). The pointer is
// read by the app, not by this window, so it never takes focus from the window
// being dragged. Only instantiated while a drag is going.
Window {
    id: zone

    title: qsTr("NectarLink Phone")
    flags: Qt.Tool | Qt.FramelessWindowHint | Qt.WindowStaysOnTopHint | Qt.WindowDoesNotAcceptFocus
        | Qt.WindowTransparentForInput
    color: "transparent"
    visible: true

    // The work area (the screen minus the taskbar), as the Shelf finds it.
    property var workArea: AppController.workArea()
    readonly property real dpr: Screen.devicePixelRatio > 0 ? Screen.devicePixelRatio : 1
    readonly property real areaX: workArea && workArea.width ? workArea.x / dpr : Screen.virtualX
    readonly property real areaY: workArea && workArea.height ? workArea.y / dpr : Screen.virtualY
    readonly property real areaH: workArea && workArea.height ? workArea.height / dpr : Screen.desktopAvailableHeight

    // The same size and gap the app hit-tests against (pc_window.rs).
    width: 112
    height: 208
    x: areaX + 12
    y: areaY + Math.round((areaH - height) / 2)

    readonly property bool over: AppController.windowDragOver

    Rectangle {
        anchors.fill: parent
        radius: 28
        color: zone.over ? Theme.primaryContainer : Theme.surfaceContainerHigh
        border.width: zone.over ? 2 : 1
        border.color: zone.over ? Theme.primary : Theme.outlineVariant
        opacity: 0.96
        scale: zone.over ? 1.04 : 1
        Behavior on scale { enabled: !Theme.reduceMotion; NumberAnimation { duration: 140; easing.type: Easing.OutCubic } }
        Behavior on color { enabled: !Theme.reduceMotion; ColorAnimation { duration: 140 } }

        Accessible.role: Accessible.Indicator
        Accessible.name: qsTr("Drop here to show this window on the phone")

        Column {
            anchors.centerIn: parent
            width: parent.width - 16
            spacing: 10
            Avatar {
                anchors.horizontalCenter: parent.horizontalCenter
                width: 48; height: 48
                iconPath: Icons.phone
                emphasized: zone.over
            }
            Txt {
                width: parent.width
                horizontalAlignment: Text.AlignHCenter
                text: qsTr("Phone")
                role: "headline"
                size: 18
                color: zone.over ? Theme.primaryContainerContent : Theme.surfaceContent
            }
            Txt {
                width: parent.width
                horizontalAlignment: Text.AlignHCenter
                text: zone.over ? qsTr("Let go") : qsTr("Drop here")
                role: "label"
                muted: true
                wrapMode: Text.WordWrap
            }
        }
    }
}
