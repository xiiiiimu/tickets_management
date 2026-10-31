package com.ticketing.ticket;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class AttemptTransactionTimingTest {
    AtomicLong clock=new AtomicLong(100);SimpleMeterRegistry metrics=new SimpleMeterRegistry();
    class Manager extends AbstractPlatformTransactionManager {
        boolean failBegin,failCommit;
        protected Object doGetTransaction(){return new Object();}
        protected void doBegin(Object t,TransactionDefinition d){clock.addAndGet(7);if(failBegin)throw new CannotCreateTransactionException("injected");}
        protected void doCommit(DefaultTransactionStatus s){clock.addAndGet(11);if(failCommit)throw new TransactionSystemException("injected commit");}
        protected void doRollback(DefaultTransactionStatus s){clock.addAndGet(13);}
    }
    double value(String outcome,String phase){io.micrometer.core.instrument.Timer t=metrics.get("attempt.transaction.duration").tags("operation","register","outcome",outcome,"phase",phase).timer();assertEquals(1,t.count());return t.totalTime(TimeUnit.NANOSECONDS);}
    void verify(String outcome,int completion){assertEquals(7,value(outcome,"begin"));assertEquals(19,value(outcome,"body"));assertEquals(completion,value(outcome,"completion"));assertEquals(7+19+completion,value(outcome,"total"));}
    @Test void commitBoundaryAndDisjointPartsAreCountedOnce(){Manager m=new Manager();assertEquals("ok",AttemptTransactionTiming.execute(new TransactionTemplate(m),metrics,"register",s->{clock.addAndGet(19);return "ok";},clock::get));verify("success",11);}
    @Test void rollbackStillRecordsTheWholeTransaction(){assertThrows(IllegalStateException.class,()->AttemptTransactionTiming.execute(new TransactionTemplate(new Manager()),metrics,"register",s->{clock.addAndGet(19);throw new IllegalStateException("injected");},clock::get));verify("failure",13);}
    @Test void failedCommitIsNotRecordedAsSuccessfulRegistration(){Manager m=new Manager();m.failCommit=true;assertThrows(TransactionSystemException.class,()->AttemptTransactionTiming.execute(new TransactionTemplate(m),metrics,"register",s->{clock.addAndGet(19);return null;},clock::get));verify("failure",11);}
    @Test void rollbackOnlyWithoutExceptionIsStillAFailedTransaction(){AttemptTransactionTiming.execute(new TransactionTemplate(new Manager()),metrics,"register",s->{clock.addAndGet(19);s.setRollbackOnly();return null;},clock::get);verify("failure",13);}
    @Test void connectionOrBeginFailureHasNoInventedBodyTime(){Manager m=new Manager();m.failBegin=true;assertThrows(CannotCreateTransactionException.class,()->AttemptTransactionTiming.execute(new TransactionTemplate(m),metrics,"register",s->null,clock::get));assertEquals(7,value("failure","total"));assertEquals(7,value("failure","begin"));assertEquals(0,value("failure","body"));assertEquals(0,value("failure","completion"));}
}
