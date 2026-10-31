package com.ticketing.ticket;

import com.ticketing.common.BusinessException;
import com.ticketing.infrastructure.Db;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Inherits the original contract; exercises the new API without changing configuration defaults. */
class ReservationDispatchBatchTest extends ReservationDispatchTest {
    void configure(int capacity,int workers){
        db.jdbc.update("UPDATE tb_reservation_dispatch SET capacity=?,active_limit=?",capacity,workers);
        queue=instance(capacity,workers);
    }
    @Test void batchPreservesWeightedOrderAndGlobalFourLimit(){
        configure(10,4);enqueue(1,"REVIEW");for(int n=2;n<=6;n++)enqueue(n,"PASS");
        List<Map<String,Object>> jobs=queue.claimBatch(4);
        List<Object> decisions=new ArrayList<>();for(Map<String,Object> job:jobs)decisions.add(job.get("decision"));
        assertEquals(Arrays.asList("PASS","PASS","REVIEW","PASS"),decisions);
        assertTrue(queue.claimBatch(4).isEmpty());assertNull(queue.claim());
        assertEquals(4,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_reservation_job WHERE state='CLAIMED'",Integer.class));
        assertThrows(IllegalArgumentException.class,()->queue.claimBatch(5));
    }
    @Test void agedReviewIsSelectedAfterOnePassWithinBatch(){
        configure(10,4);enqueue(1,"REVIEW");enqueue(2,"PASS");enqueue(3,"PASS");
        db.jdbc.update("UPDATE tb_reservation_job SET created_at=? WHERE decision='REVIEW'",new Timestamp(System.currentTimeMillis()-10000));
        db.jdbc.update("UPDATE tb_reservation_dispatch SET review_age_ms=1");
        List<Map<String,Object>> jobs=queue.claimBatch(3);
        assertEquals("PASS",jobs.get(0).get("decision"));assertEquals("REVIEW",jobs.get(1).get("decision"));
    }
    @Test void concurrentBatchAndLegacyInstancesNeverDuplicateOrExceedLimit()throws Exception{
        configure(20,4);for(int n=1;n<=12;n++)enqueue(n,"PASS");
        ReservationDispatch other=instance(20,4);ExecutorService pool=Executors.newFixedThreadPool(6);CountDownLatch start=new CountDownLatch(1);
        try{
            List<Future<List<Map<String,Object>>>> futures=new ArrayList<>();
            for(int n=0;n<8;n++){final boolean batch=n%2==0;futures.add(pool.submit(()->{start.await();if(batch)return queue.claimBatch(4);Map<String,Object> one=other.claim();return one==null?Collections.emptyList():Collections.singletonList(one);}));}
            start.countDown();Set<Object> ids=new HashSet<>();int count=0;
            for(Future<List<Map<String,Object>>> future:futures)for(Map<String,Object> job:future.get(30,TimeUnit.SECONDS)){count++;assertTrue(ids.add(job.get("id")));}
            assertEquals(4,count);
        }finally{pool.shutdownNow();}
    }
    @Test void rollbackRestoresEveryLeaseAndFairnessCounter(){
        configure(10,4);for(int n=1;n<=4;n++)enqueue(n,"PASS");
        new TransactionTemplate(manager).executeWithoutResult(s->{assertEquals(4,queue.claimBatch(4).size());s.setRollbackOnly();});
        assertEquals(4,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_reservation_job WHERE state='QUEUED' AND lease_token IS NULL",Integer.class));
        assertEquals(0,Db.number(db.one("SELECT * FROM tb_reservation_dispatch WHERE id=1"),"pass_run"));
        assertEquals(4,queue.claimBatch(4).size());
    }
    @Test void failureInLaterRenewRollsBackEarlierClaims(){
        configure(10,4);enqueue(1,"PASS");enqueue(2,"PASS");
        db.jdbc.update("UPDATE tb_reservation_job SET created_at=? WHERE user_id=1",new Timestamp(System.currentTimeMillis()-10000));
        db.jdbc.execute("ALTER TABLE tb_reservation_job ADD CONSTRAINT controlled_renew_failure CHECK (NOT(user_id=2 AND state='CLAIMED'))");
        assertThrows(RuntimeException.class,()->queue.claimBatch(4));
        assertEquals(2,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_reservation_job WHERE state='QUEUED' AND lease_token IS NULL",Integer.class));
        assertEquals(0,Db.number(db.one("SELECT * FROM tb_reservation_dispatch WHERE id=1"),"pass_run"));
    }
    @Test void mixedExpiredLeasesRecoverWithoutAllocatingAnExtraActiveSlot(){
        Map<String,Object> original=enqueue(1,"PASS"),claimed=queue.claim();
        Map<String,Object> evaluating=queue.create(request(2),"ip","fp-2",1);
        enqueue(3,"PASS");enqueue(4,"PASS");
        db.jdbc.update("UPDATE tb_reservation_job SET lease_until=? WHERE id IN (?,?)",new Timestamp(System.currentTimeMillis()-60000),claimed.get("id"),evaluating.get("id"));
        List<Map<String,Object>> jobs=queue.claimBatch(4);assertEquals(3,jobs.size());
        Map<String,Object> recovered=jobs.stream().filter(x->x.get("id").equals(claimed.get("id"))).findFirst().get();
        assertFalse(queue.owns(recovered,claimed,"CLAIMED"));assertEquals(original.get("reservation_id"),recovered.get("reservation_id"));
        assertEquals(original.get("queue_expires_at"),recovered.get("queue_expires_at"));
        Map<String,Object> recoveredEvaluation=jobs.stream().filter(x->x.get("id").equals(evaluating.get("id"))).findFirst().get();
        assertEquals("EVALUATING",recoveredEvaluation.get("state"));assertFalse(queue.owns(recoveredEvaluation,evaluating,"EVALUATING"));
        assertEquals(2,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_reservation_job WHERE state='CLAIMED'",Integer.class));
    }
    @Test void concurrentCreatesRespectTheActual512Boundary()throws Exception{
        configure(512,4);Map<String,Object> seed=queue.create(request(1),"ip","fp-1",1);
        new TransactionTemplate(manager).executeWithoutResult(s->{
            for(int n=2;n<=511;n++)db.jdbc.update("INSERT INTO tb_reservation_job(id,request_id,fingerprint,user_id,session_id,sku_id,quantity,device_id,ip,sale_version,state,queue_expires_at,reservation_id,order_id,active_owner) SELECT ?,?,'fixture',?,'s','sku',1,'d','ip',1,'EVALUATING',queue_expires_at,?,?,? FROM tb_reservation_job WHERE id=?",
                    Db.id(),"fixture-"+n,n,Db.id(),Db.id(),"owner-"+n,seed.get("id"));
        });
        ExecutorService pool=Executors.newFixedThreadPool(4);
        try{
            List<Future<Boolean>> values=new ArrayList<>();
            for(int n=600;n<604;n++){final int id=n;values.add(pool.submit(()->{try{queue.create(request(id),"ip","fp-"+id,1);return true;}catch(BusinessException full){assertEquals(429,full.status);return false;}}));}
            int accepted=0;for(Future<Boolean> f:values)if(f.get(30,TimeUnit.SECONDS))accepted++;
            assertEquals(1,accepted);assertEquals(512,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_reservation_job",Integer.class));
        }finally{pool.shutdownNow();}
    }
    @Test void repeatedRequestStillHasOneJobAndOneBatchClaim(){
        Map<String,Object> job=enqueue(1,"PASS");
        assertEquals(job.get("id"),queue.create(request(1),"ip","fp-1",1).get("id"));
        assertEquals(1,queue.claimBatch(4).size());assertTrue(queue.claimBatch(4).isEmpty());
    }
}
