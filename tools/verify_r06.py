"""R06 三模式隔离API验收。--live 使用真实模型/Embedding/网页，默认模拟模型并禁用 Tavily。

真实PostgreSQL使用一次性schema；仅上传自写小样本，不打印本机密钥。
"""
import argparse, http.client, json, os, re, shutil, socket, subprocess, tempfile, threading, time, urllib.request, urllib.error, uuid
from pathlib import Path
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
schema = 'r06_check_' + uuid.uuid4().hex
assert re.fullmatch(r'r06_check_[a-f0-9]{32}', schema)
data_root = ROOT / '.ray-data'
data_root.mkdir(exist_ok=True)
storage = Path(tempfile.mkdtemp(prefix='r06-check-', dir=data_root)).resolve()
assert storage.is_relative_to(data_root.resolve())


def sql(command):
    result = subprocess.run(['wsl', '--exec', 'docker', 'exec', 'pgvector-db', 'psql', '-U', 'postgres',
                             '-d', 'ray_agent_v2', '-At', '-c', command], check=True, capture_output=True)
    return result.stdout.decode('utf-8').strip()


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]



class Mock(BaseHTTPRequestHandler):
    def log_message(self, *unused): pass
    def do_POST(self):
        request=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        if self.path.endswith('/embeddings'):
            self.send_response(200); self.end_headers()
            self.wfile.write(json.dumps({'model':request['model'],'data':[{'index':i,'embedding':[1.,.5,.25]} for i in range(len(request['input']))]}).encode())
            return
        messages=request['messages']; system=messages[0]['content']
        user=next(message['content'] for message in reversed(messages) if message['role']=='user')
        delta=None
        if system.startswith('你只做意图分类'):
            question=json.loads(user)['question']
            output='not json' if '坏分类' in question else json.dumps({'target':'CLARIFY' if '那个' in question else 'DIRECT'})
        elif system.startswith('只判断'):
            evidence=json.loads(user.split('\n',1)[1])['untrustedEvidence']
            output=json.dumps({'answerable':bool(evidence), 'sources':[{'id':evidence[0]['source']['id'],'quote':'R6Mango91'}] if evidence else []})
        elif system.startswith('当前为 KNOWLEDGE'):
            evidence=json.loads(user.split('\n',1)[1])['untrustedEvidence']
            output='巡检代号是 R6Mango91 ['+evidence[0]['source']['id']+']。'
        elif system.startswith('当前为 AUTO 的受控混合'):
            data=json.loads(user); evidence=data['knowledge']['untrustedEvidence']
            output=json.dumps({'knowledge':'资料推荐Java17 ['+evidence[0]['source']['id']+']' if evidence else '', 'web':'','comparison':''})
        elif request.get('tools') and '搜索' in user and messages[-1]['role']!='tool':
            delta={'tool_calls':[{'index':0,'id':'r06-search','type':'function','function':{'name':'web_search','arguments':json.dumps({'query':'Java LTS'})}}]}
            output=''
        elif messages[-1]['role']=='tool': output='网页搜索未取得可用依据，无法核实最新情况。'
        elif '代号' in user: output='我无法访问你的知识库资料，不能提供该代号。'
        else: output='你好，这是一条普通对话回答。'
        try:
            self.send_response(200); self.send_header('Content-Type','text/event-stream'); self.end_headers()
            for part,reason in [(delta or {'content':output},None), ({},'tool_calls' if delta else 'stop')]:
                self.wfile.write(('data: '+json.dumps({'choices':[{'index':0,'delta':part,'finish_reason':reason}]})+'\n\n').encode())
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



if not args.live:
    env['RAY_TOOLS_WEB_TAVILY_API_KEY']=''

def ask(mode, ids, question):
    session=send('/api/sessions', {'title':'R06 '+mode,'mode':mode,'knowledgeBaseIds':ids})
    raw=send('/api/chat/stream',{'message':question,'sessionId':session['id'],'clientRequestId':str(uuid.uuid4())})
    run=None; answer=''
    for block in raw.split('\n\n'):
        event=next((line[6:].strip() for line in block.splitlines() if line.startswith('event:')), '')
        data=[line[5:].strip() for line in block.splitlines() if line.startswith('data:')]
        if not data: continue
        value=json.loads('\n'.join(data))
        if event=='meta': run=value['runId']
        if event=='text': answer+=value['text']
    assert run, 'no run metadata'
    state=get('/api/runs/'+run); events=get('/api/runs/'+run+'/events')
    assert state['status'] in ('SUCCEEDED','WAITING_USER'), 'run failed: '+str(state['errorMessage'])
    route=next((json.loads(event['detail']) for event in events if event['type']=='ROUTE_DECIDED'),None)
    return {'run':run,'session':session['id'],'answer':answer,'route':route,'events':events,'status':state['status']}

def check(row, target=None, tools=False, rag=False):
    assert row['status']=='SUCCEEDED' or target=='CLARIFY', 'only a clarification may wait for user'
    if target: assert row['route'] and row['route']['target']==target, row['route']
    else: assert row['route'] is None, 'explicit mode entered AUTO'
    assert any(event['type']=='TOOL_STARTED' for event in row['events'])==tools, 'wrong tool boundary'
    assert any(event['type']=='RAG_RETRIEVAL_STARTED' for event in row['events'])==rag, 'wrong retrieval boundary'

