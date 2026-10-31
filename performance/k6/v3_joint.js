// ONE real joint test. One stable intent/requestId per buyer for the full 30 seconds.
import http from 'k6/http';
import exec from 'k6/execution';
import {sleep} from 'k6';
import {SharedArray} from 'k6/data';
import {Counter,Trend} from 'k6/metrics';
if(__ENV.V3_JOINT_EXECUTE!=='one-owned-joint-run')throw new Error('Explicit joint authorization missing');
const t0=Number(__ENV.V3_T0),vus=Number(__ENV.V3_VUS);
if(!Number.isFinite(t0)||!Number.isInteger(vus)||vus<1||vus>512)throw new Error('Invalid fixed joint configuration');
const buyers=new SharedArray('joint-5000-real-buyers',()=>{
 const f=JSON.parse(open(__ENV.V3_FIXTURES));
 if(f.owner!==__ENV.V3_OWNER||f.requests.length!==5000||f.app_port!==18083||!/^credit_risk_v3_[a-f0-9]{8}$/.test(f.schema))throw new Error('Fixture whitelist failed');
 return f.requests;
});
const started=new Counter('joint_post_started'),completed=new Counter('joint_post_completed');
const first=new Counter('joint_first_buyer_posts'),repeated=new Counter('joint_repeated_intent_posts');
const windowComplete=new Counter('joint_completed_within_30s'),good202=new Counter('joint_202'),busy=new Counter('joint_429');
const fresh=new Counter('joint_new_admitted'),known=new Counter('joint_existing_intent_202'),system=new Counter('joint_5xx');
const other=new Counter('joint_other_status'),network=new Counter('joint_transport_errors'),timeouts=new Counter('joint_timeouts');
const tail=new Counter('joint_completed_after_30s'),skipped=new Counter('joint_iterations_after_window');
const elapsed=new Trend('joint_client_elapsed_ms',true),service=new Trend('joint_http_duration_ms',true);
const firstStart=new Trend('joint_post_start_epoch_ms');
let errors={};
export const options={discardResponseBodies:true,noConnectionReuse:false,noVUConnectionReuse:false,setupTimeout:'90s',
 summaryTrendStats:['avg','min','med','p(95)','p(99)','max'],systemTags:['status','method','scenario','name','error_code'],
 scenarios:{joint:{executor:'constant-arrival-rate',rate:5000,timeUnit:'1s',duration:'30s',preAllocatedVUs:vus,maxVUs:vus,gracefulStop:'3s'}}};
http.setResponseCallback(http.expectedStatuses(202,429,503));
export function setup(){
 if(t0<Date.now()-500||t0-Date.now()>80000)throw new Error('Missed/invalid common T0: no load sent');
 if(t0>Date.now())sleep((t0-Date.now())/1000);
}
export default function(){
 const seq=exec.scenario.iterationInTest;
 if(Date.now()>=t0+30000){skipped.add(1);return;}
 const buyer=buyers[seq%5000],rid=__ENV.V3_RUN+'-b'+(seq%5000);
 started.add(1);firstStart.add(Date.now());if(seq<5000)first.add(1);else repeated.add(1);
 const begin=Date.now();
 const response=http.post('http://127.0.0.1:18083/api/tickets/reservations',JSON.stringify({requestId:rid,userId:buyer.userId,
  eventSessionId:buyer.eventSessionId,ticketSkuId:buyer.ticketSkuId,quantity:1,deviceId:buyer.deviceId}),
  {timeout:'2s',responseType:'text',redirects:0,tags:{name:'V3_JOINT_POST'},
   headers:{'Content-Type':'application/json',authorization:buyer.token,'X-Forwarded-For':buyer.ip,'X-V3-Seq':__ENV.V3_RUN+'-'+seq}});
 const end=Date.now();elapsed.add(end-begin);service.add(response.timings.duration);
 if(response.status>0){completed.add(1);if(end<=t0+30000)windowComplete.add(1);else tail.add(1);}
 if(response.error_code){
  network.add(1,{code:String(response.error_code)});if(response.error_code===1050)timeouts.add(1);
  if(!errors[response.error_code]){errors[response.error_code]=true;console.error(JSON.stringify({kind:'transport_example',code:response.error_code,error:response.error}));}
 }
 if(response.status===401||response.status===403||response.status===400||response.status===409){other.add(1);exec.test.abort('Invalid identity/parameters/idempotency fixture; do not report invalid fast refusals as capacity');}
 let data=null;try{data=response.json('data');}catch(_){}
 if(response.status===202){
  good202.add(1);if(!data||data.receiptSemantics!=='V3_PROCESSING')exec.test.abort('Not a real V3 response');
  if(data.newSubmission===true)fresh.add(1);else known.add(1);
 }else if(response.status===429){busy.add(1);sleep(.05);} // bounded VU backoff, no extra unscheduled POST
 else if(response.status>=500){system.add(1);sleep(.05);}else other.add(1);
}
export function handleSummary(data){return {[__ENV.V3_SUMMARY]:JSON.stringify({t0_epoch_ms:t0,seconds:30,target_rate:5000,target_posts:150000,
 vus,buyers:5000,stable_request_id_per_buyer:true,first_wave:'first 5000 actually-started iterations contain no repeats',
 backoff_ms:50,request_timeout_seconds:2,no_client_gets:true,
 note:'A queue-timeout intent is never revived; this scenario does not create a new intent to force all buyers to succeed. Dropped iterations are not server refusals. HTTP 202 is not HELD.',k6:data},null,2)};}
