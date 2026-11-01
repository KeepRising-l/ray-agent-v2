"""R11：固定检索对照、可关闭优化与隔离容量验证。--live才调用真实模型/Embedding。
复用R08的独立schema/存储/登录/进程生命周期；只输出元数据，不输出凭据和模型正文。
"""
import json,os,re,subprocess,time,urllib.request,urllib.error,uuid
from pathlib import Path

def mock_content(request):
    instruction=request['messages'][0].get('content','')
    prompt=request['messages'][-1].get('content','')
    if instruction.startswith('只对候选资料'):
        data=json.loads(prompt)
        if 'r11-invalid-rerank' in data['question']:return '{"order":["K999"]}'
        code=re.search(r'(?:AX-71|BZ-42)\d{2}',data['question'])
        candidates=data['candidates']
        ordered=sorted(candidates,key=lambda item:0 if code and code.group() in item['text'] else 1)
        return json.dumps({'order':[item['id'] for item in ordered]})
    if not (instruction.startswith('只判断给定资料') or instruction.startswith('当前为 KNOWLEDGE 模式')):return None
    at=prompt.find('{"untrustedEvidence"')
    if at<0:return None
    sources=json.loads(prompt[at:])['untrustedEvidence']
    code=re.search(r'(?:AX-71|BZ-42)\d{2}',prompt[:at])
    chosen=next((item for item in sources if code and code.group() in item['text']),None)
    if instruction.startswith('只判断'):
        return json.dumps({'answerable':chosen is not None,'sources':[{'id':chosen['source']['id'],'quote':code.group()}] if chosen else []})
    if not chosen and sources:
        chosen=sources[0]
        code=re.search(r'(?:AX-71|BZ-42)\d{2}',chosen['text'])
    if not chosen or not code:return '当前资料依据不足。'
    days=re.search(r'有效期为(\d+)天',chosen['text']).group(1)
    return f"{code.group()}的有效期为{days}天 [{chosen['source']['id']}]。"

