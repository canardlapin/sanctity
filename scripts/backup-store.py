#!/usr/bin/env python3
"""Immutable backup transport with distinct publication and recovery credentials.

The service never mounts source storage. Local HTTP is a test transport; deployed
clients require HTTPS unless --allow-insecure-test is explicitly selected.
"""
import argparse
import datetime as dt
import hashlib
import hmac
import http.client
import http.server
import json
import os
from pathlib import Path
import re
import shutil
import ssl
import subprocess
import threading
import time
import urllib.parse
import uuid

VERSION = 'sanctity-backup-v2'
CHUNK = 65536
MAX_BYTES = 64 * 1024**3
MAX_MANIFEST = 32 * 1024**2
MAX_OBJECTS = 100000
KEY = re.compile(r'[0-9a-f]{64}\Z')
FILE = re.compile(r'(catalog\.dump|policy-link\.json|storage/objects/[0-9a-f]{2}/[0-9a-f]{64})\Z')


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode()


def sha(data):
    return hashlib.sha256(data).hexdigest()


def require(ok):
    if not ok:
        raise ValueError('backup verification refused')


def read_json(path):
    require(path.is_file() and not path.is_symlink() and path.stat().st_size <= MAX_MANIFEST)
    return json.loads(path.read_bytes())


def identity(manifest):
    require(manifest['version'] == VERSION and KEY.fullmatch(manifest['artifact']['sha256']))
    require(set(manifest) == {'version', 'createdAt', 'artifact', 'schema', 'migrations',
                             'postgresVersionNumber', 'installation', 'capabilities', 'policyHead', 'objects', 'files'})
    require(re.fullmatch('[a-z][a-z0-9_]{0,62}', manifest['schema']))
    require(isinstance(manifest['files'], dict) and len(manifest['files']) <= MAX_OBJECTS + 2)
    require({'catalog.dump', 'policy-link.json'} <= set(manifest['files']))
    total = 0
    for name, record in manifest['files'].items():
        require(FILE.fullmatch(name) and set(record) == {'sha256', 'size'} and KEY.fullmatch(record['sha256']))
        require(type(record['size']) is int and 0 <= record['size'] <= MAX_BYTES)
        total += record['size']
        if name.startswith('storage/'):
            require(name == 'storage/objects/' + record['sha256'][:2] + '/' + record['sha256'])
    require(total <= MAX_BYTES)
    objects = {'storage/objects/' + b['sha256'][:2] + '/' + b['sha256']: b for b in manifest['objects']}
    require(len(objects) == len(manifest['objects']) and objects == {
        k: v for k, v in manifest['files'].items() if k.startswith('storage/')})
    return sha(canonical(manifest))


def verified(root, manifest=None):
    manifest = read_json(root / 'bundle.json') if manifest is None else manifest
    key = identity(manifest)
    observed = set()
    for p in root.rglob('*'):
        require(not p.is_symlink())
        if p.is_file():
            observed.add(p.relative_to(root).as_posix())
    require(observed == set(manifest['files']) | ({'bundle.json'} if (root / 'bundle.json').exists() else set()))
    for name, expected in manifest['files'].items():
        p = root / name
        require(p.is_file() and p.stat().st_size == expected['size'])
        with p.open('rb') as f:
            require(hashlib.file_digest(f, 'sha256').hexdigest() == expected['sha256'])
    return key


