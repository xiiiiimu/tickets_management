package com.ticketing.ticket;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.springframework.core.env.Environment;
import javax.sql.DataSource;
import java.util.concurrent.atomic.AtomicLong;

/** Conservative prediction only; database bounds/deadline remain authoritative across nodes. */
public final class AttemptAdmissionBudget {
    private final DataSource dataSource;
    private final AtomicLong serviceMicros;
    /** null keeps the original rule (no waiter AND a free connection); otherwise refuse only above this many waiters. */
    private final Integer maxWaiting;
    public AttemptAdmissionBudget(Environment env,DataSource source){
        dataSource=source;
        long initial=env.getProperty("ticketing.reservations-v2.attempts.initial-service-ms",Long.class,2000L);
        if(initial<100||initial>60000)throw new IllegalArgumentException("Invalid initial attempt service estimate");
        serviceMicros=new AtomicLong(initial*1000);
        maxWaiting=env.getProperty("ticketing.reservations-v2.attempts.db-busy.max-waiting",Integer.class);
        if(maxWaiting!=null&&(maxWaiting<0||maxWaiting>1024))throw new IllegalArgumentException("Invalid db-busy max-waiting threshold");
    }
    public boolean dependenciesAvailable(){
        if(!(dataSource instanceof HikariDataSource))return true;
        HikariDataSource pool=(HikariDataSource)dataSource;HikariPoolMXBean h=pool.getHikariPoolMXBean();
        if(h==null)return false;
        if(maxWaiting!=null)return h.getThreadsAwaitingConnection()<=maxWaiting;
        return h.getThreadsAwaitingConnection()==0&&h.getActiveConnections()<pool.getMaximumPoolSize();
    }
    public int waitingLimit(int workers,int globallyProcessing){
        long free=Math.max(0,workers-globallyProcessing);
        long serviceMillis=Math.max(1,serviceMicros.get()/1000);
        // 250ms safety margin; not a guarantee that every admitted task will be claimed.
        long predicted=free+(4750L/serviceMillis)*workers;
        return (int)Math.min(512,Math.max(0,predicted));
    }
    public void completed(long nanos){
        long sample=Math.max(1000,Math.min(60000000,nanos/1000));
        serviceMicros.updateAndGet(old->(old*7+sample)/8);
    }
}
