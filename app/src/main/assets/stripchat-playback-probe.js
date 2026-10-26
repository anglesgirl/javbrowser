(function () {
    if (window.__javStripchatPlaybackProbe) return;
    var previous = null;
    function modelKey(value) {
        try {
            var url = new URL(value);
            if (url.protocol !== 'https:' || !/(^|\.)stripchat\.com$/i.test(url.hostname)) return '';
            var name = url.pathname.replace(/^\/+|\/+$/g, '');
            if (!name || name.indexOf('/') !== -1) return '';
            return name.toLowerCase();
        } catch (_) { return ''; }
    }
    function selectVideo() {
        var videos = Array.from(document.querySelectorAll(
            'video.video-element, #video-player video, .video-player video, #jav-internal-player-panel video'));
        return videos.filter(function (video) {
            if (video.matches('video.preview-video') || video.closest('a, [class*="preview"], [class*="recommend"]')) return false;
            var panel = video.closest('#jav-internal-player-panel');
            if (panel && modelKey(panel.getAttribute('data-jav-model-url')) !== modelKey(location.href)) return false;
            var rect = video.getBoundingClientRect();
            return rect.width >= 160 && rect.height >= 90;
        }).sort(function (a, b) {
            var ar = a.getBoundingClientRect(), br = b.getBoundingClientRect();
            return br.width * br.height - ar.width * ar.height;
        })[0] || null;
    }
    function sample(expectedUrl) {
        var key = modelKey(expectedUrl);
        var video = key && key === modelKey(location.href) ? selectVideo() : null;
        // A moving finite-duration preview is not evidence of a public live broadcast.
        if (!video || video.paused || video.ended || video.readyState < 2 ||
            video.videoWidth <= 0 || video.duration !== Infinity || !video.currentSrc) {
            previous = null;
            return false;
        }
        var current = {key: key, video: video, source: video.currentSrc,
            time: video.currentTime, at: Date.now()};
        var confirmed = !!previous && previous.key === current.key && previous.video === video &&
            previous.source === current.source && current.at - previous.at >= 1000 &&
            current.at - previous.at <= 5000 && current.time - previous.time > 0.1;
        previous = current;
        return confirmed;
    }
    window.__javStripchatPlaybackProbe = {sample: sample, selectVideo: selectVideo};
})();
