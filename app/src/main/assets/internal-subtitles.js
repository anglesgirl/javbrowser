(function () {
    const video = document.getElementById('video');
    const selector = document.getElementById('subtitles');
    const captions = document.createElement('div');
    captions.id = 'subtitle-overlay';
    captions.style.cssText = 'position:absolute;pointer-events:none;z-index:1;text-align:center;color:white;line-height:1.35;text-shadow:0 1px 3px black;white-space:pre-line;';
    document.getElementById('wrap').appendChild(captions);
    let generation = 0, urls = [], controller, previous = '', selected = null;
    function positionCaptions() {
        const rect = video.getBoundingClientRect();
        const parent = captions.parentElement.getBoundingClientRect();
        if (!video.videoWidth || !video.videoHeight) return;
        const scale = Math.min(rect.width / video.videoWidth, rect.height / video.videoHeight);
        const width = video.videoWidth * scale, height = video.videoHeight * scale;
        captions.style.left = (rect.left - parent.left + (rect.width - width) / 2 + width * .03) + 'px';
        captions.style.width = (width * .94) + 'px';
        captions.style.bottom = (parent.bottom - rect.top - (rect.height + height) / 2 + Math.max(8, height * .04)) + 'px';
        captions.style.fontSize = Math.max(14, Math.min(24, width * .035)) + 'px';
    }
    function renderCaptions() {
        captions.replaceChildren();
        const track = video.querySelectorAll('track[data-app-subtitle]')[Number(selected)];
        if (!track || selected === '-1') return;
        Array.from(track.track.activeCues || []).forEach(cue => {
            const line = document.createElement('div');
            const text = document.createElement('span');
            text.style.cssText = 'background:rgba(0,0,0,.65);padding:1px 4px;box-decoration-break:clone;-webkit-box-decoration-break:clone;';
            text.appendChild(cue.getCueAsHTML());
            line.appendChild(text);
            captions.appendChild(line);
        });
        positionCaptions();
    }
    new ResizeObserver(positionCaptions).observe(video);
    video.addEventListener('loadedmetadata', positionCaptions);
    video.addEventListener('resize', positionCaptions);
    window.addEventListener('resize', positionCaptions);
    selector.addEventListener('change', function () {
        selected = selector.value;
        video.querySelectorAll('track[data-app-subtitle]').forEach((track, i) => {
            track.track.mode = String(i) === selected ? 'hidden' : 'disabled';
        });
        renderCaptions();
    });
    window.setSubtitleTracks = async function (tracks) {
        const identity = JSON.stringify(tracks);
        if (identity === previous) return;
        previous = identity;
        const current = ++generation;
        if (controller) controller.abort();
        controller = new AbortController();
        video.querySelectorAll('track[data-app-subtitle]').forEach(track => track.remove());
        captions.replaceChildren();
        urls.forEach(URL.revokeObjectURL);
        urls = [];
        selector.replaceChildren();
        selector.add(new Option(selector.dataset.off, '-1'));
        selector.hidden = tracks.length === 0;
        if (!tracks.length) return;
        const preferred = tracks.findIndex(track => /^zh(-hant|-tw)?$/i.test(track.lang));
        if (selected === null) selected = String(preferred >= 0 ? preferred : 0);
        selector.value = '-1';
        try {
            const texts = await Promise.all(tracks.map(async track => {
                const response = await fetch(track.src, {signal: controller.signal});
                if (!response.ok) throw new Error('Subtitle HTTP ' + response.status);
                const text = (await response.text()).replace(/^\uFEFF/, '');
                if (!text.startsWith('WEBVTT')) throw new Error('Invalid WebVTT');
                return text;
            }));
            if (generation !== current) return;
            texts.forEach((text, i) => {
                const url = URL.createObjectURL(new Blob([text], {type: 'text/vtt'}));
                urls.push(url);
                const track = document.createElement('track');
                track.kind = 'subtitles';
                track.label = tracks[i].label;
                track.srclang = tracks[i].lang;
                track.dataset.appSubtitle = 'true';
                track.src = url;
                track.default = String(i) === selected;
                track.addEventListener('load', () => {
                    track.track.mode = String(i) === selected ? 'hidden' : 'disabled';
                    renderCaptions();
                });
                track.track.addEventListener('cuechange', renderCaptions);
                selector.add(new Option(track.label, String(i)));
                video.appendChild(track);
                track.track.mode = String(i) === selected ? 'hidden' : 'disabled';
            });
            selector.value = selected;
        } catch (error) {
            if (generation === current && error.name !== 'AbortError') {
                selector.options[0].text = selector.dataset.error;
                selector.value = '-1';
                console.warn('Subtitle loading failed');
            }
        }
    };
})();
