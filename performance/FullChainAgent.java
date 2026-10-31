package fullchain;
import java.lang.instrument.Instrumentation;
import java.util.concurrent.ThreadPoolExecutor;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.MemberSubstitution;
import static net.bytebuddy.matcher.ElementMatchers.*;

/** Test-only call-site observation: original execute/Runnable/transactions still run. */
public class FullChainAgent {
 public static void premain(String args,Instrumentation instrumentation)throws Exception{
  AgentBuilder builder=new AgentBuilder.Default().with(AgentBuilder.Listener.StreamWriting.toSystemError().withErrorsOnly());
  builder=builder.type(named("com.ticketing.ticket.ReservationIngress")).transform((b,t,l,m)->{
   FullChainProbe.TRANSFORMED.add(t.getName());
   try{return b.visit(MemberSubstitution.strict().method(named("execute").and(isDeclaredBy(ThreadPoolExecutor.class)))
    .replaceWith(FullChainProbe.class.getMethod("submit",ThreadPoolExecutor.class,Runnable.class)).on(named("receive")));}
   catch(Exception e){throw new IllegalStateException(e);}
  });
  builder=builder.type(named("com.ticketing.ticket.ReservationIngress$Work")).transform((b,t,l,m)->{FullChainProbe.TRANSFORMED.add(t.getName());return b.visit(Advice.to(Work.class).on(named("run")));});
  builder=builder.type(named("com.ticketing.ticket.ReservationV2Service")).transform((b,t,l,m)->{FullChainProbe.TRANSFORMED.add(t.getName());return b.visit(Advice.to(Process.class).on(named("process"))).visit(Advice.to(Poll.class).on(named("dispatchOnce")));});
  builder=builder.type(named("org.springframework.transaction.support.TransactionTemplate")).transform((b,t,l,m)->{FullChainProbe.TRANSFORMED.add(t.getName());return b.visit(Advice.to(Span.class).on(named("execute")));});
  builder=builder.type(named("org.springframework.jdbc.datasource.DataSourceTransactionManager")).transform((b,t,l,m)->{FullChainProbe.TRANSFORMED.add(t.getName());return b.visit(Advice.to(Span.class).on(named("doBegin").or(named("doCommit")).or(named("doRollback"))));});
  builder=builder.type(named("com.zaxxer.hikari.HikariDataSource")).transform((b,t,l,m)->{FullChainProbe.TRANSFORMED.add(t.getName());return b.visit(Advice.to(ConnectionSpan.class).on(named("getConnection").and(takesArguments(0))));});
  builder=builder.type(named("com.ticketing.infrastructure.Db")).transform((b,t,l,m)->{FullChainProbe.TRANSFORMED.add(t.getName());return b.visit(Advice.to(Sql.class).on(named("one")));});
  builder=builder.type(named("org.springframework.jdbc.core.JdbcTemplate")).transform((b,t,l,m)->{FullChainProbe.TRANSFORMED.add(t.getName());return b.visit(Advice.to(Sql.class).on(nameStartsWith("queryFor").or(named("update"))));});
  builder=builder.type(named("com.ticketing.common.util.RefreshTokenInterceptor").or(named("com.ticketing.common.util.LoginInterceptor"))).transform((b,t,l,m)->{FullChainProbe.TRANSFORMED.add(t.getName());return b.visit(Advice.to(Span.class).on(named("preHandle")));});
  builder=builder.type(named("com.ticketing.ticket.ReservationV2Controller").or(named("com.ticketing.ticket.ReservationIngress$Bucket"))).transform((b,t,l,m)->{FullChainProbe.TRANSFORMED.add(t.getName());return b.visit(Advice.to(Span.class).on(named("create").or(named("take"))));});
  builder=builder.type(named("com.ticketing.risk.RiskHistorySummary")).transform((b,t,l,m)->{FullChainProbe.TRANSFORMED.add(t.getName());return b.visit(Advice.to(Span.class).on(named("ready").or(named("read")).or(named("refresh")).or(named("rebuild"))));});
  builder=builder.type(named("com.ticketing.risk.RiskFeatureService")).transform((b,t,l,m)->{FullChainProbe.TRANSFORMED.add(t.getName());return b.visit(Advice.to(Span.class).on(named("hit").or(named("count")).or(named("blacklisted"))));});
  builder=builder.type(named("com.ticketing.infrastructure.TaskPools")).transform((b,t,l,m)->{FullChainProbe.TRANSFORMED.add(t.getName());return b.visit(Advice.to(Submit.class).on(named("execute")));});
  builder=builder.type(named("org.springframework.data.redis.core.DefaultHashOperations").or(named("org.springframework.data.redis.core.DefaultValueOperations")).or(named("org.springframework.data.redis.core.DefaultZSetOperations"))).transform((b,t,l,m)->{FullChainProbe.TRANSFORMED.add(t.getName());return b.visit(Advice.to(Span.class).on(named("entries").or(named("get")).or(named("multiGet")).or(named("put")).or(named("count"))));});
  builder=builder.type(named("org.springframework.data.redis.core.RedisTemplate")).transform((b,t,l,m)->{FullChainProbe.TRANSFORMED.add(t.getName());return b.visit(Advice.to(Span.class).on(named("expire").or(named("countExistingKeys")).or(named("hasKey"))));});
  builder.installOn(instrumentation);
 }
 public static class ConnectionSpan {
  @Advice.OnMethodEnter public static long enter(){return System.nanoTime();}
  @Advice.OnMethodExit(onThrowable=Throwable.class) public static void exit(@Advice.Origin("#t.#m") String method,@Advice.Enter long start,@Advice.Thrown Throwable error,@Advice.Return Object connection){if(connection!=null)FullChainProbe.connected(connection);FullChainProbe.record(method,start,error);}
 }
 public static class Submit {
  @Advice.OnMethodEnter public static void enter(@Advice.Argument(0) String pool){FullChainProbe.saleSubmit(pool);}
  @Advice.OnMethodExit(onThrowable=Throwable.class) public static void exit(@Advice.Thrown Throwable error){FullChainProbe.saleSubmitted(error);}
 }
 public static class Poll {
  @Advice.OnMethodEnter public static Object enter(){return FullChainProbe.pollStart();}
  @Advice.OnMethodExit(onThrowable=Throwable.class) public static void exit(@Advice.Enter Object token){FullChainProbe.pollEnd(token);}
 }
 public static class Work {
  @Advice.OnMethodEnter public static void enter(@Advice.This Object work){FullChainProbe.workStart(work);}
  @Advice.OnMethodExit(onThrowable=Throwable.class) public static void exit(){FullChainProbe.workEnd();}
 }
 public static class Process {
  @Advice.OnMethodEnter public static void enter(@Advice.Argument(0) Object job){FullChainProbe.processStart(job);}
  @Advice.OnMethodExit(onThrowable=Throwable.class) public static void exit(){FullChainProbe.processEnd();}
 }
 public static class Span {
  @Advice.OnMethodEnter public static long enter(){return System.nanoTime();}
  @Advice.OnMethodExit(onThrowable=Throwable.class) public static void exit(@Advice.Origin("#t.#m") String method,@Advice.Enter long start,@Advice.Thrown Throwable error){FullChainProbe.record(method,start,error);}
 }
 public static class Sql {
  @Advice.OnMethodEnter public static long enter(@Advice.AllArguments Object[] args){return FullChainProbe.sqlStart(args);}
  @Advice.OnMethodExit(onThrowable=Throwable.class) public static void exit(@Advice.Enter long start,@Advice.AllArguments Object[] args,@Advice.Thrown Throwable error,@Advice.Return(typing=net.bytebuddy.implementation.bytecode.assign.Assigner.Typing.DYNAMIC) Object result){FullChainProbe.sqlEnd(args,start,error,result);}
 }
}
