package lockdiag;
import java.lang.instrument.Instrumentation;
import java.util.concurrent.ThreadPoolExecutor;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.MemberSubstitution;
import static net.bytebuddy.matcher.ElementMatchers.*;

/** Test-only call-site observation: original execute/Runnable/transactions still run. */
public class LockDiagnosticAgent {
 public static void premain(String args,Instrumentation instrumentation)throws Exception{
  AgentBuilder builder=new AgentBuilder.Default().with(AgentBuilder.Listener.StreamWriting.toSystemError().withErrorsOnly());
  builder=builder.type(named("com.ticketing.ticket.ReservationIngress")).transform((b,t,l,m)->{
   LockProbe.TRANSFORMED.add(t.getName());
   try{return b.visit(MemberSubstitution.strict().method(named("execute").and(isDeclaredBy(ThreadPoolExecutor.class)))
    .replaceWith(LockProbe.class.getMethod("submit",ThreadPoolExecutor.class,Runnable.class)).on(named("receive")));}
   catch(Exception e){throw new IllegalStateException(e);}
  });
  builder=builder.type(named("com.ticketing.ticket.ReservationIngress$Work")).transform((b,t,l,m)->{LockProbe.TRANSFORMED.add(t.getName());return b.visit(Advice.to(Work.class).on(named("run")));});
  builder=builder.type(named("com.ticketing.ticket.ReservationV2Service")).transform((b,t,l,m)->{LockProbe.TRANSFORMED.add(t.getName());return b.visit(Advice.to(Process.class).on(named("process")));});
  builder=builder.type(named("org.springframework.transaction.support.TransactionTemplate")).transform((b,t,l,m)->{LockProbe.TRANSFORMED.add(t.getName());return b.visit(Advice.to(Span.class).on(named("execute")));});
  builder=builder.type(named("org.springframework.jdbc.datasource.DataSourceTransactionManager")).transform((b,t,l,m)->{LockProbe.TRANSFORMED.add(t.getName());return b.visit(Advice.to(Span.class).on(named("doBegin").or(named("doCommit")).or(named("doRollback"))));});
  builder=builder.type(named("com.zaxxer.hikari.HikariDataSource")).transform((b,t,l,m)->{LockProbe.TRANSFORMED.add(t.getName());return b.visit(Advice.to(Span.class).on(named("getConnection").and(takesArguments(0))));});
  builder=builder.type(named("com.ticketing.infrastructure.Db")).transform((b,t,l,m)->{LockProbe.TRANSFORMED.add(t.getName());return b.visit(Advice.to(Sql.class).on(named("one")));});
  builder=builder.type(named("org.springframework.jdbc.core.JdbcTemplate")).transform((b,t,l,m)->{LockProbe.TRANSFORMED.add(t.getName());return b.visit(Advice.to(Sql.class).on(nameStartsWith("queryFor").or(named("update"))));});
  builder.installOn(instrumentation);
 }
 public static class Work {
  @Advice.OnMethodEnter public static void enter(@Advice.This Object work){LockProbe.workStart(work);}
  @Advice.OnMethodExit(onThrowable=Throwable.class) public static void exit(){LockProbe.workEnd();}
 }
 public static class Process {
  @Advice.OnMethodEnter public static void enter(@Advice.Argument(0) Object job){LockProbe.processStart(job);}
  @Advice.OnMethodExit(onThrowable=Throwable.class) public static void exit(){LockProbe.clear();}
 }
 public static class Span {
  @Advice.OnMethodEnter public static long enter(){return System.nanoTime();}
  @Advice.OnMethodExit(onThrowable=Throwable.class) public static void exit(@Advice.Origin("#t.#m") String method,@Advice.Enter long start,@Advice.Thrown Throwable error){LockProbe.record(method,start,error);}
 }
 public static class Sql {
  @Advice.OnMethodEnter public static long enter(@Advice.AllArguments Object[] args){return LockProbe.sqlStart(args);}
  @Advice.OnMethodExit(onThrowable=Throwable.class) public static void exit(@Advice.Enter long start,@Advice.AllArguments Object[] args,@Advice.Thrown Throwable error){LockProbe.sqlEnd(args,start,error);}
 }
}
