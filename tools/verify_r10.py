"""复用R08隔离PG/模型夹具，增加R10鉴权、配置、审计及统计验收；不输出登录口令或密钥。"""
import json,uuid,time,http.client,subprocess,os,urllib.error,urllib.request,urllib.parse
from pathlib import Path

def verify(context):
    call=context['call'];sql=context['sql'];schema=context['schema'];port=context['port'];until=context['until']
    Stream=context['Stream'];session=context['session'];state=context['state'];gates=context['gates']
    def raw(path,method='GET',body=None,headers=None):
        connection=http.client.HTTPConnection('127.0.0.1',port,timeout=10)
        connection.request(method,path,body=body,headers=headers or {})
        response=connection.getresponse();code=response.status;payload=response.read();response_headers=response.getheaders();connection.close()
        return code,payload,response_headers
    def rejected(path,body,status,method='PUT'):
        try:call(path,body,method);raise AssertionError('expected rejection')
        except urllib.error.HTTPError as error:assert error.code==status,(path,error.code,status)
    # 原始HTTP不经自动登录助手，直接验证匿名读写与CSRF拒绝。
    for path in ['/api/sessions','/api/knowledge-bases','/api/tasks','/api/admin/models','/api/admin/statistics','/api/admin/audit']:
        assert raw(path)[0]==401,path
    assert raw('/api/admin/policy','PUT',b'{}',{'Content-Type':'application/json'})[0] in (401,403)
    headers=context['verification_headers']()
    assert raw('/api/admin/policy','PUT',b'{}',{'Cookie':headers['Cookie'],'Content-Type':'application/json'})[0]==403
    code,body,unused=raw('/api/auth/session');assert code==200 and not json.loads(body)['authenticated']
    # 独立Cookie会话验证登录时换SID、退出后失效；不输出Cookie或口令。
    anonymous=json.loads(body);initial_cookie=next(value for name,value in unused if name.lower()=='set-cookie')
    assert 'HttpOnly' in initial_cookie and 'SameSite=Strict' in initial_cookie
    account={}
    for line in Path(os.environ['RAY_TEST_AUTH_FILE']).read_text(encoding='utf-8').splitlines():
        if '=' in line:
            key,value=line.split('=',1);account[key]=value
    login_code,login_body,login_headers=raw('/api/auth/login','POST',urllib.parse.urlencode(account),{'Cookie':initial_cookie.split(';')[0],anonymous['csrfHeader']:anonymous['csrfToken'],'Content-Type':'application/x-www-form-urlencoded'})
    assert login_code==200
    authenticated_cookie=next(value for name,value in login_headers if name.lower()=='set-cookie').split(';')[0]
    assert authenticated_cookie!=initial_cookie.split(';')[0]
    assert raw('/api/admin/models',headers={'Cookie':authenticated_cookie})[0]==200
    logged=json.loads(raw('/api/auth/session',headers={'Cookie':authenticated_cookie})[1])
    assert raw('/api/auth/logout','POST',headers={'Cookie':authenticated_cookie,logged['csrfHeader']:logged['csrfToken']})[0]==200
    assert raw('/api/admin/models',headers={'Cookie':authenticated_cookie})[0]==401
    print('PASS auth: anonymous application/admin reads denied, writes denied, authenticated mutation without CSRF rejected',flush=True)
    models=call('/api/admin/models');original=models['channels'][0]
    before_run=context['first'].run
    before=call('/api/admin/runs/'+before_run+'/snapshot')
    channel='r10-secondary';model=original['model'] if context['args'].live else 'mock-r10'
    change={'revision':models['revision'],'baseUrl':original['baseUrl'],'model':model,'enabled':True,'makeDefault':True,'apiKey':'isolated-admin-key','clearKey':False}
    updated=call('/api/admin/models/'+channel,change,'PUT')
    assert updated['defaultChannel']==channel
    assert 'isolated-admin-key' not in json.dumps(updated)
    assert 'isolated-admin-key' not in sql(f'SELECT payload FROM {schema}.ra_admin_settings')
    rejected('/api/admin/models/'+channel,change,409)
    marker='credential-must-never-be-echoed'
    bad={**change,'revision':updated['revision'],'baseUrl':'http://user:'+marker+'@127.0.0.1/v1','apiKey':marker}
    try:call('/api/admin/models/'+channel,bad,'PUT');raise AssertionError('credential URL accepted')
    except urllib.error.HTTPError as error:
        assert error.code==400 and marker not in error.read().decode()
    injected=call('/api/sessions',{'title':'R10 owner binding','mode':'CHAT','ownerId':'untrusted-owner'})
    assert sql(f"SELECT owner_id FROM {schema}.ra_session WHERE id='{injected['id']}'")=='local-owner'
    created=Stream(session('R10 configured model'),'model-version-test');created.finish()
    assert call('/api/runs/'+created.run)['configuredModel']==model
    probe=call('/api/admin/models/'+channel+'/probe',{'requestId':str(uuid.uuid4())})
    assert probe['status']=='SUCCEEDED',probe['code']
    assert call('/api/admin/runs/'+before_run+'/snapshot')==before
    snapshot=call('/api/admin/runs/'+created.run+'/snapshot');assert snapshot['channelId']==channel
    print('PASS settings: default channel/model takes effect on new run, stale revision409, history snapshot unchanged, no key in API/DB',flush=True)
    # 活动与已排队运行保留原策略；配置更新对它们不能追溯生效。
    held=Stream(session('R10 policy running'),'hold-r10-policy',True)
    until(lambda: 'hold-r10-policy' in context['seen'],'held model called')
    queued=Stream(session('R10 policy queued'),'r10-queued-policy')
    old=call('/api/admin/policy')
    policy={**old,'searchEnabled':False,'fetchEnabled':False,'classifierEnabled':False,'maxModelSteps':1}
    new=call('/api/admin/policy',policy,'PUT')
    assert call('/api/admin/runs/'+queued.run+'/snapshot')['policy']['revision']==old['revision']
    gates['hold-r10-policy'].set();held.finish();queued.finish()
    fresh=Stream(session('R10 disabled tools'),'r10-no-web');fresh.finish()
    assert not call('/api/admin/runs/'+fresh.run+'/snapshot')['policy']['searchEnabled']
    assert not any(e['type']=='TOOL_STARTED' for e in call('/api/runs/'+fresh.run+'/events'))
    auto=call('/api/sessions',{'title':'R10 AUTO boundary','mode':'AUTO'})['id']
    call('/api/admin/policy',{**new,'autoEnabled':False},'PUT')
    rejected('/api/chat/stream',{'sessionId':auto,'clientRequestId':uuid.uuid4().hex,'message':'你好'},409,'POST')
    restore=call('/api/admin/policy');call('/api/admin/policy',{**old,'revision':restore['revision']},'PUT')
    print('PASS policy: queued/active immutable snapshots, new tool policy applied, AUTO disabled without mode fallback',flush=True)
    waiting_sid=session('R10 model change while waiting')
    result=call('/api/chat/stream',{'sessionId':waiting_sid,'clientRequestId':uuid.uuid4().hex,'message':'请单独调用ask_user问我目标城市，等我回答后再写一句欢迎语；当前城市尚未提供。'})
    waiting=call('/api/sessions/'+waiting_sid+'/runs')[0];assert waiting['status']=='WAITING_USER',result
    question=call('/api/runs/'+waiting['id']+'/question')['question']
    embedding=call('/api/admin/embedding')
    rejected('/api/admin/embedding',{**embedding,'apiKey':'','clearKey':False},409)
    models=call('/api/admin/models');definition=next(c for c in models['channels'] if c['id']==channel)
    call('/api/admin/models/'+channel,{**definition,'revision':models['revision'],'makeDefault':True,'apiKey':'rotated-test-credential','clearKey':False},'PUT')
    rejected('/api/runs/'+waiting['id']+'/answers',{'questionId':question['id'],'answer':'杭州'},409,'POST')
    assert call('/api/runs/'+waiting['id']+'/question')['question']['status']=='WAITING_USER'
    cancel_id=str(uuid.uuid4());action={'action':'CANCEL','requestId':cancel_id}
    first=call('/api/admin/tasks/RUN/'+waiting['id']+'/actions',action)
    again=call('/api/admin/tasks/RUN/'+waiting['id']+'/actions',action)
    assert first['id']==again['id'] and first['status']=='SUCCEEDED' and state(waiting['id'])=='CANCELLED'
    print('PASS resume: embedding update blocked while waiting, changed model fails closed without consuming question, audited cancel idempotent',flush=True)
    # 改维度后旧索引不可被新检索复用；恢复新profile后明确重建。
    kb=context['kb'];document=context['retry'];path=context['document_path'](kb,document)
    original_count=call(path)['vectorCount'];assert original_count>0
    embedding=call('/api/admin/embedding')
    mismatch={**embedding,'dimension':embedding['dimension']+1,'version':'r10-different-space','apiKey':'','clearKey':False}
    call('/api/admin/embedding',mismatch,'PUT')
    knowledge=call('/api/sessions',{'title':'R10 changed vector space','mode':'KNOWLEDGE','knowledgeBaseIds':[kb]})['id']
    refusal=call('/api/chat/stream',{'sessionId':knowledge,'clientRequestId':str(uuid.uuid4()),'message':'巡检编号是什么？'})
    current_run=call('/api/sessions/'+knowledge+'/runs')[0]
    evidence=next(e for e in call('/api/runs/'+current_run['id']+'/events') if e['type']=='RAG_EVIDENCE')
    assert not json.loads(evidence['detail'])['citations'],'old vector space silently reused'
    current=call('/api/admin/embedding')
    restored={**embedding,'revision':current['revision'],'version':'r10-restored-space','apiKey':'','clearKey':False}
    call('/api/admin/embedding',restored,'PUT')
    action={'action':'REINDEX','requestId':str(uuid.uuid4())}
    rebuilt=call('/api/admin/tasks/DOCUMENT/'+document['id']+'/actions',action)
    repeated=call('/api/admin/tasks/DOCUMENT/'+document['id']+'/actions',action)
    assert rebuilt['id']==repeated['id'] and rebuilt['status']=='SUCCEEDED'
    new_id=rebuilt['result_id']
    new_version=next(v for v in call(path.rsplit('/',1)[0]) if v['id']==new_id)
    context['ready'](kb,new_version)
    assert len(call(path.rsplit('/',1)[0]))==2
    print('PASS embedding: dimension change excludes old index, explicit rebuild publishes new profile once, complete vectors remain',flush=True)
    # 构造解析失败版本，重试身份重复不创建第二次任务。
    boundary=uuid.uuid4().hex
    data=f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="invalid-r10.txt"\r\nContent-Type: text/plain\r\n\r\n'.encode()+b'\xff'+f'\r\n--{boundary}--\r\n'.encode()
    request=urllib.request.Request(f'http://127.0.0.1:{port}/api/knowledge-bases/{kb}/documents',data=data,headers={'Content-Type':'multipart/form-data; boundary='+boundary})
    invalid=json.load(urllib.request.urlopen(request,timeout=10));invalid_path=context['document_path'](kb,invalid)
    until(lambda:call(invalid_path)['version']['status']=='FAILED','invalid document failed')
    action={'action':'RETRY','requestId':str(uuid.uuid4())}
    retry=call('/api/admin/tasks/DOCUMENT/'+invalid['id']+'/actions',action)
    assert call('/api/admin/tasks/DOCUMENT/'+invalid['id']+'/actions',action)['id']==retry['id']
    until(lambda:call(invalid_path)['version']['status']=='FAILED','retried invalid document failed')
    assert len(context['attempts'](invalid))==2
    audit=call('/api/admin/audit');assert all(row['actor']=='owner' for row in audit)
    assert not any('isolated-admin-key' in json.dumps(row) for row in audit)
    count=int(sql(f"SELECT COUNT(*) FROM {schema}.ra_run WHERE owner_id='local-owner'"))
    statistics=call('/api/admin/statistics');assert statistics['totals']['runs']==count
    states={row['status']:row['count'] for row in statistics['states']}
    assert sum(states.values())==count
    expected=sql(f"SELECT SUM(input_tokens) FROM {schema}.ra_run WHERE owner_id='local-owner'")
    assert statistics['totals']['known_input_tokens']==(int(expected) if expected else None)
    event_counts={row['type']:row['count'] for row in statistics['events']}
    for event in ('TOOL_STARTED','TOOL_SUCCEEDED','ROUTE_DECIDED'):
        actual=int(sql(f"SELECT COUNT(*) FROM {schema}.ra_event e JOIN {schema}.ra_run r ON r.id=e.run_id WHERE r.owner_id='local-owner' AND e.type='{event}'"))
        assert event_counts.get(event,0)==actual
    # 模拟已声明但未确认的后台操作；重启只能标记中断，不能代用户重放。
    pending_id=str(uuid.uuid4());pending_request=str(uuid.uuid4())
    sql(f"INSERT INTO {schema}.ra_admin_audit VALUES ('{pending_id}','local-owner','owner','EMBEDDING_PROBE','embedding','{pending_request}','REQUESTED',NULL,NULL,{int(time.time()*1000)},NULL)")
    context['stop']();context['start']()
    interrupted=next(row for row in call('/api/admin/audit') if row['id']==pending_id)
    assert interrupted['status']=='INTERRUPTED' and interrupted['code']=='PROCESS_RESTART'
    assert call('/api/admin/models')['defaultChannel']==channel
    assert call('/api/admin/embedding')['version']=='r10-restored-space'
    assert call('/api/admin/runs/'+before_run+'/snapshot')==before
    print('PASS audit/statistics: repeated document retry only one operation/two attempts, actor recorded, SQL counts/usage agree, settings survive restart',flush=True)
    report={'date':'2026-11-01','realPostgres':True,'realModel':context['args'].live,'realEmbedding':context['args'].live,
            'anonymousAndCsrfDenied':True,'snapshotAndHotSettings':True,'embeddingProfileGuard':True,'idempotentAudit':True,'statisticsSqlChecked':True,'settingsSurviveRestart':True}
    (context['ROOT']/'logs/r10').mkdir(exist_ok=True)
    (context['ROOT']/'logs/r10'/('live.json' if context['args'].live else 'mock.json')).write_text(json.dumps(report,indent=2),encoding='utf-8')
    if context['args'].browser:
        from verify_r10_browser_driver import run_browser
        context['r10_invalid_version']=invalid['id']
        run_browser(context)

if __name__=='__main__':
    import sys
    raise SystemExit(subprocess.run([sys.executable,str(Path(__file__).with_name('verify_r08.py')),'--admin',*sys.argv[1:]]).returncode)
