(function () {
    if (window.__supjavPlayerHook) return;
    window.__supjavPlayerHook = true;
    var previous = '', waiting = false, oldUrl = '', expected = '', timer;
    var controls = '.btn-server, .btnst, .server, .servers, .server-list, .video-server, #server, .tabs, [data-server]';
    function selected() {
        var node = document.querySelector('.btn-server.active, .server .active, .servers .active, .server-list .active, .video-server .active, #server .active, .tabs .active, [data-server][aria-selected="true"]');
        return node ? (node.textContent || '').trim().slice(0, 80) : '';
    }
    function frame() {
        return document.querySelector('iframe#video, #video iframe[src], iframe#player, #player iframe[src]');
    }
    function report(loaded) {
        var player = frame(), url = player && player.src || '';
        if (!/^https?:\/\//i.test(url)) return;
        if (waiting && ((expected && url !== expected) || (!expected && url === oldUrl && !loaded))) return;
        if (waiting && expected === oldUrl && !loaded) return;
        waiting = false;
        var label = selected(), key = url + '\n' + label;
        if (key === previous) return;
        previous = key;
        Android.onSupjavPlayerFrame(url, label);
    }
    function schedule() { clearTimeout(timer); timer = setTimeout(function () { report(false); }, 180); }
    document.addEventListener('click', function (event) {
        var target = event.target.closest && event.target.closest('a, button, [data-server], [data-link], [data-src]');
        if (!target || !target.closest(controls)) return;
        var player = frame();
        oldUrl = player && player.src || '';
        expected = '';
        try { if (typeof JumpChain === 'function' && target.getAttribute('data-link')) expected = JumpChain(target.getAttribute('data-link')); } catch(e) {}
        waiting = true; previous = '';
        Android.onSupjavServerChanging();
        schedule();
    }, true);
    document.addEventListener('load', function (event) { if (event.target === frame()) report(true); }, true);
    new MutationObserver(schedule).observe(document.documentElement, {childList:true, subtree:true, attributes:true,
        attributeFilter:['src', 'class', 'aria-selected']});
    report(false);
})();
