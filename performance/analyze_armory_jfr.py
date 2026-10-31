"""Extract diagnostic JFR events; sums are overlapping thread time, not wall time."""
import collections
import json
import pathlib
import subprocess
import sys
from datetime import datetime

root=pathlib.Path(sys.argv[1])
jfr=pathlib.Path(r'C:\Program Files\Java\jdk-21.0.11\bin\jfr.exe')
report={}
def event_stream(path):
    chunks=[];inside=False
    with path.open(encoding='utf-8') as source:
        for line in source:
            if not inside:
                if line.startswith('    "events": [{'):inside=True;chunks=['{\n']
            elif line.startswith('    }, {'):
                yield json.loads(''.join(chunks)+'}');chunks=['{\n']
            elif line.startswith('    }]'):
                yield json.loads(''.join(chunks)+'}');return
            else:chunks.append(line)
def live_events(process):
    chunks=[];inside=False
    for line in process.stdout:
        if not inside:
            if line.startswith('    "events": [{'):inside=True;chunks=['{\n']
        elif line.startswith('    }, {'):
            yield json.loads(''.join(chunks)+'}');chunks=['{\n']
        elif line.startswith('    }]'):
            yield json.loads(''.join(chunks)+'}');break
        else:chunks.append(line)
    process.stdout.close()
    if process.wait()!=0:raise RuntimeError('jfr print failed')
for recording in root.glob('*.jfr'):
    label=recording.stem
    window=json.loads((root/(label+'-requests.json')).read_text(encoding='utf-8'))
    events_path=root/(label+'-jfr-events.json')
    if not events_path.exists():
        with events_path.open('w',encoding='utf-8') as destination:
            subprocess.run([str(jfr),'print','--json','--stack-depth','64','--events','jdk.ThreadPark,jdk.SocketRead,jdk.ExecutionSample',str(recording)],stdout=destination,check=True)
    groups=collections.defaultdict(list);cpu=collections.Counter();examples={};depth=0
    source=event_stream(events_path)
    if '--refresh' in sys.argv:
        process=subprocess.Popen([str(jfr),'print','--json','--stack-depth','64','--events','jdk.ThreadPark,jdk.SocketRead,jdk.ExecutionSample',str(recording)],stdout=subprocess.PIPE,text=True,encoding='utf-8',errors='replace')
        source=live_events(process)
    for event in source:
        v=event['values'];ts=datetime.fromisoformat(v['startTime'].replace('Z','+00:00')).timestamp()
        if not window['start']<=ts<=window['end']:continue
        thread=v.get('eventThread') or v.get('sampledThread') or {}
        if '18083-exec' not in thread.get('javaName',''):continue
        frames=(v.get('stackTrace') or {}).get('frames',[])
        names=[f['method']['type']['name']+'.'+f['method']['name'] for f in frames]
        depth=max(depth,len(names))
        if event['type']=='jdk.ExecutionSample':
            app=next((n for n in names if n.startswith(('com/ticketing','com.ticketing'))),None)
            if app:cpu[app]+=1
            continue
        duration=v['duration']
        seconds=float(duration[2:-1]) if isinstance(duration,str) and duration.startswith('PT') and duration.endswith('S') else float(duration)
        if event['type']=='jdk.ThreadPark':
            key='hikari_park' if any('hikari' in n.lower() for n in names) else 'lettuce_park' if any('lettuce' in n.lower() for n in names) else 'other_park'
        else:
            key='mysql_socket_read' if v.get('port')==13306 else 'redis_socket_read' if v.get('port')==16379 else 'other_socket_read'
        groups[key].append(seconds)
        if key not in examples:examples[key]=names
    report[label]=dict(max_exported_stack_depth=depth,events={k:dict(count=len(v),sum_seconds=sum(v),max_seconds=max(v),observed_ms_per_request=sum(v)*1000/160) for k,v in groups.items()},example_stacks=examples,cpu_application_samples=cpu.most_common(15))
(root/'jfr-analysis.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
print(json.dumps(report,indent=2))
