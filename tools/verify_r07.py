"""R07：真实PG隔离schema，摘要/等待/重启/恢复。--live使用本机真实模型，默认模拟模型。
只检查脚本自己的进程、schema和测试文件，不输出凭据。
"""
import argparse,http.client,json,os,re,shutil,socket,subprocess,tempfile,threading,time,urllib.request,urllib.error,uuid
from pathlib import Path
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
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
schema = 'r07_check_' + uuid.uuid4().hex
assert re.fullmatch(r'r07_check_[a-f0-9]{32}', schema)
data_root = ROOT / '.ray-data'
data_root.mkdir(exist_ok=True)
storage = Path(tempfile.mkdtemp(prefix='r07-check-', dir=data_root)).resolve()
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
    def log_message(self,*unused): pass
    def do_POST(self):
        request=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        messages=request['messages'];system=messages[0]['content'];last=messages[-1];text=last.get('content','')
        delta=None
        if system.startswith('整理会话历史摘要'):
            output='用户确认巡检编号 R7Cedar83，负责人林澈；其余为虚构填充背景。'
        elif system.startswith('你只做意图分类'):
            question=json.loads(text)['question']
            output=json.dumps({'target':'DIRECT' if 'Java接口' in question else 'CLARIFY'})
        elif '先搜索' in ''.join(m.get('content','') for m in messages if m['role']=='user') and not any(m['role']=='tool' for m in messages):
            delta={'tool_calls':[{'index':0,'id':'search-first','type':'function','function':{'name':'web_search','arguments':json.dumps({'query':'fictional travel test'})}}]};output=''
        elif last['role']=='tool' and 'userAnswer' in text:
            answer=json.loads(text)['userAnswer'];output='收到补充：'+answer+'，欢迎来到'+answer+'！'
        elif 'ask_user' in ''.join(m.get('content','') for m in messages if m['role']=='user'):
            delta={'tool_calls':[{'index':0,'id':'question-city','type':'function','function':{'name':'ask_user','arguments':json.dumps({'question':'你的目标城市是哪里？'})}}]};output=''
        elif '最早约定' in text:
            assert 'R7Cedar83' in ''.join(m.get('content','') for m in messages[:-1]),'summary fact missing in actual model input'
            output='最早约定的巡检编号是 R7Cedar83，负责人是林澈。'
        elif 'Java接口' in text: output='Java接口定义行为契约。'
        else: output='已记录。'
        try:
            self.send_response(200);self.send_header('Content-Type','text/event-stream');self.end_headers()
            for d,reason in [(delta or {'content':output},None),({},'tool_calls' if delta else 'stop')]:
                self.wfile.write(('data: '+json.dumps({'choices':[{'index':0,'delta':d,'finish_reason':reason}]})+'\n\n').encode())
            self.wfile.write(b'data: {"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":4}}\n\ndata: [DONE]\n\n')
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


env.update(RAY_CONTEXT_COMPACTION_TRIGGER='1800',RAY_CONTEXT_KEEP_RECENT_TURNS='2')

def start():
    global process
    process=subprocess.Popen([str(java),'-jar',str(ROOT/'ray-agent-server/target/ray-agent-server-0.1.0-SNAPSHOT.jar'),f'--server.port={port}','--logging.file.name=logs/r07-verify.log'],cwd=ROOT,env=env,stdout=log,stderr=subprocess.STDOUT)
    for _ in range(250):
        if process.poll() is not None: raise RuntimeError('startup failed; see ignored logs/r07-verify.log')
        try: get('/api/sessions'); return
        except Exception: time.sleep(.1)
    raise RuntimeError('startup timeout')

def stop():
    if process and process.poll() is None:
        process.terminate()
        try: process.wait(timeout=10)
        except subprocess.TimeoutExpired: process.kill();process.wait(timeout=5)

def events(run): return get('/api/runs/'+run+'/events')

def stream(session,message=None,run=None,question=None,answer=None):
    raw=send('/api/chat/stream',{'message':message,'sessionId':session,'clientRequestId':str(uuid.uuid4())}) if run is None else send('/api/runs/'+run+'/answers',{'questionId':question,'answer':answer})
    result={'run':run,'text':'','question':None}
    for block in raw.split('\n\n'):
        kind=next((line[6:].strip() for line in block.splitlines() if line.startswith('event:')),'')
        lines=[line[5:].strip() for line in block.splitlines() if line.startswith('data:')]
        if not lines: continue
        value=json.loads('\n'.join(lines))
        if kind=='meta': result['run']=value['runId']
        if kind=='text': result['text']+=value['text']
        if kind=='question': result['question']=value
    result['state']=get('/api/runs/'+result['run'])
    return result

def assert_status(row,status): assert row['state']['status']==status,(row['state']['status'],row['state']['errorMessage'])

def rejected(fn,status):
    try: fn();raise AssertionError('request should have failed')
    except urllib.error.HTTPError as error: assert error.code==status,error.code

