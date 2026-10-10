'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');

// A WebOS service mock, not a physical TV or a claim of platform certification.
test('WebOS subtitles stay off across native media sessions unless explicitly selected', () => {
    const saved = {
        document: global.document,
        window: global.window,
        HTMLElement: global.HTMLElement,
        setInterval: global.setInterval,
        clearInterval: global.clearInterval,
        setTimeout: global.setTimeout,
        clearTimeout: global.clearTimeout,
    };
    const tracksPath = require.resolve('../src/tracksData');
    require(tracksPath);
    const previousGetTracksData = require.cache[tracksPath].exports;
    const eventLog = [];
    const requests = [];
    const timeouts = new Map();
    let nextTimerId = 0;
    let poll = null;
    let videoElement = null;

    class Container {
        constructor() { this.children = []; this.style = {}; }
        appendChild(child) { this.children.push(child); return child; }
        removeChild(child) { this.children.splice(this.children.indexOf(child), 1); return child; }
    }
    function videoMock() {
        return {
            style: {}, textTracks: [], readyState: 4, HAVE_METADATA: 1, HAVE_FUTURE_DATA: 3,
            mediaId: null, currentTime: 0, duration: 90, paused: true, volume: 1, muted: false,
            buffered: { length: 0 }, controls: false,
            play() { eventLog.push('play'); this.paused = false; this.onplaying?.(); return Promise.resolve(); },
            pause() { this.paused = true; this.onpause?.(); },
            load() {}, removeAttribute(name) { if (name === 'src') this.src = ''; },
        };
    }
    try {
        global.HTMLElement = Container;
        global.document = {
            createElement(tag) {
                if (tag === 'video') return (videoElement = videoMock());
                if (tag === 'style') return { sheet: { insertRule() {} } };
                return { style: {} };
            },
        };
        global.window = {
            webOS: { service: { request(_service, options) {
                requests.push({ method: options.method, parameters: options.parameters });
                eventLog.push(options.method);
                options.onSuccess?.({ configs: {} });
            } } },
        };
        global.setInterval = (fn) => { poll = fn; return 123; };
        global.clearInterval = () => { poll = null; };
        global.setTimeout = (fn) => { const id = ++nextTimerId; timeouts.set(id, fn); return id; };
        global.clearTimeout = (id) => { timeouts.delete(id); };
        require.cache[tracksPath].exports = (_url, callback) => callback({
            subs: [{ id: 0, lang: 'eng', codec: 'subrip', label: 'English' }],
            audio: [{ id: 0, lang: 'eng', codec: 'AAC', label: 'English AAC' }],
        });
        delete require.cache[require.resolve('../src/WebOsVideo/WebOsVideo')];
        const WebOsVideo = require('../src/WebOsVideo/WebOsVideo');
        const player = new WebOsVideo({ containerElement: new Container() });
        const propEvents = [];
        player.on('propValue', (name, value) => propEvents.push({ name, value }));
        const selection = () => {
            player.dispatch({ type: 'observeProp', propName: 'selectedSubtitlesTrackId' });
            return propEvents.at(-1).value;
        };
        const tracks = () => {
            player.dispatch({ type: 'observeProp', propName: 'subtitlesTracks' });
            return propEvents.at(-1).value;
        };
        const load = (id) => {
            player.dispatch({ type: 'command', commandName: 'load', commandArgs: {
                stream: { url: 'https://fixture.invalid/' + id }, time: 0,
            } });
            videoElement.mediaId = id;
            assert.equal(typeof poll, 'function', 'native media ID polling must be armed');
            poll();
        };
        load('media-one');
        assert.equal(selection(), null, 'new media has no selected subtitle');
        assert.equal(tracks().length, 1, 'subtitle discovery must remain available');
        assert.equal(tracks()[0].mode, 'disabled', 'discovery must not display subtitles');
        assert.equal(requests.filter(x => x.method === 'setSubtitleEnable').length, 1);
        assert.deepEqual(requests.find(x => x.method === 'setSubtitleEnable').parameters,
            { mediaId: 'media-one', enable: false });
        assert.ok(eventLog.indexOf('setSubtitleEnable') < eventLog.indexOf('play'),
            'native subtitle disable must occur before starting video');
        assert.equal(requests.some(x => x.method === 'selectTrack'), false,
            'new media must not automatically select native subtitle track');

        player.dispatch({ type: 'setProp', propName: 'selectedSubtitlesTrackId', propValue: 'EMBEDDED_0' });
        assert.equal(requests.at(-1).parameters.mediaId, 'media-one');
        const pending = [...timeouts.values()];
        assert.ok(pending.length >= 1, 'explicit track selection must wait for native API');
        for (const fn of pending) fn();
        timeouts.clear();
        assert.equal(selection(), 'EMBEDDED_0', 'explicit subtitle selection should succeed');
        assert.ok(requests.some(x => x.method === 'setSubtitleEnable' &&
            x.parameters.enable === true && x.parameters.mediaId === 'media-one'));
        assert.ok(requests.some(x => x.method === 'selectTrack' &&
            x.parameters.mediaId === 'media-one' && x.parameters.type === 'text'));

        const before = requests.length;
        load('media-two');
        const newActions = requests.slice(before);
        assert.equal(selection(), null, 'the next source must not inherit a subtitle selection');
        assert.equal(tracks()[0].mode, 'disabled');
        assert.deepEqual(newActions.find(x => x.method === 'setSubtitleEnable').parameters,
            { mediaId: 'media-two', enable: false });
        assert.equal(newActions.some(x => x.method === 'selectTrack'), false);
        player.dispatch({ type: 'command', commandName: 'destroy' });
        console.log('WebOS native start disabled, manual enable and next source reset PASS');
    } finally {
        require.cache[tracksPath].exports = previousGetTracksData;
        delete require.cache[require.resolve('../src/WebOsVideo/WebOsVideo')];
        for (const [key, value] of Object.entries(saved)) {
            if (value === undefined) delete global[key];
            else global[key] = value;
        }
    }
});
