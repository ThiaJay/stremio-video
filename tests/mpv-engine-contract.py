"""Exercise the exact recovery commands in MPV with generated local media.
Null outputs prove the engine contract, not physical lip synchronisation.
"""
import json
import math
import socket
import subprocess
import tempfile
import time
from pathlib import Path


def wait_for(predicate, description, timeout=8):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        value = predicate()
        if value:
            return value
        time.sleep(0.05)
    raise AssertionError(description)


with tempfile.TemporaryDirectory(prefix='av-sync-engine-') as temp:
    root = Path(temp)
    endpoint = root / 'mpv.sock'
    process = subprocess.Popen([
        'mpv', '--no-config', '--idle=yes', '--no-terminal', '--pause',
        '--vo=null', '--ao=null', '--keep-open=yes', '--video-sync=display-resample',
        '--input-ipc-server=' + str(endpoint)
    ], stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    connection = socket.socket(socket.AF_UNIX)
    connection.settimeout(5)
    try:
        wait_for(endpoint.exists, 'MPV IPC did not start')
        connection.connect(str(endpoint))
        reader = connection.makefile('r', encoding='utf-8')
        counter = 0

        def command(*args, optional=False):
            global counter
            counter += 1
            connection.sendall((json.dumps({'command': args, 'request_id': counter}) + '\n').encode())
            while True:
                line = reader.readline()
                assert line, 'MPV IPC closed unexpectedly'
                response = json.loads(line)
                if response.get('request_id') != counter:
                    continue
                if optional and response.get('error') != 'success':
                    return None
                assert response.get('error') == 'success', response
                return response.get('data')

        def get(name, optional=False):
            return command('get_property', name, optional=optional)

        version = get('mpv-version')
        passed = []
        for numerator, denominator in [(24000, 1001), (24, 1), (25, 1), (30000, 1001), (30, 1), (50, 1), (60000, 1001), (60, 1)]:
            file = root / ('fixture-' + str(numerator) + '-' + str(denominator) + '.mkv')
            subprocess.run([
                'ffmpeg', '-nostdin', '-hide_banner', '-loglevel', 'error',
                '-f', 'lavfi', '-i', f'testsrc=size=64x64:rate={numerator}/{denominator}',
                '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=48000',
                '-t', '3', '-c:v', 'mpeg4', '-q:v', '8', '-c:a', 'pcm_s16le', '-shortest', str(file)
            ], check=True, timeout=15)
            command('loadfile', str(file), 'replace')
            wait_for(lambda: get('duration', True), 'No decoded media duration')
            assert get('video-sync') == 'display-resample', 'Previous file clock override leaked'
            fps = get('container-fps')
            assert abs(fps - numerator / denominator) < 0.1, (fps, numerator, denominator)
            command('set_property', 'audio-delay', 0.125)
            command('set_property', 'speed', 1.25)
            command('set_property', 'file-local-options/video-sync', 'audio')
            assert get('video-sync') == 'audio'
            assert math.isclose(get('audio-delay'), 0.125, abs_tol=0.000001)
            assert math.isclose(get('speed'), 1.25, abs_tol=0.000001)
            command('seek', 0.75, 'absolute+exact')
            wait_for(lambda: get('seeking', True) is False, 'Initial seek did not settle')
            position = get('time-pos')
            command('seek', 0, 'relative+exact')
            wait_for(lambda: get('seeking', True) is False, 'Recovery seek did not settle')
            assert abs(get('time-pos') - position) < 0.15, 'Recovery moved away from the current position'
            assert get('pause') is True, 'Recovery changed pause state'
            assert math.isclose(get('audio-delay'), 0.125, abs_tol=0.000001)
            assert math.isclose(get('speed'), 1.25, abs_tol=0.000001)
            command('set_property', 'pause', False)
            time.sleep(0.2)
            assert get('time-pos') >= position, 'Playback failed to advance after recovery'
            command('set_property', 'pause', True)
            command('stop')
            wait_for(lambda: get('video-sync') == 'display-resample', 'File clock override did not restore')
            command('set_property', 'audio-delay', 0)
            command('set_property', 'speed', 1)
            passed.append(f'{numerator}/{denominator}')
        print(json.dumps({'mpv': version, 'frameRates': passed, 'engineContract': 'passed', 'physicalOutputAcceptance': False}))
    finally:
        connection.close()
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)