try:
    sql('CREATE SCHEMA '+schema)
    process=subprocess.Popen([str(java),'-jar',str(ROOT/'ray-agent-server/target/ray-agent-server-0.1.0-SNAPSHOT.jar'),f'--server.port={port}','--logging.file.name=logs/r06-verify.log'],cwd=ROOT,env=env,stdout=log,stderr=subprocess.STDOUT)
    for _ in range(250):
        if process.poll() is not None: raise RuntimeError('backend startup failed; see ignored logs/r06-verify.log')
        try: get('/api/sessions'); break
        except Exception: time.sleep(.1)
    else: raise RuntimeError('startup timeout')
    kb=send('/api/knowledge-bases',{'name':'R06 demo'})['id']
    empty=send('/api/knowledge-bases',{'name':'R06 empty'})['id']
    version=upload(kb,'r06-notes.md','# 自写巡检资料\n内部巡检计划代号是 R6Mango91。项目建议使用 Java17。它是本地历史方案，不代表目前最新发布。')
    wait_ready(kb,version)
    observations=[]
    fixture=json.loads((ROOT/'tests/fixtures/mode-boundary-cases.json').read_text(encoding='utf-8'))
    scopes={'demo':[kb],'empty':[empty],'none':[]}
    cases=[(item['mode'],scopes[item['scope']],item['question'],item['target'],item['expectTools'],item['expectRetrieval']) for item in fixture['cases']]
    for index,(mode,ids,question,target,tool_expected,rag_expected) in enumerate(cases,1):
        row=ask(mode,ids,question); check(row,target,tool_expected,rag_expected)
        if index==1: assert 'R6Mango91' not in row['answer'], 'CHAT leaked KB content'
        if index in (2,5):
            assert 'R6Mango91' in row['answer'] and re.search(r'\[K\d+\]',row['answer'])
            source=re.search(r'\[(K\d+)\]',row['answer']).group(1)
            assert get(f'/api/runs/{row["run"]}/citations/{source}')['valid']
        if index==3: assert '没有足够依据' in row['answer']
        if index==8:
            assert all(title in row['answer'] for title in ('知识库资料','网页资料','比较与局限'))
            if args.live: assert re.search(r'\[K\d+\]',row['answer']) and re.search(r'\[W\d+\]',row['answer']), 'live hybrid missing both source types'
            else: assert 'NOT_CONFIGURED' in row['answer'] and '暂不能完成' in row['answer']
        if index==10: assert row['status']=='WAITING_USER' and get('/api/runs/'+row['run']+'/question')['question']
        if index==9: assert '没有知识库事实依据' in row['answer'] or '两类证据均不可用' in row['answer']
        observations.append({'case':index,'mode':mode,'target':target,'run':row['run'],'passed':True})
        print(f'PASS R06 case {index}: {mode} -> {target or mode}',flush=True)
    for bad in ({'title':'bad','mode':'ILLEGAL'}, {'title':'bad','mode':'CHAT','knowledgeBaseIds':[kb]},
                {'title':'bad','mode':'KNOWLEDGE','knowledgeBaseIds':[]}, {'title':'bad','mode':'AUTO','knowledgeBaseIds':[str(uuid.uuid4())]}):
        try: send('/api/sessions',bad); raise AssertionError('invalid mode/scope accepted')
        except urllib.error.HTTPError as error: assert error.code in (400,404)
    print('PASS invalid modes/scopes rejected',flush=True)
    if args.browser:
        frontend_port=free_port(); front_env=env.copy(); front_env['RAY_DEV_API_TARGET']=f'http://127.0.0.1:{port}'
        frontend=subprocess.Popen(['node',str(ROOT/'ray-agent-web/node_modules/vite/bin/vite.js'),'--host','127.0.0.1','--port',str(frontend_port),'--strictPort'],cwd=ROOT/'ray-agent-web',env=front_env,stdout=log,stderr=subprocess.STDOUT)
        browser_env=os.environ.copy(); browser_env.setdefault('NODE_PATH',str(Path.home()/'.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules'))
        subprocess.run(['node',str(ROOT/'tools/verify_r06_browser.cjs'),f'http://127.0.0.1:{frontend_port}','live' if args.live else 'mock'],env=browser_env,cwd=ROOT,check=True)
    (ROOT/'logs').mkdir(exist_ok=True)
    report={'date':time.strftime('%Y-%m-%d'),'liveModelEmbeddingWeb':args.live,'realPostgres':True,'browser':args.browser,'executed':len(observations),'passed':len(observations),'cases':observations}
    (ROOT/'logs'/('r06-live.json' if args.live else 'r06-mock.json')).write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
finally:
    if frontend and frontend.poll() is None: frontend.terminate(); frontend.wait(timeout=10)
    if process and process.poll() is None:
        process.terminate()
        try: process.wait(timeout=10)
        except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=5)
    mock.shutdown(); mock.server_close()
    sql('DROP SCHEMA IF EXISTS '+schema+' CASCADE')
    assert storage.is_relative_to(data_root.resolve()) and storage.name.startswith('r06-check-')
    shutil.rmtree(storage)
    log.close()
