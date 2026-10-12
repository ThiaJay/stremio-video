'use strict';

// Reference contract for player adapters. No persistent store and no audio mutation here.
// Shared Stremio Core remains the long-term authority for activation semantics.
const PRESETS = Object.freeze({
    gentle: Object.freeze({ threshold: 0.16, ratio: 1.7, attackMs: 30, releaseMs: 400 }),
    balanced: Object.freeze({ threshold: 0.13, ratio: 2.8, attackMs: 20, releaseMs: 320 }),
    strong: Object.freeze({ threshold: 0.10, ratio: 4.0, attackMs: 12, releaseMs: 250 }),
});
const FILTER_LABEL = '@stremio_night_audio';

function parseClock(value) {
    if (typeof value !== 'string' || !/^\d{2}:\d{2}$/.test(value)) return null;
    const hour = Number(value.slice(0, 2));
    const minute = Number(value.slice(3));
    return hour <= 23 && minute <= 59 ? hour * 60 + minute : null;
}

function localMinute(instant, timeZone) {
    const date = instant instanceof Date ? instant : new Date(instant);
    if (!Number.isFinite(date.getTime()) || typeof timeZone !== 'string' || !timeZone) return null;
    try {
        const parts = new Intl.DateTimeFormat('en-GB', {
            timeZone, hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
        }).formatToParts(date);
        const hour = Number(parts.find((part) => part.type === 'hour')?.value);
        const minute = Number(parts.find((part) => part.type === 'minute')?.value);
        return Number.isInteger(hour) && hour >= 0 && hour < 24 &&
            Number.isInteger(minute) && minute >= 0 && minute < 60 ?
            hour * 60 + minute : null;
    } catch (_) {
        return null;
    }
}

function scheduleMatches(schedule, instant) {
    if (!schedule || !Array.isArray(schedule.windows) || !schedule.windows.length) return false;
    const at = localMinute(instant, schedule.timeZone);
    if (at === null) return false;
    return schedule.windows.some(({ start, end }) => {
        const from = parseClock(start);
        const to = parseClock(end);
        if (from === null || to === null || from === to) return false;
        return from < to ? at >= from && at < to : at >= from || at < to;
    });
}

// Callers must use a stable client installation key and a verified output route key.
// Transient Android device numbers and guessed soundbar driver counts are not valid.
function outputProfileKey(clientKey, routeKey) {
    if (typeof clientKey !== 'string' || !clientKey.trim() ||
        typeof routeKey !== 'string' || !routeKey.trim()) return null;
    return encodeURIComponent(clientKey) + '::' + encodeURIComponent(routeKey);
}

function selectOutputProfile(profiles, clientKey, routeKey) {
    const key = outputProfileKey(clientKey, routeKey);
    return key && profiles && Object.prototype.hasOwnProperty.call(profiles, key) ?
        profiles[key] : null;
}

function validOverride(override, context, instant) {
    if (!override || typeof override !== 'object' ||
        typeof context.playbackId !== 'string' || !context.playbackId ||
        override.playbackId !== context.playbackId ||
        !Number.isFinite(override.expiresAtMs) ||
        !Number.isFinite(instant.getTime()) ||
        instant.getTime() >= override.expiresAtMs) return null;
    return typeof override.enabled === 'boolean' ? override.enabled : null;
}

function resolveNightAudio(profile, context) {
    const settings = profile && typeof profile === 'object' ? profile : {};
    const ctx = context && typeof context === 'object' ? context : {};
    const instant = ctx.now instanceof Date ? ctx.now : new Date(ctx.now ?? NaN);
    const when = ['always', 'scheduled'].includes(settings.when) ? settings.when : 'off';
    const configuration = settings.configuration === 'custom' ? 'custom' : 'automatic';
    const strength = Object.prototype.hasOwnProperty.call(PRESETS, settings.strength) ?
        settings.strength : 'balanced';
    let requested = when === 'always' || (when === 'scheduled' && scheduleMatches(settings.schedule, instant));
    const manual = validOverride(ctx.override, ctx, instant);
    if (manual !== null) requested = manual;
    const audioPath = ctx.backend?.audioPath;
    let status = 'off';
    if (requested) {
        if (audioPath === 'bitstream') {
            status = 'bypassed';
        } else if (audioPath !== 'pcm' || ctx.backend?.canCompress !== true) {
            status = 'unavailable';
        } else {
            const confirmed = ctx.backend.applied === true &&
                typeof ctx.playbackId === 'string' && !!ctx.playbackId &&
                ctx.backend.appliedPlaybackId === ctx.playbackId &&
                ctx.backend.appliedStrength === strength;
            status = confirmed ? 'active' : 'ready';
        }
    }
    const dialogue = ['low', 'high'].includes(settings.dialogueClarity) ?
        'requested-not-implemented' : 'off';
    return Object.freeze({
        requested, status, when, configuration, strength, dialogue,
        params: PRESETS[strength],
    });
}

// Pure MPV command intent only. The adapter must check true decoded PCM output,
// send these via its real transport, observe the named filter and prove audible
// operation before it reports processing as active. No forced passthrough changes.
function buildMpvFilter(strength) {
    if (!Object.prototype.hasOwnProperty.call(PRESETS, strength)) return null;
    const p = PRESETS[strength];
    return FILTER_LABEL + ':lavfi=[acompressor=threshold=' + p.threshold +
        ':ratio=' + p.ratio + ':attack=' + p.attackMs +
        ':release=' + p.releaseMs +
        ':makeup=1:link=maximum:detection=rms,' +
        'alimiter=limit=0.9:level=0:attack=5:release=80]';
}

function proposeMpvCommands(result, ownedFilterPresent) {
    if (!result || typeof result !== 'object') return [];
    if (!result.requested) {
        return ownedFilterPresent === true ? [['af', 'remove', FILTER_LABEL]] : [];
    }
    if (result.status !== 'ready') return [];
    const filter = buildMpvFilter(result.strength);
    return filter ? [
        ['af', 'remove', FILTER_LABEL],
        ['af', 'add', filter],
    ] : [];
}

module.exports = {
    PRESETS, FILTER_LABEL, parseClock, localMinute, scheduleMatches,
    outputProfileKey, selectOutputProfile, resolveNightAudio,
    buildMpvFilter, proposeMpvCommands,
};
