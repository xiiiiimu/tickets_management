"""Plan only. No process spawning, network, Docker, resource creation or HTTP traffic."""
import json,pathlib
root=pathlib.Path(__file__).resolve().parents[1]
out=root/'performance/results/controlled-dispatch-plan.json'
sequence=['A','B','B','A','A','B']
plan=dict(approved_to_execute=False,execution_performed=False,requires_user_confirmation=True,requires_batch_aware_diagnostic_preflight=True,branch='codex/reservation-dispatch-controlled',rounds=[],resources=dict(concurrent_java_services=1,http_client_concurrency=10,jvm='JDK21.0.11 -Xms128m -Xmx384m -XX:+UseSerialGC -Duser.timezone=UTC',unchanged=dict(ingress_workers=4,ingress_capacity=256,persistent_capacity=512,global_active=4,sale_workers=4,sale_queue=0,hikari_max=10,post_rate=1000),extra_candidate_control_threads=1,min_host_available_mib_before_start=1024,stop_host_available_mib=256,estimated_total_minutes=[10,20]),docker_commands_allowed=False,performance_driver='Must complete localhost provisioning + batch-aware observation preflight after approval; do not use legacy Docker/single-claim driver unchanged')
for i,mode in enumerate(sequence,1):
    enabled=mode=='B'
    plan['rounds'].append(dict(round=i,mode=mode,unique_requests=50,max_sending_concurrency=10,idempotent_retries=4,stock=1000,buckets=16,new_jvm=True,ticket_warmup_requests=0,new_owned_schema=True,new_owned_redis_db=True,properties={'ticketing.reservations-v2.dispatch.'+key:enabled for key in ['isolated','batch-claim','wakeup']}))
out.parent.mkdir(exist_ok=True);out.write_text(json.dumps(plan,ensure_ascii=False,indent=2),encoding='utf-8')
print('Plan only; no traffic or resource operations. '+str(out))
