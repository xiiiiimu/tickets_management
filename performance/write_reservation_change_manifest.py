"""Compare with the exact pre-task working tree, not HEAD (which includes unrelated user work)."""
import difflib,hashlib,pathlib,zipfile
ROOT=pathlib.Path(__file__).resolve().parents[1]
SNAP=ROOT/'.tmp/architecture-rebuild-20261028'
with zipfile.ZipFile(SNAP/'source-before.zip') as archive:
    before={name.replace('\\','/'):archive.read(name) for name in archive.namelist() if not name.endswith('/')}
before['ticket-risk-web/src/App.tsx']=(SNAP/'App-before.tsx').read_bytes()
paths=list((ROOT/'ticket-risk-platform/src').rglob('*'))+[ROOT/'ticket-risk-platform/pom.xml',ROOT/'ticket-risk-web/src/App.tsx']
records=[];patch=[]
def phase(name):
    if '/sale/' in name or 'SaleManifestCache' in name or 'ArmoryRuntimeGate' in name:return '2 Armory'
    if any(v in name for v in ('Gateway','PaymentGateway','ReservationPaymentPolicy','/payment/','OrderStateMachine','OrderService.java','OrderController','V107','V108')):return '5 payment'
    if any(v in name for v in ('/notification/','V109','TicketEventConsumer','Outbox','Workflow','App.tsx')):return '6 fulfillment'
    if any(v in name for v in ('RiskHistory','RiskFeature','JdbcPaymentRepository','JdbcOrderRepository','RiskProfile','V105','risk_sliding_window')):return '3 features / 4 fence'
    if name.endswith('schema.sql'):return '3-6 additive schema'
    if 'BusinessMetrics' in name:return '7 metrics'
    return '4 dispatch / reliability'
for path in sorted(set(paths)):
    if not path.is_file():continue
    name=path.relative_to(ROOT).as_posix();current=path.read_bytes();old=before.get(name,b'')
    if current==old:continue
    records.append((phase(name),'modified' if name in before else 'added',name,hashlib.sha256(current).hexdigest()))
    patch.extend(difflib.unified_diff(old.decode('utf-8-sig').splitlines(keepends=True),current.decode('utf-8-sig').splitlines(keepends=True),fromfile='a/'+name if name in before else '/dev/null',tofile='b/'+name))
missing=[name for name in before if not (ROOT/name).exists()]
if missing:raise RuntimeError('Preexisting files missing; inspect without changing anything: '+str(missing))
(SNAP/'implementation-only.patch').write_text(''.join(patch),encoding='utf-8',newline='')
lines=['# 本批实际源码修改清单','',
 '比较基准是本任务开始前的完整工作树源码快照，不是 Git HEAD；因此不会把此前已存在的 Armory、库存桶、P0/P1 等成果算成本批新建文件。',
 '', '| 阶段 | 变更 | 文件 | SHA256 前12位 |','|---|---|---|---|']
for group,kind,name,digest in sorted(records):lines.append(f'| {group} | {kind} | `{name}` | `{digest[:12]}` |')
lines+=['','配套新增：`performance/run-reservation-v2-tests.ps1`、`performance/run-reservation-pipeline-benchmark.ps1`、`performance/reservation_pipeline_benchmark.py`、`performance/analyze_reservation_pipeline.py`、本清单生成器、`docker/credit_risk/compose-v2-tests.yaml`、`docker/grafana/dashboards/reservation-v2.json`、本批设计/操作/性能报告。',
 '', '源码增量审阅补丁：`.tmp/architecture-rebuild-20261028/implementation-only.patch`。只生成补丁，不应用、不暂存、不提交或回滚任何文件。跨阶段共享文件集中列出，不伪装成可任意顺序应用的独立补丁。',
 '', '原有变更保护：`preexisting.patch`、`preexisting-status.txt`、`source-before.zip`、`source-before.json` 和 `App-before.tsx` 均保留。测试日志/数据库/缓存数据保留，不执行清理。']
(ROOT/'docs/RESERVATION_V2_CHANGED_FILES.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
print('Compared',len(paths),'paths;',len(records),'changed source files; no preexisting source files removed.')