def force_dir(path):
    fd = os.open(path, os.O_RDONLY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def atomic_json(path, value):
    temporary = path.with_name('.writing-' + uuid.uuid4().hex)
    with temporary.open('xb') as f:
        f.write(canonical(value)); f.flush(); os.fsync(f.fileno())
    os.replace(temporary, path)
    force_dir(path.parent)


def credential(path):
    require(path.is_file() and not path.is_symlink() and path.stat().st_mode & 0o077 == 0)
    token = path.read_text().strip()
    require(32 <= len(token) <= 512 and all(33 <= ord(c) < 127 for c in token))
    return token


class Store(http.server.ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 8

    def __init__(self, address, root, tokens, maximum):
        super().__init__(address, Handler)
        self.root, self.tokens, self.maximum = root, tokens, maximum
        self.mutation = threading.Lock()
        self.slots = threading.BoundedSemaphore(8)
        for name in ['pending', 'bundles']:
            (root / name).mkdir(mode=0o700, exist_ok=True)

    def process_request(self, request, address):
        if not self.slots.acquire(blocking=False):
            request.close(); return
        try:
            super().process_request(request, address)
        except BaseException:
            self.slots.release(); raise

    def process_request_thread(self, request, address):
        try:
            super().process_request_thread(request, address)
        finally:
            self.slots.release()

    def handle_error(self, request, address):
        pass  # No request headers, token, paths or exception strings enter logs.


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def setup(self):
        super().setup(); self.connection.settimeout(30)

    def answer(self, code, value):
        data = canonical(value)
        self.send_response(code); self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(data))); self.end_headers(); self.wfile.write(data)

    def authorized(self, role):
        value = self.headers.get('Authorization', '')
        return hmac.compare_digest(sha(value.encode()), self.server.tokens[role])

    def do_PUT(self):
        if not self.authorized('writer'):
            self.answer(403, {'status': 'denied'}); return
        try:
            match = re.fullmatch(r'/v2/pending/([0-9a-f]{64})/(.+)', self.path)
            require(match is not None and FILE.fullmatch(match[2]))
            key, name = match[1], match[2]
            size = int(self.headers['Content-Length']); require(0 <= size <= MAX_BYTES)
            with self.server.mutation:
                require(not (self.server.root / 'bundles' / key).exists())
                used = sum(p.stat().st_size for p in self.server.root.rglob('*') if p.is_file())
                root = self.server.root / 'pending' / key
                require(root.exists() or sum(1 for p in self.server.root.glob('*/*') if p.is_dir()) < 128)
                target = root / name
                require(used - (target.stat().st_size if target.exists() else 0) + size <= self.server.maximum)
                require(target.exists() or not root.exists() or sum(1 for p in root.rglob('*') if p.is_file()) < MAX_OBJECTS + 2)
                target.parent.mkdir(parents=True, mode=0o700, exist_ok=True)
                temporary = target.with_name('.writing-' + uuid.uuid4().hex)
                try:
                    remaining = size; started = time.monotonic()
                    with temporary.open('xb') as f:
                        while remaining:
                            require(time.monotonic() - started < 300)
                            block = self.rfile.read(min(remaining, CHUNK)); require(block)
                            f.write(block); remaining -= len(block)
                        f.flush(); os.fsync(f.fileno())
                    # Only unpublished staging may be replaced on a verified retry.
                    os.replace(temporary, target); force_dir(target.parent)
                finally:
                    temporary.unlink(missing_ok=True)
            self.answer(200, {'status': 'staged'})
        except (ValueError, OSError, KeyError, TypeError):
            self.answer(409, {'status': 'incomplete'})

    def do_POST(self):
        if not self.authorized('writer'):
            self.answer(403, {'status': 'denied'}); return
        try:
            match = re.fullmatch(r'/v2/publish/([0-9a-f]{64})', self.path)
            require(match is not None)
            size = int(self.headers['Content-Length']); require(0 < size <= MAX_MANIFEST)
            manifest = json.loads(self.rfile.read(size)); key = identity(manifest); require(key == match[1])
            with self.server.mutation:
                target = self.server.root / 'bundles' / key
                if target.exists():
                    require(verified(target) == key)
                else:
                    pending = self.server.root / 'pending' / key
                    require(pending.is_dir() and verified(pending, manifest) == key)
                    atomic_json(pending / 'bundle.json', manifest)
                    for directory in sorted((p for p in pending.rglob('*') if p.is_dir()), key=lambda p: len(p.parts), reverse=True):
                        force_dir(directory)
                    force_dir(pending)
                    os.rename(pending, target); force_dir(target.parent)
            self.answer(200, {'status': 'published', 'bundle': key})
        except (ValueError, OSError, KeyError, TypeError):
            self.answer(409, {'status': 'incomplete'})

    def do_GET(self):
        if not self.authorized('reader'):
            self.answer(403, {'status': 'denied'}); return
        try:
            if self.path == '/v2/inventory':
                rows = []
                for root in sorted((self.server.root / 'bundles').iterdir()):
                    manifest = read_json(root / 'bundle.json'); require(identity(manifest) == root.name)
                    created = dt.datetime.fromisoformat(manifest['createdAt'].replace('Z', '+00:00'))
                    rows.append({'bundle': root.name, 'createdAt': manifest['createdAt'],
                                 'ageSeconds': max(0, (dt.datetime.now(dt.timezone.utc) - created).total_seconds()),
                                 'bytes': sum(x['size'] for x in manifest['files'].values()),
                                 'objects': len(manifest['objects']), 'policyHead': manifest['policyHead'],
                                 'policyLinkSha256': manifest['files']['policy-link.json']['sha256'],
                                 'verifiedAtPublication': True})
                self.answer(200, {'version': 1, 'bundles': rows}); return
            match = re.fullmatch(r'/v2/bundles/([0-9a-f]{64})/(.+)', self.path); require(match is not None)
            root = self.server.root / 'bundles' / match[1]
            manifest = read_json(root / 'bundle.json'); require(identity(manifest) == match[1])
            name = match[2]; require(name == 'bundle.json' or name in manifest['files'])
            path = root / name; require(path.is_file() and not path.is_symlink())
            self.send_response(200); self.send_header('Content-Type', 'application/octet-stream')
            self.send_header('Content-Length', str(path.stat().st_size)); self.end_headers()
            with path.open('rb') as f:
                shutil.copyfileobj(f, self.wfile, CHUNK)
        except (ValueError, OSError, KeyError, TypeError):
            self.answer(404, {'status': 'unavailable'})


