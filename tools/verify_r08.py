"""R08 HTTP验收：隔离真实PG，受控模型/Embedding故障；--live转发到本机配置的真实接口。
仅清理本脚本创建的进程、schema和文件；不输出凭据，不修改用户会话。
"""
import argparse,json,os,re,shutil,socket,subprocess,tempfile,threading,time,urllib.request,urllib.error,uuid
from pathlib import Path
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer

ROOT=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser()
parser.add_argument('--live',action='store_true')
parser.add_argument('--browser',action='store_true')
parser.add_argument('--skip-embedding',action='store_true',help='仅在真实模式中跳过未配置密钥的Embedding，单独记录聊天验证范围')
args=parser.parse_args()
assert not args.skip_embedding or args.live,'--skip-embedding only applies to --live'
props={}
for line in (ROOT/'application-local.properties').read_text(encoding='utf-8').splitlines():
    if '=' in line and not line.lstrip().startswith('#'):
        key,value=line.split('=',1);props[key.strip()]=value.strip()
schema='r08_check_'+uuid.uuid4().hex
assert re.fullmatch(r'r08_check_[a-f0-9]{32}',schema)
data_root=(ROOT/'.ray-data').resolve();data_root.mkdir(exist_ok=True)
storage=Path(tempfile.mkdtemp(prefix='r08-check-',dir=data_root)).resolve()
assert storage.is_relative_to(data_root)
gates={};seen=[];embedding_calls={};embedding_crash=threading.Event()

def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1',0));return sock.getsockname()[1]

class Relay(BaseHTTPRequestHandler):
    def log_message(self,*unused): pass
    def do_POST(self):
        request=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        embedding=self.path.endswith('/embeddings')
        if embedding:
            text=''.join(request['input'])
            label='crash-doc' if 'crash-doc' in text else 'retry-doc'
            embedding_calls[label]=embedding_calls.get(label,0)+1
            if label=='crash-doc' and embedding_calls[label]==1:
                embedding_crash.set();gates['crash-doc'].wait(90)
            if label=='retry-doc' and embedding_calls[label]==1:
                self.send_response(503);self.end_headers();self.wfile.write(b'{"error":"controlled transient failure"}');return
        else:
            messages=request['messages']
            text=next((m.get('content','') for m in reversed(messages) if m['role']=='user'),'')
            label=text.split('\n',1)[0]
            seen.append(label)
            if label in gates: gates[label].wait(90)
        try:
            if args.live:
                prefix='ray.embedding' if embedding else 'ray.model'
                url=props[prefix+'.base-url'].rstrip('/')+('/embeddings' if embedding else '/chat/completions')
                forwarded=urllib.request.Request(url,data=json.dumps(request).encode(),headers={'Content-Type':'application/json','Authorization':'Bearer '+props[prefix+'.api-key']})
                with urllib.request.urlopen(forwarded,timeout=75) as response:
                    self.send_response(response.status);self.send_header('Content-Type',response.headers.get('Content-Type','application/json'));self.end_headers()
                    while True:
                        block=response.read(4096)
                        if not block: break
                        self.wfile.write(block);self.wfile.flush()
            elif embedding:
                self.send_response(200);self.send_header('Content-Type','application/json');self.end_headers()
                self.wfile.write(json.dumps({'model':request['model'],'data':[{'index':i,'embedding':[1,.1,.2]} for i in range(len(request['input']))]}).encode())
            else:
                last=messages[-1];delta={'content':'队列验收完成。'};reason='stop'
                if last['role']=='tool': delta={'content':'收到补充：'+json.loads(last['content'])['userAnswer']}
                elif 'ask_user' in text:
                    delta={'tool_calls':[{'index':0,'id':'r08-question','type':'function','function':{'name':'ask_user','arguments':json.dumps({'question':'目标城市是哪里？'})}}]};reason='tool_calls'
                self.send_response(200);self.send_header('Content-Type','text/event-stream');self.end_headers()
                for chunk,finish in [(delta,None),({},reason)]:
                    self.wfile.write(('data: '+json.dumps({'choices':[{'index':0,'delta':chunk,'finish_reason':finish}]})+'\n\n').encode())
                self.wfile.write(b'data: {"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":4}}\n\ndata: [DONE]\n\n')
        except (OSError,urllib.error.URLError): pass

