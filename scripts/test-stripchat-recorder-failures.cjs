const {readFileSync} = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const {test} = require('node:test');
const nativeSource = readFileSync('app/src/main/java/com/example/javbrowser/MainActivity.kt', 'utf8');
const start = nativeSource.indexOf('                    function startDirectRecording() {');
const end = nativeSource.indexOf('\n                    window.__javStartStripchatDirectRecording', start);
assert.ok(start >= 0 && end > start, 'Test must execute the actual embedded recorder startup function');
const recorderSource = nativeSource.slice(start, end);

function fixture({unsupported = false, startError = false, writeError = false} = {}) {
    const reports = [], finishes = [], stops = [];
    const videoTrack = {stop() {}}, audioTrack = {stop() {}}, canvasTrack = {stop() {}};
    const sourceStream = {getVideoTracks: () => [videoTrack], getAudioTracks: () => [audioTrack],
        getTracks: () => [videoTrack, audioTrack]};
    const tracks = [canvasTrack];
    const canvasStream = {addTrack: t => tracks.push(t), getTracks: () => tracks,
        getAudioTracks: () => tracks.filter(t => t === audioTrack)};
    class Recorder {
        static isTypeSupported() { return true; }
        constructor() {this.mimeType = 'video/mp4'; this.state = 'inactive';}
        start() {if (startError) throw new Error('fixture encoder startup failure'); this.state = 'recording';}
        stop() {this.state = 'inactive';}
    }
    const context = {reports, finishes, stops, Promise,
        video: {captureStream: unsupported ? undefined : () => sourceStream,
            videoWidth: 1280, videoHeight: 720, currentTime: 12, addEventListener() {}},
        document: {createElement: () => ({getContext: () => ({drawImage() {}}), captureStream: () => canvasStream})},
        MediaRecorder: Recorder, location: {href: 'https://zt.stripchat.com/Fixture'},
        setInterval: () => 1, requestAnimationFrame: () => 2, stopDirectFramePump() {},
        setRecordingState() {}, arrayBufferToBase64: () => 'AA==',
        finishDirectRecording: (...args) => finishes.push(args),
        stopDirectRecording: reason => stops.push(reason),
        Android: {onStripchatRecordingError: (...args) => reports.push(args),
            beginStripchatStreamRecording: () => true,
            finishStripchatStreamRecording: (...args) => finishes.push(args),
            appendStripchatStreamRecordingChunk: () => !writeError}};
    vm.createContext(context);
    vm.runInContext(recorderSource, context);
    return context;
}

test('reports unsupported capture instead of silently returning failure', () => {
    const f = fixture({unsupported: true});
    assert.equal(f.startDirectRecording(), false);
    assert.equal(f.reports[0][0], 'capability');
    assert.equal(f.finishes.length, 0);
});

test('closes native recording storage if the encoder throws after file creation', () => {
    const f = fixture({startError: true});
    assert.equal(f.startDirectRecording(), false);
    assert.equal(f.reports[0][0], 'start');
    assert.match(f.reports[0][1], /encoder startup failure/);
    assert.equal(f.finishes.length, 1);
    assert.equal(f.finishes[0][0], false);
    assert.equal(f.directRecorder, null);
});

test('reports chunk write failure and stops a failed recording', async () => {
    const f = fixture({writeError: true});
    assert.equal(f.startDirectRecording(), true);
    f.directRecorder.ondataavailable({data: {size: 8, arrayBuffer: async () => new ArrayBuffer(8)}});
    await f.directChunkChain;
    assert.equal(f.directRecordingFailed, true);
    assert.equal(f.reports[0][0], 'write');
    assert.equal(f.stops.length, 1);
});
