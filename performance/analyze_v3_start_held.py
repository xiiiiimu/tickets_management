"""Offline analysis of saved V3 joint metrics. No HTTP, Redis, SQL or load generation."""
import argparse, json
from pathlib import Path
from summarize_v3_joint import readprom

def analyze(directory):
    p=Path(directory);before=readprom(p/'metrics-before.prom');after=readprom(p/'metrics-recovery.prom')
    def value(rows,name,tags):return sum(v for n,t,v in rows if n==name and all(t.get(k)==x for k,x in tags.items()))
    def delta(name,**tags):return value(after,name,tags)-value(before,name,tags)
    def timer(name,**tags):
        n=delta(name+'_count',**tags);seconds=delta(name+'_sum',**tags)
        return dict(count=n,sum_seconds=seconds,mean_ms=seconds*1000/n if n else None,max_snapshot_ms=value(after,name+'_max',tags)*1000)
    def bucket_bounds(name,quantile):
        n=delta(name+'_count');bins={}
        for sign,rows in [(-1,before),(1,after)]:
            for metric,t,v in rows:
                if metric==name+'_bucket':bins[float(t['le'])]=bins.get(float(t['le']),0)+sign*v
        previous=0
        for upper,count in sorted(bins.items()):
            if count>=quantile*n:return dict(lower_ms=previous*1000,upper_ms=upper*1000)
            previous=upper
    phases={key:timer('attempt_path_seconds',phase=key,outcome='ok') for key in ['v3_t0_begin','v3_process','plan_lookup','risk_classify','decided','prepare','reserve_and_held','v3_settle']}
    phases['ordered_transaction']=timer('attempt_ordered_lua_transaction_seconds')
    phases['final_held_transaction']=timer('reservation_v2_transaction_seconds')
    sub=['plan_lookup','risk_classify','decided','prepare','reserve_and_held']
    remainder=phases['v3_process']['mean_ms']-sum(phases[x]['mean_ms'] for x in sub)
    hit=delta('sale_plan_cache_total',outcome='hit');miss=delta('sale_plan_cache_total',outcome='miss')
    data=dict(phases=phases,process_unattributed_mean_ms=remainder,
        hikari_acquire=timer('hikaricp_connections_acquire_seconds'),hikari_usage=timer('hikaricp_connections_usage_seconds'),
        hikari_acquire_quantile_bucket_bounds={str(q):bucket_bounds('hikaricp_connections_acquire_seconds',q) for q in [.5,.95,.99]},
        cache=dict(hits=hit,misses=miss,hit_ratio=hit/(hit+miss)),
        prepare_transaction={phase:timer('attempt_transaction_duration_seconds',operation='prepare',outcome='success',phase=phase) for phase in ['begin','body','completion','total']},
        gc=timer('jvm_gc_pause_seconds'),
        scope_notes=['Hikari covers the whole app and background work between the two scrapes, not specifically starter.begin.',
            'Wall-clock phase timers include waiting; they are not CPU time.',
            'reserve_and_held includes ordered_transaction and final_held_transaction; do not add inclusive durations twice.',
            'No per-request SQL/connection/commit attribution exists for starter.begin in this run.',
            'Histogram bounds are not exact quantiles; max is a rolling snapshot, not a subtractable counter.'])
    target=p/'start-held-offline-analysis.json';target.write_text(json.dumps(data,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(data,ensure_ascii=False,indent=2));return data

if __name__=='__main__':
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('directory',type=Path);a=ap.parse_args();analyze(a.directory)