port=free_port();relay=ThreadingHTTPServer(('127.0.0.1',0),Relay)
threading.Thread(target=relay.serve_forever,daemon=True).start()
env=os.environ.copy();base=f'http://127.0.0.1:{relay.server_port}/v1'
db_url=props['spring.datasource.url'];separator='&' if '?' in db_url else '?'
env.update(SPRING_DATASOURCE_URL=db_url+separator+'currentSchema='+schema,
           RAY_KNOWLEDGE_STORAGE_ROOT=str(storage/'documents'),RAY_MODEL_EXTRA_CHANNELS='',
           RAY_MODEL_BASE_URL=base,RAY_MODEL_API_KEY='relay-test-only',
           RAY_MODEL_MODEL=props['ray.model.model'] if args.live else 'mock-chat',
           RAY_EMBEDDING_BASE_URL=base,RAY_EMBEDDING_API_KEY='relay-test-only',
           RAY_EMBEDDING_MODEL=props['ray.embedding.model'] if args.live else 'mock-embedding',
           RAY_EMBEDDING_DIMENSION=props['ray.embedding.dimension'] if args.live else '3',
           RAY_RUN_MAX_CONCURRENT='1',RAY_RUN_QUEUE_CAPACITY='2',RAY_RUN_MAX_PER_OWNER='0',
           RAY_RUN_QUEUE_TIMEOUT_MS='20000',RAY_RUN_TIMEOUT_MS='60000',RAY_DOCUMENT_RETRY_BACKOFF_MS='100',
           RAY_TOOLS_WEB_TAVILY_API_KEY='')
settings=subprocess.run(['java','-XshowSettings:properties','-version'],capture_output=True,text=True)
java=Path(re.search(r'java\.home\s*=\s*(.+)',settings.stderr).group(1).strip())/'bin/java.exe'
process=None;frontend=None;keepalive=None;log=tempfile.TemporaryFile()

def sql(command):
    result=subprocess.run(['wsl','--exec','docker','exec','pgvector-db','psql','-U','postgres','-d','ray_agent_v2','-At','-c',command],check=True,capture_output=True)
    return result.stdout.decode('utf-8').strip()

def call(path,body=None,method=None):
    request=urllib.request.Request(f'http://127.0.0.1:{port}'+path,method=method or ('POST' if body is not None else 'GET'),data=None if body is None else json.dumps(body).encode(),headers={'Content-Type':'application/json'})
    with urllib.request.urlopen(request,timeout=100) as response:
        raw=response.read().decode()
        return raw if response.headers.get('Content-Type','').startswith('text/event-stream') else json.loads(raw)

def until(predicate,label,seconds=35):
    deadline=time.monotonic()+seconds
    while time.monotonic()<deadline:
        value=predicate()
        if value: return value
        time.sleep(.1)
    raise AssertionError('timeout: '+label)

def start():
    global process
    process=subprocess.Popen([str(java),'-jar',str(ROOT/'ray-agent-server/target/ray-agent-server-0.1.0-SNAPSHOT.jar'),f'--server.port={port}','--logging.file.name=logs/r08-verify.log'],cwd=ROOT,env=env,stdout=log,stderr=subprocess.STDOUT)
    def ready():
        if process.poll() is not None: raise RuntimeError('startup failed; see ignored logs/r08-verify.log')
        try: call('/api/sessions');return True
        except (urllib.error.URLError,TimeoutError): return False
    until(ready,'server startup')

def stop(crash=False):
    if process and process.poll() is None:
        process.kill() if crash else process.terminate()
        try: process.wait(timeout=10)
        except subprocess.TimeoutExpired: process.kill();process.wait(timeout=5)

