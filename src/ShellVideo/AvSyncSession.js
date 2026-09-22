// Supervisory adapter only. Core owns persistence and recovery policy.
// Never infer speaker latency, alter audio-delay or change user playback speed.
var PROPERTIES = ['path', 'pause', 'seeking', 'paused-for-cache', 'eof-reached', 'aid', 'vid', 'speed', 'duration', 'time-pos', 'seekable', 'video-sync', 'audio-delay', 'initial-audio-sync', 'frame-drop-count', 'decoder-frame-drop-count', 'display-fps'];
var BOUNDARIES = ['path', 'pause', 'seeking', 'paused-for-cache', 'eof-reached', 'aid', 'vid', 'speed', 'video-sync', 'audio-delay', 'display-fps'];
var DISPLAY_MODES = ['display-resample', 'display-resample-vdrop', 'display-tempo'];

function AvSyncSession(options) {
    var clock = options.clock || function() {
        return typeof performance !== 'undefined' ? Math.floor(performance.now()) : NaN;
    };
    var props = {};
    var sessionId = 0;
    var epoch = 0;
    var sampleId = 0;
    var reading = null;
    var acknowledgement = null;
    var opened = false;
    var lastTime = null;
    var lastPublished = null;
    var stableSince = null;
    var wasStable = false;
    var usedSoft = false;
    var usedHard = false;
    var nextAllowed = 0;
    var generation = 0;

    function safe(value) { return Number.isSafeInteger(value) && value >= 0; }
    function time() {
        var value = clock();
        if (!safe(value) || (lastTime !== null && value < lastTime)) return null;
        lastTime = value;
        return value;
    }
    function publish(name, value) { options.emit(name, value); }
    function invalidate() {
        epoch += 1;
        reading = null;
        lastPublished = null;
        publish('avSyncV2', null);
    }
    function active() {
        var playback = options.getPlayback();
        return opened && playback.loaded === true && typeof playback.url === 'string' &&
            props.path === playback.url && props.pause === false && props.seeking === false &&
            props['paused-for-cache'] === false && props['eof-reached'] !== true &&
            Number.isSafeInteger(props.aid) && props.aid > 0 && Number.isSafeInteger(props.vid) && props.vid > 0;
    }
    function stable(now) {
        return stableSince !== null && now - stableSince >= 3000 &&
            safe(props['frame-drop-count']) && safe(props['decoder-frame-drop-count']);
    }
    function capabilities(now) {
        var permitted = active() && stable(now) && props.speed === 1 &&
            props['audio-delay'] === 0 && props['initial-audio-sync'] === true;
        return {
            soft: permitted && !usedSoft && DISPLAY_MODES.indexOf(props['video-sync']) !== -1,
            hard: permitted && !usedHard && props['video-sync'] === 'audio' && props.seekable === true &&
                Number.isFinite(props.duration) && Number.isFinite(props['time-pos']) &&
                props['time-pos'] >= 2 && props['time-pos'] <= props.duration - 2
        };
    }
    this.begin = function(id) {
        opened = safe(id) && id > 0 && id >= sessionId;
        if (opened && id !== sessionId) {
            usedSoft = false;
            usedHard = false;
            nextAllowed = 0;
            generation = 0;
            sessionId = id;
            sampleId = 0;
        }
        props = {};
        stableSince = null;
        wasStable = false;
        acknowledgement = null;
        invalidate();
        publish('avSyncV2Ack', null);
    };
    this.close = function() {
        opened = false;
        props = {};
        stableSince = null;
        wasStable = false;
        acknowledgement = null;
        invalidate();
        publish('avSyncV2Ack', null);
    };
    this.interrupt = function() { stableSince = time(); wasStable = false; invalidate(); };
    this.update = function(name, value) {
        if (PROPERTIES.indexOf(name) === -1) return;
        var changed = props[name] !== value;
        props[name] = value;
        if (changed && (name === 'frame-drop-count' || name === 'decoder-frame-drop-count')) {
            stableSince = time();
            wasStable = false;
            invalidate();
        } else if (changed && BOUNDARIES.indexOf(name) !== -1) {
            stableSince = time();
            wasStable = false;
            invalidate();
        }
    };
    this.observe = function(seconds) {
        var now = time();
        if (now === null || !active() || typeof seconds !== 'number' || !Number.isFinite(seconds) || Math.abs(seconds) > 60 || !safe(epoch) || !safe(sampleId + 1)) {
            if (reading !== null) invalidate();
            return;
        }
        var videoStable = stable(now);
        if (videoStable !== wasStable) { invalidate(); wasStable = videoStable; }
        if (lastPublished !== null && now - lastPublished < 500) return;
        lastPublished = now;
        sampleId += 1;
        var capability = capabilities(now);
        reading = Object.freeze({
            sessionId: sessionId, epoch: epoch, sampleId: sampleId, capturedAtMs: now,
            offsetMs: Math.round(seconds * 1000) || 0, active: true, videoStable: videoStable,
            canSoftCorrect: capability.soft, canHardCorrect: capability.hard
        });
        publish('avSyncV2', reading);
    };
    this.snapshot = function() {
        var now = time();
        return now !== null && active() && reading !== null && now - reading.capturedAtMs <= 1500 ? reading : null;
    };
    this.lastAck = function() { return acknowledgement; };
    this.correct = function(request) {
        if (!request || !['sessionId', 'epoch', 'generation', 'sampleId', 'issuedAtMs', 'expiresAtMs'].every(function(key) { return safe(request[key]); })) return;
        var now = time();
        var sample = this.snapshot();
        var capability = now === null ? { soft: false, hard: false } : capabilities(now);
        var valid = now !== null && sample !== null && request.sessionId === sessionId && request.epoch === epoch &&
            request.sampleId === sample.sampleId && request.generation > generation && now >= nextAllowed &&
            request.issuedAtMs >= sample.capturedAtMs && request.issuedAtMs <= now && request.expiresAtMs >= now &&
            request.expiresAtMs - request.issuedAtMs <= 1500 && request.expiresAtMs >= request.issuedAtMs &&
            sample.videoStable === true;
        var accepted = false;
        if (valid && ((request.correction === 'nativeClock' && capability.soft && Math.abs(sample.offsetMs) > 60) ||
            (request.correction === 'reseek' && capability.hard && Math.abs(sample.offsetMs) >= 250))) {
            generation = request.generation;
            nextAllowed = now + 30000;
            if (request.correction === 'nativeClock') usedSoft = true;
            else usedHard = true;
            try {
                if (request.correction === 'nativeClock') {
                    options.send('mpv-set-prop', ['file-local-options/video-sync', 'audio']);
                } else {
                    options.send('mpv-command', ['seek', 0, 'relative+exact']);
                }
                accepted = true;
            } catch (_) {
                // A failed transport is not retried against an uncertain native state.
            }
            invalidate();
            stableSince = now;
            wasStable = false;
        }
        acknowledgement = Object.freeze({ sessionId: request.sessionId, epoch: request.epoch, generation: request.generation, accepted: accepted });
        publish('avSyncV2Ack', acknowledgement);
    };
}
AvSyncSession.properties = PROPERTIES;
module.exports = AvSyncSession;
