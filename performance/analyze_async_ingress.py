"""Offline analysis only. Never connects to a service or changes the raw benchmark JSON."""
import argparse, collections, json, pathlib, re

def wire(path):
    result={}
    for line in path.read_text(encoding='utf-8').splitlines():
        if line and not line.startswith('#'):
            try:key,value=line.rsplit(' ',1);result[key]=float(value)
            except ValueError:pass
    return result

def percentile(values):
    import math
    data=sorted(values)
    return {f'p{p}':data[math.ceil(len(data)*p/100)-1] for p in (50,95,99)} if data else {}

def analyze(directory):
    output=[]
    for label in ('smoke','burst','sustained'):
        path=directory/(label+'.json')
        if not path.exists():continue
        result=json.loads(path.read_text(encoding='utf-8'));rows=result['requests'];samples=result['samples']+result['drain_samples']
        before=wire(directory/(label+'-before.prom'));after=wire(directory/(label+'-after.prom'))
        def delta(name):return sum(v-before.get(k,0) for k,v in after.items() if k==name or k.startswith(name+'{'))
        begin=min(r['begin'] for r in rows);entry_end=max(r['end'] for r in rows)
        committed_in_post_window=sum(j['state']=='RESERVED' and begin<=j['completed']<=entry_end for j in result['jobs'])
        rejected={key:after[key]-before.get(key,0) for key in after if key.startswith('reservation_ingress_rejected_total') and after[key]!=before.get(key,0)}
        measurements=dict(label=label,post_total=result['post_requests'],post_seconds=result['post_seconds'],total_post_qps=result['total_post_qps'],submitted=result['submitted'],submitted_qps=result['submitted_qps'],post_latency_all_ms=result['http_ms'],post_latency_submitted_ms=percentile([r['ms'] for r in rows if r['submitted']]),http_status=result['http_status'],http_rejection_fraction=sum(r['code']==429 for r in rows)/len(rows),http_system_error_count=sum(r['code']==0 or r['code']>=500 for r in rows),ingress_executor_submitted_counter=delta('reservation_ingress_submitted_total'),ingress_rejection_counter=rejected,prestock_expired=delta('reservation_ingress_expired_total'),background_rejected=delta('reservation_ingress_background_rejected_total'),background_unavailable=delta('reservation_ingress_background_unavailable_total'),reserved=result['reserved'],cohort_reliable_tps=result['reliable_tps'],reserved_in_post_window=committed_in_post_window,reliable_tps_in_post_window=committed_in_post_window/result['post_seconds'],click_to_reserved_ms=result['click_to_reserved_ms'],risk_completed=sum(result['risk_decisions'].values()),risk_decisions=result['risk_decisions'],risk_cohort_observation_tps=sum(result['risk_decisions'].values())/result['total_observation_seconds'],max_sampled_volatile_queue=max(x.get('queue',x.get('volatile_queue',0)) for x in samples),max_sampled_oldest_volatile_wait_seconds=max(x.get('oldest_wait_seconds',0) for x in samples),max_sampled_durable_pending=max(x.get('durable_pending',0) for x in samples),drain_seconds=result['drain_seconds'],unconfirmed=len(result['unconfirmed_request_ids']),stock_conserved=result['stock']['conserved'],duplicate_reservations=result['stock']['duplicate_reservations'],terminal_states=result['terminal_states'],operations_mean_ms={})
        for key in after:
            if key.startswith('ticketing_operation_duration_seconds_count{') and 'outcome="success"' in key:
                count=after[key]-before.get(key,0)
                if count:
                    total=key.replace('_seconds_count{','_seconds_sum{');operation=re.search(r'operation="([^"]+)"',key).group(1)
                    measurements['operations_mean_ms'][operation]=1000*(after.get(total,0)-before.get(total,0))/count
        output.append(measurements)
    target=directory/'analysis.json';target.write_text(json.dumps(output,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(output,ensure_ascii=True,indent=2));return output

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('directory',type=pathlib.Path);args=parser.parse_args();analyze(args.directory)
