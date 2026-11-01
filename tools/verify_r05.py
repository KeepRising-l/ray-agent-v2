"""R05 隔离API验收及固定20问基线。默认模拟模型/Embedding，--live 使用本机真实配置。

仅测试自写资料；结果保存在受忽略的 logs，模拟结果不算真实模型质量指标。
"""
import argparse
import http.client
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
import urllib.request
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--live', action='store_true')
parser.add_argument('--browser', action='store_true')
parser.add_argument('--limit', type=int, default=20, help='实际运行的题数；报告保留运行数量，不把未跑问题记为通过')
args = parser.parse_args()
slow_started = threading.Event()
fixture = json.loads((ROOT / 'tests/fixtures/rag-cases.json').read_text(encoding='utf-8'))
props = {}
for line in (ROOT / 'application-local.properties').read_text(encoding='utf-8').splitlines():
    if '=' in line and not line.lstrip().startswith('#'):
        key, value = line.split('=', 1)
        props[key.strip()] = value.strip()
schema = 'r05_check_' + uuid.uuid4().hex
assert re.fullmatch(r'r05_check_[a-f0-9]{32}', schema)
data_root = ROOT / '.ray-data'
data_root.mkdir(exist_ok=True)
storage = Path(tempfile.mkdtemp(prefix='r05-check-', dir=data_root)).resolve()
assert storage.is_relative_to(data_root.resolve())


def sql(command):
    result = subprocess.run(['wsl', '--exec', 'docker', 'exec', 'pgvector-db', 'psql', '-U', 'postgres',
                             '-d', 'ray_agent_v2', '-At', '-c', command], check=True, capture_output=True)
    return result.stdout.decode('utf-8').strip()


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def normalize(value):
    return re.sub(r'\s+', '', value).replace('：', ':').replace('－', '-').replace('‑', '-')


class Mock(BaseHTTPRequestHandler):
    def log_message(self, *unused): pass

    def do_POST(self):
        request = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        if self.path.endswith('/embeddings'):
            data = [{'index': i, 'embedding': [1., .5, .25]} for i in range(len(request['input']))]
            body = json.dumps({'model': request['model'], 'data': data}).encode()
            self.send_response(200)
            self.end_headers()
            self.wfile.write(body)
            return
        messages = request['messages']
        judge = messages[0]['content'].startswith('只判断')
        question = next(message['content'] for message in reversed(messages) if message['role'] == 'user')
        question, raw = question.split('\n', 1)
        question = question.removeprefix('问题：')
        sources = json.loads(raw)['untrustedEvidence']
        if judge:
            if question == 'test-cancel':
                slow_started.set()
                time.sleep(3)
        case = next((case for case in fixture['cases'] if case['question'] == question), None)
        chosen = None
        quote = None
        terms = case['answerTerms'] if case and not case['mustRefuse'] else []
        if case and not case['mustRefuse']:
            chosen = next((source for source in sources if all(normalize(term) in normalize(source['text']) for term in case['evidenceTerms'])), None)
            quote = case['evidenceTerms'][0]
        elif not case and '代号' in question:
            for source in sources:
                match = re.search(r'Pine42|Oak73', source['text'])
                if match:
                    chosen, quote, terms = source, match.group(), [match.group()]
                    break
        if judge:
            answer = json.dumps({'answerable': chosen is not None, 'sources': [{'id': chosen['source']['id'], 'quote': quote}] if chosen else []})
        else:
            chosen = chosen or sources[0]
            answer = '资料答案：' + '，'.join(terms) + ' [' + chosen['source']['id'] + ']'
        try:
            self.send_response(200)
            self.send_header('Content-Type', 'text/event-stream')
            self.end_headers()
            for delta, reason in [({'content': answer}, None), ({}, 'stop')]:
                data = {'choices': [{'index': 0, 'delta': delta, 'finish_reason': reason}]}
                self.wfile.write(('data: ' + json.dumps(data) + '\n\n').encode())
            self.wfile.write(b'data: [DONE]\n\n')
        except OSError: pass


