'use strict';

const Policy = require('./nightAudioPolicy');

// Physical output routing remains the host's responsibility. Only decoded
// PCM is eligible, and no command changes --audio-spdif or the channel layout.
const PCM = /^(?:u8|s8|s16|s24|s32|s64|float|double)(?:p|le|be|ne)?$/i;
const COMPRESSED = /(?:spdif|iec61937|ac3|eac3|truehd|dts|dsd)/i;
const TIMEOUT_MS = 4000;
const MAX_ADDS_PER_PLAYBACK = 2;

function classifyAudio(input, output) {
    const formats = [input && input.format, output && output.format];
    if (formats.some((format) => typeof format === 'string' && COMPRESSED.test(format))) {
        return 'bitstream';
    }
    if (!formats.every((format) => typeof format === 'string' && PCM.test(format))) return 'unknown';
    const outputChannels = output['channel-count'];
    if (outputChannels !== undefined &&
        (!Number.isInteger(outputChannels) || outputChannels < 1 || outputChannels > 32)) return 'unknown';
    return 'pcm';
}

function hasOwnedFilter(filters) {
    return Array.isArray(filters) && filters.some((filter) => {
        if (!filter || typeof filter !== 'object') return false;
        const label = filter.label;
        return label === Policy.FILTER_LABEL || label === Policy.FILTER_LABEL.slice(1);
    });
}

function isAudioSelected(value) {
    return typeof value === 'number' && value > 0 ||
        typeof value === 'string' && value !== '' && value !== 'no' && value !== 'auto';
}