def connection(url, insecure):
    parsed = urllib.parse.urlsplit(url)
    require(parsed.scheme in ('http', 'https') and parsed.hostname and not parsed.username and
            not parsed.password and not parsed.query and not parsed.fragment and parsed.path in ('', '/'))
    require(parsed.scheme == 'https' or insecure)
    cls = http.client.HTTPSConnection if parsed.scheme == 'https' else http.client.HTTPConnection
    return cls(parsed.hostname, parsed.port, timeout=30)


def request(url, token, method, route, body=None, path=None, output=None, insecure=False, allowed=(200,)):
    for attempt in range(3):
        conn = connection(url, insecure)
        started = time.monotonic()
        try:
            size = path.stat().st_size if path else len(body or b'')
            conn.putrequest(method, route); conn.putheader('Authorization', 'Bearer ' + token)
            conn.putheader('Content-Length', str(size)); conn.endheaders()
            if path:
                with path.open('rb') as f:
                    for block in iter(lambda: f.read(CHUNK), b''):
                        require(time.monotonic() - started < 300); conn.send(block)
            elif body:
                conn.send(body)
            response = conn.getresponse()
            require(response.status in allowed)
            length = int(response.getheader('Content-Length', '-1')); require(0 <= length <= MAX_BYTES)
            if output:
                remaining = length
                with output.open('wb') as f:
                    while remaining:
                        require(time.monotonic() - started < 300)
                        block = response.read(min(remaining, CHUNK)); require(block)
                        f.write(block); remaining -= len(block)
                    f.flush(); os.fsync(f.fileno())
                return response.status, None
            require(length <= MAX_MANIFEST)
            data = response.read(length); require(len(data) == length)
            return response.status, json.loads(data)
        except (OSError, http.client.HTTPException):
            if attempt == 2:
                raise RuntimeError('backup transport unavailable') from None
            time.sleep(.2 * (attempt + 1))
        finally:
            conn.close()


def push(bundle, url, token, insecure):
    manifest = read_json(bundle / 'bundle.json'); key = verified(bundle)
    code, result = request(url, token, 'POST', '/v2/publish/' + key, body=canonical(manifest), insecure=insecure, allowed=(200, 409))
    if code == 200:
        return result
    for name in sorted(manifest['files']):
        request(url, token, 'PUT', '/v2/pending/' + key + '/' + name, path=bundle / name, insecure=insecure)
    return request(url, token, 'POST', '/v2/publish/' + key, body=canonical(manifest), insecure=insecure)[1]


