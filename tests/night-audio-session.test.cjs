'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { NightAudioSession, classifyAudio, hasOwnedFilter } = require('../src/audio/MpvNightAudioSession');

const pcm = { format: 'float', samplerate: 48000, 'channel-count': 2 };
const encoded = { format: 'spdif-ac3', samplerate: 48000, 'channel-count': 2 };
const activeFilter = [{ label: 'stremio_night_audio', name: 'lavfi' }];
const nights = { when: 'scheduled', strength: 'balanced', schedule: {
    timeZone: 'Europe/London', windows: [{ start: '22:00', end: '07:00' }],
} };
function harness(now = Date.parse('2026-10-12T23:00:00Z')) {
    const commands = [], states = [];
    const session = new NightAudioSession({
        send: (...args) => commands.push(args),
        changed: (state) => states.push(state),
        clock: () => now,
    });
    return {
        session, commands, states,
        setNow: (stamp) => { now = Date.parse(stamp); },
        prepare: (input = pcm, output = pcm, filter = []) => {
            session.begin();
            session.observe('af', filter);
            session.observe('audio-params', input);
            session.observe('audio-out-params', output);
            session.observe('aid', 1);
            session.setReady(true);
        },
    };
}
const filterCommands = (items) => items.filter(([channel, args]) => channel === 'mpv-command' && args[0] === 'af');
const result = (h) => h.session.snapshot();