function NightAudioSession(options) {
    if (!options || typeof options.send !== 'function' || typeof options.changed !== 'function') {
        throw new TypeError('NightAudioSession requires send and changed callbacks');
    }
    const send = options.send;
    const changed = options.changed;
    const clock = typeof options.clock === 'function' ? options.clock : Date.now;

    let profile = null;
    let generation = 0;
    let playing = false;
    let playbackReady = false;
    let currentAudio = null;
    let outputAudio = null;
    let selectedTrack = null;
    let observedFilters = false;
    let filterPresent = false;
    let confirmedStrength = null;
    let confirmedGeneration = null;
    let pending = null;
    let failed = false;
    let attempts = 0;
    let snapshotText = null;
    let lastResult = null;

    function report(state) {
        const text = JSON.stringify(state);
        if (text !== snapshotText) {
            snapshotText = text;
            lastResult = Object.freeze(state);
            changed(lastResult);
        }
        return lastResult;
    }

    function currentChoice() {
        const instant = new Date(clock());
        const audioPath = classifyAudio(currentAudio, outputAudio);
        return Policy.resolveNightAudio(profile, {
            now: instant,
            playbackId: String(generation),
            backend: {
                audioPath,
                canCompress: playing && playbackReady && isAudioSelected(selectedTrack) &&
                    observedFilters && !failed,
                applied: filterPresent && confirmedGeneration === generation,
                appliedPlaybackId: String(confirmedGeneration),
                appliedStrength: confirmedStrength,
            },
        });
    }

    function removeFilter() {
        send('mpv-command', ['af', 'remove', Policy.FILTER_LABEL]);
        pending = { action: 'remove', at: clock() };
    }

    function addFilter(strength) {
        const filter = Policy.buildMpvFilter(strength);
        if (filter === null) return;
        send('mpv-command', ['af', 'add', filter]);
        attempts += 1;
        pending = { action: 'add', strength, at: clock() };
    }

    function reconcile() {
        const choice = currentChoice();
        // Playback verification precedes all sound-changing operations.
        const canProcess = playing && playbackReady && isAudioSelected(selectedTrack) &&
            classifyAudio(currentAudio, outputAudio) === 'pcm' && observedFilters;
        const validOwned = filterPresent && confirmedGeneration === generation &&
            confirmedStrength === choice.strength;

        if (pending && clock() - pending.at > TIMEOUT_MS) {
            failed = true;
            if (pending.action === 'add') {
                // A command with no readback cannot be considered safely active.
                send('mpv-command', ['af', 'remove', Policy.FILTER_LABEL]);
            }
            pending = null;
        }
        // Recalculate after timeout. Never re-add a command that just failed.
        const desired = choice.requested && canProcess && !failed;
        if (!pending) {
            if (filterPresent && (!desired || !validOwned)) {
                confirmedGeneration = null;
                confirmedStrength = null;
                removeFilter();
            } else if (desired && !filterPresent && attempts < MAX_ADDS_PER_PLAYBACK) {
                addFilter(choice.strength);
            } else if (desired && !filterPresent && attempts >= MAX_ADDS_PER_PLAYBACK) {
                failed = true;
            }
        }

        let status = 'off';
        if (!choice.requested) {
            status = pending || filterPresent ? 'removing' : 'off';
        } else if (pending && pending.action === 'remove') {
            status = 'removing';
        } else if (pending && pending.action === 'add') {
            status = 'applying';
        } else if (failed) {
            status = 'unavailable';
        } else if (!playing || !playbackReady || !observedFilters || !isAudioSelected(selectedTrack)) {
            status = 'checking';
        } else if (choice.status === 'bypassed') {
            status = 'bypassed';
        } else if (choice.status === 'unavailable') {
            status = 'unavailable';
        } else if (filterPresent && validOwned) {
            status = 'active';
        } else {
            status = 'checking';
        }
        return report({
            status,
            requested: choice.requested,
            when: choice.when,
            configuration: choice.configuration,
            strength: choice.strength,
            dialogue: choice.dialogue,
            audioPath: classifyAudio(currentAudio, outputAudio),
        });
    }

    this.configure = function(nextProfile) {
        profile = nextProfile && typeof nextProfile === 'object' ? nextProfile : null;
        failed = false;
        attempts = 0;
        return reconcile();
    };

    this.begin = function() {
        generation += 1;
        playing = true;
        playbackReady = false;
        currentAudio = null;
        outputAudio = null;
        selectedTrack = null;
        observedFilters = false;
        filterPresent = false;
        confirmedGeneration = null;
        confirmedStrength = null;
        pending = null;
        failed = false;
        attempts = 0;
        return reconcile();
    };

    this.setReady = function(value) {
        const next = value === true;
        if (playbackReady !== next) {
            playbackReady = next;
            return reconcile();
        }
        return this.snapshot();
    };

    this.observe = function(name, value) {
        if (name === 'af') {
            observedFilters = Array.isArray(value);
            filterPresent = hasOwnedFilter(value);
            if (pending && pending.action === 'add' && filterPresent) {
                confirmedStrength = pending.strength;
                confirmedGeneration = generation;
                pending = null;
            } else if (pending && pending.action === 'remove' && observedFilters && !filterPresent) {
                pending = null;
                confirmedStrength = null;
                confirmedGeneration = null;
            } else if (!filterPresent) {
                confirmedStrength = null;
                confirmedGeneration = null;
            }
        } else if (name === 'audio-params') {
            currentAudio = value && typeof value === 'object' ? value : null;
        } else if (name === 'audio-out-params') {
            outputAudio = value && typeof value === 'object' ? value : null;
        } else if (name === 'aid') {
            selectedTrack = value;
        } else {
            return this.snapshot();
        }
        return reconcile();
    };

    this.tick = function() {
        return reconcile();
    };

    this.end = function() {
        // Named filter removal is bounded to this feature. No other filters,
        // volume, track, video clock or codec options are changed.
        if (filterPresent || (pending && pending.action === 'add') ||
            confirmedGeneration === generation) {
            send('mpv-command', ['af', 'remove', Policy.FILTER_LABEL]);
        }
        playing = false;
        playbackReady = false;
        currentAudio = null;
        outputAudio = null;
        selectedTrack = null;
        filterPresent = false;
        observedFilters = false;
        confirmedStrength = null;
        confirmedGeneration = null;
        pending = null;
        failed = false;
        attempts = 0;
        return reconcile();
    };

    this.snapshot = function() {
        return lastResult || reconcile();
    };
    reconcile();
}

module.exports = { NightAudioSession, classifyAudio, hasOwnedFilter, isAudioSelected };
