'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const audio = require('../src/audio/nightAudioPolicy');

const at = (date) => new Date(date);
const ready = { audioPath: 'pcm', canCompress: true };
const ctx = (now, backend = ready, extra = {}) => ({
    now: at(now), backend, playbackId: 'sample-session', ...extra,
});
const profile = (when = 'always', other = {}) => ({
    when, configuration: 'automatic', strength: 'balanced', ...other,
});
const nights = {
    timeZone: 'Europe/London',
    windows: [{ start: '22:00', end: '07:00' }],
};

test('existing users default to off with no implicit processing', () => {
    const result = audio.resolveNightAudio(null, ctx('2026-10-12T23:00:00Z'));
    assert.equal(result.status, 'off');
    assert.equal(result.requested, false);
    assert.deepEqual(audio.proposeMpvCommands(result, false), []);
});
test('automatic is independent of always or scheduled activation', () => {
    const first = audio.resolveNightAudio(profile('always'), ctx('2026-10-12T12:00:00Z'));
    const second = audio.resolveNightAudio(profile('scheduled', { schedule: nights }), ctx('2026-10-12T12:00:00Z'));
    assert.equal(first.configuration, 'automatic');
    assert.equal(first.status, 'ready');
    assert.equal(second.status, 'off');
});
test('custom strength selects a fixed allowlisted DSP policy', () => {
    const x = audio.resolveNightAudio(profile('always', { configuration: 'custom', strength: 'strong' }),
        ctx('2026-10-12T12:00:00Z'));
    assert.equal(x.strength, 'strong');
    assert.equal(x.params.ratio, 4);
    assert.ok(audio.buildMpvFilter(x.strength).includes('ratio=4'));
    assert.equal(audio.buildMpvFilter('strong,af=evil'), null);
});
test('an invalid schedule does not silently become permanent on', () => {
    const x = audio.resolveNightAudio(profile('scheduled', { schedule: {
        timeZone: 'Not/AZone', windows: [{ start: '22:00', end: '07:00' }],
    } }), ctx('2026-10-12T23:00:00Z'));
    assert.equal(x.status, 'off');
});
test('overnight schedules include late evening and early morning, not midday', () => {
    for (const time of ['2026-10-12T21:00:00Z', '2026-10-13T05:30:00Z']) {
        assert.equal(audio.scheduleMatches(nights, at(time)), true);
    }
    assert.equal(audio.scheduleMatches(nights, at('2026-10-13T12:00:00Z')), false);
});
test('end boundary is exclusive and equal start and end is not permanent mode', () => {
    assert.equal(audio.scheduleMatches(nights, at('2026-10-13T06:00:00Z')), false);
    assert.equal(audio.scheduleMatches({ timeZone: 'UTC', windows: [{ start: '07:00', end: '07:00' }] },
        at('2026-10-13T07:00:00Z')), false);
});
test('spring daylight saving clock skips nonexistent local time', () => {
    const window = { timeZone: 'Europe/London', windows: [{ start: '01:00', end: '02:00' }] };
    assert.equal(audio.scheduleMatches(window, at('2026-03-29T01:30:00Z')), false);
});
test('both occurrences of repeated autumn local clock time are respected', () => {
    const window = { timeZone: 'Europe/London', windows: [{ start: '01:00', end: '02:00' }] };
    assert.equal(audio.scheduleMatches(window, at('2026-10-25T00:30:00Z')), true);
    assert.equal(audio.scheduleMatches(window, at('2026-10-25T01:30:00Z')), true);
});
test('multiple user windows work without confusing their boundaries', () => {
    const windows = { timeZone: 'UTC', windows: [{ start: '12:00', end: '13:00' }, { start: '20:00', end: '23:00' }] };
    assert.equal(audio.scheduleMatches(windows, at('2026-10-12T20:30:00Z')), true);
    assert.equal(audio.scheduleMatches(windows, at('2026-10-12T15:30:00Z')), false);
});
test('manual override requires current playback and expires exactly at its boundary', () => {
    const override = { playbackId: 'sample-session', enabled: true, expiresAtMs: at('2026-10-12T15:00:00Z').getTime() };
    const x = profile('off');
    assert.equal(audio.resolveNightAudio(x, ctx('2026-10-12T14:59:59Z', ready, { override })).status, 'ready');
    assert.equal(audio.resolveNightAudio(x, ctx('2026-10-12T15:00:00Z', ready, { override })).status, 'off');
    assert.equal(audio.resolveNightAudio(x, ctx('2026-10-12T14:59:59Z', ready, { override, playbackId: 'different' })).status, 'off');
});
test('an override can temporarily disable an always profile', () => {
    const override = { playbackId: 'sample-session', enabled: false, expiresAtMs: at('2026-10-12T15:00:00Z').getTime() };
    assert.equal(audio.resolveNightAudio(profile(), ctx('2026-10-12T14:00:00Z', ready, { override })).status, 'off');
});
test('passthrough is never mistaken for decoded sound', () => {
    const x = audio.resolveNightAudio(profile(), ctx('2026-10-12T12:00:00Z', { audioPath: 'bitstream', canCompress: true }));
    assert.equal(x.status, 'bypassed');
    assert.deepEqual(audio.proposeMpvCommands(x, false), []);
});
test('unknown or unsupported audio capability fails closed', () => {
    for (const backend of [null, { audioPath: 'unknown', canCompress: true }, { audioPath: 'pcm', canCompress: false }]) {
        const x = audio.resolveNightAudio(profile(), ctx('2026-10-12T12:00:00Z', backend));
        assert.equal(x.status, 'unavailable');
        assert.deepEqual(audio.proposeMpvCommands(x, false), []);
    }
});
test('active status requires a matching, observed playback and strength', () => {
    const now = '2026-10-12T12:00:00Z';
    const exact = { ...ready, applied: true, appliedStrength: 'balanced', appliedPlaybackId: 'sample-session' };
    assert.equal(audio.resolveNightAudio(profile(), ctx(now, exact)).status, 'active');
    assert.equal(audio.resolveNightAudio(profile(), ctx(now, { ...exact, appliedPlaybackId: 'old' })).status, 'ready');
    assert.equal(audio.resolveNightAudio(profile('always', { strength: 'strong' }), ctx(now, exact)).status, 'ready');
});
test('the current output profile is not reused for another output', () => {
    const key = audio.outputProfileKey('fire-tv', 'HDMI soundbar');
    const profiles = { [key]: profile('always') };
    assert.deepEqual(audio.selectOutputProfile(profiles, 'fire-tv', 'HDMI soundbar'), profiles[key]);
    assert.equal(audio.selectOutputProfile(profiles, 'fire-tv', 'TV speakers'), null);
    assert.equal(audio.selectOutputProfile(profiles, 'second-tv', 'HDMI soundbar'), null);
    assert.equal(audio.outputProfileKey('fire-tv', ''), null);
});
test('audio filter commands modify only the named filter, never the full filter chain', () => {
    const x = audio.resolveNightAudio(profile(), ctx('2026-10-12T12:00:00Z'));
    const commands = audio.proposeMpvCommands(x, true);
    assert.deepEqual(commands.map((c) => c.slice(0, 2)), [['af', 'remove'], ['af', 'add']]);
    assert.equal(commands[0][2], audio.FILTER_LABEL);
    assert.ok(commands[1][2].startsWith(audio.FILTER_LABEL + ':lavfi='));
    assert.ok(!JSON.stringify(commands).includes('"set"'));
});
test('disabling removes only the owned filter when it was verified present', () => {
    const x = audio.resolveNightAudio(null, ctx('2026-10-12T12:00:00Z'));
    assert.deepEqual(audio.proposeMpvCommands(x, true), [['af', 'remove', audio.FILTER_LABEL]]);
});
test('dialogue boosting is not falsely claimed implemented by compressor alone', () => {
    const x = audio.resolveNightAudio(profile('always', { dialogueClarity: 'high' }), ctx('2026-10-12T12:00:00Z'));
    assert.equal(x.dialogue, 'requested-not-implemented');
});
test('policy decisions contain no media titles, URLs or identifiers', () => {
    const x = audio.resolveNightAudio(profile(), ctx('2026-10-12T12:00:00Z', ready, { playbackId: 'private-title' }));
    assert.equal(JSON.stringify(x).includes('private-title'), false);
    assert.equal(Object.isFrozen(x), true);
});
