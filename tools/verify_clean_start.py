"""在明确新建的Compose项目/数据卷里验证，不读取用户本机模型配置，不清理其他容器或卷。"""
import argparse,http.cookiejar,json,os,re,secrets,shutil,socket,subprocess,tempfile,time,urllib.request,urllib.parse,urllib.error,uuid
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser();parser.add_argument('--image',default='ray-agent-v2:r11-check');args=parser.parse_args()
project='r11-clean-'+uuid.uuid4().hex
assert re.fullmatch(r'r11-clean-[a-f0-9]{32}',project)
data_root=(ROOT/'.ray-data').resolve();data_root.mkdir(exist_ok=True)
storage=Path(tempfile.mkdtemp(prefix='r11-clean-',dir=data_root)).resolve()
assert storage.is_relative_to(data_root)
folder=ROOT/'logs/r11';folder.mkdir(exist_ok=True)
log=(folder/'clean-compose.log').open('w',encoding='utf-8')
def linux(path):
    value=Path(path).resolve().as_posix();return '/mnt/'+value[0].lower()+value[2:]
def docker(*command,sensitive=False):
    result=subprocess.run(['wsl','--exec','docker',*command],capture_output=True)
    if not sensitive:
        log.write(result.stdout.decode('utf-8',errors='replace'));log.write(result.stderr.decode('utf-8',errors='replace'));log.flush()
    if result.returncode:raise RuntimeError('Isolated Docker operation failed; inspect ignored logs/r11/clean-compose.log')
    return result.stdout.decode('utf-8').strip()
with socket.socket() as sock:sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
(storage/'compose.env').write_text('RAY_DB_PASSWORD='+secrets.token_urlsafe(24)+'\nRAY_HTTP_PORT='+str(port)+'\n',encoding='utf-8')
(storage/'override.json').write_text(json.dumps({'services':{'app':{'image':args.image,'pull_policy':'never','environment':{'SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT':'3000','SPRING_DATASOURCE_HIKARI_VALIDATION_TIMEOUT':'1000'}}}}),encoding='utf-8')
prefix=['compose','--project-name',project,'--env-file',linux(storage/'compose.env'),'-f',linux(ROOT/'deploy/compose.yml'),'-f',linux(storage/'override.json')]
def compose(*command,sensitive=False):return docker(*prefix,*command,sensitive=sensitive)
def until(predicate,label,seconds=120):
    deadline=time.monotonic()+seconds
    while time.monotonic()<deadline:
        try:
            if predicate():return
        except (urllib.error.URLError,TimeoutError,ConnectionError):pass
        time.sleep(.3)
    raise AssertionError('timeout: '+label)
base=f'http://127.0.0.1:{port}'
opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
session={}
def call(path,body=None):
    headers={'Content-Type':'application/json'}
    if body is not None:headers[session['csrfHeader']]=session['csrfToken']
    request=urllib.request.Request(base+path,data=None if body is None else json.dumps(body).encode(),headers=headers)
    with opener.open(request,timeout=12) as response:return json.load(response)
def ready():return call('/api/health')['ready']
def login():
    global session
    session=call('/api/auth/session')
    request=urllib.request.Request(base+'/api/auth/login',data=urllib.parse.urlencode(account).encode(),headers={'Content-Type':'application/x-www-form-urlencoded',session['csrfHeader']:session['csrfToken']})
    opener.open(request,timeout=10).close();session=call('/api/auth/session');assert session['authenticated']
keepalive=subprocess.Popen(['wsl','--exec','sleep','1800'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
try:
    assert not docker('volume','ls','-q','--filter','label=com.docker.compose.project='+project),'test project was not empty'
    compose('config','--quiet')
    compose('up','-d','--no-build','--wait','--wait-timeout','180')
    until(ready,'fresh app ready')
    app_id=compose('ps','-q','app');assert app_id
    assert docker('inspect','--format','{{index .Config.Labels "com.docker.compose.project"}}',app_id)==project
    initial=compose('exec','-T','app','cat','/app/.ray-data/identity/initial-login.txt',sensitive=True)
    account=dict(line.split('=',1) for line in initial.splitlines() if '=' in line)
    assert account['username']=='owner' and len(account['password'])>=32
    login();assert not call('/api/admin/models')['channels']
    kb=call('/api/knowledge-bases',{'name':'clean-start-persistence'})['id']
    with opener.open(base,timeout=10) as response:index=response.read().decode()
    assets=re.findall(r'(?:src|href)="(/assets/[^\"]+)"',index);assert assets
    for asset in assets:
        with opener.open(base+asset,timeout=10) as response:assert response.status==200 and response.read()
    credentials=storage/'initial-login.txt';credentials.write_text(initial,encoding='utf-8')
    child=os.environ.copy();child['RAY_TEST_AUTH_FILE']=str(credentials)
    child.setdefault('NODE_PATH',str(Path.home()/'.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules'))
    subprocess.run(['node',str(ROOT/'tools/verify_r11_clean_browser.cjs'),base],cwd=ROOT,env=child,check=True)
    # 只停止这个随机测试项目的数据库；用户的pgvector-db不属于本项目。
    compose('stop','db')
    def unavailable():
        try:call('/api/health');return False
        except urllib.error.HTTPError as error:return error.code==503
    until(unavailable,'database outage readiness503',30)
    compose('start','db');until(ready,'database reconnect ready')
    compose('restart','app');until(ready,'app restart ready')
    assert not call('/api/auth/session')['authenticated'];login()
    assert any(row['id']==kb for row in call('/api/knowledge-bases'))
    assert compose('exec','-T','app','cat','/app/.ray-data/identity/initial-login.txt',sensitive=True)==initial
    report={'project':project,'freshVolumes':True,'bundledFrontend':True,'browser':True,'databaseOutage503':True,
            'reconnected':True,'restartPreservesDataAndAccount':True,'realModel':False,'realEmbedding':False,
            'image':args.image,'imageId':docker('image','inspect','--format','{{.Id}}',args.image),'publicDeployment':False}
    (folder/'clean-start.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
    print('PASS clean Compose: fresh PG/vector + nonroot bundled app, login/browser, DB503/reconnect, persisted account/data; external providers not used',flush=True)
finally:
    try:
        # project由本脚本生成且已校验；-v仅删除本次随机隔离项目的数据卷。
        assert re.fullmatch(r'r11-clean-[a-f0-9]{32}',project)
        compose('down','--volumes','--remove-orphans')
    finally:
        log.close()
        assert storage.is_relative_to(data_root) and storage.name.startswith('r11-clean-')
        shutil.rmtree(storage)
        if keepalive.poll() is None:keepalive.terminate();keepalive.wait(timeout=10)
