"""Whisper worker. Credentials stay on the server; no speech is sent to a third-party API."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
from urllib.request import Request, urlopen
from urllib.parse import quote

MAX_BYTES = 20 * 1024 * 1024


def configuration(local=False):
    if local:
        repo = Path(__file__).resolve().parents[2]
        config = json.loads(subprocess.check_output([str(repo / 'node_modules/.bin/supabase'), 'status', '-o', 'json'], cwd=repo, stderr=subprocess.DEVNULL))
        base, key = config['API_URL'], config['SERVICE_ROLE_KEY']
        if not base.startswith(('http://127.0.0.1:', 'http://localhost:')):
            raise ValueError('Local mode requires a loopback backend')
        return base, key
    return os.environ['SUPABASE_URL'].rstrip('/'), os.environ['SUPABASE_SERVICE_ROLE_KEY']


def rpc(base, key, function, payload):
    request = Request(base + '/rest/v1/rpc/' + function, data=json.dumps(payload).encode(),
                      headers={'apikey': key, 'Authorization': 'Bearer ' + key, 'Content-Type': 'application/json'})
    with urlopen(request, timeout=30) as response:
        return json.load(response)


def transcribe_job(base, key, model, job):
    payload = dict(p_file_id=job['file_id'], p_lease_id=job['lease_id'], p_transcript=None, p_language=None, p_error_code=None)
    try:
        if not 0 < job['size_bytes'] <= MAX_BYTES:
            raise ValueError('audio_too_large')
        path = quote(job['storage_path'], safe='/')
        request = Request(base + '/storage/v1/object/authenticated/group-files/' + path,
                          headers={'apikey': key, 'Authorization': 'Bearer ' + key})
        with tempfile.NamedTemporaryFile(suffix='.audio') as audio:
            with urlopen(request, timeout=60) as response:
                total = 0
                while chunk := response.read(64 * 1024):
                    total += len(chunk)
                    if total > MAX_BYTES or total > job['size_bytes']:
                        raise ValueError('audio_too_large')
                    audio.write(chunk)
            if total != job['size_bytes']:
                raise ValueError('audio_incomplete')
            audio.flush()
            import av
            with av.open(audio.name) as container:
                if not container.streams.audio or container.duration is None or container.duration / av.time_base > 1800:
                    raise ValueError('audio_too_long_or_invalid')
            segments, info = model.transcribe(audio.name, beam_size=5, vad_filter=True, condition_on_previous_text=False)
            if info.duration > 1800:
                raise ValueError('audio_too_long')
            payload['p_transcript'] = ' '.join(segment.text.strip() for segment in segments).strip()[:200000]
            payload['p_language'] = info.language
    except Exception as error:
        print("Transcription error: " + type(error).__name__, flush=True)
        # Do not put filenames, audio, tokens or provider internals in logs/client errors.
        payload['p_error_code'] = 'transcription_failed'
    rpc(base, key, 'finish_file_transcript', payload)
    print('Transcription completed' if payload['p_error_code'] is None else 'Transcription failed', flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--local', action='store_true')
    parser.add_argument('--once', action='store_true')
    args = parser.parse_args()
    base, key = configuration(args.local)
    from faster_whisper import WhisperModel
    model = WhisperModel(os.environ.get('WHISPER_MODEL', 'base'), device='cpu', compute_type='int8',
                         download_root=os.environ.get('WHISPER_MODEL_DIR'), cpu_threads=4)
    print('Whisper worker ready', flush=True)
    while True:
        try:
            jobs = rpc(base, key, 'claim_file_transcript', {})
            if jobs:
                transcribe_job(base, key, model, jobs[0])
            elif not args.once:
                time.sleep(3)
        except KeyboardInterrupt:
            return
        except Exception:
            print('Backend unavailable; retrying', flush=True)
            if not args.once:
                time.sleep(10)
        if args.once:
            return


if __name__ == '__main__':
    main()
