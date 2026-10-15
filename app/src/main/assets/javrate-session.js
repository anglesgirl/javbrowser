(function () {
    'use strict';
    if (window.top !== window || window.__javrateAuthInstalled) return;
    window.__javrateAuthInstalled = true;
    let config, current, timer, pending, nextAttempt = 0, failures = 0, stopped = false;
    const now = () => Date.now() - (Math.abs(Number(config.timeDiff) || 0) > 20000 ? Number(config.timeDiff) : 0);
    function report(data) {
        const url = new URL(config.initialSignedUrl || config.signedUrl);
        // The refresh API already percent-encodes tokenPath; URLSearchParams encodes it again.
        let path = data.tokenPath;
        if (typeof path === 'string' && /^%2f/i.test(path)) path = decodeURIComponent(path);
        url.searchParams.set('token', data.token);
        url.searchParams.set('expires', String(data.expires));
        url.searchParams.set('token_path', path);
        current = data;
        JavrateAuth.onSource(url.href, Math.max(0, Number(data.expires) * 1000 - now()));
    }
    async function refresh() {
        if (!config || stopped || Date.now() < nextAttempt) return;
        if (pending) return pending;
        pending = (async function () {
            const timestamp = Math.floor(now() / 1000);
            const encoder = new TextEncoder();
            const key = await crypto.subtle.importKey('raw', encoder.encode(config.apiSecret),
                {name: 'HMAC', hash: 'SHA-256'}, false, ['sign']);
            const signatureBytes = await crypto.subtle.sign('HMAC', key,
                encoder.encode(config.movieId + '|' + timestamp + '|' + config.sessionId));
            const signature = btoa(String.fromCharCode.apply(null, new Uint8Array(signatureBytes)));
            const controller = new AbortController();
            const timeout = setTimeout(() => controller.abort(), 8000);
            try {
                const response = await fetch('/api/token/generate', {
                    method: 'POST', headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify({sessionId: config.sessionId, movieId: config.movieId,
                        guidId: config.guidId, timestamp: timestamp, url: location.href, signature: signature}),
                    signal: controller.signal
                });
                if (!response.ok) {
                    if (response.status === 400 || response.status === 403) stopped = true;
                    throw new Error('Token HTTP ' + response.status);
                }
                const body = await response.json();
                const data = body.data;
                if (!data || !data.token || !data.tokenPath || !data.expires) throw new Error('Invalid token response');
                report(data);
                failures = 0;
                nextAttempt = 0;
            } finally { clearTimeout(timeout); }
        })().catch(function () {
            nextAttempt = Date.now() + Math.min(++failures * 3000, 30000);
            JavrateAuth.onFailure();
        }).finally(function () { pending = null; });
        return pending;
    }
    window.__javrateRefresh = refresh;
    let previousTracks = '';
    function reportTracks() {
        const tracks = Array.from(document.querySelectorAll('video track[src]')).map(track => ({
            src: track.src, label: track.label || track.srclang || 'Subtitles', lang: track.srclang || ''
        }));
        const json = JSON.stringify(tracks);
        if (json !== previousTracks && typeof JavrateAuth.onSubtitles === 'function') {
            previousTracks = json;
            JavrateAuth.onSubtitles(json);
        }
    }
    // Capture the site's own configuration; no hidden video decoder or duplicate stream runs.
    Object.defineProperty(window, 'createPlayer', {
        configurable: true,
        get: function () { return function (options) {
            config = options;
            return {
                init: function () {
                    reportTracks();
                    new MutationObserver(reportTracks).observe(document.documentElement,
                        {childList: true, subtree: true, attributes: true, attributeFilter: ['src', 'label', 'srclang']});
                    clearInterval(timer);
                    if (Number(config.cloudType) === 1) {
                        JavrateAuth.onSource(new URL(config.m3u8Url, location.href).href, 0);
                        return;
                    }
                    report({token: config.initialToken, expires: config.initialExpires,
                        tokenPath: config.initialTokenPath});
                    if (Number(current.expires) * 1000 - now() < 12000) refresh();
                    timer = setInterval(function () {
                        if (!current || Number(current.expires) * 1000 - now() < 12000) refresh();
                    }, 3000);
                },
                setReporter: function () {},
                destroy: function () { clearInterval(timer); }
            };
        }; },
        set: function () {}
    });
})();
