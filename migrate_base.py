from pathlib import Path
import json

root = Path(__file__).parent
backend = root / 'ticket-risk-platform'
base = backend / 'src/main/java/com/ticketing'
def write(path, text):
    p = root / path
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text.strip()+'\n', encoding='utf-8')

# Read all source/configuration material, inventory its size and dependencies.
inventory = []
for module in ['ticket-risk-platform', 'ticket-risk-web', 'risk-agent', 'docker', 'docs']:
    for p in (root/module).rglob('*'):
        if not p.is_file() or any(x in p.parts for x in ['node_modules','target','.venv','venv','dist','__pycache__','.git']):
            continue
        if p.suffix in ['.java','.py','.tsx','.ts','.sql','.yml','.yaml','.json','.xml','.md'] or p.name=='Dockerfile':
            text=p.read_text(encoding='utf-8-sig')
            inventory.append({'path':str(p.relative_to(root)), 'lines':len(text.splitlines()), 'imports':[l for l in text.splitlines() if l.startswith(('import ','from '))]})
write('migration-history/source-inventory.json',json.dumps(inventory,ensure_ascii=False,indent=2))
for p in backend.rglob('*.java'):
    if 'target' in p.parts: continue
    s=p.read_text(encoding='utf-8-sig').replace('com.credit','com.ticketing')
    if '/workflow/' in p.as_posix():
        s=s.replace('applicationId','orderId').replace('ApplicationId','OrderId').replace('application_id','order_id')
    s=s.replace('"credit.', '"ticketing.')
    p.write_text(s,encoding='utf-8')

pom=(backend/'pom.xml').read_text(encoding='utf-8')
pom=pom.replace('com.credit','com.ticketing').replace('credit-risk-platform','ticket-risk-platform').replace('Independent credit risk control platform','Ticket trading and real-time fraud prevention')
pom=pom.replace('<dependencies>','''<dependencies>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-jdbc</artifactId></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-validation</artifactId></dependency>
        <dependency><groupId>io.micrometer</groupId><artifactId>micrometer-registry-prometheus</artifactId></dependency>
        <dependency><groupId>com.h2database</groupId><artifactId>h2</artifactId><scope>test</scope></dependency>
        <dependency><groupId>org.testcontainers</groupId><artifactId>junit-jupiter</artifactId><version>1.19.8</version><scope>test</scope></dependency>
        <dependency><groupId>org.testcontainers</groupId><artifactId>mysql</artifactId><version>1.19.8</version><scope>test</scope></dependency>''',1)
(backend/'pom.xml').write_text(pom,encoding='utf-8')
for f in ['package.json','package-lock.json','index.html']:
    p=root/'ticket-risk-web'/f
    p.write_text(p.read_text(encoding='utf-8').replace('credit-risk-web','ticket-risk-web').replace('信贷风控','票务交易与反欺诈'),encoding='utf-8')
p=root/'.gitignore'
p.write_text(p.read_text().replace('credit-risk-platform','ticket-risk-platform').replace('credit-risk-web','ticket-risk-web').replace('credit-agent','risk-agent')+'\n*.tsbuildinfo\n',encoding='utf-8')
