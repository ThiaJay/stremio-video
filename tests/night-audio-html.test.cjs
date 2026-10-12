'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { HtmlNightAudioSession, safeDecodedSource } =
    require('../src/audio/HtmlNightAudioSession');

const HOST = 'https://appassets.androidplatform.net';

class FakeNode {
    constructor() { this.connections = []; this.disconnected = 0; }
    connect(to) { this.connections.push(to); }
    disconnect() { this.connections = []; this.disconnected += 1; }
}
class FakeCompressor extends FakeNode {
    constructor() {
        super();
        for (const key of ['threshold', 'knee', 'ratio', 'attack', 'release']) {
            this[key] = { value: null };
        }
    }
}
class FakeContext {
    constructor() {
        FakeContext.instances.push(this);
        this.state = 'running';
        this.destination = new FakeNode();
        this.compressor = new FakeCompressor();
        this.gain = new FakeNode();
        this.gain.gain = { value: 1 };
        this.mediaSource = new FakeNode();
        this.mediaCreationCount = 0;
    }
    createDynamicsCompressor() { return this.compressor; }
    createGain() { return this.gain; }
    createMediaElementSource() { this.mediaCreationCount++; return this.mediaSource; }
    close() { this.closed = true; this.state = 'closed'; return Promise.resolve(); }
    resume() { this.state = 'running'; return Promise.resolve(); }
}
FakeContext.instances = [];

function harness(overrides = {}) {
    FakeContext.instances.length = 0;
    const events = [];
    const media = Object.assign({
        src: 'blob:' + HOST + '/synthetic-media',
        currentSrc: '',
        paused: false,
        readyState: 4,
        HAVE_CURRENT_DATA: 2,
        volume: 0.47,
        currentTime: 12,
    }, overrides.media);
    const cfg = Object.assign({
        videoElement: media,
        changed: state => events.push(state),
        AudioContextCtor: FakeContext,
        origin: HOST,
        clock: () => Date.parse('2026-10-12T21:30:00Z'),
    }, overrides.config);
    const session = new HtmlNightAudioSession(cfg);
    return { session, media, events };
}

test('only origin-matching blob media is eligible to avoid irreversible redirect capture', () => {
    for (const url of [
        'blob:' + HOST + '/30ab', 'blob:' + HOST + '/synthetic-media',
    ]) assert.equal(safeDecodedSource(url, HOST), true, url);
    for (const url of [
        HOST + '/film.webm', '/assets/video.mp4',
        'https://stream.example/film.mp4', 'blob:https://stream.example/30ab',
        'http://appassets.androidplatform.net/film.mp4',
        'file:///video.mp4', 'data:audio/wav;base64,AA', 'javascript:alert(1)',
        '//stream.example/film', '', null,
    ]) assert.equal(safeDecodedSource(url, HOST), false, String(url));
});

test('off is inert, leaves browser volume and time unchanged and allocates no audio context', () => {
    const { session, media } = harness();
    session.begin();
    assert.equal(session.snapshot().status, 'off');
    assert.equal(FakeContext.instances.length, 0);
    assert.equal(media.volume, 0.47);
    assert.equal(media.currentTime, 12);
    session.destroy();
});

test('irreversible media capture is signalled before an element can be reused', () => {
    const { session } = harness();
    assert.equal(session.hasCapturedMediaElement(), false);
    session.begin();
    session.configure({ when: 'always' });
    assert.equal(session.snapshot().status, 'active');
    assert.equal(session.hasCapturedMediaElement(), true);
    session.configure({ when: 'off' });
    session.end();
    assert.equal(session.hasCapturedMediaElement(), true);
    session.destroy();
    assert.equal(session.hasCapturedMediaElement(), true);
});

