"""Analyze retained raw rounds; never infer success from HTTP 202 or mutate fixtures."""
import argparse,collections,json,pathlib,statistics,re

def metrics(path):
    result={}
    for line in path.read_text(encoding='utf-8').splitlines():
        if not line or line.startswith('#'):continue
        parts=line.rsplit(' ',1)
        try:result[parts[0]]=float(parts[1])
        except (ValueError,IndexError):pass
    return result

def analyze(directory):
    rounds=[]
    for path in directory.glob('*.json'):
        row=json.loads(path.read_text(encoding='utf-8'))
        if not isinstance(row,dict) or 'reliable_tps' not in row or row.get('warm'):continue
        prefix=row['label'];before=metrics(directory/(prefix+'-before.prom'))
        end_file=directory/(prefix+'-cohort-end.prom') if row.get('observation')=='counter' else directory/(prefix+'-after.prom')
        after=metrics(end_file)
        means={}
        for name,value in after.items():
            if not name.startswith('ticketing_operation_duration_seconds_sum{'):continue
            count_key=name.replace('_seconds_sum{','_seconds_count{')
            count=after.get(count_key,0)-before.get(count_key,0)
            if count:
                operation=re.search(r'operation="([^"]+)"',name).group(1)
                outcome=re.search(r'outcome="([^"]+)"',name).group(1)
                means[operation+'/'+outcome]=dict(count=count,mean_ms=1000*(value-before.get(name,0))/count)
        sql0={x['sql']:x for x in row['sql_before']};sql=[]
        for item in (row.get('sql_after_core') or row['sql_after']):
            previous=sql0.get(item['sql'],{});delta={k:item[k]-previous.get(k,0) for k in ('count','ms','lock_ms','rows')}
            if delta['count']>0:sql.append(dict(sql=item['sql'],**delta,mean_ms=delta['ms']/delta['count']))
        acquire={}
        for name,value in after.items():
            if name.startswith('hikaricp_connections_acquire_seconds_sum{'):
                key=name.replace('_seconds_sum{','_seconds_count{');n=after.get(key,0)-before.get(key,0)
                if n:acquire[name]=dict(count=n,mean_ms=1000*(value-before.get(name,0))/n)
        status_count=row.get('status_queries',sum(x['count'] for x in sql if re.search(r'FROM\s+tb_reservation_job\s+WHERE\s+reservation_id',x['sql'].replace('`',''),re.I)))
        status_seconds=row.get('status_query_seconds',max(.000001,row['seconds']-160/row['http_qps']))
        rounds.append(dict(label=prefix,kind=row['kind'],mixed=row['mixed'],observation=row.get('observation','poll'),http_qps=row['http_qps'],status_queries=status_count,status_query_qps=status_count/status_seconds,business_http_qps=row.get('business_http_qps',(160+status_count)/row['seconds']),reliable_tps=row['reliable_tps'],successes=row['successes'],queued=row['queued'],http_latency_ms=row['http_latency_ms'],completion_latency_ms=row['completion_latency_ms'],poll_errors=sum(len(x.get('poll_errors',[])) for x in row['requests']),operations=means,hikari_acquisition=acquire,sql_count=sum(x['count'] for x in sql),sql_top=sorted(sql,key=lambda x:x['ms'],reverse=True)[:20],inventory=row['inventory'],fulfillment=row.get('fulfillment')))
    groups=collections.defaultdict(list)
    for row in rounds:groups[row['kind']+('/mixed' if row['mixed'] else '/valid')].append(row)
    comparison={}
    for name,items in groups.items():
        comparison[name]=dict(rounds=len(items),complete_three_rounds=len(items)>=3,median_http_qps=statistics.median(x['http_qps'] for x in items),median_status_query_qps=statistics.median(x['status_query_qps'] for x in items),status_queries=sum(x['status_queries'] for x in items),median_business_http_qps=statistics.median(x['business_http_qps'] for x in items),median_reliable_tps=statistics.median(x['reliable_tps'] for x in items),median_http_ms={p:statistics.median(x['http_latency_ms'][p] for x in items) for p in ('p50','p95','p99')},median_confirmation_ms={p:statistics.median(x['completion_latency_ms'][p] for x in items) for p in ('p50','p95','p99')},successful_orders=sum(x['successes'] for x in items),poll_errors=sum(x['poll_errors'] for x in items))
    output=dict(directory=str(directory),comparison=comparison,rounds=rounds)
    (directory/'analysis.json').write_text(json.dumps(output,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(comparison,ensure_ascii=False,indent=2))

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('directory',type=pathlib.Path);args=parser.parse_args();analyze(args.directory)
