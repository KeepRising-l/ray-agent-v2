// UI验收专用：自写内容、内存配置与模拟登录，不读取本机密钥、不调用真实外部服务。
const http = require('node:http')
const { randomUUID } = require('node:crypto')
const { start: startChat } = require('./r09_fixture.cjs')
async function start({ authenticated = false, richChat = true } = {}) {
  const chat = await startChat(0, { richChat })
  const base = 'http://127.0.0.1:'+chat.address().port
  let models = { revision:0, defaultChannel:'local', channels:[{ id:'local', model:'GLM-5.3-Flash', baseUrl:'https://api.example.com/v1', enabled:true, configured:true, version:'ui-v1' }] }
  let embedding = { revision:0, baseUrl:'https://api.example.com/v1', model:'BAAI/bge-m3', dimension:1024, version:'v1', profileId:'ui-profile', configured:true }
  let policy = { revision:0, searchEnabled:true, fetchEnabled:true, autoEnabled:true, classifierEnabled:true, maxModelSteps:5, maxToolCalls:6, timeoutMs:60000, toolTimeoutMs:15000 }
  const audit = []
  const record = (action,target,result) => { const row = {id:randomUUID(),actor:'owner',action,target,status:'SUCCEEDED',code:'OK',result_id:result,created_at:Date.now()};audit.unshift(row);return row }
  const server = http.createServer(async (req,res) => {
    try {
      const path = req.url.split('?')[0]
      let raw='';for await(const part of req) raw+=part
      const body=raw && req.headers['content-type']?.includes('application/json') ? JSON.parse(raw) : {}
      const signed = req.headers.cookie?.includes('ui-auth=ok') || (authenticated && !req.headers.cookie?.includes('ui-auth=out'))
      const send=(value,status=200)=>{res.writeHead(status,{'Content-Type':'application/json'});res.end(JSON.stringify(value))}
      if(path==='/api/auth/session')return send({authenticated:!!signed,username:signed?'owner':'',runtimeReady:true,csrfToken:'ui-check-csrf',csrfHeader:'X-CSRF-TOKEN'})
      if(path==='/api/auth/login'){
        const form=new URLSearchParams(raw)
        if(form.get('username')!=='owner'||form.get('password')!=='ui-local-check')return send({message:'账号或密码不正确'},401)
        res.setHeader('Set-Cookie','ui-auth=ok; Path=/; HttpOnly; SameSite=Strict');return send({message:'登录成功'})
      }
      if(path==='/api/auth/logout'){res.setHeader('Set-Cookie','ui-auth=out; Path=/; HttpOnly; SameSite=Strict');return send({message:'已退出'})}
      if(!signed)return send({message:'请先登录'},401)
      if(!['GET','HEAD'].includes(req.method)&&req.headers['x-csrf-token']!=='ui-check-csrf')return send({message:'页面已过期'},403)
      if(path==='/api/model-channels')return send(models.channels.filter(item=>item.enabled&&item.configured).map(item=>({id:item.id,model:item.model})))
      if(path==='/api/knowledge-bases/embedding')return send({model:embedding.model,dimension:embedding.dimension,configured:embedding.configured})
      if(path==='/api/admin/overview')return send({username:'owner',knowledgeBases:1,documents:1,capacity:{running:0,runningLimit:2,queued:0,queueLimit:8,rejected:0}})
      if(path==='/api/admin/models')return send(models)
      if(path.startsWith('/api/admin/models/')&&req.method==='PUT'){
        if(body.model==='fail-save')return send({message:'暂时无法保存，请保留输入后重试'},503)
        if(body.revision!==models.revision)return send({message:'配置已变化，请刷新'},409)
        const id=path.split('/').at(-1);const old=models.channels.find(item=>item.id===id)
        models={revision:models.revision+1,defaultChannel:body.makeDefault?id:models.defaultChannel,channels:[...models.channels.filter(item=>item.id!==id),{id,model:body.model,baseUrl:body.baseUrl,enabled:body.enabled,configured:!body.clearKey&&(!!body.apiKey||old?.configured||false),version:'ui-v'+(models.revision+1)}]}
        record('SETTINGS_UPDATED','model-catalogue');return send(models)
      }
      if(path==='/api/admin/embedding'&&req.method==='GET')return send(embedding)
      if(path==='/api/admin/embedding'&&req.method==='PUT'){embedding={...embedding,...body,revision:embedding.revision+1,configured:!body.clearKey};delete embedding.apiKey;delete embedding.clearKey;record('SETTINGS_UPDATED','embedding');return send(embedding)}
      if(path.endsWith('/probe'))return send(record(path.includes('embedding')?'EMBEDDING_PROBE':'MODEL_PROBE',path,path.includes('embedding')?'1024':'local'))
      if(path==='/api/admin/policy'){
        if(req.method==='PUT'){policy={...body,revision:policy.revision+1};record('SETTINGS_UPDATED','runtime-policy')}
        return send(policy)
      }
      if(path==='/api/admin/audit')return send(audit)
      if(path==='/api/admin/tasks')return send([])
      if(path==='/api/admin/sessions')return send([])
      if(path==='/api/admin/statistics')return send({basis:'当前个人工作空间 · 按运行创建时间统计',totals:{runs:0,runs_with_missing_usage:0,known_input_tokens:null,known_output_tokens:null,completed_average_total_ms:null},states:[],models:[],events:[],routes:[]})
      const upstream=http.request(base+req.url,{method:req.method,headers:{...req.headers,host:new URL(base).host}},response=>{res.writeHead(response.statusCode,response.headers);response.pipe(res)})
      upstream.on('error',()=>{if(!res.headersSent)send({message:'本机验收服务不可用'},503);else res.end()})
      upstream.end(raw)
    }catch{if(!res.headersSent){res.writeHead(500,{'Content-Type':'application/json'});res.end('{"message":"UI验收接口错误"}')}else res.end()}
  })
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve))
  return {server,url:'http://127.0.0.1:'+server.address().port,close:async()=>{server.closeAllConnections();chat.closeAllConnections();await Promise.all([new Promise(r=>server.close(r)),new Promise(r=>chat.close(r))])}}
}
module.exports={start}
