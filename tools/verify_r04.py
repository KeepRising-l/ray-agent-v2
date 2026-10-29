"""R04 隔离验收：真实 PostgreSQL/pgvector；默认模拟 Embedding，--live 用本机真实配置。

原文、测试文件和截图只放 Git 忽略目录；不输出密钥或向量内容。
"""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--live', action='store_true')
parser.add_argument('--browser', action='store_true')
args = parser.parse_args()
props = {}
for line in (ROOT / 'application-local.properties').read_text(encoding='utf-8').splitlines():
    if '=' in line and not line.lstrip().startswith('#'):
        key, value = line.split('=', 1)
        props[key.strip()] = value.strip()
schema = 'r04_check_' + uuid.uuid4().hex
assert re.fullmatch(r'r04_check_[a-f0-9]{32}', schema)
data_root = ROOT / '.ray-data'
data_root.mkdir(exist_ok=True)
storage = Path(tempfile.mkdtemp(prefix='r04-check-', dir=data_root)).resolve()
assert storage.is_relative_to(data_root.resolve())
hold = True


def sql(command):
    result = subprocess.run(['wsl', '--exec', 'docker', 'exec', 'pgvector-db', 'psql', '-U', 'postgres',
                             '-d', 'ray_agent_v2', '-At', '-c', command], capture_output=True, check=True)
    return result.stdout.decode('utf-8').strip()


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


class MockEmbedding(BaseHTTPRequestHandler):
    def log_message(self, *unused):
        pass

    def do_POST(self):
        request = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        if hold and any('hold-embedding' in text for text in request['input']):
            time.sleep(8)
        data = [{'object': 'embedding', 'index': i, 'embedding': [1.0, float(i + 1), .5]}
                for i in reversed(range(len(request['input'])))]
        body = json.dumps({'model': request['model'], 'data': data}).encode()
        try:
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.end_headers()
            self.wfile.write(body)
        except OSError:
            pass


