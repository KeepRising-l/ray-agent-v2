import json,os,subprocess,time,urllib.request

def run_browser(context):
    root=context['ROOT'];port=context['free_port']();env=context['env'].copy()
    env['RAY_DEV_API_TARGET']=f'http://127.0.0.1:{context["port"]}'
    process=subprocess.Popen(['node',str(root/'ray-agent-web/node_modules/vite/bin/vite.js'),'--host','127.0.0.1','--port',str(port),'--strictPort'],cwd=root/'ray-agent-web',env=env,stdout=context['log'],stderr=subprocess.STDOUT)
    try:
        base=f'http://127.0.0.1:{port}'
        def ready():
            try:urllib.request.urlopen(base,timeout=2).close();return True
            except Exception:return False
        context['until'](ready,'admin frontend ready')
        browser_env=os.environ.copy();browser_env.setdefault('NODE_PATH',str(__import__('pathlib').Path.home()/'.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules'))
        # 模拟环境连续走三轮异步交互；真实接口只走一轮，避免额外消耗探测用量。
        for round_number in range(1, (1 if context['args'].live else 3)+1):
            subprocess.run(['node',str(root/'tools/verify_r10_browser.cjs'),base,context['r10_invalid_version']],cwd=root,env=browser_env,check=True)
            print(f'PASS R10 browser round {round_number}',flush=True)
        if context['args'].review:
            folder=root/'logs/r10';signal=folder/'review.done'
            if signal.exists():signal.unlink()
            (folder/'review-info.json').write_text(json.dumps({'url':base,'credentialsFile':os.environ['RAY_TEST_AUTH_FILE'],'safety':'isolated schema, mock model/embedding; do not access other ports'}),encoding='utf-8')
            print('R10_REVIEW_READY '+base,flush=True)
            deadline=time.monotonic()+600
            while time.monotonic()<deadline and not signal.exists():time.sleep(.5)
    finally:
        if process.poll() is None:process.terminate();process.wait(timeout=10)