def session(title): return call('/api/sessions',{'title':title,'mode':'CHAT'})['id']
def state(run): return call('/api/runs/'+run)['status']
def capacity_idle(): return call('/api/tasks/capacity')['admitted']==0
def rejected(path,body,status):
    try: call(path,body);raise AssertionError('expected rejection')
    except urllib.error.HTTPError as error:
        assert error.code==status,(error.code,status)
        return json.loads(error.read())

class Stream:
    """后台读取SSE，主线程同时检查持久状态、取消或模拟进程退出。"""
    def __init__(self,sid,label,hold=False):
        self.sid=sid;self.request=uuid.uuid4().hex
        self.message=label+'\n这是队列验收，只回复一句验收完成，不调用工具。'
        self.raw=None;self.error=None;self.done=threading.Event()
        if hold: gates[label]=threading.Event()
        def read():
            try: self.raw=call('/api/chat/stream',self.body())
            except Exception as error: self.error=error
            finally: self.done.set()
        threading.Thread(target=read,daemon=True).start()
        self.run=until(lambda: next((r['id'] for r in call('/api/sessions/'+sid+'/runs') if r['clientRequestId']==self.request),None),'run created')
    def body(self): return {'message':self.message,'sessionId':self.sid,'clientRequestId':self.request}
    def finish(self,status='SUCCEEDED'):
        assert self.done.wait(90),'stream stalled'
        if self.error: raise self.error
        assert state(self.run)==status,(state(self.run),self.raw)
        return self.raw

def upload(kb,label):
    boundary=uuid.uuid4().hex
    content=label+'。这是自主编写的隔离验收资料，记录青杉仓库的巡检编号R8Maple52。'
    data=f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{label}.txt"\r\nContent-Type: text/plain\r\n\r\n{content}\r\n--{boundary}--\r\n'.encode()
    request=urllib.request.Request(f'http://127.0.0.1:{port}/api/knowledge-bases/{kb}/documents',data=data,headers={'Content-Type':'multipart/form-data; boundary='+boundary})
    return json.load(urllib.request.urlopen(request,timeout=10))

def document_path(kb,version): return f'/api/knowledge-bases/{kb}/documents/{version["documentId"]}/versions/{version["id"]}'
def attempts(version): return call('/api/tasks/documents/'+version['id']+'/attempts')
def ready(kb,version):
    path=document_path(kb,version)
    def published():
        value=call(path)
        return value if value['version']['status']=='READY' else None
    result=until(published,'document ready',90)
    chunks=call(path+'/chunks')
    assert result['vectorCount']==len(chunks)>0
    assert len({c['id'] for c in chunks})==len(chunks)