def pdf_fixture(blank=False):
    # 自写的最小两页文本 PDF，无图片、私人教材或外部内容。
    objects = [b'<< /Type /Catalog /Pages 2 0 R >>', b'<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>',
               b'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 5 0 R >> >> /Contents 6 0 R >>',
               b'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 5 0 R >> >> /Contents 7 0 R >>',
               b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>']
    for text in ('RayAgent self-written page one.', 'Pine42 is on page two.'):
        content = b'' if blank else ('BT /F1 12 Tf 50 700 Td (' + text + ') Tj ET').encode()
        objects.append(b'<< /Length ' + str(len(content)).encode() + b' >>\nstream\n' + content + b'\nendstream')
    output = b'%PDF-1.4\n'
    offsets = [0]
    for index, obj in enumerate(objects, 1):
        offsets.append(len(output))
        output += str(index).encode() + b' 0 obj\n' + obj + b'\nendobj\n'
    start = len(output)
    output += f'xref\n0 {len(offsets)}\n0000000000 65535 f \n'.encode()
    output += b''.join(f'{offset:010d} 00000 n \n'.encode() for offset in offsets[1:])
    return output + f'trailer\n<< /Size {len(offsets)} /Root 1 0 R >>\nstartxref\n{start}\n%%EOF\n'.encode()


port = free_port()
mock = ThreadingHTTPServer(('127.0.0.1', 0), MockEmbedding)
threading.Thread(target=mock.serve_forever, daemon=True).start()
env = os.environ.copy()
env.update(SPRING_DATASOURCE_URL=props['spring.datasource.url'] + '?currentSchema=' + schema,
           RAY_KNOWLEDGE_STORAGE_ROOT=str(storage / 'documents'))
if not args.live:
    env.update(RAY_EMBEDDING_BASE_URL=f'http://127.0.0.1:{mock.server_port}', RAY_EMBEDDING_API_KEY='test-only',
               RAY_EMBEDDING_MODEL='mock-embedding', RAY_EMBEDDING_DIMENSION='3')
settings = subprocess.run(['java', '-XshowSettings:properties', '-version'], capture_output=True, text=True)
java = Path(re.search(r'java\.home\s*=\s*(.+)', settings.stderr).group(1).strip()) / 'bin/java.exe'
process = None
frontend = None
log = tempfile.TemporaryFile()


def get(path):
    return json.load(urllib.request.urlopen(f'http://127.0.0.1:{port}/api/knowledge-bases' + path, timeout=5))


def post(path, body):
    req = urllib.request.Request(f'http://127.0.0.1:{port}/api/knowledge-bases' + path,
                                 data=json.dumps(body).encode(), headers={'Content-Type': 'application/json'})
    return json.load(urllib.request.urlopen(req, timeout=10))


def upload(kb, name, content, doc=None, checksum=None):
    boundary = uuid.uuid4().hex
    mime = 'application/pdf' if name.endswith('.pdf') else 'text/plain'
    body = f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\nContent-Type: {mime}\r\n\r\n'.encode() + content + b'\r\n'
    if checksum:
        body += f'--{boundary}\r\nContent-Disposition: form-data; name="sha256"\r\n\r\n{checksum}\r\n'.encode()
    body += f'--{boundary}--\r\n'.encode()
    path = f'/{kb}/documents' + (f'/{doc}/versions' if doc else '')
    req = urllib.request.Request(f'http://127.0.0.1:{port}/api/knowledge-bases' + path, data=body,
                                 headers={'Content-Type': 'multipart/form-data; boundary=' + boundary})
    return json.load(urllib.request.urlopen(req, timeout=15))


def version_path(kb, version):
    return f'/{kb}/documents/{version["documentId"]}/versions/{version["id"]}'


def wait(kb, version, wanted=('READY',), timeout=80):
    deadline = time.time() + timeout
    while time.time() < deadline:
        result = get(version_path(kb, version))
        state = result['version']['status']
        if state in wanted:
            return result
        if state in ('FAILED', 'INTERRUPTED'):
            raise AssertionError('Unexpected document state: ' + state + '; ' + str(result['version']['errorMessage']))
        time.sleep(.2)
    raise AssertionError('Document processing timeout')


def start():
    global process
    process = subprocess.Popen([str(java), '-jar', str(ROOT / 'ray-agent-server/target/ray-agent-server-0.1.0-SNAPSHOT.jar'),
                                f'--server.port={port}', '--logging.file.name=logs/r04-verify.log'], cwd=ROOT, env=env,
                               stdout=log, stderr=subprocess.STDOUT)
    for _ in range(200):
        if process.poll() is not None:
            raise RuntimeError('Backend startup failed; inspect ignored logs/r04-verify.log')
        try:
            get('')
            return
        except Exception:
            time.sleep(.1)
    raise RuntimeError('Backend startup timeout')


try:
    sql('CREATE SCHEMA ' + schema)
    start()
    if args.browser:
        fixtures = storage / 'fixtures'
        fixtures.mkdir()
        (fixtures / 'r04-source.md').write_text('# 自写资料\n\n测试代号 Pine42，树木在夏天需要浇水。\n', encoding='utf-8')
        (fixtures / 'r04-pages.pdf').write_bytes(pdf_fixture())
        (fixtures / 'r04-empty.pdf').write_bytes(pdf_fixture(blank=True))
        front_port = free_port()
        front_env = env.copy()
        front_env['RAY_DEV_API_TARGET'] = f'http://127.0.0.1:{port}'
        frontend = subprocess.Popen(['node', str(ROOT / 'ray-agent-web/node_modules/vite/bin/vite.js'), '--host', '127.0.0.1',
                                     '--port', str(front_port), '--strictPort'], cwd=ROOT / 'ray-agent-web', env=front_env,
                                    stdout=log, stderr=subprocess.STDOUT)
        browser_env = os.environ.copy()
        browser_env.setdefault('NODE_PATH', str(Path.home() / '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules'))
        subprocess.run(['node', str(ROOT / 'tools/verify_r04_browser.cjs'), f'http://127.0.0.1:{front_port}', str(fixtures)],
                        env=browser_env, cwd=ROOT, check=True)
    else:
        kb = post('', {'name': 'R04 verification'})['id']
        source = ('# 自写资料\n\n测试代号 Pine42，树木需要浇水。\n\n' + '这是用于分块核对的自写长段落。' * 150).encode()
        first = upload(kb, 'source.md', source)
        detail = wait(kb, first)
        chunks = get(version_path(kb, first) + '/chunks')
        assert len(chunks) > 2 and detail['vectorCount'] == len(chunks)
        assert any('Pine42' in chunk['content'] and chunk['heading'] == '自写资料' for chunk in chunks)
        assert upload(kb, 'source.md', source)['id'] == first['id']
        print('PASS upload -> parse -> located chunks -> embedding -> pgvector; duplicate content reuses version')
        for name, checksum in [('bad.exe', None), ('../bad.txt', None), ('checksum.txt', '0' * 64)]:
            try:
                upload(kb, name, b'test', checksum=checksum)
                raise AssertionError('Invalid upload was accepted')
            except urllib.error.HTTPError as error:
                assert error.code == 400
        try:
            upload(kb, 'too-large.txt', b'x' * (5 * 1024 * 1024 + 1))
            raise AssertionError('Oversized upload was accepted')
        except urllib.error.HTTPError as error:
            assert error.code == 413
        pdf = upload(kb, 'pages.pdf', pdf_fixture())
        wait(kb, pdf)
        pages = get(version_path(kb, pdf) + '/chunks')
        assert {chunk['page'] for chunk in pages} == {1, 2}
        assert any(chunk['page'] == 2 and 'Pine42' in chunk['content'] for chunk in pages)
        blank = upload(kb, 'blank.pdf', pdf_fixture(blank=True))
        assert 'OCR' in wait(kb, blank, ('FAILED',))['version']['errorMessage']
        broken = upload(kb, 'broken.pdf', b'%PDF-broken', first['documentId'])
        wait(kb, broken, ('FAILED',))
        assert next(doc for doc in get(f'/{kb}/documents') if doc['id'] == first['documentId'])['activeVersionId'] == first['id']
        newest = upload(kb, 'source.txt', b'Updated self-written source Oak73.', first['documentId'])
        wait(kb, newest)
        assert next(doc for doc in get(f'/{kb}/documents') if doc['id'] == first['documentId'])['activeVersionId'] == newest['id']
        expected = int(props['ray.embedding.dimension']) if args.live else 3
        actual = sql(f'SELECT min(public.vector_dims(embedding)),max(public.vector_dims(embedding)) FROM {schema}.ra_chunk_vector')
        assert actual == f'{expected}|{expected}'
        vector = json.loads(sql(f'SELECT embedding::text FROM {schema}.ra_chunk_vector LIMIT 1'))
        assert len(vector) == expected and any(value != 0 for value in vector)
        print(f'PASS PDF page locations, empty PDF failure, validation, version publication and vector readback ({expected} dimensions)')
        if not args.live:
            interrupted = upload(kb, 'restart.txt', b'hold-embedding: restart test')
            wait(kb, interrupted, ('EMBEDDING',))
            process.kill()
            process.wait(timeout=10)
            hold = False
            start()
            wait(kb, interrupted, ('INTERRUPTED',))
            post(version_path(kb, interrupted) + '/retry', {})
            wait(kb, interrupted)
            print('PASS killed process -> INTERRUPTED -> explicit retry -> READY')
        print('R04 API validation mode: ' + ('REAL SiliconFlow embedding' if args.live else 'MOCK embedding'))
finally:
    if frontend and frontend.poll() is None:
        frontend.terminate()
        frontend.wait(timeout=10)
    if process and process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)
    mock.shutdown()
    mock.server_close()
    sql('DROP SCHEMA IF EXISTS ' + schema + ' CASCADE')
    assert storage.is_relative_to(data_root.resolve()) and storage.name.startswith('r04-check-')
    shutil.rmtree(storage)
    log.close()
