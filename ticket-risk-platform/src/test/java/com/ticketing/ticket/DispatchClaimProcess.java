package com.ticketing.ticket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.infrastructure.Db;
import com.ticketing.risk.RiskSubjectFence;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.File;
import java.util.*;

/** Minimal independent JVM actor; does not start HTTP, schedulers or inventory. */
public class DispatchClaimProcess {
    public static void main(String[] args)throws Exception{
        DriverManagerDataSource ds=new DriverManagerDataSource(System.getenv("CONTROLLED_TEST_URL"),System.getenv("CONTROLLED_TEST_USER"),System.getenv("CONTROLLED_TEST_PASSWORD"));
        Db db=new Db(new JdbcTemplate(ds),new ObjectMapper());String schema=db.jdbc.queryForObject("SELECT DATABASE()",String.class);
        if(schema==null||!schema.startsWith("credit_risk_controlled_"))throw new IllegalStateException("Owned schema required");
        Map<String,Object> settings=db.one("SELECT * FROM tb_reservation_dispatch WHERE id=1");
        ReservationDispatch queue=new ReservationDispatch(db,new RiskSubjectFence(db,new StringRedisTemplate()),new DataSourceTransactionManager(ds));
        for(String[] entry:new String[][]{{"capacity","capacity"},{"workers","active_limit"},{"passWeight","pass_weight"}})ReflectionTestUtils.setField(queue,entry[0],(int)Db.number(settings,entry[1]));
        ReflectionTestUtils.setField(queue,"reviewAge",Db.number(settings,"review_age_ms"));ReflectionTestUtils.setField(queue,"queueTimeout",30000L);queue.initialize();
        db.jdbc.update("INSERT INTO tb_controlled_claim_barrier VALUES(?)",args[0]);long until=System.currentTimeMillis()+20000;
        while(db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_controlled_claim_barrier",Integer.class)<2){if(System.currentTimeMillis()>until)throw new IllegalStateException("Actor barrier timeout");Thread.sleep(10);}
        List<Object> ids=new ArrayList<>();for(Map<String,Object> job:queue.claimBatch(4))ids.add(job.get("id"));new ObjectMapper().writeValue(new File(args[1]),ids);
    }
}
