// Real V3 Controller/auth/risk/Stream/stock/HELD, never a fast consumer.
import http from 'k6/http';
import exec from 'k6/execution';
import {sleep} from 'k6';
import {SharedArray} from 'k6/data';
import {Counter, Trend} from 'k6/metrics';
const mode=__ENV.V3_MODE;
const seconds=Number(__ENV.V3_SECONDS||30), vus=Number(__ENV.V3_VUS||64);
const rate=Number(__ENV.V3_RATE||5000);
if(__ENV.V3_EXECUTE!=='owned-local-environment' || !['ingress','held'].includes(mode) || seconds!==30 || vus<1 || vus>512 || rate<1 || rate>10000)
  throw new Error('Explicit owned local 30-second workload required');
const users=new SharedArray('owned-v3-buyers',()=>{
  const f=JSON.parse(open(__ENV.V3_FIXTURES));
  if(f.owner!==__ENV.V3_OWNER || f.app_port!==18083 || !/^credit_risk_v3_[a-f0-9]{8}$/.test(f.schema) || f.requests.length!==5000)
    throw new Error('Fixture owner/schema/port/5000 buyer whitelist failed');
  return f.requests;
});
const posts=new Counter('v3_post_attempted'),responses=new Counter('v3_post_responses');
const accepted=new Counter('v3_http_202'),fresh=new Counter('v3_new_submissions');
const busy=new Counter('v3_http_429'),unavailable=new Counter('v3_http_503'),other=new Counter('v3_other_status');
const authErrors=new Counter('v3_auth_error'),serverErrors=new Counter('v3_http_5xx'),networkErrors=new Counter('v3_transport_error');
const gets=new Counter('v3_get_queries'),held=new Counter('v3_client_observed_held'),timeouts=new Counter('v3_client_observed_queue_timeout');
const latency=new Trend('v3_post_ms',true),firstPost=new Trend('v3_post_start_epoch_ms');
export const options={discardResponseBodies:true,noConnectionReuse:false,noVUConnectionReuse:false,
  setupTimeout:'90s',
  summaryTrendStats:['avg','min','med','p(95)','p(99)','max'],systemTags:['status','method','scenario','name'],
  scenarios:mode==='ingress'?{load:{executor:'constant-arrival-rate',rate,timeUnit:'1s',duration:'30s',preAllocatedVUs:vus,maxVUs:vus,gracefulStop:'3s'}}:
    {load:{executor:'shared-iterations',vus,iterations:5000,maxDuration:'30s',gracefulStop:'0s'}},
};
http.setResponseCallback(http.expectedStatuses(200,202,429,503));
export function setup(){
  const opening=Number(__ENV.V3_SALE_OPEN);
  if(!Number.isFinite(opening) || opening<Date.now()-500)throw new Error('Missed fresh opening; no misleading open-to-HELD result');
  if(opening-Date.now()>80000)throw new Error('Opening is outside the bounded setup window');
  if(opening>Date.now())sleep((opening-Date.now())/1000+.05);
}
function request(user,rid){
  posts.add(1);firstPost.add(Date.now());
  const res=http.post('http://127.0.0.1:18083/api/tickets/reservations',JSON.stringify({requestId:rid,userId:user.userId,
    eventSessionId:user.eventSessionId,ticketSkuId:user.ticketSkuId,quantity:1,deviceId:user.deviceId}),
    {timeout:'2s',responseType:'text',redirects:0,headers:{'Content-Type':'application/json',authorization:user.token,'X-Forwarded-For':user.ip},tags:{name:'V3_POST'}});
  latency.add(res.timings.duration);if(res.status>0)responses.add(1);
  if(res.error_code)networkErrors.add(1);
  if(res.status>=500)serverErrors.add(1);
  if(res.status===401 || res.status===403){authErrors.add(1);exec.test.abort('Authentication/identity fixture invalid; do not turn fast 401/403 into capacity');}
  let data=null;try{data=res.json('data');}catch(_){}
  if(res.status===202){
    accepted.add(1);
    if(!data || data.receiptSemantics!=='V3_PROCESSING')exec.test.abort('Not a real V3 receipt');
    if(data.newSubmission===true)fresh.add(1);
  }else if(res.status===429)busy.add(1);else if(res.status===503)unavailable.add(1);else other.add(1);
  return {status:res.status,data};
}
export default function(){
  const i=exec.scenario.iterationInTest,user=users[i%5000];
  let attempt=mode==='ingress'?Math.floor(i/5000):0;
  let rid=__ENV.V3_RUN+'-'+(i%5000)+'-'+attempt;
  if(mode==='ingress'){request(user,rid);return;}
  // One iteration per buyer; 429/503/unknown retries KEEP requestId. Only an authoritative
  // terminal failure permits a simulated new click and new requestId. No rejection is HELD.
  const end=exec.scenario.startTime+30000;
  while(Date.now()<end){
    const response=request(user,rid);
    if(response.status!==202){sleep(.2);continue;}
    while(Date.now()<end){
      gets.add(1);
      const res=http.get('http://127.0.0.1:18083/api/tickets/reservations/by-request?requestId='+encodeURIComponent(rid),
        {timeout:'2s',responseType:'text',headers:{authorization:user.token,'X-Forwarded-For':user.ip},tags:{name:'V3_GET'}});
      let data=null;try{data=res.json('data');}catch(_){}
      if(data && data.authoritative===true && data.reservationSuccess===true){held.add(1);return;}
      if(data && data.authoritative===true && data.terminal===true){
        if(data.state==='QUEUE_TIMEOUT')timeouts.add(1);
        if(data.state==='QUEUE_TIMEOUT' || data.retryWithNewAttempt===true){attempt++;rid=__ENV.V3_RUN+'-'+i+'-'+attempt;break;}
        other.add(1);return;
      }
      sleep(.2);
    }
  }
}
export function handleSummary(data){return {[__ENV.V3_SUMMARY]:JSON.stringify({mode,seconds,rate:mode==='ingress'?rate:null,vus,
  buyers:5000,ingress_buyer_reuse:mode==='ingress',note:'202/newSubmission are not HELD. SQL sampler is authoritative. POST/GET metrics are separate. Dropped iterations are client work not sent.',k6:data},null,2)};}
