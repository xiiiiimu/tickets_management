"""WSL Linux-side preflight; explicit direct-IP access, no proxy or load generation."""
import json,sys,time,urllib.request
target=sys.argv[1];rows=[]
client=urllib.request.build_opener(urllib.request.ProxyHandler({}))
for i in range(10):
    start=time.perf_counter();row=dict(i=i+1,target=target)
    try:
        with client.open(target,timeout=1) as response:
            body=response.read(64);row.update(status=response.status,body=body.decode('utf-8',errors='replace'),ok=response.status==200 and body==b'OK')
    except Exception as error:row.update(ok=False,error=str(error))
    row['ms']=1000*(time.perf_counter()-start);rows.append(row)
print(json.dumps(dict(target=target,proxy_used=False,total=len(rows),successful=sum(r['ok'] for r in rows),requests=rows)))