test('when the browser media node connection fails capture still requires replacement', () => {
    class CannotConnectMediaSource extends FakeNode {
        connect() { throw new Error('Synthetic audio graph output rejected'); }
    }
    class BrokenSourceContext extends FakeContext {
        constructor() {
            super();
            this.mediaSource = new CannotConnectMediaSource();
        }
    }
    const { session } = harness({ config: { AudioContextCtor: BrokenSourceContext } });
    session.begin();
    session.configure({ when: 'always' });
    assert.equal(session.snapshot().status, 'unavailable');
    assert.equal(session.hasCapturedMediaElement(), true);
    session.destroy();
});

test('a supported media stream uses one real Web Audio graph with no double output path', () => {
    const { session, media } = harness();
    session.begin();
    session.configure({ when: 'always', strength: 'balanced' });
    const ctx = FakeContext.instances[0];
    assert.equal(session.snapshot().status, 'active');
    assert.equal(ctx.mediaCreationCount, 1);
    assert.deepEqual(ctx.mediaSource.connections, [ctx.compressor]);
    assert.deepEqual(ctx.compressor.connections, [ctx.gain]);
    assert.deepEqual(ctx.gain.connections, [ctx.destination]);
    assert.equal(ctx.gain.gain.value, 0.9);
    assert.equal(ctx.compressor.ratio.value, 2.8);
    assert.equal(media.volume, 0.47);
    assert.equal(media.currentTime, 12);
    session.refresh();
    assert.equal(ctx.mediaCreationCount, 1);
    session.destroy();
});

test('Off and unload restore an unprocessed path rather than leaving a filter installed', () => {
    const { session } = harness();
    session.begin();
    session.configure({ when: 'always' });
    const ctx = FakeContext.instances[0];
    session.configure({ when: 'off' });
    assert.equal(session.snapshot().status, 'off');
    assert.deepEqual(ctx.mediaSource.connections, [ctx.destination]);
    session.configure({ when: 'always' });
    session.end();
    assert.equal(session.snapshot().status, 'checking');
    assert.deepEqual(ctx.mediaSource.connections, [ctx.destination]);
    session.destroy();
    assert.equal(ctx.closed, true);
});

test('direct third-party streams are blocked before MediaElementAudioSource can silence CORS media', () => {
    const { session, media } = harness({
        media: { currentSrc: 'https://provider.invalid/film.mp4' },
    });
    session.begin();
    session.configure({ when: 'always' });
    assert.equal(session.snapshot().status, 'unavailable');
    assert.equal(FakeContext.instances.length, 0);
    assert.equal(media.volume, 0.47);
    session.destroy();
});

test('new cross-origin stream bypasses an earlier eligible filter immediately', () => {
    const { session, media } = harness();
    session.begin();
    session.configure({ when: 'always' });
    const ctx = FakeContext.instances[0];
    session.end();
    media.currentSrc = 'https://provider.invalid/media.mp4';
    session.begin();
    assert.equal(session.snapshot().status, 'unavailable');
    assert.deepEqual(ctx.mediaSource.connections, [ctx.destination]);
    assert.equal(ctx.mediaCreationCount, 1);
    session.destroy();
});

test('invalid language independent strength is never promoted beyond the balanced preset', () => {
    const { session } = harness();
    session.begin();
    session.configure({ when: 'always', strength: 'very-loud-and-unsafe' });
    assert.equal(session.snapshot().strength, 'balanced');
    assert.equal(FakeContext.instances[0].compressor.ratio.value, 2.8);
    session.destroy();
});

test('strength transitions update the existing compressor without duplicate media routing', () => {
    const { session } = harness();
    session.begin();
    session.configure({ when: 'always', strength: 'gentle' });
    const ctx = FakeContext.instances[0];
    assert.equal(ctx.compressor.ratio.value, 1.7);
    session.configure({ when: 'always', strength: 'strong' });
    assert.equal(ctx.compressor.ratio.value, 4);
    assert.equal(ctx.mediaCreationCount, 1);
    assert.equal(ctx.mediaSource.connections.length, 1);
    session.destroy();
});

