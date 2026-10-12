'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { EventEmitter } = require('node:events');
const ShellVideo = require('../src/ShellVideo/ShellVideo');

function makeShell() {
    global.window = { document: { getElementsByTagName: () => [] } };
    const ipc = new EventEmitter();
    const commands = [], updates = [];
    ipc.send = (...args) => commands.push(args);
    const shell = new ShellVideo({
        shellTransport: ipc,
        containerElement: { style: {}, parentElement: null },
    });
    shell.on('propChanged', (name, state) => updates.push([name, state]));
    const event = (name, data) => ipc.emit('mpv-prop-change', { name, data });
    const recent = (kind) => commands.filter(([name, args]) =>
        name === 'mpv-command' && args[0] === 'af' && args[1] === kind);
    async function load() {
        shell.dispatch({ type: 'command', commandName: 'load', commandArgs: {
            stream: { url: 'https://example.invalid/audio-fixture.mkv' },
            avSyncSessionId: 123,
            time: 0,
        } });
        event('mpv-version', '0.40.0');
        await Promise.resolve();
        ipc.emit('mpv-event-video-ready', { loadId: 1, ready: false });
        event('path', 'https://example.invalid/audio-fixture.mkv');
        event('af', []);
        event('audio-params', { format: 'float', samplerate: 48000, 'channel-count': 2 });
        event('audio-out-params', { format: 'float', samplerate: 48000, 'channel-count': 2 });
        event('aid', 1);
        ipc.emit('mpv-event-video-ready', { loadId: 1, ready: true });
    }
    const configure = (profile) => shell.dispatch({
        type: 'command', commandName: 'setNightAudio', commandArgs: { profile },
    });
    const state = () => updates.filter(([name]) => name === 'nightAudio').at(-1)?.[1];
    shell.dispatch({ type: 'observeProp', propName: 'nightAudio' });
    return { ipc, shell, event, commands, updates, load, configure, state, recent };
}

test('real shell transport issues DSP filter only after verified decoded output', async () => {
    const h = makeShell();
    h.configure({ when: 'always', strength: 'balanced' });
    assert.equal(h.state().status, 'checking');
    await h.load();
    assert.equal(h.recent('add').length, 1);
    assert.equal(h.state().status, 'applying');
    h.event('af', [{ label: 'stremio_night_audio', name: 'lavfi' }]);
    assert.equal(h.state().status, 'active');
    assert.equal(h.state().audioPath, 'pcm');
    h.shell.dispatch({ type: 'command', commandName: 'destroy' });
    assert.ok(h.recent('remove').length > 0);
});

test('real shell route changing to encoded audio removes the filter', async () => {
    const h = makeShell();
    h.configure({ when: 'always', strength: 'gentle' });
    await h.load();
    h.event('af', [{ label: 'stremio_night_audio', name: 'lavfi' }]);
    h.event('audio-out-params', { format: 'spdif-ac3' });
    assert.equal(h.state().status, 'removing');
    assert.equal(h.recent('remove').length, 1);
    h.event('af', []);
    assert.equal(h.state().status, 'bypassed');
    assert.equal(h.recent('add').length, 1);
    h.shell.dispatch({ type: 'command', commandName: 'destroy' });
});

test('real shell does not apply a filter until source path matches', async () => {
    const h = makeShell();
    h.configure({ when: 'always' });
    await h.load();
    h.event('path', 'https://example.invalid/different.mp4');
    assert.equal(h.state().status, 'removing');
    assert.equal(h.recent('remove').length, 1);
    h.event('af', []);
    assert.equal(h.state().status, 'checking');
    assert.equal(h.recent('add').length, 1);
    h.shell.dispatch({ type: 'command', commandName: 'destroy' });
});

test('real shell off does not alter codec routing or unrelated audio filters', async () => {
    const h = makeShell();
    await h.load();
    assert.equal(h.state().status, 'off');
    assert.equal(h.recent('add').length, 0);
    assert.equal(h.commands.some(([channel, args]) =>
        channel === 'mpv-set-prop' && ['audio-channels', 'audio-spdif'].includes(args[0])), false);
    h.shell.dispatch({ type: 'command', commandName: 'destroy' });
});

test('MPV stream property clears on unload preserving Web player identity', async () => {
    const h = makeShell();
    const streams = [];
    h.shell.on('propChanged', (name, value) => {
        if (name === 'stream') streams.push(value);
    });
    h.shell.dispatch({ type: 'observeProp', propName: 'stream' });
    await h.load();
    assert.equal(streams.at(-1).url, 'https://example.invalid/audio-fixture.mkv');
    h.shell.dispatch({ type: 'command', commandName: 'unload' });
    assert.equal(streams.at(-1), null);
    h.shell.dispatch({ type: 'command', commandName: 'destroy' });
});

test('seeking and waiting together cannot falsely clear buffering in Web', async () => {
    const h = makeShell();
    const buffering = [];
    h.shell.on('propChanged', (name, value) => {
        if (name === 'buffering') buffering.push(value);
    });
    h.shell.dispatch({ type: 'observeProp', propName: 'buffering' });
    await h.load();
    h.event('paused-for-cache', true);
    h.event('seeking', true);
    h.event('paused-for-cache', false);
    assert.equal(buffering.at(-1), true);
    h.event('seeking', false);
    assert.equal(buffering.at(-1), false);
    h.shell.dispatch({ type: 'command', commandName: 'destroy' });
});
