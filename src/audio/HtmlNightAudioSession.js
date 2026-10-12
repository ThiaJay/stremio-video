'use strict';

// Isolated HTMLVideo backend. Only same-origin or same-origin blob media can
// enter Web Audio. Third-party direct streams stay on the untouched media path
// because a cross-origin MediaElementAudioSourceNode may output silence.
const Policy = require('./nightAudioPolicy');

function safeDecodedSource(rawUrl, origin) {
    if (typeof rawUrl !== 'string' || !rawUrl || typeof origin !== 'string') return false;
    try {
        const host = new URL(origin);
        const media = new URL(rawUrl, host.origin);
        return host.protocol === 'https:' &&
            (media.protocol === 'https:' || media.protocol === 'blob:') &&
            media.origin === host.origin;
    } catch (_) {
        return false;
    }
}

function HtmlNightAudioSession(options) {
    if (!options || !options.videoElement || typeof options.changed !== 'function') {
        throw new TypeError('HTML Night Audio requires a media element and changed callback');
    }
    const video = options.videoElement;
    const changed = options.changed;
    const now = typeof options.clock === 'function' ? options.clock : Date.now;
    const origin = options.origin || (typeof location !== 'undefined' && location.origin) || '';
    const AudioContextCtor = options.AudioContextCtor ||
        (typeof window !== 'undefined' && (window.AudioContext || window.webkitAudioContext));
    const every = options.setInterval || setInterval;
    const cancel = options.clearInterval || clearInterval;
    let profile = { when: 'off' };
    let context = null;
    let source = null;
    let compressor = null;
    let outputTrim = null;
    let connectedThroughDSP = false;
    let destroyed = false;
    let streamPresent = false;
    let failed = false;
    let pendingResume = false;
    let clockTimer = null;
    let revision = 0;
    let previous = null;
    let published = null;

    function choice() {
        return Policy.resolveNightAudio(profile, {
            now: new Date(now()),
            playbackId: String(revision),
            backend: { audioPath: 'pcm', canCompress: true },
        });
    }

    function report(value) {
        const next = JSON.stringify(value);
        if (next !== previous) {
            previous = next;
            published = Object.freeze(value);
            changed(published);
        }
        return published;
    }

    function bypass() {
        if (!connectedThroughDSP || !source || !context) return;
        // Restore the original unprocessed path before disconnecting effects.
        try {
            source.disconnect();
            source.connect(context.destination);
            compressor.disconnect();
            outputTrim.disconnect();
            connectedThroughDSP = false;
        } catch (_) {
            // Do not retry a possibly damaged graph or report processing as active.
            connectedThroughDSP = false;
            failed = true;
        }
    }

    function mediaEligible() {
        const src = video.currentSrc || video.src;
        return streamPresent && safeDecodedSource(src, origin);
    }

    function playing() {
        return video.paused === false &&
            Number.isFinite(video.readyState) &&
            video.readyState >= (video.HAVE_CURRENT_DATA || 2);
    }

    function beginGraph() {
        if (!context || source || context.state !== 'running') return;
        try {
            // Allocate processing nodes first. MediaElementAudioSource captures
            // the media element when created, so it must be the final allocation.
            const nextCompressor = context.createDynamicsCompressor();
            const nextOutput = context.createGain();
            const nextSource = context.createMediaElementSource(video);
            nextSource.connect(context.destination);
            source = nextSource;
            compressor = nextCompressor;
            outputTrim = nextOutput;
        } catch (_) {
            failed = true;
        }
    }

    function setStrength(strength) {
        const params = Policy.PRESETS[strength];
        if (!params || !compressor || !outputTrim) return false;
        compressor.threshold.value = 20 * Math.log10(params.threshold);
        compressor.knee.value = 8;
        compressor.ratio.value = params.ratio;
        compressor.attack.value = params.attackMs / 1000;
        compressor.release.value = params.releaseMs / 1000;
        // Conservative output trim. This is NOT an independently verified
        // peak limiter, dialogue isolation or calibrated speaker equalisation.
        outputTrim.gain.value = 0.9;
        return true;
    }

    function apply(strength) {
        if (!source || !context || !compressor || !outputTrim) return;
        try {
            if (!setStrength(strength)) return;
            if (connectedThroughDSP) return;
            source.disconnect();
            source.connect(compressor);
            compressor.connect(outputTrim);
            outputTrim.connect(context.destination);
            connectedThroughDSP = true;
        } catch (_) {
            failed = true;
            try {
                source.disconnect();
                source.connect(context.destination);
                compressor.disconnect();
                outputTrim.disconnect();
            } catch (_) {
                // A damaged graph cannot be safely described as audible.
            }
            connectedThroughDSP = false;
        }
    }

    function ensureRunningContext() {
        if (failed || destroyed || !AudioContextCtor) return;
        if (!context) {
            try { context = new AudioContextCtor(); }
            catch (_) { failed = true; return; }
        }
        if (context.state === 'running') {
            beginGraph();
            return;
        }
        // Do not attach the audio source to a suspended graph. Until Web Audio
        // is actually running, the original browser playback stays intact.
        if (pendingResume || typeof context.resume !== 'function') return;
        pendingResume = true;
        const startRevision = revision;
        let attempt;
        try { attempt = context.resume(); }
        catch (_) { pendingResume = false; failed = true; return; }
        Promise.resolve(attempt).then(() => {
            pendingResume = false;
            if (!destroyed && revision === startRevision) this.refresh();
        }, () => {
            pendingResume = false;
            failed = true;
            if (!destroyed) this.refresh();
        });
    }

    this.refresh = function() {
        if (destroyed) return published;
        const selected = choice();
        const eligible = mediaEligible();
        if (!selected.requested || !eligible || !playing() || failed) {
            bypass();
        } else {
            ensureRunningContext.call(this);
            if (context && context.state === 'running') {
                beginGraph();
                apply(selected.strength);
            }
        }
        let status = 'off';
        if (selected.requested) {
            status = failed || !AudioContextCtor || (streamPresent && !eligible) ?
                'unavailable' : connectedThroughDSP && context && context.state === 'running' ?
                    'active' : 'checking';
        }
        return report({
            requested: selected.requested,
            status,
            strength: selected.strength,
            when: selected.when,
            configuration: selected.configuration,
            dialogue: selected.dialogue,
            audioPath: eligible ? 'pcm' : 'unknown',
        });
    };

    this.configure = function(nextProfile) {
        if (destroyed) return published;
        profile = nextProfile && typeof nextProfile === 'object' ? nextProfile : { when: 'off' };
        failed = false;
        if (clockTimer !== null) { cancel(clockTimer); clockTimer = null; }
        if (profile.when === 'scheduled') clockTimer = every(() => this.refresh(), 60000);
        return this.refresh();
    };

    this.begin = function() {
        if (destroyed) return published;
        revision += 1;
        streamPresent = true;
        failed = false;
        bypass();
        return this.refresh();
    };

    this.end = function() {
        if (destroyed) return published;
        revision += 1;
        streamPresent = false;
        bypass();
        return this.refresh();
    };

    this.snapshot = function() {
        return published || this.refresh();
    };

    this.destroy = function() {
        if (destroyed) return;
        this.end();
        destroyed = true;
        if (clockTimer !== null) { cancel(clockTimer); clockTimer = null; }
        if (context) {
            bypass();
            // The original HTMLVideo owner is destroying this element too.
            try { context.close(); } catch (_) { /* best effort */ }
        }
    };

    this.refresh();
}

module.exports = { HtmlNightAudioSession, safeDecodedSource };
