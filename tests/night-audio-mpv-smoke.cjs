'use strict';

// Isolated CI verification on synthetic audio, never real account content.
const assert = require('node:assert/strict');
const { spawn, execFileSync } = require('node:child_process');
const fs = require('node:fs');
const net = require('node:net');
const os = require('node:os');
const path = require('node:path');
const { buildMpvFilter, FILTER_LABEL } = require('../src/audio/nightAudioPolicy');

function createSyntheticWav(filename) {
    const rate = 48000, channels = 2, frames = rate * 4;
    const dataSize = frames * channels * 2;
    const wav = Buffer.alloc(44 + dataSize);
    wav.write('RIFF', 0);
    wav.writeUInt32LE(36 + dataSize, 4);
    wav.write('WAVEfmt ', 8);
    wav.writeUInt32LE(16, 16);
    wav.writeUInt16LE(1, 20);
    wav.writeUInt16LE(channels, 22);
    wav.writeUInt32LE(rate, 24);
    wav.writeUInt32LE(rate * channels * 2, 28);
    wav.writeUInt16LE(channels * 2, 32);
    wav.writeUInt16LE(16, 34);
    wav.write('data', 36);
    wav.writeUInt32LE(dataSize, 40);
    for (let i = 0; i < frames; i++) {
        const amplitude = i < rate * 2 ? 0.035 : 0.80;
        const sample = Math.round(Math.sin(i * Math.PI * 2 * 440 / rate) * amplitude * 32767);
        for (let c = 0; c < channels; c++) wav.writeInt16LE(sample, 44 + (i * channels + c) * 2);
    }
    fs.writeFileSync(filename, wav);
}

function readWav(filename) {
    const bytes = fs.readFileSync(filename);
    assert.equal(bytes.toString('ascii', 0, 4), 'RIFF');
    assert.equal(bytes.toString('ascii', 8, 12), 'WAVE');
    let offset = 12, audioFormat = 0, channels = 0, rate = 0, bits = 0, start = -1, length = 0;
    while (offset + 8 <= bytes.length) {
        const kind = bytes.toString('ascii', offset, offset + 4);
        const size = bytes.readUInt32LE(offset + 4);
        if (kind === 'fmt ') {
            audioFormat = bytes.readUInt16LE(offset + 8);
            channels = bytes.readUInt16LE(offset + 10);
            rate = bytes.readUInt32LE(offset + 12);
            bits = bytes.readUInt16LE(offset + 22);
        } else if (kind === 'data') {
            start = offset + 8;
            length = size;
        }
        offset += 8 + size + (size % 2);
    }
    assert.equal(audioFormat, 1);
    assert.equal(channels, 2);
    assert.equal(rate, 48000);
    assert.equal(bits, 16);
    assert.ok(start > 0 && length >= rate * 4 * channels * 2);
    const sampleAt = (i) => bytes.readInt16LE(start + i * channels * 2) / 32768;
    const measure = (from, to) => {
        let squared = 0, peak = 0, count = 0;
        for (let i = Math.floor(from * rate); i < Math.floor(to * rate); i++) {
            const s = sampleAt(i);
            squared += s * s;
            peak = Math.max(peak, Math.abs(s));
            count += 1;
        }
        return { rms: Math.sqrt(squared / count), peak };
    };
    return { quiet: measure(0.4, 1.7), loud: measure(2.6, 3.7) };
}

function checkAudioFixture(input, output) {
    const before = readWav(input);
    const after = readWav(output);
    const originalRatio = before.loud.rms / before.quiet.rms;
    const processedRatio = after.loud.rms / after.quiet.rms;
    assert.ok(after.quiet.rms >= before.quiet.rms * 0.75, 'quiet material not suppressed');
    assert.ok(after.quiet.rms <= before.quiet.rms * 1.25, 'quiet material not unexpectedly amplified');
    assert.ok(processedRatio < originalRatio * 0.7,
        'processed sound must have materially less loud-to-quiet difference');
    assert.ok(after.loud.peak < 0.92, 'processed peaks safely limited');
    return { originalRatio, processedRatio, quietBefore: before.quiet.rms, quietAfter: after.quiet.rms };
}

