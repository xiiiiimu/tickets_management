package com.ticketing.ticket;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReservationDispatchWakeTest extends ReservationDispatchTest {
    AtomicInteger notifications=new AtomicInteger();
    ReservationDispatchScheduler listen(){
        ReservationDispatchScheduler scheduler=mock(ReservationDispatchScheduler.class);ReflectionTestUtils.setField(queue,"scheduler",scheduler);
        doAnswer(call->{assertTrue(TransactionSynchronizationManager.isActualTransactionActive());TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){notifications.incrementAndGet();}});return null;}).when(scheduler).afterCommitWake();return scheduler;
    }
    @Test void committedQueuedTransitionNotifiesOnce(){listen();enqueue(1,"PASS");assertEquals(1,notifications.get());}
    @Test void rolledBackQueuedTransitionDoesNotNotify(){
        listen();Map<String,Object> job=queue.create(request(1),"ip","fp",1);
        Map<String,Object> pass=new HashMap<>();pass.put("decision","PASS");pass.put("resultCode","PASS");
        new TransactionTemplate(manager).executeWithoutResult(s->{queue.decided(job,pass);assertEquals(0,notifications.get());s.setRollbackOnly();});
        assertEquals(0,notifications.get());assertEquals("EVALUATING",queue.get(job.get("id").toString()).get("state"));
    }
    @Test void blockAndStaleDecisionsDoNotNotify(){
        ReservationDispatchScheduler scheduler=listen();Map<String,Object> job=enqueue(1,"BLOCK");
        Map<String,Object> pass=new HashMap<>();pass.put("decision","PASS");pass.put("resultCode","PASS");queue.decided(job,pass);
        verifyNoInteractions(scheduler);assertEquals(0,notifications.get());
    }
}