test('paused playback stays on original media path until playing can be verified', () => {
    const { session, media } = harness({ media: { paused: true } });
    session.begin();
    session.configure({ when: 'always' });
    assert.equal(FakeContext.instances.length, 0);
    assert.equal(session.snapshot().status, 'checking');
    media.paused = false;
    session.refresh();
    assert.equal(session.snapshot().status, 'active');
    session.destroy();
});

test('without Web Audio no browser media source is intercepted', () => {
    const { session } = harness({ config: { AudioContextCtor: false } });
    session.begin();
    session.configure({ when: 'always' });
    assert.equal(session.snapshot().status, 'unavailable');
    assert.equal(FakeContext.instances.length, 0);
    session.destroy();
});

test('a suspended AudioContext never captures the media element until it has resumed', async () => {
    let resume;
    class SuspendedContext extends FakeContext {
        constructor() {
            super();
            this.state = 'suspended';
        }
        resume() {
            return new Promise(resolve => { resume = () => {
                this.state = 'running';
                resolve();
            }; });
        }
    }
    const { session } = harness({ config: { AudioContextCtor: SuspendedContext } });
    session.begin();
    session.configure({ when: 'always' });
    const ctx = FakeContext.instances[0];
    assert.equal(ctx.mediaCreationCount, 0);
    assert.equal(session.snapshot().status, 'checking');
    resume();
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(ctx.mediaCreationCount, 1);
    assert.equal(session.snapshot().status, 'active');
    session.destroy();
});

test('stale async resume cannot install Night Audio after the stream is unloaded', async () => {
    let resume;
    class SuspendedContext extends FakeContext {
        constructor() { super(); this.state = 'suspended'; }
        resume() { return new Promise(resolve => { resume = () => {
            this.state = 'running'; resolve();
        }; }); }
    }
    const { session } = harness({ config: { AudioContextCtor: SuspendedContext } });
    session.begin();
    session.configure({ when: 'always' });
    const ctx = FakeContext.instances[0];
    session.end();
    resume();
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(ctx.mediaCreationCount, 0);
    assert.equal(session.snapshot().status, 'checking');
    session.destroy();
});

test('scheduled activation honours the explicit timezone and cleans up the timer', () => {
    let callback;
    const cleared = [];
    let instant = '2026-10-12T20:50:00Z';
    const { session } = harness({ config: {
        clock: () => Date.parse(instant),
        setInterval: cb => { callback = cb; return 19; },
        clearInterval: id => cleared.push(id),
    } });
    session.begin();
    session.configure({ when: 'scheduled', schedule: {
        timeZone: 'Europe/London',
        windows: [{ start: '22:00', end: '06:00' }],
    } });
    assert.equal(session.snapshot().status, 'off');
    instant = '2026-10-12T21:05:00Z';
    callback();
    assert.equal(session.snapshot().status, 'active');
    session.destroy();
    assert.deepEqual(cleared, [19]);
});

test('there is no duplicate audio output after error recovery', () => {
    class BrokenCompressor extends FakeCompressor {
        connect() { throw new Error('DSP unavailable'); }
    }
    class BrokenContext extends FakeContext {
        constructor() { super(); this.compressor = new BrokenCompressor(); }
    }
    const { session } = harness({ config: { AudioContextCtor: BrokenContext } });
    session.begin();
    session.configure({ when: 'always' });
    const ctx = FakeContext.instances[0];
    assert.equal(session.snapshot().status, 'unavailable');
    assert.deepEqual(ctx.mediaSource.connections, [ctx.destination]);
    session.destroy();
});

test('web player module remains free of account, storage and device permissions', () => {
    const fs = require('node:fs');
    const src = fs.readFileSync(require.resolve('../src/audio/HtmlNightAudioSession'), 'utf8');
    for (const prohibited of ['localStorage.', 'sessionStorage.', 'fetch(', 'XMLHttpRequest',
        'navigator.mediaDevices', 'setSinkId(', 'playbackRate =', 'currentTime =']) {
        assert.equal(src.includes(prohibited), false, prohibited);
    }
});
