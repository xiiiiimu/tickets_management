package com.ticketing.ticket;

import com.ticketing.infrastructure.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.mockito.Mockito.*;

class ReservationDispatchSubmissionTest {
    @Test void partialRejectionYieldsAllUnsentLeasesEvenIfOneYieldFails(){
        ReservationDispatch queue=mock(ReservationDispatch.class);TaskPools pools=mock(TaskPools.class);
        ReservationV2Service service=new ReservationV2Service(queue,null,null,null,null,pools,new SimpleMeterRegistry());ReflectionTestUtils.setField(service,"batchClaim",true);
        List<Map<String,Object>> jobs=new ArrayList<>();for(int n=0;n<4;n++){Map<String,Object> job=new HashMap<>();job.put("id","job-"+n);jobs.add(job);}
        when(queue.claimBatch(4)).thenReturn(jobs);AtomicInteger submitted=new AtomicInteger();
        doAnswer(call->{if(submitted.incrementAndGet()==2)throw new RejectedExecutionException("full");return null;}).when(pools).execute(eq("sale"),any());
        doThrow(new IllegalStateException("DB down")).when(queue).yieldClaim(jobs.get(1));service.dispatchOnce();
        verify(queue,never()).yieldClaim(jobs.get(0));for(int n=1;n<4;n++)verify(queue).yieldClaim(jobs.get(n));
    }
    @Test void isolatedSharedSchedulerCallbackDoesNotPerformSql(){
        ReservationDispatch queue=mock(ReservationDispatch.class);ReservationDispatchScheduler control=mock(ReservationDispatchScheduler.class);when(control.isIsolated()).thenReturn(true);
        ReservationV2Service service=new ReservationV2Service(queue,null,null,null,null,mock(TaskPools.class),new SimpleMeterRegistry());ReflectionTestUtils.setField(service,"scheduler",control);
        service.dispatch();verifyNoInteractions(queue);
    }
}
