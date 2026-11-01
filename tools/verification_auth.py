"""验收脚本专用：只为指定后端origin添加Cookie/CSRF，不把会话传给模型或第三方地址。"""
import http.cookiejar,json,os,time,urllib.request,urllib.error,urllib.parse
from pathlib import Path

def install(base, env, directory):
    credentials=Path(directory)/'identity'/'owner.properties'
    env['RAY_AUTH_CREDENTIALS_FILE']=str(credentials)
    env['RAY_ADMIN_SECRETS_FILE']=str(Path(directory)/'admin-secrets.properties')
    os.environ['RAY_TEST_AUTH_FILE']=str(credentials.parent/'initial-login.txt')
    original=urllib.request.urlopen
    opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    state={'token':'','header':'X-CSRF-TOKEN'}
    def authenticate():
        with opener.open(base+'/api/auth/session',timeout=10) as response: status=json.load(response)
        data={}
        for line in credentials.with_name('initial-login.txt').read_text(encoding='utf-8').splitlines():
            if '=' in line:
                key,value=line.split('=',1);data[key]=value
        request=urllib.request.Request(base+'/api/auth/login',data=urllib.parse.urlencode(data).encode(),headers={status['csrfHeader']:status['csrfToken'],'Content-Type':'application/x-www-form-urlencoded'})
        opener.open(request,timeout=10).close()
        with opener.open(base+'/api/auth/session',timeout=10) as response: status=json.load(response)
        assert status['authenticated'],'verification login failed'
        for _ in range(300):
            if status.get('runtimeReady'):break
            time.sleep(.1)
            with opener.open(base+'/api/auth/session',timeout=10) as response: status=json.load(response)
        assert status.get('runtimeReady'),'runtime settings were not initialized'
        state.update(token=status['csrfToken'],header=status['csrfHeader'])
    def wrapped(url, data=None, timeout=60, **options):
        address=url.full_url if isinstance(url,urllib.request.Request) else str(url)
        if not address.startswith(base+'/'):
            return original(url,data=data,timeout=timeout,**options)
        request=url if isinstance(url,urllib.request.Request) else urllib.request.Request(address,data=data)
        if request.get_method() not in ('GET','HEAD','OPTIONS') and state['token']:request.add_header(state['header'],state['token'])
        try:return opener.open(request,timeout=timeout)
        except urllib.error.HTTPError as error:
            if error.code not in (401,403):raise
            # 认证/CSRF在业务处理前拒绝，验收环境重新登录后只重试一次。
            error.close();authenticate()
            request.remove_header('Cookie')
            if request.get_method() not in ('GET','HEAD','OPTIONS'):request.add_header(state['header'],state['token'])
            return opener.open(request,timeout=timeout)
    urllib.request.urlopen=wrapped
    def headers():
        request=urllib.request.Request(base+'/')
        for handler in opener.handlers:
            if isinstance(handler,urllib.request.HTTPCookieProcessor):handler.cookiejar.add_cookie_header(request)
        return {'Cookie':request.get_header('Cookie',''),state['header']:state['token']}
    return headers
