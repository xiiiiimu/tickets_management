"""Offline bounded-memory JFR analysis, no Redis or HTTP load."""
import pathlib, subprocess, sys
import fast_ingress_benchmark as f
cp=(f.ROOT/'ticket-risk-platform/target/benchmark-classpath.txt').read_text(encoding='utf-8-sig').strip()
classes=f.ROOT/'.tmp/lettuce-wait-classes'
subprocess.run([str(f.JAVA/'javac.exe'),'-encoding','UTF-8','-proc:none','-cp',cp,'-d',str(classes),str(f.ROOT/'performance/JfrWaitSummary.java')],check=True,timeout=40)
subprocess.run([str(f.JAVA/'java.exe'),'-Xms32m','-Xmx128m','-XX:+UseSerialGC','-cp',str(classes)+';'+cp,'JfrWaitSummary',str(pathlib.Path(sys.argv[1]).resolve())],check=True,timeout=90)