test('compression is never added by default', () => {
    const h = harness();
    h.prepare();
    assert.equal(result(h).status, 'off');
    assert.deepEqual(filterCommands(h.commands), []);
});
test('preparation only sends a named filter after confirmed PCM and a chosen audio track', () => {
    const h = harness();
    h.session.configure({ when: 'always' });
    h.session.begin();
    h.session.observe('af', []);
    h.session.observe('audio-params', pcm);
    h.session.observe('audio-out-params', pcm);
    h.session.setReady(true);
    assert.equal(filterCommands(h.commands).length, 0);
    h.session.observe('aid', 1);
    assert.equal(result(h).status, 'applying');
    assert.deepEqual(filterCommands(h.commands)[0][1].slice(0, 2), ['af', 'add']);
});
test('the filter must be observed before active is reported', () => {
    const h = harness();
    h.session.configure({ when: 'always' });
    h.prepare();
    assert.equal(result(h).status, 'applying');
    h.session.observe('af', activeFilter);
    assert.equal(result(h).status, 'active');
    assert.equal(result(h).audioPath, 'pcm');
});
test('surround PCM remains eligible without implicit downmix', () => {
    const h = harness();
    h.session.configure({ when: 'always' });
    h.prepare({ ...pcm, 'channel-count': 6 }, { ...pcm, 'channel-count': 6 });
    assert.equal(result(h).status, 'applying');
    assert.ok(!JSON.stringify(h.commands).includes('audio-channels'));
});
test('compressed passthrough never receives a DSP command', () => {
    const h = harness();
    h.session.configure({ when: 'always' });
    h.prepare(encoded, encoded);
    assert.equal(result(h).status, 'bypassed');
    assert.deepEqual(filterCommands(h.commands), []);
});
test('unknown output audio format fails safely', () => {
    const h = harness();
    h.session.configure({ when: 'always' });
    h.prepare(pcm, { format: 'mystery' });
    assert.equal(result(h).status, 'unavailable');
    assert.deepEqual(filterCommands(h.commands), []);
});
test('switching to passthrough removes the named filter but not other filters', () => {
    const h = harness();
    h.session.configure({ when: 'always' });
    h.prepare();
    h.session.observe('af', activeFilter);
    h.session.observe('audio-out-params', encoded);
    assert.deepEqual(filterCommands(h.commands).at(-1), ['mpv-command', ['af', 'remove', '@stremio_night_audio']]);
    h.session.observe('af', []);
    assert.equal(result(h).status, 'bypassed');
});
test('disabling removes only Night Audio then confirms off from readback', () => {
    const h = harness();
    h.session.configure({ when: 'always' });
    h.prepare();
    h.session.observe('af', activeFilter);
    h.session.configure({ when: 'off' });
    assert.equal(result(h).status, 'removing');
    h.session.observe('af', []);
    assert.equal(result(h).status, 'off');
    assert.deepEqual(filterCommands(h.commands).at(-1), ['mpv-command', ['af', 'remove', '@stremio_night_audio']]);
});
test('changing strength removes before adding a different preset', () => {
    const h = harness();
    h.session.configure({ when: 'always', strength: 'gentle' });
    h.prepare();
    h.session.observe('af', activeFilter);
    h.session.configure({ when: 'always', strength: 'strong' });
    assert.equal(result(h).status, 'removing');
    const before = filterCommands(h.commands).length;
    h.session.observe('af', []);
    assert.equal(filterCommands(h.commands).length, before + 1);
    assert.ok(filterCommands(h.commands).at(-1)[1][2].includes('ratio=4'));
    h.session.observe('af', activeFilter);
    assert.equal(result(h).status, 'active');
});
test('the scheduled boundary is applied without restarting the stream', () => {
    const h = harness(Date.parse('2026-10-12T19:00:00Z'));
    h.session.configure(nights);
    h.prepare();
    assert.equal(result(h).status, 'off');
    h.setNow('2026-10-12T21:00:00Z');
    h.session.tick();
    assert.equal(result(h).status, 'applying');
    h.session.observe('af', activeFilter);
    h.setNow('2026-10-13T06:00:00Z');
    h.session.tick();
    assert.equal(result(h).status, 'removing');
    h.session.observe('af', []);
    assert.equal(result(h).status, 'off');
});
test('ending a session removes any named filter without keeping the old profile active', () => {
    const h = harness();
    h.session.configure({ when: 'always' });
    h.prepare();
    h.session.observe('af', activeFilter);
    h.session.end();
    assert.equal(result(h).status, 'checking');
    assert.deepEqual(filterCommands(h.commands).at(-1), ['mpv-command', ['af', 'remove', '@stremio_night_audio']]);
});
test('audio properties from a previous playback cannot activate without current readiness', () => {
    const h = harness();
    h.session.configure({ when: 'always' });
    h.prepare();
    h.session.observe('af', activeFilter);
    h.session.end();
    h.session.begin();
    h.session.observe('af', []);
    h.session.observe('audio-params', pcm);
    h.session.observe('audio-out-params', pcm);
    h.session.observe('aid', 1);
    assert.equal(result(h).status, 'checking');
});
test('invalid configurations cannot inject additional MPV commands', () => {
    const h = harness();
    h.session.configure({ when: 'always', strength: 'strong:evil,af=add' });
    h.prepare();
    assert.ok(filterCommands(h.commands)[0][1][2].includes('ratio=2.8'));
    assert.equal(filterCommands(h.commands).length, 1);
});
test('one add without readback times out rather than becoming active', () => {
    const h = harness();
    h.session.configure({ when: 'always' });
    h.prepare();
    h.setNow('2026-10-12T23:00:05Z');
    h.session.tick();
    assert.equal(result(h).status, 'unavailable');
    assert.deepEqual(filterCommands(h.commands).at(-1), ['mpv-command', ['af', 'remove', '@stremio_night_audio']]);
});
test('no repeated additions on an unchanged or stalled filter observation', () => {
    const h = harness();
    h.session.configure({ when: 'always' });
    h.prepare();
    h.session.tick(); h.session.tick(); h.session.tick();
    h.session.observe('af', []);
    assert.equal(filterCommands(h.commands).length, 1);
});
test('session status is private and contains no media identifier', () => {
    const h = harness();
    h.session.configure({ when: 'always' });
    h.prepare();
    assert.ok(Object.isFrozen(h.session.snapshot()));
    assert.ok(!JSON.stringify(h.session.snapshot()).includes('https:'));
});
test('formats do not infer PCM just because a codec is playable', () => {
    assert.equal(classifyAudio(null, pcm), 'unknown');
    assert.equal(classifyAudio(pcm, encoded), 'bitstream');
    assert.equal(classifyAudio(pcm, pcm), 'pcm');
    assert.equal(classifyAudio({ format: 'truehd' }, pcm), 'bitstream');
});
test('only our own named MPV filter is recognised', () => {
    assert.equal(hasOwnedFilter([{ label: 'compressor' }]), false);
    assert.equal(hasOwnedFilter(activeFilter), true);
});
