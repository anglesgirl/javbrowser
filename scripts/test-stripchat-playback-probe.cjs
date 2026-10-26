const {readFileSync} = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const {test} = require('node:test');
const source = readFileSync('app/src/main/assets/stripchat-playback-probe.js', 'utf8');

function fixture() {
    let now = 10000;
    const video = {paused: false, ended: false, readyState: 4, videoWidth: 1280,
        duration: Infinity, currentSrc: 'blob:fixture-live', currentTime: 10,
        matches: () => false, closest: () => null,
        getBoundingClientRect: () => ({width: 640, height: 480})};
    const context = {URL, location: {href: 'https://stripchat.com/Example'},
        Date: {now: () => now}, document: {querySelectorAll: () => [video]}};
    context.window = context;
    vm.runInNewContext(source, context);
    return {video, context, probe: context.__javStripchatPlaybackProbe,
        advance: () => {now += 1250; video.currentTime += 1.25;}};
}

test('requires two progressing live-player samples for the same model and source', () => {
    const f = fixture();
    assert.equal(f.probe.sample(f.context.location.href), false);
    f.advance();
    assert.equal(f.probe.sample(f.context.location.href), true);
    f.video.currentSrc = 'blob:new-source';
    f.advance();
    assert.equal(f.probe.sample(f.context.location.href), false);
    f.context.location.href = 'https://stripchat.com/AnotherModel';
    f.advance();
    assert.equal(f.probe.sample('https://stripchat.com/Example'), false);
});

test('does not mistake paused, stalled, finite previews or other domains for live playback', () => {
    for (const change of [v => {v.paused = true;}, v => {v.duration = 60;},
        v => {v.matches = () => true;}, v => {v.videoWidth = 0;}]) {
        const f = fixture(); change(f.video);
        f.probe.sample(f.context.location.href); f.advance();
        assert.equal(f.probe.sample(f.context.location.href), false);
    }
    const f = fixture();
    f.probe.sample(f.context.location.href);
    f.advance(); f.video.currentTime = 10;
    assert.equal(f.probe.sample(f.context.location.href), false);
    assert.equal(f.probe.sample('https://notstripchat.com/Example'), false);
});

test('ignores stale overlay players belonging to a different model', () => {
    const f = fixture();
    f.video.closest = selector => selector === '#jav-internal-player-panel'
        ? {getAttribute: () => 'https://stripchat.com/PreviousModel'} : null;
    f.probe.sample(f.context.location.href); f.advance();
    assert.equal(f.probe.sample(f.context.location.href), false);
});