port = free_port()
mock = ThreadingHTTPServer(('127.0.0.1', 0), Mock)
threading.Thread(target=mock.serve_forever, daemon=True).start()
env = os.environ.copy()
env.update(SPRING_DATASOURCE_URL=props['spring.datasource.url'] + '?currentSchema=' + schema,
           RAY_KNOWLEDGE_STORAGE_ROOT=str(storage / 'documents'), RAY_MODEL_EXTRA_CHANNELS='')
if args.live:
    env.update(RAY_MODEL_BASE_URL=props['ray.model.base-url'], RAY_MODEL_MODEL=props['ray.model.model'], RAY_MODEL_API_KEY=props['ray.model.api-key'],
               RAY_EMBEDDING_BASE_URL=props['ray.embedding.base-url'], RAY_EMBEDDING_MODEL=props['ray.embedding.model'],
               RAY_EMBEDDING_DIMENSION=props['ray.embedding.dimension'], RAY_EMBEDDING_API_KEY=props['ray.embedding.api-key'])
else:
    base = f'http://127.0.0.1:{mock.server_port}/v1'
    env.update(RAY_MODEL_BASE_URL=base, RAY_MODEL_MODEL='mock-chat', RAY_MODEL_API_KEY='test-only',
               RAY_EMBEDDING_BASE_URL=base, RAY_EMBEDDING_MODEL='mock-embedding', RAY_EMBEDDING_DIMENSION='3', RAY_EMBEDDING_API_KEY='test-only')
settings = subprocess.run(['java', '-XshowSettings:properties', '-version'], capture_output=True, text=True)
java = Path(re.search(r'java\.home\s*=\s*(.+)', settings.stderr).group(1).strip()) / 'bin/java.exe'
process = None
frontend = None
log = tempfile.TemporaryFile()
from verification_auth import install
verification_headers=install(f'http://127.0.0.1:{port}',env,storage)


def get(path):
    return json.load(urllib.request.urlopen(f'http://127.0.0.1:{port}' + path, timeout=5))


