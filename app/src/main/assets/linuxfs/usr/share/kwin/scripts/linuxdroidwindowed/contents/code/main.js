(function () {
    function isTargetWindow(c) {
        if (!c) {
            return false;
        }
        try {
            if (c.specialWindow || c.desktopWindow || c.dock || c.panel || c.toolbar || c.menu || c.splash || c.notification || c.popupWindow || c.tooltip || c.dialog || c.utility) {
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

    function applyInitialWindowedBoundsOnce(c) {
        if (!isTargetWindow(c)) {
            return;
        }
        if (c._linuxdroidInitialFitDone) {
            return;
        }
        c._linuxdroidInitialFitDone = true;
        try {
            if (c.fullScreen) {
                c.fullScreen = false;
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
                if (g.width >= maxW - 8 || g.height >= dispH - 24 || g.y < minY || (g.y + g.height) > (minY + maxH)) {
                    var targetW = Math.max(560, maxW - 48);
                    var targetH = Math.max(420, maxH - 24);
                    var targetX = minX + Math.max(8, Math.floor((maxW - targetW) / 2));
                    var targetY = minY + Math.max(8, Math.floor((maxH - targetH) / 2));
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
            applyInitialWindowedBoundsOnce(c);
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
