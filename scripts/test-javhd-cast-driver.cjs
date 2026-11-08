const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/javhd_driver_cast.js'), 'utf8');
function boot({ serverCount = 0, token = 'current-token', buttonCount = 1 } = {}) {
  const events = [];
  function collection(kind, count, index) {
    return {
      length: count,
      removeClass(name) { events.push([kind, 'removeClass', name]); return this; },
      eq(i) { return collection(kind, i < count ? 1 : 0, i); },
      trigger(name) { events.push([kind, 'trigger', name, index]); return this; }
    };
  }
  function jq(selector) {
    if (selector === '.box-server .srv') return collection('server', serverCount);
    if (selector === '#video-player .play-button.button') return collection('button', buttonCount);
    throw new Error('unexpected selector: ' + selector);
  }
  const window = { jQuery: jq };
  const document = { getElementById(id) { return id === 'video-player' && token ? { getAttribute(name) { return name === 'data-mpu' ? token : null; } } : null; } };
  vm.runInNewContext(source, { window, document });
  return { window, jq, events };
}
let count = 0;
function test(name, fn) { fn(); count++; console.log('PASS', name); }
test('startup AJAX cannot shift play callback indexes', () => {
  const { window, jq } = boot(); let startup = 0; const received = [];
  jq.ajax({ url: '/api/scribe/' }).done(() => startup++);
  const fields = { sources: 'opaque-source', ver: '2', extra: 'keep-me' };
  jq.ajax({ url: '/api/play/', type: 'post', data: fields }).done(value => received.push(value)).then(value => received.push(value));
  assert.equal(window.__javhdDoneCbs.length, 1);
  assert.equal(window.__javhdAjaxCalls[0].playIndex, -1);
  assert.equal(window.__javhdAjaxCalls[1].playIndex, 0);
  assert.equal(window.__javhdAjaxCalls[1].data, fields);
  const response = { status: true, data: 'encrypted' };
  window.__javhdDoneCbs[0](response);
  assert.equal(startup, 0); assert.deepEqual(received, [response, response]);
});
test('each Part dispatches only its own callback', () => {
  const { window, jq } = boot(); const received = [];
  jq.ajax({ url: '/api/play/', data: { sources: 'one', ver: '2' } }).done(x => received.push(['one', x]));
  jq.ajax({ url: '/other/' }).done(() => received.push(['wrong']));
  jq.ajax('https://www.javhdporn.net/api/play/?x=1', { method: 'POST', data: 'sources=two&ver=2' }).done(x => received.push(['two', x]));
  window.__javhdDoneCbs[1]('response-two'); window.__javhdDoneCbs[0]('response-one');
  assert.deepEqual(received, [['two', 'response-two'], ['one', 'response-one']]);
  assert.equal(window.__javhdAjaxCalls[2].data, 'sources=two&ver=2');
  assert.equal(window.__javhdAjaxCalls[2].playIndex, 1);
});
test('similar non-play endpoint is not a play request', () => {
  const { window, jq } = boot(); jq.ajax({ url: '/api/playback/' }).done(() => {});
  assert.equal(window.__javhdDoneCbs.length, 0);
});
test('data-mpu page triggers its real player button without synthetic servers', () => {
  const { window, events } = boot(); assert.equal(window.__javhdTriggerPart(0), 'player-button');
  assert.deepEqual(events, [['button', 'trigger', 'click', undefined]]);
});
test('genuine legacy server Parts keep their own entry path', () => {
  const { window, events } = boot({ serverCount: 2 }); assert.equal(window.__javhdTriggerPart(1), 'server');
  assert.deepEqual(events, [['server', 'removeClass', 'default-srv'], ['server', 'trigger', 'click', 1]]);
});
test('out-of-range Part never falls through to a different player', () => {
  const { window, events } = boot({ serverCount: 2 }); assert.equal(window.__javhdTriggerPart(2), 'server-index-out-of-range'); assert.equal(events.length, 0);
});
test('missing or ambiguous real entry fails without a click', () => {
  for (const options of [{ token: '' }, { buttonCount: 0 }, { buttonCount: 2 }]) {
    const { window, events } = boot(options); assert.notEqual(window.__javhdTriggerPart(0), 'player-button'); assert.equal(events.length, 0);
  }
});
test('missing jQuery is reported explicitly', () => {
  const window = {}; vm.runInNewContext(source, { window, document: {} }); assert.equal(window.__javhdError, 'no-jquery');
});
console.log(count + ' JavHD driver tests passed');
