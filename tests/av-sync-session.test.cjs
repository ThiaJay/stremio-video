'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const Session = require('../src/ShellVideo/AvSyncSession');
function setup(mode = 'display-resample') {
    let now = 1000;
    const sent = [], emitted = [];
    const playback = { loaded: true, url: 'https://example.invalid/synthetic.mkv' };
    const session = new Session({ clock: () => now, getPlayback: () => playback, send: (...args) => sent.push(args), emit: (...args) => emitted.push(args) });
    function ready() {
        for (const [key, value] of Object.entries({ path: playback.url, pause: false, seeking: false, 'paused-for-cache': false, 'eof-reached': false, aid: 1, vid: 1, speed: 1, duration: 120, 'time-pos': 30, seekable: true, 'video-sync': mode, 'audio-delay': 0, 'initial-audio-sync': true, 'frame-drop-count': 0, 'decoder-frame-drop-count': 0 })) session.update(key, value);
    }
    session.begin(1); ready(); now = 4500; session.observe(0.4);
    function request(correction = 'nativeClock') {
        const s = session.snapshot();
        return { sessionId: 1, epoch: s.epoch, sampleId: s.sampleId, generation: 1, issuedAtMs: now, expiresAtMs: now + 1500, correction };
    }
    return { session, sent, emitted, playback, ready, request, set: (value) => { now = value; } };
}
test('native correction is scoped to the current file and never writes audio delay', () => {
    const x = setup(); x.session.correct(x.request());
    assert.deepEqual(x.sent, [['mpv-set-prop', ['file-local-options/video-sync', 'audio']]]);
    assert.equal(x.session.lastAck().accepted, true);
});
test('same position recovery uses relative exact seek rather than stale absolute time', () => {
    const x = setup('audio'); x.session.correct(x.request('reseek'));
    assert.deepEqual(x.sent, [['mpv-command', ['seek', 0, 'relative+exact']]]);
});
test('unbound clients cannot accidentally activate recovery', () => {
    const x = setup(); x.session.begin(undefined); x.ready(); x.set(9000); x.session.observe(0.4); assert.equal(x.session.snapshot(), null); assert.equal(x.sent.length, 0);
});
for (const [field, value] of [['sessionId', 2], ['epoch', 0], ['sampleId', 999], ['generation', 0], ['issuedAtMs', 6000], ['expiresAtMs', 4499], ['expiresAtMs', 9999], ['sampleId', NaN], ['sessionId', '1'], ['correction', 'audioDelay']]) {
    test('invalid request rejected for ' + field + ' ' + value, () => {
        const x = setup(); const r = x.request(); r[field] = value; x.session.correct(r); assert.equal(x.sent.length, 0);
    });
}
test('repeated requests do not replay native commands', () => {
    const x = setup(); const r = x.request(); x.session.correct(r); x.session.correct(r); assert.equal(x.sent.length, 1);
});
test('later samples invalidate earlier request sample identities', () => {
    const x = setup(); const r = x.request(); x.set(5000); x.session.observe(0.4); x.session.correct(r); assert.equal(x.sent.length, 0);
});
test('expired observations cannot trigger a command', () => {
    const x = setup(); const r = x.request(); x.set(6001); x.session.correct(r); assert.equal(x.sent.length, 0); assert.equal(x.session.snapshot(), null);
});
for (const [name, value] of [['pause', true], ['seeking', true], ['paused-for-cache', true], ['eof-reached', true], ['aid', false], ['vid', false], ['path', 'https://example.invalid/other.mkv'], ['audio-delay', 0.12], ['speed', 1.5], ['frame-drop-count', 1], ['decoder-frame-drop-count', 1]]) {
    test('current native boundary rejects pending correction for ' + name, () => {
        const x = setup(); const r = x.request(); x.session.update(name, value); x.session.correct(r); assert.equal(x.sent.length, 0);
    });
}
test('seek completion never hides ongoing buffering', () => {
    const x = setup(); x.session.update('paused-for-cache', true); x.session.update('seeking', true); x.session.update('seeking', false); x.set(9000); x.session.observe(0.4); assert.equal(x.session.snapshot(), null);
});
test('quiet audio and manual offsets do not become guessed clock repairs', () => {
    const x = setup(); x.session.update('audio-delay', 0.2); x.set(9000); x.session.observe(0.4); assert.equal(x.session.snapshot().canSoftCorrect, false); assert.equal(x.session.snapshot().canHardCorrect, false);
});
test('live and unseekable sources never advertise same position recovery', () => {
    for (const [name, value] of [['duration', Infinity], ['seekable', false], ['time-pos', 119], ['time-pos', 0]]) {
        const x = setup('audio'); x.session.update(name, value); x.set(9000); x.session.observe(0.4); assert.equal(x.session.snapshot().canHardCorrect, false);
    }
});
test('intentional desync mode is not overridden', () => {
    const x = setup('desync'); const s = x.session.snapshot(); assert.equal(s.canSoftCorrect, false); assert.equal(s.canHardCorrect, false);
});
test('clock reversal is rejected', () => {
    const x = setup(); const r = x.request(); x.set(4499); x.session.correct(r); assert.equal(x.sent.length, 0);
});
test('both budgets survive reopening the same Core session', () => {
    const x = setup(); x.session.correct(x.request()); x.session.close(); x.session.begin(1); x.ready(); x.set(40000); x.session.observe(0.4); assert.equal(x.session.snapshot().canSoftCorrect, false);
});
test('a new Core load may start a fresh recovery budget', () => {
    const x = setup(); x.session.correct(x.request()); x.session.begin(2); x.ready(); x.set(40000); x.session.observe(0.4); assert.equal(x.session.snapshot().canSoftCorrect, true);
});
test('invalid samples do not masquerade as zero timing difference', () => {
    for (const value of [null, undefined, NaN, Infinity, -Infinity, '0.4', true, 61, -61]) {
        const x = setup(); x.set(5000); x.session.observe(value); assert.equal(x.session.snapshot(), null);
    }
});
test('close invalidates queued requests and future samples', () => {
    const x = setup(); const r = x.request(); x.session.close(); x.session.correct(r); x.session.observe(0.4); assert.equal(x.sent.length, 0); assert.equal(x.session.snapshot(), null);
});
test('timing samples contain no source URLs or personal identifiers', () => {
    const x = setup(); assert.ok(Object.isFrozen(x.session.snapshot())); assert.ok(!JSON.stringify(x.session.snapshot()).includes('example.invalid')); assert.deepEqual(Object.keys(x.session.snapshot()).sort(), ['sessionId','epoch','sampleId','capturedAtMs','offsetMs','active','videoStable','canSoftCorrect','canHardCorrect'].sort());
});
test('actual ShellVideo cancels superseded loads before MPV version arrives', async () => {
    const { EventEmitter } = require('node:events');
    const ShellVideo = require('../src/ShellVideo/ShellVideo');
    global.window = { document: { getElementsByTagName: () => [] } };
    const ipc = new EventEmitter(); const sent = []; ipc.send = (...args) => sent.push(args);
    const shell = new ShellVideo({ shellTransport: ipc, containerElement: { style: {}, parentElement: null } });
    for (const id of [1, 2]) shell.dispatch({ type: 'command', commandName: 'load', commandArgs: { stream: { url: 'https://example.invalid/' + id }, avSyncSessionId: id, time: 0 } });
    ipc.emit('mpv-prop-change', { name: 'mpv-version', data: '0.40.0' }); await Promise.resolve();
    const loads = sent.filter(([name, args]) => name === 'mpv-command' && args[0] === 'loadfile');
    assert.equal(loads.length, 1); assert.equal(loads[0][1][1], 'https://example.invalid/2');
    shell.dispatch({ type: 'command', commandName: 'destroy' }); const before = sent.length;
    ipc.emit('mpv-prop-change', { name: 'avsync', data: 0.4 }); assert.equal(sent.length, before);
    assert.ok(ShellVideo.manifest.commands.includes('correctAvSyncV2')); assert.ok(!ShellVideo.manifest.commands.includes('correctAvSync'));
});
