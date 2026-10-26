(function () {
    if (!/(^|\.)stripchat\.com$/i.test(location.hostname)) return;
    if (window.__javStripchatWatchControls) return window.__javStripchatWatchControls();
    var lastUrl = '';
    var watchId = 'jav-stripchat-watch-button';
    var privacyId = 'jav-stripchat-watch-privacy-button';
    function isModelPage() {
        var name = location.pathname.replace(/^\/+|\/+$/g, '').toLowerCase();
        return !!name && name.indexOf('/') === -1 &&
            ['login', 'signup', 'favorites', 'models', 'search', 'category', 'tags', 'live'].indexOf(name) === -1;
    }
    function createButton(id, top, click) {
        var button = document.createElement('button');
        button.id = id;
        button.type = 'button';
        button.style.cssText = 'position:fixed;right:12px;top:' + top + 'px;z-index:2147483647;' +
            'border:1px solid rgba(255,255,255,.5);border-radius:18px;padding:8px 13px;' +
            'font:700 13px sans-serif;color:#fff;background:rgba(20,20,20,.94);' +
            'box-shadow:0 2px 8px rgba(0,0,0,.45);pointer-events:auto;';
        button.onclick = function (event) {
            event.preventDefault(); event.stopPropagation();
            try { click(); } catch (_) {}
            refresh();
        };
        document.body.appendChild(button);
        return button;
    }
    function refresh() {
        if (!document.body) return 'waiting-for-body';
        if (!isModelPage()) {
            [watchId, privacyId].forEach(function (id) {
                var node = document.getElementById(id); if (node) node.remove();
            });
            return 'not-model-page';
        }
        var watch = document.getElementById(watchId) || createButton(watchId, 92, function () {
            if (Android.isStripchatWatchSessionActive(location.href)) Android.stopStripchatWatchSession();
            else Android.beginStripchatWatchSession(location.href);
        });
        var privacy = document.getElementById(privacyId) || createButton(privacyId, 136, function () {
            Android.enterStripchatPrivacyMode();
        });
        privacy.textContent = '◼ 隱私';
        var active = false, recording = false;
        try {
            active = Android.isStripchatWatchSessionActive(location.href);
            recording = Android.isStripchatStreamRecording() || Android.isStripchatRecording();
            watch.textContent = Android.getStripchatWatchStateLabel(location.href);
        } catch (_) { watch.textContent = active ? '◌ 正在確認直播狀態' : '◎ 監錄'; }
        watch.style.background = active
            ? (recording ? 'rgba(145,18,34,.94)' : 'rgba(176,108,0,.94)') : 'rgba(20,20,20,.94)';
        var display = document.getElementById('jav-internal-player-panel') ? 'none' : 'block';
        watch.style.setProperty('display', display, 'important');
        privacy.style.setProperty('display', display, 'important');
        if (location.href !== lastUrl) {
            lastUrl = location.href;
            try { Android.onStripchatLocationChanged(lastUrl); } catch (_) {}
        }
        return 'watch-controls-ready';
    }
    window.__javStripchatWatchControls = refresh;
    window.__javRefreshStripchatWatchUi = refresh;
    // The site can replace body children during SPA navigation. Remount without
    // depending on a load event, and keep native watch labels current.
    setInterval(refresh, 1000);
    document.addEventListener('DOMContentLoaded', refresh, {once: true});
    return refresh();
})();
