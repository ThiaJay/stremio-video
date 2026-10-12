'use strict';
const { test, after } = require('node:test');
const assert = require('node:assert/strict');

// These are actual StremioVideo dispatch tests with an isolated mock
// implementation at the selector seam. No browser, account or stream request.
const selectorPath = require.resolve('../src/StremioVideo/selectVideoImplementation');
const originalSelector = require.cache[selectorPath];
const enginePath = require.resolve('../src/StremioVideo/StremioVideo');

function fixture() {
    const instances = [];
    class FakeHTMLVideo {
        constructor() {
            this.captured = false;
            this.destroyed = false;
            this.loadCount = 0;
            instances.push(this);
        }
        on() {}
        requiresFreshInstanceForLoad() { return this.captured; }
        dispatch(action) {
            if (action.type !== 'command') return;
            if (action.commandName === 'load') this.loadCount += 1;
            if (action.commandName === 'destroy') this.destroyed = true;
        }
    }
    FakeHTMLVideo.manifest = {
        name: 'HTMLVideo', external: false,
    };
    require.cache[selectorPath] = {
        id: selectorPath, filename: selectorPath, loaded: true,
        exports: () => FakeHTMLVideo,
    };
    delete require.cache[enginePath];
    const StremioVideo = require(enginePath);
    return { instances, StremioVideo };
}

    test('an HTMLVideo implementation is reused when its media element was never captured', () => {
        const { instances, StremioVideo } = fixture();
        const player = new StremioVideo();
        const command = { type: 'command', commandName: 'load',
            commandArgs: { stream: { url: 'blob:https://appassets.androidplatform.net/fixture' } } };
        player.dispatch(command);
        player.dispatch(command);
        assert.equal(instances.length, 1);
        assert.equal(instances[0].loadCount, 2);
        assert.equal(instances[0].destroyed, false);
        player.destroy();
    });

    test('a previously captured video element is destroyed and replaced before any new stream', () => {
        const { instances, StremioVideo } = fixture();
        const player = new StremioVideo();
        player.dispatch({ type: 'command', commandName: 'load',
            commandArgs: { stream: { url: 'blob:https://appassets.androidplatform.net/fixture' } } });
        const old = instances[0];
        old.captured = true; // actual Night Audio session now reports this irreversibly
        player.dispatch({ type: 'command', commandName: 'load',
            commandArgs: { stream: { url: 'https://external.invalid/remote.mp4' } } });
        assert.equal(old.destroyed, true);
        assert.equal(old.loadCount, 1);
        assert.equal(instances.length, 2);
        assert.notEqual(instances[1], old);
        assert.equal(instances[1].loadCount, 1);
        assert.equal(instances[1].captured, false);
        player.destroy();
    });

    test('turning processing Off does not authorise reuse of a captured media element', () => {
        const { instances, StremioVideo } = fixture();
        const player = new StremioVideo();
        const command = { type: 'command', commandName: 'load',
            commandArgs: { stream: { url: 'blob:https://appassets.androidplatform.net/fixture' } } };
        player.dispatch(command);
        const previous = instances[0];
        previous.captured = true;
        player.dispatch({ type: 'command', commandName: 'setNightAudio',
            commandArgs: { profile: { when: 'off' } } });
        player.dispatch(command);
        assert.equal(previous.destroyed, true);
        assert.equal(instances.length, 2);
        player.destroy();
    });
after(() => {
    if (originalSelector) require.cache[selectorPath] = originalSelector;
    else delete require.cache[selectorPath];
    delete require.cache[enginePath];
});
