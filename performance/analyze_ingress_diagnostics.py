"""Offline, no service calls. Inclusive Redis/auth timings must not be added twice."""
import argparse,json,pathlib,re,statistics,math

def percentiles(values):
    a=sorted(values)
    return {f'p{p}':a[math.ceil(len(a)*p/100)-1] for p in (50,95,99)} if a else {}
def cores(before,after,cpu='cpu_nanos',clock='nano'):
    return (after[cpu]-before[cpu])/(after[clock]-before[clock]) if before and after and after[clock]>before[clock] else None
def redis_commands(raw):
    return {m.group(1):{k:float(v) for k,v in re.findall(r'(?:^|,)(calls|usec)=([0-9.]+)',m.group(2))} for m in re.finditer(r'^cmdstat_([^:]+):(.+)$',raw,re.M)}
def analyze(folder):
    output=[]
    for path in sorted(folder.glob('*isolated*.json')):
        d=json.loads(path.read_text(encoding='utf-8'));rows=d['requests'];a=d.get('diagnostic_after');samples=[s for s in d.get('resource_samples',[]) if 'server' in s]
        r=dict(file=path.name,concurrency=d['concurrency'],target_rate=d['offered_rate'],counts=d['counts'],http_qps=d['http_qps'],submitted_qps=d['submitted_qps'],consumed=d['consumed'],http_ms=d['http_ms'],legacy_before_controller_mean_ms=d['before_controller_mean_ms'],controller_mean_ms=d['controller_mean_ms'],producer_late_skipped=d['producer_late_skipped'],client_skipped=d['client_skipped'],new_tcp_connections=d['connections'],client_cpu_cores=cores(d['client_before'],d['client_after']),java_cpu_cores=cores(d.get('java_native_cpu_before'),d.get('java_native_cpu_after')),client_rss_mib=d['client_after'].get('working_set_bytes',0)/2**20,host_available_gib=d['client_after'].get('host_memory_available',0)/2**30,host_memory_load_percent=d['client_after'].get('host_memory_load_percent'))
        before=d['client_before'];after=d['client_after']
        if 'host_cpu_100ns' in before:r['host_cpu_busy_percent']=100*(1-(after['host_idle_100ns']-before['host_idle_100ns'])/(after['host_cpu_100ns']-before['host_cpu_100ns']))
        completed_at_stop=sum(x['response_completed'] and x['response_done_offset_ms']<=d['duration_config_seconds']*1000 for x in rows)
        writes_at_stop=sum(x['write_completed'] and x['write_done_offset_ms']<=d['duration_config_seconds']*1000 for x in rows)
        r['load_stop']=dict(attempt_inflight=len(rows)-completed_at_stop,writing_not_done=len(rows)-writes_at_stop,sent_waiting_response=writes_at_stop-completed_at_stop)
        if a:
            traces=a['traces'];r['trace_count']=len(traces);r['omitted_traces']=a['omitted_samples'];r['server_completed']=a['completed'];r['server_cohort_seconds']=(max(t['server_end_nano'] for t in traces)-min(t['server_start_nano'] for t in traces))/1e9 if not a['omitted_samples'] else None;r['server_cohort_qps']=a['completed']/r['server_cohort_seconds'] if r['server_cohort_seconds'] else None
            exclusive=['filters_forward_outer_to_inner','dispatcher_before_interceptors','interceptors_pre_envelope','adapter_before_binding','binding_envelope','binding_to_controller','controller_method','response_and_mvc_completion','filters_unwind_inner_to_outer']
            r['exclusive_timeline_max_accounting_error_ns']=max(abs(t['phases_ns']['server_observed_filter_span']-sum(t['phases_ns'].get(k,0) for k in exclusive)) for t in traces)
            phases={key:dict(mean_ms=tot['nanos']/max(1,tot['count'])/1e6,count=tot['count'],**percentiles([t['phases_ns'][key]/1e6 for t in traces if key in t['phases_ns']])) for key,tot in a['phases'].items()};r['server_phases']=phases
            by_id={t['requestId']:t for t in traces};residual=[row['ms']-by_id[row['requestId']]['phases_ns']['server_observed_filter_span']/1e6 for row in rows if row['requestId'] in by_id]
            r['rtt_minus_server_span_ms']=dict(mean=statistics.mean(residual),minimum=min(residual),maximum=max(residual),**percentiles(residual))
            r['client_phases_ms']={key:dict(mean=statistics.mean([row[key] for row in rows if key in row]),**percentiles([row[key] for row in rows if key in row])) for key in ('producer_delay_ms','client_queue_wait_ms','client_connect_ms','client_request_write_ms','client_wait_headers_ms','client_read_body_ms','client_decode_ms')}
            r['connect_mean_per_new_connection_ms']=sum(x.get('client_connect_ms',0) for x in rows)/max(1,d['connections'])
            if len(samples)>1:r['java_cpu_cores']=cores(samples[0]['server']['jvm'],samples[-1]['server']['jvm'],'process_cpu_nanos');r['java_cpu_sample_seconds']=(samples[-1]['server']['jvm']['nano']-samples[0]['server']['jvm']['nano'])/1e9
            r['resource_samples']=len(samples);r['resource_errors']=[s['error'] for s in d.get('resource_samples',[]) if 'error' in s]
            r['tomcat_sample_max']={key:max(s['server']['tomcat'].get(key,0) for s in samples) for key in ('active','threads','max_threads','executor_queue','connections')} if samples else {}
            r['client_sample_max_inflight']=max((s['flow']['max_inflight'] for s in samples),default=None);r['client_sample_max_queue']=max((s['flow'].get('client_queue_depth',0) for s in samples),default=None)
            r['snapshot_ms']=[s['server']['snapshot_nanos']/1e6 for s in samples];r['heap_sample_max_mib']=max((s['server']['jvm']['heap_used']/2**20 for s in samples),default=None)
            g0={g['name']:g for g in d['diagnostic_before']['jvm']['gc']};r['gc_delta']={g['name']:dict(collections=g['count']-g0[g['name']]['count'],time_ms=g['time_ms']-g0[g['name']]['time_ms']) for g in a['jvm']['gc']}
            rb=redis_commands(d['redis_info_before']);ra=redis_commands(d['redis_info_after']);r['redis_server_command_cpu']={name:dict(calls=ra[name]['calls']-rb[name]['calls'],mean_usec=(ra[name]['usec']-rb[name]['usec'])/(ra[name]['calls']-rb[name]['calls'])) for name in ('hgetall','pexpire') if name in rb and name in ra and ra[name]['calls']>rb[name]['calls']}
            r['spring_security_filter_chain']=a['spring_security_filter_chain'];r['servlet_filters']=a['servlet_filters'];r['same_client_server_host']=d['client_before']['hostname'].lower()==a['jvm']['host'].lower()
        output.append(r)
    (folder/'diagnostic-analysis.json').write_text(json.dumps(output,ensure_ascii=False,indent=2)+'\n',encoding='utf-8');return output

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('folder',type=pathlib.Path);args=parser.parse_args();result=analyze(args.folder)
    print(json.dumps(result,ensure_ascii=True,indent=2))
