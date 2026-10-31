from pathlib import Path
import json
root=Path(__file__).parent
inventory=json.loads((root/'migration-history/source-inventory.json').read_text(encoding='utf-8'))
obsolete=[]
java_domains=['credit/','product/','ocr/','input/','agent/','platformconfig/','workflow/tool/']
for row in inventory:
 p=Path(row['path']);s=p.as_posix()
 is_old=False
 main='ticket-risk-platform/src/main/java/com/ticketing/'
 test='ticket-risk-platform/src/test/java/com/ticketing/'
 if s.startswith(main):
  relative=s[len(main):]
  is_old=any(relative.startswith(x) for x in java_domains) or relative in ['CreditPlatformApplication.java','config/CreditDevProperties.java']
 if s.startswith(test):
  is_old=any(s[len(test):].startswith(x) for x in ['credit/','product/','ocr/','input/','agent/','platformconfig/'])
 if s.startswith('ticket-risk-platform/src/main/resources/db/'):
  is_old=True
 if s.startswith('risk-agent/app/') and s not in ['risk-agent/app/main.py','risk-agent/app/__init__.py']:is_old=True
 if s.startswith('risk-agent/tests/'):is_old=True
 if s.startswith(('ticket-risk-web/src/pages/','ticket-risk-web/src/layouts/','ticket-risk-web/src/api/','docs/')):is_old=True
 if is_old and (root/p).is_file():obsolete.append(s)
obsolete.extend(p.name for p in root.glob('credit-*.jmx'))
(root/'migration-history/obsolete-files.txt').write_text('\n'.join(sorted(set(obsolete)))+'\n',encoding='utf-8')
print('Pending explicitly enumerated obsolete files:',len(set(obsolete)))
