package com.ticketing.ticket;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.support.TransactionTemplate;
import com.ticketing.infrastructure.Db;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Selected methods reuse only owner-checked MySQL provisioning, not the phase-B test suite. */
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="ticketing.attempts.mysql",matches="true")
class QualificationOrderMysqlTest extends ReservationAttemptMysqlTest {
    @BeforeAll static void additiveMigration(){
        DriverManagerDataSource ds=new DriverManagerDataSource(System.getenv("SPRING_DATASOURCE_URL"),System.getenv("SPRING_DATASOURCE_USERNAME"),System.getenv("SPRING_DATASOURCE_PASSWORD"));JdbcTemplate jdbc=new JdbcTemplate(ds);
        assertEquals(System.getenv("CONTROLLED_TEST_OWNER"),jdbc.queryForObject("SELECT owner FROM tb_controlled_owner",String.class));
        Map<String,Object> before=jdbc.queryForMap("SELECT * FROM tb_reservation_dispatch WHERE id=1");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V111__qualification_lua_order.sql")).execute(ds);
        Map<String,Object> after=jdbc.queryForMap("SELECT * FROM tb_reservation_dispatch WHERE id=1");for(String key:before.keySet())assertEquals(before.get(key),after.get(key));assertEquals(0,((Number)after.get("ordered_lua_enabled")).intValue());
    }
    @BeforeEach void emptyOwnedLanes(){db.jdbc.update("DELETE FROM tb_reservation_sku_order");}
    Map<String,Object> processing(int user){register(user);Map<String,Object> job=first.claim();db.jdbc.update("UPDATE tb_reservation_job SET admission='{}',decision='PASS' WHERE id=?",job.get("id"));return queue.get(job.get("id").toString());}
    void publish(ReservationQualificationOrder order,Map<String,Object> job){new TransactionTemplate(manager).executeWithoutResult(s->{Map<String,Object> current=db.one("SELECT * FROM tb_reservation_job WHERE id=? FOR UPDATE",job.get("id"));order.publish(current);});}
    @Test void twoInstancePublicationIsContiguousAndDuplicatePublishIsIdempotent()throws Exception{
        Map<String,Object> a=processing(1),b=processing(2);ReservationQualificationOrder one=new ReservationQualificationOrder(db),two=new ReservationQualificationOrder(db);CyclicBarrier go=new CyclicBarrier(2);ExecutorService pool=Executors.newFixedThreadPool(2);
        try{Future<?> x=pool.submit(()->{await(go);publish(one,a);}),y=pool.submit(()->{await(go);publish(two,b);});x.get(6,TimeUnit.SECONDS);y.get(6,TimeUnit.SECONDS);publish(two,a);
            assertEquals(new HashSet<>(Arrays.asList(1L,2L)),new HashSet<>(db.jdbc.queryForList("SELECT qualification_seq FROM tb_reservation_job",Long.class)));assertEquals(2,db.jdbc.queryForObject("SELECT published_seq FROM tb_reservation_sku_order",Long.class));}
        finally{pool.shutdownNow();}
    }
    @Test void transactionRollbackDoesNotLeaveAHoleOrCompletedTurn(){
        Map<String,Object> a=processing(1);ReservationQualificationOrder order=new ReservationQualificationOrder(db);TransactionTemplate tx=new TransactionTemplate(manager);
        tx.executeWithoutResult(s->{order.publish(a);s.setRollbackOnly();});assertNull(queue.get(a.get("id").toString()).get("qualification_seq"));assertEquals(0,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_reservation_sku_order",Integer.class));
        publish(order,a);final Map<String,Object> committed=queue.get(a.get("id").toString());
        tx.executeWithoutResult(s->{order.passed(committed);s.setRollbackOnly();});assertFalse(ReservationQualificationOrder.finished(queue.get(a.get("id").toString())));assertEquals(1,db.jdbc.queryForObject("SELECT next_lua_seq FROM tb_reservation_sku_order",Long.class));
    }
    @Test void laterTaskCannotPassCursorAndOtherSkuHasAnIndependentLane(){
        Map<String,Object> a=processing(1),b=processing(2),c=processing(3);db.jdbc.update("UPDATE tb_reservation_job SET sku_id='other-sku' WHERE id=?",c.get("id"));c=queue.get(c.get("id").toString());ReservationQualificationOrder order=new ReservationQualificationOrder(db);
        publish(order,a);publish(order,b);publish(order,c);Map<String,Object> later=queue.get(b.get("id").toString()),other=queue.get(c.get("id").toString());TransactionTemplate tx=new TransactionTemplate(manager);
        assertThrows(ReservationQualificationOrder.Pending.class,()->tx.executeWithoutResult(s->order.passed(later)));tx.executeWithoutResult(s->order.passed(other));assertEquals(2,db.jdbc.queryForObject("SELECT next_lua_seq FROM tb_reservation_sku_order WHERE sku_id='other-sku'",Long.class));assertEquals(1,db.jdbc.queryForObject("SELECT next_lua_seq FROM tb_reservation_sku_order WHERE sku_id='sku'",Long.class));
    }
    static void await(CyclicBarrier barrier){try{barrier.await(3,TimeUnit.SECONDS);}catch(Exception e){throw new AssertionError(e);}}
}