async function checkMpv(input, dir) {
    const socketPath = path.join(dir, 'player.sock');
    const mpv = spawn('mpv', [
        '--no-config', '--idle=yes', '--no-terminal', '--vo=null', '--ao=null',
        '--audio-display=no', '--loop-file=inf', '--input-ipc-server=' + socketPath,
    ], { stdio: ['ignore', 'pipe', 'pipe'] });
    let logs = '';
    mpv.stderr.on('data', (data) => { logs = (logs + String(data)).slice(-4000); });
    const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
    let socket;
    try {
        for (let n = 0; n < 50; n++) {
            if (fs.existsSync(socketPath)) {
                try {
                    socket = net.createConnection(socketPath);
                    await new Promise((resolve, reject) => {
                        socket.once('connect', resolve);
                        socket.once('error', reject);
                    });
                    break;
                } catch (_) {
                    socket.destroy();
                    socket = null;
                }
            }
            if (mpv.exitCode !== null) throw new Error('mpv exited before IPC startup ' + logs);
            await delay(100);
        }
        assert.ok(socket, 'mpv IPC socket did not start');
        socket.setEncoding('utf8');
        let buffer = '', nextId = 1;
        const pending = new Map();
        socket.on('data', (part) => {
            buffer += part;
            for (;;) {
                const pos = buffer.indexOf('\n');
                if (pos < 0) break;
                const line = buffer.slice(0, pos);
                buffer = buffer.slice(pos + 1);
                if (!line) continue;
                let event;
                try { event = JSON.parse(line); } catch (_) { continue; }
                const request = pending.get(event.request_id);
                if (request) {
                    pending.delete(event.request_id);
                    clearTimeout(request.timer);
                    request.resolve(event);
                }
            }
        });
        const command = (...args) => new Promise((resolve, reject) => {
            const id = nextId++;
            const timer = setTimeout(() => {
                pending.delete(id);
                reject(new Error('mpv IPC timeout for ' + args[0]));
            }, 2500);
            pending.set(id, { resolve, timer });
            socket.write(JSON.stringify({ command: args, request_id: id }) + '\n');
        });
        const must = async (...args) => {
            const response = await command(...args);
            assert.equal(response.error, 'success', args[0] + ': ' + response.error);
            return response.data;
        };
        await must('loadfile', input, 'replace');
        let audioReady = false;
        for (let n = 0; n < 35; n++) {
            const x = await command('get_property', 'audio-out-params');
            if (x.error === 'success' && x.data && x.data.format) {
                audioReady = true;
                break;
            }
            await delay(100);
        }
        assert.ok(audioReady, 'mpv never established decoded audio');
        const target = buildMpvFilter('balanced');
        await must('af', 'add', target);
        let observed;
        for (let n = 0; n < 25; n++) {
            observed = await must('get_property', 'af');
            if (Array.isArray(observed) && observed.some((node) =>
                node.label === FILTER_LABEL || node.label === FILTER_LABEL.slice(1))) break;
            await delay(100);
        }
        assert.ok(Array.isArray(observed) && observed.some((node) =>
            node.label === FILTER_LABEL || node.label === FILTER_LABEL.slice(1)),
        'mpv did not report installed named filter');
        await must('af', 'remove', FILTER_LABEL);
        const cleared = await must('get_property', 'af');
        assert.ok(!Array.isArray(cleared) || !cleared.some((node) =>
            node.label === FILTER_LABEL || node.label === FILTER_LABEL.slice(1)),
        'mpv did not remove named filter');
        return true;
    } finally {
        if (socket) socket.destroy();
        mpv.kill('SIGTERM');
        await Promise.race([
            new Promise((resolve) => mpv.once('exit', resolve)),
            delay(1500),
        ]);
        if (mpv.exitCode === null) mpv.kill('SIGKILL');
    }
}

async function main() {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'stremio-night-audio-'));
    try {
        const input = path.join(dir, 'synthetic.wav');
        const output = path.join(dir, 'compressed.wav');
        createSyntheticWav(input);
        const wrapper = buildMpvFilter('balanced');
        const graph = wrapper.slice(wrapper.indexOf('[') + 1, -1);
        execFileSync('ffmpeg', [
            '-hide_banner', '-nostdin', '-loglevel', 'error', '-y',
            '-i', input, '-af', graph, '-ac', '2', '-ar', '48000', '-c:a', 'pcm_s16le', output,
        ], { stdio: 'pipe', timeout: 15000 });
        const measurement = checkAudioFixture(input, output);
        process.stdout.write('Synthetic DSP measurement PASS ' + JSON.stringify(measurement) + '\n');
        await checkMpv(input, dir);
        process.stdout.write('MPV live filter apply, observe and remove PASS\n');
    } finally {
        fs.rmSync(dir, { recursive: true, force: true });
    }
}

main().catch((error) => {
    process.stderr.write('Night Audio smoke test failed: ' + error.message + '\n');
    process.exitCode = 1;
});
