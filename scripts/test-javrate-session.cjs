const {readFileSync} = require('node:fs');
const {resolve} = require('node:path');
const vm = require('node:vm');
const {webcrypto, createHmac} = require('node:crypto');
const assert = require('node:assert/strict');
const {test} = require('node:test');

test('captures site config, renews once for concurrent requests, keeps valid token on failure', async () => {
    const reports = [], calls = [], subtitles = [];
    let failures = 0, fail = false, timer;
    const config = {initialSignedUrl: 'https://videocdn.avking.xyz/movie/playlist.m3u8',
        initialToken: 'initial', initialExpires: Math.floor(Date.now()/1000)+30,
        initialTokenPath: '/movie/', movieId: 'movie', sessionId: 'session', guidId: 'guid',
        apiSecret: 'fixture-secret', timeDiff: 0};
    const scope = {URL, TextEncoder, crypto: webcrypto, AbortController, Date,
        location: {href: 'https://www.javrate.com/player/v2?payload=fixture'},
        btoa: x => Buffer.from(x, 'binary').toString('base64'),
        setTimeout, clearTimeout, setInterval: fn => {timer = fn; return 1;}, clearInterval: () => {},
        document: {documentElement: {}, querySelectorAll: () => [{src: 'https://subtitle.avking.xyz/TEST-001/sub.vtt', label: '繁體中文', srclang: 'zh-hant'}]},
        MutationObserver: class {observe() {}},
        JavrateAuth: {onSource: (...args) => reports.push(args), onFailure: () => failures++, onSubtitles: raw => subtitles.push(JSON.parse(raw))},
        fetch: async (url, options) => {
            calls.push(JSON.parse(options.body));
            assert.equal(url, '/api/token/generate');
            const body = calls.at(-1);
            assert.equal(body.signature, createHmac('sha256', config.apiSecret)
                .update(`movie|${body.timestamp}|session`).digest('base64'));
            await new Promise(resolve => setImmediate(resolve));
            return {ok: !fail, status: fail ? 503 : 200,
                json: async () => ({data: {token: 'renewed', expires: config.initialExpires+30, tokenPath: '%2Fmovie%2F'}})};
        }};
    scope.window = scope; scope.top = scope;
    vm.runInNewContext(readFileSync(resolve(__dirname, '../app/src/main/assets/javrate-session.js'), 'utf8'), scope);
    scope.createPlayer = () => {throw new Error('The hidden page must not start a second video decoder');};
    scope.createPlayer(config).init();
    assert.equal(reports.length, 1);
    assert.ok(reports[0][0].includes('token=initial'));
    assert.equal(typeof timer, 'function');
    assert.equal(subtitles[0][0].lang, 'zh-hant');
    assert.equal(subtitles[0][0].label, '繁體中文');
    await Promise.all([scope.__javrateRefresh(), scope.__javrateRefresh()]);
    assert.equal(calls.length, 1);
    assert.equal(reports.length, 2);
    assert.ok(reports[1][0].includes('token=renewed'));
    assert.equal(new URL(reports[1][0]).searchParams.get('token_path'), '/movie/');
    assert.ok(!reports[1][0].includes('%252F'));
    fail = true;
    await scope.__javrateRefresh();
    assert.equal(reports.length, 2);
    assert.equal(failures, 1);
});