try:
    sql('CREATE SCHEMA '+schema);start()
    session=send('/api/sessions',{'title':'R07 compaction','mode':'CHAT'})['id']
    for n in range(5):
        fact='请记住：巡检编号是R7Cedar83，负责人是林澈。' if n==0 else '这是后续的虚构背景，不改变先前约定。'
        row=stream(session,fact+'只回复已记录。'+('虚构背景补充用于测试较长历史，不包含其他编号或人名。'*28))
        assert_status(row,'SUCCEEDED')
    recalled=stream(session,'最早约定的巡检编号和负责人是什么？请只回答这两项。')
    assert_status(recalled,'SUCCEEDED');assert 'R7Cedar83' in recalled['text'] and '林澈' in recalled['text']
    context=get('/api/sessions/'+session+'/context');assert context['source']['valid'] and context['source']['covered']>0
    assert not any(m['role']=='SYSTEM' for m in context['projection'])
    history=get('/api/sessions/'+session+'/messages');assert len(history)==12,'raw messages removed by compaction'
    assert any(any(e['type']=='COMPACTION_APPLIED' for e in events(r['id'])) for r in get('/api/sessions/'+session+'/runs'))
    stop();start()
    assert get('/api/sessions/'+session+'/context')['projection']==context['projection'],'projection changed across restart'
    print('PASS compaction: actual trigger, critical facts retained, original history intact, identical projection after restart',flush=True)
    waiting_session=send('/api/sessions',{'title':'R07 waiting','mode':'CHAT'})['id']
    prompt='请单独调用ask_user问我目标城市，等我回答后再写一句欢迎语；当前城市尚未提供。'
    if not args.live: prompt='先搜索一次fictional travel test，然后单独用ask_user问我目标城市，等我回答后再欢迎。'
    paused=stream(waiting_session,prompt);assert_status(paused,'WAITING_USER');q=paused['question'];assert q and q['status']=='WAITING_USER'
    run=paused['run'];before=events(run);old_tool_count=sum(e['type']=='TOOL_STARTED' for e in before)
    rejected(lambda:stream(waiting_session,'并发新问题'),409)
    rejected(lambda:send('/api/runs/'+run+'/answers',{'questionId':str(uuid.uuid4()),'answer':'杭州'}),404)
    stop();start()
    assert get('/api/runs/'+run)['status']=='WAITING_USER'
    assert get('/api/runs/'+run+'/question')['question']['id']==q['id']
    resumed=stream(waiting_session,run=run,question=q['id'],answer='杭州');assert_status(resumed,'SUCCEEDED')
    assert resumed['run']==run and '杭州' in resumed['text']
    assert sum(e['type']=='TOOL_STARTED' for e in events(run))==old_tool_count,'old tools replayed after resume'
    assert len(get('/api/sessions/'+waiting_session+'/runs'))==1
    assert len(get('/api/sessions/'+waiting_session+'/messages'))==2
    rejected(lambda:send('/api/runs/'+run+'/answers',{'questionId':q['id'],'answer':'重复'}),409)
    print('PASS waiting: same run, question identity, restart, one-shot answer, no tool replay, no duplicate assistant',flush=True)
    route_session=send('/api/sessions',{'title':'R07 AUTO waiting','mode':'AUTO'})['id']
    route=stream(route_session,'那个方案到底如何？请先问清我想做什么，不要猜。');assert_status(route,'WAITING_USER')
    clarified=stream(route_session,run=route['run'],question=route['question']['id'],answer='直接解释Java接口，只用常识。')
    assert_status(clarified,'SUCCEEDED');assert '接口' in clarified['text']
    print('PASS AUTO route clarification resumes original run',flush=True)
    if args.browser:
        frontend_port=free_port();front_env=env.copy();front_env['RAY_DEV_API_TARGET']=f'http://127.0.0.1:{port}'
        frontend=subprocess.Popen(['node',str(ROOT/'ray-agent-web/node_modules/vite/bin/vite.js'),'--host','127.0.0.1','--port',str(frontend_port),'--strictPort'],cwd=ROOT/'ray-agent-web',env=front_env,stdout=log,stderr=subprocess.STDOUT)
        browser_env=os.environ.copy();browser_env.setdefault('NODE_PATH',str(Path.home()/'.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules'))
        subprocess.run(['node',str(ROOT/'tools/verify_r07_browser.cjs'),f'http://127.0.0.1:{frontend_port}'],env=browser_env,cwd=ROOT,check=True)
    (ROOT/'logs').mkdir(exist_ok=True)
    (ROOT/'logs'/('r07-live.json' if args.live else 'r07-mock.json')).write_text(json.dumps({'date':time.strftime('%Y-%m-%d'),'realModel':args.live,'realPostgres':True,'summaryCoveredTurns':context['source']['covered'],'rawMessages':len(history),'sameRunResumed':True,'toolsReplayed':False,'autoClarification':True,'browser':args.browser},ensure_ascii=False,indent=2),encoding='utf-8')
finally:
    if frontend and frontend.poll() is None: frontend.terminate();frontend.wait(timeout=10)
    stop();mock.shutdown();mock.server_close()
    sql('DROP SCHEMA IF EXISTS '+schema+' CASCADE')
    assert storage.is_relative_to(data_root.resolve()) and storage.name.startswith('r07-check-')
    shutil.rmtree(storage);log.close()
