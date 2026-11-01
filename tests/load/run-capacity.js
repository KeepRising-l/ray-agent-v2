// 只由隔离验收夹具传入登录会话；不接受公网目标，不输出Cookie/CSRF或模型正文。
import fs from 'node:fs'
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
const base = process.argv[2]
assert.match(base, /^http:\/\/127\.0\.0\.1:\d+$/)
const auth = JSON.parse(process.env.RAY_LOAD_AUTH)
async function call(path, body) {
  const response = await fetch(base+path, {method:body?'POST':'GET',headers:{...auth,'Content-Type':'application/json'},body:body?JSON.stringify(body):undefined})
  assert.equal(response.status,200)
  return response.json()
}
const sessions=[]
for(let i=0;i<24;i++) sessions.push((await call('/api/sessions',{title:'R11 isolated load '+i,mode:'CHAT'})).id)
const observations=[];let cursor=0;let maxAdmitted=0
const began=performance.now()
await Promise.all(Array.from({length:12},async()=>{
  while(cursor<sessions.length){
    const i=cursor++;const start=performance.now()
    const response=await fetch(base+'/api/chat/stream',{method:'POST',headers:{...auth,'Content-Type':'application/json'},body:JSON.stringify({sessionId:sessions[i],clientRequestId:randomUUID(),message:'r11-load-'+i})})
    const raw=await response.text()
    assert.ok([200,429].includes(response.status),'unexpected HTTP '+response.status)
    if(response.status===200) assert.match(raw,/event: ?done/)
    const runs=await call('/api/sessions/'+sessions[i]+'/runs')
    assert.equal(runs.length,response.status===200?1:0)
    const capacity=await call('/api/tasks/capacity');maxAdmitted=Math.max(maxAdmitted,capacity.admitted)
    assert.ok(capacity.admitted<=3 && capacity.running<=1 && capacity.queued<=2)
    observations.push({status:response.status,ms:performance.now()-start})
  }
}))
const elapsedMs=performance.now()-began
const accepted=observations.filter(row=>row.status===200);const times=accepted.map(row=>row.ms).sort((a,b)=>a-b)
assert.ok(accepted.length>0 && accepted.length<24)
assert.equal((await call('/api/tasks/capacity')).admitted,0)
const report={mockModel:true,realPostgres:true,controlledModelDelayMs:150,requests:24,concurrency:12,runningLimit:1,queueLimit:2,
  accepted:accepted.length,rejected429:24-accepted.length,maxObservedAdmitted:maxAdmitted,elapsedMs,
  acceptedP50Ms:times[Math.ceil(times.length*.5)-1],acceptedP95Ms:times[Math.ceil(times.length*.95)-1],
  scope:'localhost scheduling/HTTP/PG only; not real-model QPS or supported production capacity'}
fs.mkdirSync('logs/r11',{recursive:true});fs.writeFileSync('logs/r11/capacity.json',JSON.stringify(report,null,2))
console.log(JSON.stringify(report))
