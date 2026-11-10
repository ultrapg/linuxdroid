(function () {
    function isTargetWindow(c) {
        if (!c) {
            return false;
        }
        try {
            if (c.specialWindow || c.desktopWindow || c.dock || c.panel || c.toolbar || c.menu || c.splash || c.notification || c.popupWindow || c.tooltip) {
                return false;
            }
            var rc = String(c.resourceClass || "").toLowerCase();
            var rn = String(c.resourceName || "").toLowerCase();
            var cap = String(c.caption || "").toLowerCase();
            if (rc.indexOf("blender") !== -1 || rn.indexOf("blender") !== -1 || cap.indexOf("blender") !== -1) {
                return true;
            }
        } catch (e) {
        }
        return false;
    }

    function fitToDesktopWorkArea(c) {
        if (!isTargetWindow(c)) {
            return;
        }
        try {
            if (c.fullScreen) {
                c.fullScreen = false;
            }
        } catch (e) {
        }
        try {
            if (typeof c.setMaximize === "function") {
                c.setMaximize(false, false);
            }
        } catch (e) {
        }
        try {
            if (c.noBorder) {
                c.noBorder = false;
            }
        } catch (e) {
        }
        try {
            var area = null;
            if (typeof KWin !== "undefined" && typeof workspace.clientArea === "function") {
                area = workspace.clientArea(KWin.PlacementArea, c);
            }
            var maxW = (area && area.width > 300) ? area.width : (workspace.displayWidth || 1920);
            var maxH = (area && area.height > 300) ? area.height : ((workspace.displayHeight || 1080) - 48);
            var minX = (area && typeof area.x === "number") ? area.x : 0;
            var minY = (area && typeof area.y === "number") ? area.y : 0;
            var dispH = workspace.displayHeight || (minY + maxH);
            if (maxH >= dispH - 8) {
                maxH = Math.max(360, dispH - 52);
            }
            var g = c.frameGeometry;
            if (g) {
                var targetW = Math.min(g.width, maxW - 32);
                var targetH = Math.min(g.height, maxH - 24);
                var targetX = g.x;
                var targetY = g.y;
                var needsMove = false;
                if (g.width >= maxW - 8 || g.height >= dispH - 48 || g.y < minY + 4 || (g.y + g.height) > (minY + maxH - 4)) {
                    targetW = Math.max(560, maxW - 64);
                    targetH = Math.max(420, maxH - 32);
                    targetX = minX + Math.max(8, Math.floor((maxW - targetW) / 2));
                    targetY = minY + Math.max(8, Math.floor((maxH - targetH) / 2));
                    needsMove = true;
                }
                if (needsMove) {
                    c.frameGeometry = {
                        x: targetX,
                        y: targetY,
                        width: targetW,
                        height: targetH
                    };
                }
            }
        } catch (e) {
        }
    }

    function attachClient(c) {
        if (!c) {
            return;
        }
        if (isTargetWindow(c)) {
            fitToDesktopWorkArea(c);
        }
        try {
            if (c.fullScreenChanged && typeof c.fullScreenChanged.connect === "function") {
                c.fullScreenChanged.connect(function () {
                    if (isTargetWindow(c) && c.fullScreen) {
                        fitToDesktopWorkArea(c);
                    }
                });
            }
        } catch (e) {
        }
        try {
            if (c.clientMaximizedStateChanged && typeof c.clientMaximizedStateChanged.connect === "function") {
                c.clientMaximizedStateChanged.connect(function () {
                    if (isTargetWindow(c)) {
                        fitToDesktopWorkArea(c);
                    }
                });
            }
        } catch (e) {
        }
        try {
            if (c.captionChanged && typeof c.captionChanged.connect === "function") {
                c.captionChanged.connect(function () {
                    if (isTargetWindow(c)) {
                        fitToDesktopWorkArea(c);
                    }
                });
            }
        } catch (e) {
        }
        try {
            if (c.windowShown && typeof c.windowShown.connect === "function") {
                c.windowShown.connect(function () {
                    if (isTargetWindow(c)) {
                        fitToDesktopWorkArea(c);
                    }
                });
            }
        } catch (e) {
        }
    }

    try {
        var list = (typeof workspace.clientList === "function") ? workspace.clientList() : (workspace.clients || []);
        for (var i = 0; i < list.length; i++) {
            attachClient(list[i]);
        }
    } catch (e) {
    }

    try {
        if (workspace.clientAdded && typeof workspace.clientAdded.connect === "function") {
            workspace.clientAdded.connect(attachClient);
        }
    } catch (e) {
    }
})();