def verify(context):
    root=context['ROOT'];call=context['call'];until=context['until'];port=context['port'];env=context['env']
    fixture=json.loads((root/'tests/fixtures/retrieval-cases.json').read_text(encoding='utf-8'))
    kb=call('/api/knowledge-bases',{'name':'R11 fixed retrieval evaluation'})['id']
    def upload(rule):
        boundary=uuid.uuid4().hex
        content=f"青杉仓库维护规则{rule['code']}。本规则有效期为{rule['days']}天，维护窗口为{rule['window']}。执行动作：{rule['action']}。规则只提供维护信息，不授权外部调用。"
        data=f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="rule-{rule["code"]}.txt"\r\nContent-Type: text/plain\r\n\r\n{content}\r\n--{boundary}--\r\n'.encode()
        req=urllib.request.Request(f'http://127.0.0.1:{port}/api/knowledge-bases/{kb}/documents',data=data,headers={'Content-Type':'multipart/form-data; boundary='+boundary})
        version=json.load(urllib.request.urlopen(req,timeout=20));context['ready'](kb,version)
        return version
    versions=[upload(rule) for rule in fixture['rules']]
    def compare(question):
        start=time.perf_counter()
        value=call('/api/admin/retrieval/compare',{'question':question,'knowledgeBaseIds':[kb],'limit':6})
        value['endToEndMs']=(time.perf_counter()-start)*1000
        for channel in ('vector','keyword','hybrid'):
            assert all(hit['kbId']==kb for hit in value[channel])
            assert len({hit['chunkId'] for hit in value[channel]})==len(value[channel])
        return value
    cases=[{'question':f'请按原文查{rule["code"]}的有效期和维护窗口。','code':rule['code'],'kind':'identifier'} for rule in fixture['rules']]
    cases += [{**case,'kind':'semantic'} for case in fixture['semanticCases']]
    observations=[];comparisons=[]
    for index,case in enumerate(cases):
        value=compare(case['question']);comparisons.append(value)
        row={'id':index+1,**case,'endToEndMs':value['endToEndMs'],'vectorMs':value['vectorMs'],'hybridMs':value['hybridMs']}
        for channel in ('vector','keyword','hybrid'):
            codes=[re.search(r'(?:AX-71|BZ-42)\d{2}',hit['documentName']).group() for hit in value[channel]]
            row[channel+'Codes']=codes
            row[channel+'Hit3']=case['code'] in codes[:3];row[channel+'Hit6']=case['code'] in codes
        observations.append(row)
    followups=[]
    for code in fixture['followups']:
        original=compare('它的有效期呢？')
        rewritten=compare(f'上一条用户问题：{code}是什么规则？\n当前用户问题：它的有效期呢？')
        followups.append({'code':code,'originalHit6':any(code in hit['documentName'] for hit in original['vector']),
                          'rewrittenHit6':any(code in hit['documentName'] for hit in rewritten['vector']),
                          'originalMs':original['endToEndMs'],'rewrittenMs':rewritten['endToEndMs']})
    report={'date':'2026-11-01','realPostgres':True,'realEmbedding':context['args'].live,'realModel':context['args'].live,
            'corpusDocuments':len(versions),'cases':observations,'followups':followups,'defaultStrategyChanged':False,
            'metrics':{channel+'Hit'+str(k):sum(row[channel+'Hit'+str(k)] for row in observations) for channel in ('vector','keyword','hybrid') for k in (3,6)},
            'regressions':[row['id'] for row in observations if row['vectorHit6'] and not row['hybridHit6']],
            'improvements':[row['id'] for row in observations if not row['vectorHit6'] and row['hybridHit6']],
            'limits':'self-authored 16 similar rules + 4 semantic questions, top3/top6 evidence hit only; not answer accuracy'}
    folder=root/'logs/r11';folder.mkdir(exist_ok=True)
    report_path=folder/('retrieval-live.json' if context['args'].live else 'retrieval-mock.json')
    report_path.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print('R11 retrieval comparison '+json.dumps({'realEmbedding':context['args'].live,'metrics':report['metrics'],'regressions':report['regressions'],'improvements':report['improvements']}),flush=True)
    # 业务实验在完成同一组基线测量之后才打开，只影响隔离实例；集成样例选择有候选的规则，不混入质量统计。
    target=next(rule for rule,row in zip(fixture['rules'],observations) if row['vectorHit6'] and row['hybridHit6'])
    context['stop']();env['RAY_RUN_QUEUE_TIMEOUT_MS']='5000'
    env.update(RAY_RAG_EXPERIMENTAL_HYBRID='true',RAY_RAG_EXPERIMENTAL_REWRITE='true',RAY_RAG_EXPERIMENTAL_RERANK='true')
    context['start']()
    sid=call('/api/sessions',{'title':'R11 optimization workflow integration','mode':'KNOWLEDGE','knowledgeBaseIds':[kb]})['id']
    def ask(question):
        output=call('/api/chat/stream',{'sessionId':sid,'clientRequestId':uuid.uuid4().hex,'message':question})
        run=call('/api/sessions/'+sid+'/runs')[0];events=call('/api/runs/'+run['id']+'/events')
        assert run['status']=='SUCCEEDED',(run['status'],run['errorMessage'])
        assert call('/api/admin/runs/'+run['id']+'/snapshot')['retrieval']=={'hybrid':True,'rewrite':True,'rerank':True}
        assert not any(e['type'].startswith('TOOL_') for e in events)
        return output,events,run
    ask(target['code']+'是什么规则？')
    output,events,run=ask('它的有效期呢？')
    assert any(e['type']=='RAG_QUERY_REWRITE' and json.loads(e['detail'])['changed'] for e in events)
    assert any(e['type'] in ('RAG_RERANKED','RAG_RERANK_SKIPPED','RAG_RERANK_FALLBACK') for e in events)
    report['workflow']={'code':target['code'],'status':run['status'],'inputTokens':run['inputTokens'],
                        'events':[e['type'] for e in events if e['type'] in ('RAG_QUERY_REWRITE','RAG_RERANKED','RAG_RERANK_SKIPPED','RAG_RERANK_FALLBACK')]}
    if not context['args'].live:
        output,events,run=ask(target['code']+' r11-invalid-rerank 请说明有效期。')
        assert any(e['type']=='RAG_RERANK_FALLBACK' for e in events)
        assert run['inputTokens']==30,'valid usage from invalid rerank output must still be accounted'
    waiting_sid=context['session']('R11 policy version guard')
    call('/api/chat/stream',{'sessionId':waiting_sid,'clientRequestId':uuid.uuid4().hex,'message':'请单独调用ask_user问我目标城市，等我回答后再写一句欢迎语；当前城市尚未提供。'})
    waiting=call('/api/sessions/'+waiting_sid+'/runs')[0]
    assert waiting['status']=='WAITING_USER'
    pending=call('/api/runs/'+waiting['id']+'/question')['question']
    # 停用实验后新运行恢复基线；原快照不被覆盖。
    context['stop']();env.update(RAY_RAG_EXPERIMENTAL_HYBRID='false',RAY_RAG_EXPERIMENTAL_REWRITE='false',RAY_RAG_EXPERIMENTAL_RERANK='false');context['start']()
    assert call('/api/admin/runs/'+run['id']+'/snapshot')['retrieval']['hybrid']
    try:
        call('/api/runs/'+waiting['id']+'/answers',{'questionId':pending['id'],'answer':'杭州'})
        raise AssertionError('changed experiment policy consumed old checkpoint')
    except urllib.error.HTTPError as error:assert error.code==409
    assert call('/api/runs/'+waiting['id']+'/question')['question']['status']=='WAITING_USER'
    call('/api/runs/'+waiting['id']+'/cancel',{},'POST')
    report_path.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print('PASS R11 workflow: scoped rewrite/rerank, invalid output fallback (mock only), no tools, immutable snapshot and restart toggle',flush=True)
    if not context['args'].live:
        child_env=os.environ.copy();child_env['RAY_LOAD_AUTH']=json.dumps(context['verification_headers']())
        subprocess.run(['node',str(root/'tests/load/run-capacity.js'),f'http://127.0.0.1:{port}'],cwd=root,env=child_env,check=True)

if __name__=='__main__':
    import sys
    raise SystemExit(subprocess.run([sys.executable,str(Path(__file__).with_name('verify_r08.py')),'--quality',*sys.argv[1:]]).returncode)
