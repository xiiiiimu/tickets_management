"""Opt-in localhost InnoDB commit probe. Never uses an existing schema.

Credentials: COMMIT_MYSQL_PASSWORD (environment only). Uses the project's JDBC driver.
--compile-only is offline; --execute is required before connecting to MySQL.
--connections 1..64 (default 1), one distinct row per worker connection.
All connections warm up three times, then start measured work at one barrier.
Each sample is UPDATE + COMMIT; commit latency is reported separately.
Global throughput excludes setup/warmup/output and covers first UPDATE to last COMMIT.
The single-connection SAMPLE/CSV and commit_ms/update_and_commit_ms keys remain compatible.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import uuid

ROOT = Path(__file__).resolve().parents[1]
SOURCE = r'''
import java.sql.*;
import java.util.*;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
public class CommitLatencyProbe {
 static void cleanup(Connection c, String schema, String owner) throws Exception {
  try(Statement s=c.createStatement()) {
   s.setQueryTimeout(5);
   try(ResultSet r=s.executeQuery("SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='"+schema+"'")) {
    r.next();if(r.getInt(1)==0) {System.out.println("CLEANUP verified_absent "+schema);return;}
   }
   try(ResultSet r=s.executeQuery("SELECT owner FROM "+schema+".owner_marker")) {
    if(!r.next() || !owner.equals(r.getString(1)) || r.next()) throw new IllegalStateException("Cleanup owner mismatch");
   }
   s.execute("DROP DATABASE "+schema);
   try(ResultSet r=s.executeQuery("SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='"+schema+"'")) {
    r.next();if(r.getInt(1)!=0) throw new IllegalStateException("Cleanup verification failed");
   }
   System.out.println("CLEANUP verified_absent "+schema);
  }
 }
 static double percentile(List<Double> v,double p) {
  return v.get(Math.max(0,(int)Math.ceil(v.size()*p)-1));
 }
 static String summary(List<Double> samples) {
  List<Double> v=new ArrayList<>(samples);Collections.sort(v);
  return "{\"count\":"+v.size()+",\"p50\":"+percentile(v,.50)+",\"p95\":"+percentile(v,.95)+",\"p99\":"+percentile(v,.99)+",\"max\":"+v.get(v.size()-1)+"}";
 }
 static class Samples {
  int id;List<Double> commits=new ArrayList<>(),totals=new ArrayList<>();
  Samples(int id){this.id=id;}
 }
 static Connection connect(String url) throws SQLException {
  return DriverManager.getConnection(url,System.getenv("COMMIT_MYSQL_USER"),System.getenv("COMMIT_MYSQL_PASSWORD"));
 }
 static void await(CountDownLatch latch, AtomicReference<Throwable> failure, long deadline) throws Exception {
  while(!latch.await(50,TimeUnit.MILLISECONDS)) {
   if(failure.get()!=null)throw new Exception("Worker failed",failure.get());
   if(System.nanoTime()>deadline)throw new TimeoutException("Bounded start barrier timeout");
  }
  if(failure.get()!=null)throw new Exception("Worker failed",failure.get());
 }
 public static void main(String[] a) throws Exception {
  String schema=a[0], owner=a[1]; int n=Integer.parseInt(a[2]);
  boolean cleanupOnly=Arrays.asList(a).contains("cleanup");
  int connections=a.length>=6?Integer.parseInt(a[4]):1;
  int timeout=a.length>=6?Integer.parseInt(a[5]):120;
  if(!schema.matches("credit_risk_commit_[a-f0-9]{32}") || !owner.matches("[a-f0-9]{32}") || connections<1 || connections>64 || n<1 || n>10000) throw new IllegalArgumentException("Owned namespace and bounded parameters required");
  String url="jdbc:mysql://127.0.0.1:"+Integer.parseInt(a[3])+"/?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=5000&socketTimeout=5000&serverTimezone=UTC";
  boolean created=false,marked=false,workersStopped=true;ExecutorService pool=null;
  CountDownLatch connected=new CountDownLatch(connections),startWarm=new CountDownLatch(1),warmed=new CountDownLatch(connections),startMeasure=new CountDownLatch(1);
  AtomicReference<Throwable> failure=new AtomicReference<>();
  AtomicLong first=new AtomicLong(Long.MAX_VALUE),last=new AtomicLong(0);
  long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(Math.max(1,timeout-10));
  try(Connection c=connect(url); Statement s=c.createStatement()) {
   if(cleanupOnly) {cleanup(c,schema,owner);return;}
   s.setQueryTimeout(5);
   try {
    s.execute("CREATE DATABASE "+schema); created=true;
    s.execute("CREATE TABLE "+schema+".owner_marker(owner VARCHAR(32) PRIMARY KEY) ENGINE=InnoDB");
    s.execute("INSERT INTO "+schema+".owner_marker VALUES('"+owner+"')"); marked=true;
    s.execute("CREATE TABLE "+schema+".probe(id INT PRIMARY KEY,value BIGINT NOT NULL) ENGINE=InnoDB");
    for(int id=1;id<=connections;id++)s.execute("INSERT INTO "+schema+".probe VALUES("+id+",0)");
    try(ResultSet r=s.executeQuery("SELECT @@innodb_flush_log_at_trx_commit,@@sync_binlog,@@version")) {
     r.next(); System.out.println("SETTINGS flush="+r.getInt(1)+" sync_binlog="+r.getInt(2)+" version="+r.getString(3));
    }
    pool=Executors.newFixedThreadPool(connections);workersStopped=false;
    List<Future<Samples>> futures=new ArrayList<>();
    for(int id=1;id<=connections;id++) {
     final int row=id;
     futures.add(pool.submit(()->{
      Samples sample=new Samples(row);
      try(Connection worker=connect(url);PreparedStatement p=worker.prepareStatement("UPDATE "+schema+".probe SET value=value+1 WHERE id=?")) {
       worker.setAutoCommit(false);p.setInt(1,row);p.setQueryTimeout(5);
       connected.countDown();startWarm.await();
       for(int warm=0;warm<3;warm++){if(p.executeUpdate()!=1)throw new IllegalStateException("Unexpected warm row count");worker.commit();}
       warmed.countDown();startMeasure.await();
       try {
        for(int i=0;i<n;i++) {
         if(Thread.currentThread().isInterrupted() || System.nanoTime()>deadline)throw new TimeoutException("Worker deadline exceeded");
         long start=System.nanoTime();first.accumulateAndGet(start,Math::min);
         if(p.executeUpdate()!=1)throw new IllegalStateException("Unexpected row count");
         long before=System.nanoTime();worker.commit();long end=System.nanoTime();last.accumulateAndGet(end,Math::max);
         sample.commits.add((end-before)/1e6);sample.totals.add((end-start)/1e6);
        }
       }catch(Exception error){try{worker.rollback();}catch(SQLException rollback){error.addSuppressed(rollback);}throw error;}
       return sample;
      }catch(Exception error){failure.compareAndSet(null,error);throw error;}
     }));
    }
    await(connected,failure,deadline);startWarm.countDown();
    await(warmed,failure,deadline);startMeasure.countDown();
    List<Samples> all=new ArrayList<>();List<Double> commits=new ArrayList<>(),totals=new ArrayList<>();
    for(Future<Samples> f:futures){Samples sample=f.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);all.add(sample);commits.addAll(sample.commits);totals.addAll(sample.totals);}
    double seconds=(last.get()-first.get())/1e9;
    StringBuilder per=new StringBuilder("[");
    for(Samples sample:all){
     if(per.length()>1)per.append(',');
     per.append("{\"connection\":").append(sample.id).append(",\"commit_ms\":").append(summary(sample.commits)).append(",\"update_and_commit_ms\":").append(summary(sample.totals)).append('}');
     for(int i=0;i<sample.commits.size();i++)System.out.println(connections==1?"SAMPLE "+i+","+sample.commits.get(i)+","+sample.totals.get(i):"CONNECTION_SAMPLE "+sample.id+","+i+","+sample.commits.get(i)+","+sample.totals.get(i));
    }
    per.append(']');
    System.out.println("RESULT {\"commit_ms\":"+summary(commits)+",\"update_and_commit_ms\":"+summary(totals)+",\"connections\":"+connections+",\"per_connection\":"+per+",\"total_commits\":"+commits.size()+",\"measured_wall_seconds\":"+seconds+",\"commits_per_second\":"+(commits.size()/seconds)+"}");
   } finally {
    startWarm.countDown();startMeasure.countDown();
    if(pool!=null){pool.shutdownNow();workersStopped=pool.awaitTermination(10,TimeUnit.SECONDS);}
    if(created) {
     // If marker creation failed, never guess ownership or delete an unverified schema.
     if(!marked) throw new IllegalStateException("Unmarked schema remains; inspect "+schema);
     if(!workersStopped)throw new IllegalStateException("Worker did not stop; defer owned cleanup to outer watchdog");
     cleanup(c,schema,owner);
    }
   }
  }
 }
}
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--compile-only', action='store_true')
    mode.add_argument('--execute', action='store_true')
    parser.add_argument('--samples', type=int, default=100)
    parser.add_argument('--connections', type=int, default=1)
    parser.add_argument('--port', type=int, default=13306)
    parser.add_argument('--java-home', type=Path, default=Path(r'C:/Program Files/Java/jdk-21.0.11'))
    parser.add_argument('--timeout', type=int, default=120)
    args = parser.parse_args()
    if not 1 <= args.samples <= 10000 or not 1 <= args.connections <= 64 or not 1 <= args.port <= 65535 or not 10 <= args.timeout <= 300:
        parser.error('samples=1..10000 per connection, connections=1..64, port=1..65535, timeout=10..300 required')
    if args.execute and not os.environ.get('COMMIT_MYSQL_PASSWORD'):
        parser.error('COMMIT_MYSQL_PASSWORD is required; credentials are never accepted in CLI arguments')
    run = uuid.uuid4().hex
    out = ROOT / 'performance/results' / ('mysql-commit-' + run)
    out.mkdir(parents=True)
    source = out / 'CommitLatencyProbe.java'
    source.write_text(SOURCE, encoding='utf-8')
    cp = (ROOT / 'ticket-risk-platform/target/benchmark-classpath.txt').read_text(encoding='utf-8-sig').strip()
    compile_cmd = [str(args.java_home / 'bin/javac.exe'), '-J-Xmx128m', '-proc:none', '-encoding', 'UTF-8', '-d', str(out), str(source)]
    compile_result = subprocess.run(compile_cmd, capture_output=True, timeout=45)
    (out / 'compile.stdout').write_bytes(compile_result.stdout)
    (out / 'compile.stderr').write_bytes(compile_result.stderr)
    compile_result.check_returncode()
    manifest = dict(mode='compile-only' if args.compile_only else 'execute', schema='credit_risk_commit_' + run,
                    owner=run, samples=args.samples, connections=args.connections, warmups=3, port=args.port, timeout=args.timeout,
                    source_sha256=hashlib.sha256(SOURCE.encode()).hexdigest(), executed=False)
    (out / 'manifest.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
    if args.compile_only:
        print(out)
        return
    env = os.environ.copy()
    env.setdefault('COMMIT_MYSQL_USER', 'root')
    cmd = [str(args.java_home / 'bin/java.exe'), '-Xmx96m', '-cp', str(out) + os.pathsep + cp,
           'CommitLatencyProbe', manifest['schema'], run, str(args.samples), str(args.port), str(args.connections), str(args.timeout)]
    try:
        with (out / 'stdout.log').open('wb') as stdout, (out / 'stderr.log').open('wb') as stderr:
            result = subprocess.run(cmd, env=env, stdout=stdout, stderr=stderr, timeout=args.timeout)
        manifest.update(executed=True, exit_code=result.returncode)
        log = (out / 'stdout.log').read_text(encoding='utf-8', errors='replace')
        manifest['cleanup_verified'] = ('CLEANUP verified_absent ' + manifest['schema']) in log
        rows = [line[7:] for line in log.splitlines() if line.startswith('SAMPLE ')]
        (out / 'samples.csv').write_text('sample,commit_ms,update_and_commit_ms\n' + '\n'.join(rows) + '\n', encoding='utf-8')
        if args.connections > 1:
            rows = [line[len('CONNECTION_SAMPLE '):] for line in log.splitlines() if line.startswith('CONNECTION_SAMPLE ')]
            (out / 'samples.csv').write_text('connection,sample,commit_ms,update_and_commit_ms\n' + '\n'.join(rows) + '\n', encoding='utf-8')
        for line in log.splitlines():
            if line.startswith('RESULT '):
                (out / 'result.json').write_text(json.dumps(json.loads(line[7:]), indent=2), encoding='utf-8')
        if result.returncode or not manifest['cleanup_verified']:
            raise RuntimeError('Probe failed or cleanup not verified; inspect ' + str(out))
    except subprocess.TimeoutExpired:
        # subprocess.run terminates only its own Java child. Do not delete a schema without its marker check.
        manifest.update(executed=True, timed_out=True, cleanup_verified=False,
                        recovery='Inspect the exact owner-marked schema in this manifest before cleanup; do not rerun blindly.')
        raise
    finally:
        if not manifest.get('cleanup_verified'):
            # Reconnect only to verify/drop this run's exact owner-marked schema, never existing data.
            try:
                cleanup_result = subprocess.run(cmd + ['cleanup'], env=env, capture_output=True, timeout=30)
                (out / 'cleanup.stdout').write_bytes(cleanup_result.stdout)
                (out / 'cleanup.stderr').write_bytes(cleanup_result.stderr)
                manifest['cleanup_verified'] = cleanup_result.returncode == 0 and (
                    'CLEANUP verified_absent ' + manifest['schema']).encode() in cleanup_result.stdout
            except subprocess.TimeoutExpired:
                manifest['cleanup_verified'] = False
        (out / 'manifest.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
        print(out)


if __name__ == '__main__':
    main()
