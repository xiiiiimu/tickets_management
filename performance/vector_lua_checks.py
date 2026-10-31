"""Bounded native Lua correctness checks on an atomically claimed empty test Redis DB."""
from pathlib import Path
import os,sys,json,time,uuid,concurrent.futures
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT/'performance'))
from controlled_dispatch_checks import RedisAdmin
import controlled_dispatch_compare as c
out=ROOT/'performance/results'/('vector-lua-'+time.strftime('%Y%m%d_%H%M%S'));out.mkdir();save=lambda n,v:(out/n).write_text(json.dumps(v,indent=2),encoding='utf-8')
gate=c.memory_gate();save('resource-gate.json',gate);assert gate['ok']
env=dict(x.split('=',1) for x in (ROOT/'docker/credit_risk/.env').read_text(encoding='utf-8-sig').splitlines() if '=' in x and not x.startswith('#'))
owner=uuid.uuid4().hex;client=RedisAdmin(env['CREDIT_RISK_REDIS_PASSWORD']);db=None;checks=[]
scripts={n:(ROOT/'ticket-risk-platform/src/main/resources/lua'/f'{n}.lua').read_text(encoding='utf-8') for n in ['reserve_ticket_vector','release_ticket_vector','mark_sold_out_attempt']}
def keys(user,res):return [p+'config']+[p+f'stock:bucket:{i}' for i in range(16)]+[p+'user:'+str(user),p+'reservation:'+res,p+'pending',p+'assembly-fence']
def call(n,user,res,args,conn=None):return (conn or client).command('EVAL',scripts[n],21,*keys(user,res),*map(str,args))
def stock():return sum(int(client.command('GET',p+f'stock:bucket:{i}')) for i in range(16))
def reserve(res,q=2,user=1,conn=None):return call('reserve_ticket_vector',user,res,[res,q,res,0,user,0,1,int(time.time()*1000)+600000],conn)
def release(res,user=1):return call('release_ticket_vector',user,res,['',res,'','RELEASE'])
try:
 for n in range(1,256):
  client.command('SELECT',n)
  if client.command('EVAL',"if redis.call('DBSIZE')==0 then redis.call('SET',KEYS[1],ARGV[1]);return 1 else return 0 end",1,'credit-risk:controlled-owner',owner)==1:db=n;break
 assert db is not None;save('ownership.json',dict(owner=owner,redis_db=db));p='ticket:{vector-'+owner+'}:';now=int(time.time()*1000)
 client.command('HSET',p+'config','status','ON_SALE','limit',100,'singleLimit',20,'reservationProtocol',3,'assemblyVersion',1,'start',now-1000,'end',now+600000);client.command('SET',p+'assembly-fence',1)
 for i in range(16):client.command('SET',p+f'stock:bucket:{i}',1)
 assert reserve('fragment',5)==1 and stock()==11;vector=json.loads(client.command('HGET',p+'reservation:fragment','allocations'));assert sum(vector)==5 and len([x for x in vector if x])==5;assert reserve('fragment',5)==1 and stock()==11;assert release('fragment')==1 and release('fragment')==0 and stock()==16;checks.append('fragment/idempotent reserve and release')
 assert reserve('insufficient',17)==-12 and stock()==16;checks.append('insufficient total has no partial decrement')
 client.command('DEL',p+'stock:bucket:15');assert reserve('missing')==-8;assert int(client.command('GET',p+'stock:bucket:0'))==1;client.command('SET',p+'stock:bucket:15',1);checks.append('missing bucket rejects without refill')
 def worker(i):
  conn=RedisAdmin(env['CREDIT_RISK_REDIS_PASSWORD'])
  try:conn.command('SELECT',db);return i,reserve('parallel-'+str(i),2,i+10,conn)
  finally:conn.close()
 with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:results=list(pool.map(worker,range(16)))
 winners=[i for i,result in results if result==1];assert len(winners)==8 and all(v in (1,-3) for _,v in results) and stock()==0;checks.append('8-way concurrent atomic vectors cannot oversell')
 assert call('mark_sold_out_attempt',999,'waiting',['waiting','waiting',1,int(time.time()*1000)+5000])==1
 for i in winners:assert release('parallel-'+str(i),i+10)==1
 assert stock()==16 and reserve('waiting',1,999)==-11 and stock()==16;checks.append('zero-stock tombstone cannot revive after release')
 assert reserve('fresh',2,999)==1;assert release('fresh',999)==1
 client.command('SET',p+'pending','wrong-type');assert reserve('pending-corrupt')==-8 and stock()==16;client.command('DEL',p+'pending');checks.append('wrong pending type checked before mutation')
 save('result.json',dict(passed=True,checks=checks,parallel_results=results,final_stock=stock(),docker_operations=0))
finally:
 if db is not None:
  client.command('SELECT',db);assert client.command('GET','credit-risk:controlled-owner')==owner
  all_keys=client.command('KEYS','*');assert all(k=='credit-risk:controlled-owner' or k.startswith('ticket:{vector-'+owner+'}:') for k in all_keys)
  if all_keys:client.command('UNLINK',*all_keys)
  save('cleanup.json',dict(keys_left=client.command('DBSIZE'),owner_verified=True))
 client.close()
print('RESULT_DIRECTORY='+str(out));print(json.dumps(checks))