try:
    # Windows转发需要WSL实例持续存活；只保留并终止本脚本的sleep进程。
    keepalive=subprocess.Popen(['wsl','--exec','sleep','1800'],stdout=log,stderr=log)
    until(lambda: subprocess.run(['wsl','--exec','docker','exec','pgvector-db','pg_isready','-U','postgres'],capture_output=True).returncode==0,'PostgreSQL ready')
    sql('CREATE SCHEMA '+schema);start()
    first=Stream(session('R08 held running'),'hold-first',True)
    until(lambda: 'hold-first' in seen,'first model called')
    second=Stream(session('R08 queued cancel'),'queued-cancel')
    third=Stream(session('R08 FIFO survivor'),'queued-survivor')
    assert state(first.run)=='RUNNING' and state(second.run)==state(third.run)=='QUEUED'
    full=call('/api/tasks/capacity');assert (full['running'],full['queued'],full['admitted'])==(1,2,3),full
    fourth=session('R08 overload')
    rejected('/api/chat/stream',{'message':'overload','sessionId':fourth,'clientRequestId':'overload'},429)
    assert not call('/api/sessions/'+fourth+'/runs'),'overload persisted a run'
    duplicate=rejected('/api/chat/stream',first.body(),409)
    assert duplicate['runId']==first.run and duplicate['sameInput']
    changed=first.body();changed['message']='different'
    assert not rejected('/api/chat/stream',changed,409)['sameInput']
    assert call('/api/sessions/'+first.sid+'/requests/'+first.request)['id']==first.run
    call('/api/runs/'+second.run+'/cancel',method='POST');second.finish('CANCELLED')
    assert 'queued-cancel' not in seen
    rejected('/api/chat/stream',{'message':'same session','sessionId':first.sid,'clientRequestId':'other'},409)
    gates['hold-first'].set();first.finish();third.finish()
    assert seen.index('hold-first')<seen.index('queued-survivor') and 'queued-cancel' not in seen
    until(capacity_idle,'all permits released')
    print('PASS queue: one running, FIFO, full429 without DB run, duplicate409+lookup, same-session409, queued cancel never calls model',flush=True)
    active=Stream(session('R08 active cancel'),'hold-cancel',True)
    until(lambda:'hold-cancel' in seen,'active model called')
    call('/api/runs/'+active.run+'/cancel',method='POST');active.finish('CANCELLED')
    until(capacity_idle,'active cancel releases permit');gates['hold-cancel'].set()
    after=Stream(session('R08 after cancel'),'after-cancel');after.finish()
    kb=None;retry=None
    if not args.skip_embedding:
        kb=call('/api/knowledge-bases',{'name':'R08 isolated documents'})['id']
        retry=upload(kb,'retry-doc');ready(kb,retry)
        history=attempts(retry)
        assert len(history)==2 and history[0]['error_code']=='HTTP_503' and history[1]['status']=='SUCCEEDED',history
        print('PASS ingestion: controlled503 then retry succeeds, two persisted attempts, unique chunks and complete vectors',flush=True)
    if args.browser:
        browser_active=Stream(session('R08 browser active'),'hold-browser',True)
        until(lambda:'hold-browser' in seen,'browser held run')
        browser_queued=Stream(session('R08 browser queued'),'browser-queued')
        frontport=free_port();frontenv=env.copy();frontenv['RAY_DEV_API_TARGET']=f'http://127.0.0.1:{port}'
        frontend=subprocess.Popen(['node',str(ROOT/'ray-agent-web/node_modules/vite/bin/vite.js'),'--host','127.0.0.1','--port',str(frontport),'--strictPort'],cwd=ROOT/'ray-agent-web',env=frontenv,stdout=log,stderr=subprocess.STDOUT)
        def front_ready():
            if frontend.poll() is not None: raise RuntimeError('frontend stopped before ready')
            try: urllib.request.urlopen(f'http://127.0.0.1:{frontport}',timeout=2).close();return True
            except urllib.error.URLError: return False
        until(front_ready,'frontend startup')
        browserenv=os.environ.copy();browserenv.setdefault('NODE_PATH',str(Path.home()/'.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules'))
        subprocess.run(['node',str(ROOT/'tools/verify_r08_browser.cjs'),f'http://127.0.0.1:{frontport}',browser_queued.run,retry['id'] if retry else ''],cwd=ROOT,env=browserenv,check=True)
        browser_queued.finish('CANCELLED');gates['hold-browser'].set();browser_active.finish()
        assert 'browser-queued' not in seen
        frontend.terminate();frontend.wait(timeout=10);frontend=None
    # 同时切断活跃聊天、排队聊天与embedding中的文档；内存队列不可重放。
    running=Stream(session('R08 restart running'),'hold-restart',True)
    until(lambda:'hold-restart' in seen,'restart held run')
    queued=Stream(session('R08 restart queued'),'never-replay')
    if not args.skip_embedding:
        gates['crash-doc']=threading.Event();crashed=upload(kb,'crash-doc')
        assert embedding_crash.wait(20),'embedding did not begin'
    stop(crash=True);gates['hold-restart'].set()
    if not args.skip_embedding: gates['crash-doc'].set()
    start()
    assert state(running.run)==state(queued.run)=='INTERRUPTED'
    assert 'never-replay' not in seen
    assert call('/api/runs/'+queued.run+'/recovery')['action']=='NONE'
    if not args.skip_embedding:
        assert call(document_path(kb,crashed))['version']['status']=='INTERRUPTED'
        assert attempts(crashed)[0]['status']=='INTERRUPTED'
        call(document_path(kb,crashed)+'/retry',method='POST');ready(kb,crashed)
        assert len(attempts(crashed))==2
    until(capacity_idle,'restart capacity clean')
    print('PASS restart: RUNNING+QUEUED interrupted without replay'+('' if args.skip_embedding else '; document interrupted then explicit retry publishes once'),flush=True)
    waiting_sid=session('R08 checkpoint')
    raw=call('/api/chat/stream',{'message':'请单独调用ask_user问我目标城市，等我回答后再写一句欢迎语；当前城市尚未提供。','sessionId':waiting_sid,'clientRequestId':uuid.uuid4().hex})
    paused=call('/api/sessions/'+waiting_sid+'/runs')[0]
    assert paused['status']=='WAITING_USER',raw
    question=call('/api/runs/'+paused['id']+'/question')['question']
    until(capacity_idle,'waiting user releases slot')
    stop();start()
    assert call('/api/runs/'+paused['id']+'/recovery')['action']=='ANSWER_PENDING'
    answer=call('/api/runs/'+paused['id']+'/answers',{'questionId':question['id'],'answer':'杭州'})
    assert state(paused['id'])=='SUCCEEDED' and '杭州' in answer,answer
    task=next(t for t in call('/api/tasks') if t['id']==paused['id'])
    assert task['attempt']==2 and not task['attached'] and task['recovery']=='NONE',task
    assert len(call('/api/sessions/'+waiting_sid+'/runs'))==1
    assert any(s['sessionId']==waiting_sid for s in call('/api/sessions/status'))
    until(capacity_idle,'resumed completion releases slot')
    print('PASS recovery: WAITING_USER releases slot, survives restart, resumes same run in scheduling phase2, queries persistent vs attached state',flush=True)
    if not args.live:
        stop();env['RAY_RUN_QUEUE_TIMEOUT_MS']='300';start()
        held=Stream(session('R08 queue deadline active'),'hold-deadline',True)
        until(lambda:'hold-deadline' in seen,'deadline held model')
        expired=Stream(session('R08 queue deadline waiting'),'never-start-timeout')
        expired.finish('TIMED_OUT')
        assert 'never-start-timeout' not in seen and state(held.run)=='RUNNING'
        assert any(e['type']=='TIMED_OUT' and '排队超过时限' in e['detail'] for e in call('/api/runs/'+expired.run+'/events'))
        assert call('/api/tasks/capacity')['admitted']==1
        gates['hold-deadline'].set();held.finish();until(capacity_idle,'queue deadline releases permit')
        print('PASS deadline: queued timeout never calls model or consumes active run time; slot released',flush=True)
    (ROOT/'logs').mkdir(exist_ok=True)
    report={'date':'2026-10-31','realPostgres':True,'realModel':args.live,'realEmbedding':args.live and not args.skip_embedding,
            'controlledDelay':True,'controlledFirstEmbedding503':not args.skip_embedding,'queueCancelOverloadIdempotency':True,
            'restartDoesNotReplay':True,'documentRetryAndAttempts':not args.skip_embedding,'sameRunResume':True,'browser':args.browser,
            'queueDeadlineMock':not args.live}
    (ROOT/'logs'/('r08-live.json' if args.live else 'r08-mock.json')).write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
finally:
    for gate in gates.values(): gate.set()
    if frontend and frontend.poll() is None: frontend.terminate();frontend.wait(timeout=10)
    stop();relay.shutdown();relay.server_close()
    sql('DROP SCHEMA IF EXISTS '+schema+' CASCADE')
    assert storage.is_relative_to(data_root) and storage.name.startswith('r08-check-')
    shutil.rmtree(storage);log.close()
    if keepalive and keepalive.poll() is None: keepalive.terminate();keepalive.wait(timeout=10)
