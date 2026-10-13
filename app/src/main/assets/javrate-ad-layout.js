(function () {
    'use strict';
    if (!/(^|\.)javrate\.com$/i.test(location.hostname) || window.__javrateAdLayoutObserver) return;

    // JavRate reserves fixed 90/250px banners and full-width grid cells even
    // when the advertising request is blocked. Remove their pure ad wrappers.
    var adSelector = '.ads[data-ad-slot][data-ad-platform]';
    var playerSelector = 'video, audio, #v2-player, #player, .video-js, .jwplayer, iframe[allowfullscreen]';

    function hasPlayer(node) {
        return node.matches(playerSelector) || !!node.querySelector(playerSelector);
    }

    function isAdOnlyWrapper(wrapper) {
        if (!wrapper || hasPlayer(wrapper)) return false;
        return Array.prototype.every.call(wrapper.childNodes, function (child) {
            if (child.nodeType === 3) return !child.textContent.trim();
            if (child.nodeType !== 1) return true;
            return child.matches(adSelector + ', script, style, template');
        });
    }

    function clean() {
        document.querySelectorAll(adSelector).forEach(function (ad) {
            if (!ad.isConnected || hasPlayer(ad)) return;
            var wrapper = ad.closest('.ads-box, .desktop-search-ad, .mobile-search-ad');
            // A wrapper that also contains real content must remain intact.
            (isAdOnlyWrapper(wrapper) ? wrapper : ad).remove();
        });
    }

    clean();
    var observer = new MutationObserver(clean);
    observer.observe(document.documentElement, {
        childList: true, subtree: true, attributes: true,
        attributeFilter: ['class', 'data-ad-slot', 'data-ad-platform']
    });
    window.__javrateAdLayoutObserver = observer;
})();