def send(path, body=None, method='POST'):
    request = urllib.request.Request(f'http://127.0.0.1:{port}' + path, method=method,
                                     data=None if body is None else json.dumps(body).encode(), headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(request, timeout=100) as response:
        data = response.read().decode()
        return data if response.headers.get('Content-Type', '').startswith('text/event-stream') else json.loads(data)


def upload(kb, name, content, doc=None):
    boundary = uuid.uuid4().hex
    data = f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\nContent-Type: text/plain\r\n\r\n{content}\r\n--{boundary}--\r\n'.encode()
    path = f'/api/knowledge-bases/{kb}/documents' + (f'/{doc}/versions' if doc else '')
    request = urllib.request.Request(f'http://127.0.0.1:{port}' + path, data=data, headers={'Content-Type': 'multipart/form-data; boundary=' + boundary})
    return json.load(urllib.request.urlopen(request, timeout=10))


def wait_ready(kb, version):
    path = f'/api/knowledge-bases/{kb}/documents/{version["documentId"]}/versions/{version["id"]}'
    for _ in range(300):
        value = get(path)['version']
        if value['status'] == 'READY': return
        if value['status'] in ('FAILED', 'INTERRUPTED'): raise AssertionError(value['errorMessage'])
        time.sleep(.2)
    raise AssertionError('ingestion timeout')


def ask(kb, question):
    session = send('/api/sessions', {'title': 'R05 evaluation', 'mode': 'KNOWLEDGE', 'knowledgeBaseIds': [kb]})['id']
    text = send('/api/chat/stream', {'message': question, 'sessionId': session, 'clientRequestId': str(uuid.uuid4())})
    answer, run = '', None
    for block in text.split('\n\n'):
        event = next((line[6:].strip() for line in block.splitlines() if line.startswith('event:')), '')
        raw = [line[5:].strip() for line in block.splitlines() if line.startswith('data:')]
        if not raw: continue
        data = json.loads('\n'.join(raw))
        if event == 'meta': run = data['runId']
        if event == 'text': answer += data['text']
    assert run
    state = get('/api/runs/' + run)
    events = get('/api/runs/' + run + '/events')
    assert not any(event['type'].startswith('TOOL_') for event in events), 'KNOWLEDGE executed a tool'
    evidence = next((json.loads(event['detail'])['citations'] for event in events if event['type'] == 'RAG_EVIDENCE'), [])
    used = next((json.loads(event['detail'])['sourceIds'] for event in events if event['type'] == 'RAG_VALIDATED'), [])
    refused = any(event['type'] == 'RAG_REFUSED' for event in events)
    return {'run': run, 'answer': answer, 'status': state['status'], 'error': state['errorMessage'], 'evidence': evidence, 'used': used, 'refused': refused}


try:
    sql('CREATE SCHEMA ' + schema)
    process = subprocess.Popen([str(java), '-jar', str(ROOT / 'ray-agent-server/target/ray-agent-server-0.1.0-SNAPSHOT.jar'),
                                f'--server.port={port}', '--logging.file.name=logs/r05-verify.log'], cwd=ROOT, env=env,
                               stdout=log, stderr=subprocess.STDOUT)
    for _ in range(200):
        if process.poll() is not None: raise RuntimeError('backend startup failed; inspect ignored logs/r05-verify.log')
        try:
            get('/api/sessions')
            break
        except Exception: time.sleep(.1)
    else: raise RuntimeError('backend startup timeout')
    bases = {scope: send('/api/knowledge-bases', {'name': 'R05 ' + scope})['id'] for scope in ('orchard', 'database', 'empty')}
    versions = {}
    for document in fixture['corpus']:
        version = upload(bases[document['scope']], document['name'], document['content'])
        wait_ready(bases[document['scope']], version)
        versions[document['name']] = version
    if args.browser:
        frontend_port = free_port()
        front_env = env.copy()
        front_env['RAY_DEV_API_TARGET'] = f'http://127.0.0.1:{port}'
        frontend = subprocess.Popen(['node', str(ROOT / 'ray-agent-web/node_modules/vite/bin/vite.js'), '--host', '127.0.0.1', '--port', str(frontend_port), '--strictPort'],
                                    cwd=ROOT / 'ray-agent-web', env=front_env, stdout=log, stderr=subprocess.STDOUT)
        browser_env = os.environ.copy()
        browser_env.setdefault('NODE_PATH', str(Path.home() / '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules'))
        subprocess.run(['node', str(ROOT / 'tools/verify_r05_browser.cjs'), f'http://127.0.0.1:{frontend_port}'], env=browser_env, cwd=ROOT, check=True)
    else:
        observations = []
        for case in fixture['cases'][:args.limit]:
            started = time.monotonic()
            result = ask(bases[case['scope']], case['question'])
            evidence_text = '\n'.join(get(f'/api/runs/{result["run"]}/citations/{source["id"]}')['text'] for source in result['evidence'])
            citations_valid = bool(result['used']) and all(get(f'/api/runs/{result["run"]}/citations/{source}')['valid'] for source in result['used'])
            retrieval_hit = not case['mustRefuse'] and all(normalize(term) in normalize(evidence_text) for term in case['evidenceTerms'])
            answer_match = not case['mustRefuse'] and all(normalize(term) in normalize(result['answer']) for term in case['answerTerms'])
            passed = result['status'] == 'SUCCEEDED' and (result['refused'] if case['mustRefuse'] else answer_match and citations_valid)
            observations.append({'id': case['id'], 'scope': case['scope'], 'expectedRefusal': case['mustRefuse'], 'retrievalHit': retrieval_hit,
                                 'refused': result['refused'], 'answerTermsMatched': answer_match, 'citationsValid': citations_valid,
                                 'passed': passed, 'status': result['status'], 'error': result['error'], 'answer': result['answer'],
                                 'milliseconds': int((time.monotonic() - started) * 1000)})
            print(case['id'], 'PASS' if passed else 'FAIL', result['status'], flush=True)
        report = {'mode': 'configured local gateway + REAL SiliconFlow' if args.live else 'MOCK model and embedding',
                  'executed': len(observations), 'positiveCases': sum(not row['expectedRefusal'] for row in observations),
                  'negativeCases': sum(row['expectedRefusal'] for row in observations),
                  'retrievalHits': sum(row['retrievalHit'] for row in observations), 'answerTermsMatched': sum(row['answerTermsMatched'] for row in observations),
                  'correctRefusals': sum(row['refused'] and row['expectedRefusal'] for row in observations),
                  'validCitedAnswers': sum(row['citationsValid'] for row in observations), 'passed': sum(row['passed'] for row in observations), 'cases': observations}
        (ROOT / 'logs').mkdir(exist_ok=True)
        output = ROOT / 'logs' / ('r05-evaluation-live.json' if args.live else 'r05-evaluation-mock.json')
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
        print(json.dumps({key: value for key, value in report.items() if key != 'cases'}), flush=True)
        first = ask(bases['orchard'], '当前果园代号是什么？')
        assert first['status'] == 'SUCCEEDED' and 'Pine42' in first['answer'], first['error']
        original = versions['orchard.md']
        path = f'/api/knowledge-bases/{bases["orchard"]}/documents/{original["documentId"]}'
        reindexed = send(path + '/reindex', {})
        wait_ready(bases['orchard'], reindexed)
        assert all(not get(f'/api/runs/{first["run"]}/citations/{source}')['valid'] for source in first['used'])
        content = fixture['corpus'][0]['content'].replace('Pine42', 'Oak73')
        updated = upload(bases['orchard'], 'updated.md', content, original['documentId'])
        wait_ready(bases['orchard'], updated)
        newer = ask(bases['orchard'], '当前果园代号是什么？')
        assert newer['status'] == 'SUCCEEDED' and 'Oak73' in newer['answer'] and 'Pine42' not in newer['answer'], newer['error']
        send(path, method='DELETE')
        deleted = ask(bases['orchard'], '当前果园代号是什么？')
        assert deleted['refused'] and deleted['status'] == 'SUCCEEDED', deleted['error']
        print('PASS reindex -> old citation expires -> update switches evidence -> delete stops retrieval/refuses', flush=True)
        if not args.live:
            session = send('/api/sessions', {'title': 'R05 cancel', 'mode': 'KNOWLEDGE', 'knowledgeBaseIds': [bases['orchard']]})['id']
            connection = http.client.HTTPConnection('127.0.0.1', port, timeout=10)
            connection.request('POST', '/api/chat/stream', body=json.dumps({'message': 'test-cancel', 'sessionId': session}), headers={'Content-Type': 'application/json'})
            response = connection.getresponse()
            while True:
                line = response.readline().decode()
                if line.startswith('data:') and 'runId' in line:
                    run_id = json.loads(line[5:])['runId']
                    break
                if not line: raise AssertionError('missing run metadata')
            assert slow_started.wait(10)
            assert send('/api/runs/' + run_id + '/cancel', {})['status'] == 'CANCELLED'
            connection.close()
            print('PASS explicit cancellation while evidence judge waits for mock model', flush=True)
        if not all(row['passed'] for row in observations):
            raise AssertionError('Some evaluation cases failed; inspect ignored JSON report')
finally:
    if frontend and frontend.poll() is None:
        frontend.terminate(); frontend.wait(timeout=10)
    if process and process.poll() is None:
        process.terminate()
        try: process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill(); process.wait(timeout=5)
    mock.shutdown(); mock.server_close()
    sql('DROP SCHEMA IF EXISTS ' + schema + ' CASCADE')
    assert storage.is_relative_to(data_root.resolve()) and storage.name.startswith('r05-check-')
    shutil.rmtree(storage)
    log.close()
