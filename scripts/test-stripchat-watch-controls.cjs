const {readFileSync} = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const {test} = require('node:test');
const source = readFileSync('app/src/main/assets/stripchat-watch-controls.js', 'utf8');

function fixture(withBody = true) {
    const elements = new Map(), timers = [], events = [];
    const body = {appendChild: node => elements.set(node.id, node)};
    const context = {location: {hostname: 'zt.stripchat.com', pathname: '/AnAn-o',
        href: 'https://zt.stripchat.com/AnAn-o'},
        setInterval: fn => timers.push(fn),
        document: {body: withBody ? body : null, addEventListener() {},
            getElementById: id => elements.get(id),
            createElement: () => ({style: {setProperty(key, value) {this[key] = value;}},
                remove() {elements.delete(this.id);}})},
        Android: {isStripchatWatchSessionActive: () => false,
            isStripchatStreamRecording: () => false, isStripchatRecording: () => false,
            getStripchatWatchStateLabel: () => 'Watch and record',
            onStripchatLocationChanged: url => events.push(url)}};
    context.window = context;
    const scope = vm.createContext(context);
    return {elements, timers, events, body, context,
        run: () => vm.runInContext(source, scope)};
}

test('mounts on localized model domains without waiting for page load and restores removed controls', () => {
    const f = fixture();
    assert.equal(f.run(), 'watch-controls-ready');
    assert.equal(f.elements.size, 2);
    assert.equal(f.elements.get('jav-stripchat-watch-button').textContent, 'Watch and record');
    f.elements.clear();
    f.timers[0]();
    assert.equal(f.elements.size, 2);
    f.run();
    assert.equal(f.timers.length, 1);
    assert.equal(f.events.length, 1);
});

test('handles delayed body creation and model-to-home-to-model SPA navigation', () => {
    const f = fixture(false);
    assert.equal(f.run(), 'waiting-for-body');
    f.context.document.body = f.body;
    f.timers[0]();
    assert.equal(f.elements.size, 2);
    f.context.location.pathname = '/';
    f.context.location.href = 'https://zt.stripchat.com/';
    f.timers[0]();
    assert.equal(f.elements.size, 0);
    f.context.location.pathname = '/AnotherModel';
    f.context.location.href = 'https://zt.stripchat.com/AnotherModel';
    f.timers[0]();
    assert.equal(f.elements.size, 2);
    assert.equal(f.events.at(-1), f.context.location.href);
});
