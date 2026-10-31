"""Linux-only owner of one bounded k6 child. Writes Linux /proc resource samples."""
import json,os,pathlib,resource,subprocess,sys,threading,time
folder=pathlib.Path(sys.argv[1]);url=sys.argv[2];rate=int(sys.argv[3]);vus=int(sys.argv[4])
assert (rate,vus) in [(1000,128),(3000,256),(5000,256),(5000,512)]
root=pathlib.Path(__file__).resolve().parents[2];binary=root/'.tools/k6-v2.3.0-linux-amd64/k6'
env=os.environ.copy();env.update(RATE=str(rate),VUS=str(vus),KIND='tiny',URL=url,SUMMARY_PATH=str(folder/'k6-summary.json'),K6_NO_USAGE_REPORT='true',K6_NO_COLOR='true')
for key in ('http_proxy','https_proxy','HTTP_PROXY','HTTPS_PROXY','ALL_PROXY','all_proxy'):env.pop(key,None)
env['NO_PROXY']='*';command=[str(binary),'run','--quiet','--address','127.0.0.1:0',str(root/'performance/k6/arrival.js')]
samples=[];guard=[];stop=threading.Event();proc=None;usage=resource.getrusage(resource.RUSAGE_CHILDREN);start=time.monotonic()
def memory():return {line.split(':')[0]:int(line.split()[1])*1024 for line in pathlib.Path('/proc/meminfo').read_text().splitlines() if line.startswith(('MemTotal:','MemAvailable:'))}
def observe():
    high=0
    while not stop.wait(.5):
        try:
            stat=pathlib.Path('/proc/'+str(proc.pid)+'/stat').read_text().rsplit(')',1)[1].split();mem=memory();rss=int(stat[21])*os.sysconf('SC_PAGE_SIZE');samples.append(dict(epoch_ms=time.time_ns()/1e6,cpu_seconds=(int(stat[11])+int(stat[12]))/os.sysconf('SC_CLK_TCK'),rss_bytes=rss,linux_memory=mem))
            high=high+1 if mem['MemAvailable']<256*1024*1024 or rss>768*1024*1024 else 0
            if high>=3 or (folder/'STOP').exists():guard.append('resource/Windows stop guard');proc.terminate();return
        except (FileNotFoundError,ProcessLookupError):return
try:
    with (folder/'console.log').open('w') as log:
        proc=subprocess.Popen(command,env=env,stdout=log,stderr=log,start_new_session=True)
        (folder/'owned-linux-pid.json').write_text(json.dumps(dict(pid=proc.pid,wrapper_pid=os.getpid(),command=command)))
        watcher=threading.Thread(target=observe,daemon=True);watcher.start()
        try:proc.wait(timeout=20)
        except subprocess.TimeoutExpired:guard.append('20s deadline');proc.terminate()
        finally:
            if proc.poll() is None:
                try:proc.wait(2)
                except subprocess.TimeoutExpired:proc.kill();proc.wait(2)
            stop.set();watcher.join(2)
finally:
    after=resource.getrusage(resource.RUSAGE_CHILDREN);elapsed=time.monotonic()-start
    (folder/'linux-resources.json').write_text(json.dumps(dict(command=command,rate=rate,vus=vus,elapsed_seconds=elapsed,cpu_cores=(after.ru_utime+after.ru_stime-usage.ru_utime-usage.ru_stime)/elapsed,max_rss_bytes=after.ru_maxrss*1024,samples=samples,guard=guard,exit_code=proc.returncode if proc else None,child_exited=not proc or proc.poll() is not None),indent=2))
if not proc or proc.returncode:sys.exit(1)