def pull(key, output, url, token, insecure):
    require(KEY.fullmatch(key) and not output.exists())
    _, manifest = request(url, token, 'GET', '/v2/bundles/' + key + '/bundle.json', insecure=insecure)
    require(identity(manifest) == key)
    temporary = output.with_name('.pending-' + output.name + '-' + uuid.uuid4().hex)
    temporary.mkdir(mode=0o700)
    for name in sorted(manifest['files']):
        target = temporary / name; target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        request(url, token, 'GET', '/v2/bundles/' + key + '/' + name, output=target, insecure=insecure)
    atomic_json(temporary / 'bundle.json', manifest); require(verified(temporary) == key)
    os.rename(temporary, output); force_dir(output.parent)
    return {'status': 'verified', 'bundle': key}


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    serve = commands.add_parser('serve'); serve.add_argument('--root', type=Path, required=True)
    serve.add_argument('--writer-token', type=Path, required=True); serve.add_argument('--reader-token', type=Path, required=True)
    serve.add_argument('--bind', default='127.0.0.1'); serve.add_argument('--port', type=int, required=True)
    serve.add_argument('--certificate', type=Path); serve.add_argument('--private-key', type=Path)
    serve.add_argument('--max-bytes', type=int, default=MAX_BYTES)
    for name in ['push', 'pull', 'inventory', 'schedule']:
        command = commands.add_parser(name); command.add_argument('--url', required=True)
        command.add_argument('--token-file', type=Path, required=True); command.add_argument('--allow-insecure-test', action='store_true')
        if name == 'push': command.add_argument('--bundle', type=Path, required=True)
        if name == 'pull':
            command.add_argument('--bundle-id', required=True); command.add_argument('--output', type=Path, required=True)
        if name == 'schedule':
            command.add_argument('--capture-command', type=Path, required=True)
            command.add_argument('--staging-root', type=Path, required=True)
            command.add_argument('--interval', type=int, required=True); command.add_argument('--count', type=int, required=True)
    args = parser.parse_args()
    if args.command == 'serve':
        writer, reader = credential(args.writer_token), credential(args.reader_token); require(writer != reader)
        require(0 < args.max_bytes <= MAX_BYTES)
        require(args.root.is_dir() and not args.root.is_symlink())
        store = Store((args.bind, args.port), args.root, {'writer': sha(('Bearer '+writer).encode()), 'reader': sha(('Bearer '+reader).encode())}, args.max_bytes)
        if args.certificate:
            context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER); context.load_cert_chain(args.certificate, args.private_key)
            store.socket = context.wrap_socket(store.socket, server_side=True)
        store.serve_forever()
    else:
        token = credential(args.token_file); insecure = args.allow_insecure_test
        if args.command == 'push': result = push(args.bundle, args.url, token, insecure)
        elif args.command == 'pull': result = pull(args.bundle_id, args.output, args.url, token, insecure)
        elif args.command == 'inventory': result = request(args.url, token, 'GET', '/v2/inventory', insecure=insecure)[1]
        else:
            command = read_json(args.capture_command)
            require(isinstance(command, list) and all(isinstance(x, str) for x in command) and command.count('{output}') == 1)
            require(args.staging_root.is_dir() and 1 <= args.interval <= 86400 and 1 <= args.count <= 10000)
            results = []
            for index in range(args.count):
                output = args.staging_root / ('backup-' + uuid.uuid4().hex)
                completed = subprocess.run([str(output) if x == '{output}' else x for x in command], capture_output=True, timeout=3600)
                if completed.returncode != 0:
                    print(json.dumps({'scheduledRun': index + 1, 'status': 'failed', 'phase': 'capture'}), flush=True)
                require(completed.returncode == 0)
                results.append(push(output, args.url, token, insecure))
                print(json.dumps({'scheduledRun': index + 1, **results[-1]}), flush=True)
                if index + 1 < args.count: time.sleep(args.interval)
            result = {'status': 'completed', 'runs': len(results)}
        print(json.dumps(result, sort_keys=True))


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, KeyError, TypeError, RuntimeError, subprocess.SubprocessError):
        print(json.dumps({'status': 'failed', 'detail': 'backup operation refused; inspect protected configuration and retained incomplete attempt'}))
        raise SystemExit(1) from None
