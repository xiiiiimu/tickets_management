"""Offline XML stream filter: only TCP connect events referencing test port18089."""
import collections,json,pathlib,sys,xml.etree.ElementTree as E
root=pathlib.Path(sys.argv[1]);ns={'e':'http://schemas.microsoft.com/win/2004/08/events/event'};counts=collections.Counter();statuses=collections.Counter();rows=[]
for _,event in E.iterparse(root/'tcpip.xml',events=('end',)):
    if event.tag!='{'+ns['e']+'}Event':continue
    data={d.get('Name'):(d.text or '').strip() for d in event.findall('e:EventData/e:Data',ns)}
    if any(data.get(k,'').endswith(':18089') for k in ('LocalAddress','RemoteAddress','SocketAddress')):
        eid=event.findtext('e:System/e:EventID','',ns);counts[eid]+=1
        if eid in ('1002','1017','1033','1034','1183','1184'):
            rows.append(dict(event_id=eid,time=event.find('e:System/e:TimeCreated',ns).get('SystemTime'),data=data,message=event.findtext('e:RenderingInfo/e:Message','',ns)))
            if eid=='1034':statuses[data.get('Status','')]+=1
    event.clear()
(root/'tcp-port-18089-events.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2),encoding='utf-8')
summary=dict(port=18089,event_counts=counts,connection_failure_statuses=statuses,failed_by_rst=counts['1183'],accepted_by_tcp=counts['1017'])
(root/'tcp-port-18089-summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8');print(summary)
