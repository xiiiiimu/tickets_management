package com.ticketing.ticket;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisStreamCommands;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * V3 Redis key space ({@code {tkv3}} hash slot) and its scripts. Separate from every inventory key ({@code ticket:{sku}:}).
 * Redis state here is a dispatch/accounting cache: final outcomes are always read from MySQL.
 */
@Component
@ConditionalOnProperty(name="ticketing.reservations-v3.enabled",havingValue="true")
public class V3Redis {
    static final String TAG="{tkv3}:",CFG=TAG+"cfg",WAITING=TAG+"waiting",ACTIVE=TAG+"active",STREAM=TAG+"stream",REQ=TAG+"req:",GROUP="v3";
    static String req(String requestId){return REQ+requestId;}
    static String owner(String userId,String sessionId){return TAG+"owner:"+userId+":"+sessionId;}
    static String userInflight(String userId){return TAG+"uinf:"+userId;}

    private final StringRedisTemplate redis;
    final long retentionMillis;
    @SuppressWarnings("rawtypes") private final DefaultRedisScript<List> accept=script("v3_accept"),start=script("v3_start"),capacity=script("v3_capacity");
    private final DefaultRedisScript<Long> renew=longScript("v3_renew"),settle=longScript("v3_settle"),ackDel=longScript("v3_ackdel");
    public V3Redis(StringRedisTemplate redis,Environment env){
        this.redis=redis;
        retentionMillis=env.getProperty("ticketing.reservations-v3.retention-ms",Long.class,1800000L);
        if(retentionMillis<60000||retentionMillis>86400000L)throw new IllegalArgumentException("reservations-v3.retention-ms must be 1min..24h");
    }
    @SuppressWarnings("rawtypes") private static DefaultRedisScript<List> script(String name){DefaultRedisScript<List> s=new DefaultRedisScript<>();s.setLocation(new ClassPathResource("lua/"+name+".lua"));s.setResultType(List.class);return s;}
    private static DefaultRedisScript<Long> longScript(String name){DefaultRedisScript<Long> s=new DefaultRedisScript<>();s.setLocation(new ClassPathResource("lua/"+name+".lua"));s.setResultType(Long.class);return s;}
    @SuppressWarnings("unchecked") private static List<String> strings(List<?> raw){List<String> out=new ArrayList<>();if(raw!=null)for(Object o:raw)out.add(o==null?null:o.toString());return out;}

    /** Everything the consumer needs to insert the protocol-4 job row; carried by the Stream message. */
    public static final class Message {
        public final String streamId,requestId,attemptId,reservationId,orderId,userId,sessionId,skuId,deviceId,ip,fingerprint,traceId;
        public final int quantity;public final long saleVersion,deadline,acceptedAt;
        Message(String streamId,Map<?,?> f){
            this.streamId=streamId;requestId=s(f,"requestId");attemptId=s(f,"attemptId");reservationId=s(f,"reservationId");orderId=s(f,"orderId");
            userId=s(f,"userId");sessionId=s(f,"sessionId");skuId=s(f,"skuId");deviceId=s(f,"deviceId");ip=s(f,"ip");fingerprint=s(f,"fingerprint");
            traceId=f.get("traceId")==null?"":f.get("traceId").toString();
            if(!"1".equals(String.valueOf(f.get("v"))))throw new IllegalArgumentException("Unsupported V3 message version "+f.get("v"));
            quantity=Integer.parseInt(s(f,"quantity"));saleVersion=Long.parseLong(s(f,"saleVersion"));deadline=Long.parseLong(s(f,"deadline"));acceptedAt=Long.parseLong(s(f,"acceptedAt"));
        }
        private static String s(Map<?,?> f,String k){Object v=f.get(k);if(v==null||v.toString().isEmpty())throw new IllegalArgumentException("V3 message misses "+k);return v.toString();}
    }

    // ---------------- acceptance ----------------
    List<String> accept(ReserveRequest r,String fingerprint,String attemptId,String reservationId,String orderId,String ip,long saleVersion,
                        long waitMillis,int userLimit,int fallbackCap,long capFreshMillis,String traceId){
        return strings(redis.execute(accept,Arrays.asList(CFG,req(r.getRequestId()),owner(r.getUserId().toString(),r.getEventSessionId()),WAITING,STREAM,userInflight(r.getUserId().toString())),
            r.getRequestId(),fingerprint,attemptId,r.getUserId().toString(),r.getEventSessionId(),Long.toString(waitMillis),Long.toString(retentionMillis),Integer.toString(userLimit),
            reservationId,orderId,r.getTicketSkuId(),Integer.toString(r.getQuantity()),r.getDeviceId(),ip,Long.toString(saleVersion),traceId==null?"":traceId,
            Integer.toString(fallbackCap),Long.toString(capFreshMillis),REQ));
    }
    Map<Object,Object> request(String requestId){return redis.opsForHash().entries(req(requestId));}

    // ---------------- dispatch ----------------
    List<String> start(Message m,String token,long leaseMillis,int fallbackActiveLimit){
        return strings(redis.execute(start,Arrays.asList(req(m.requestId),WAITING,ACTIVE,userInflight(m.userId),CFG),
            m.requestId,token,Long.toString(leaseMillis),Integer.toString(fallbackActiveLimit),Long.toString(m.deadline),m.fingerprint,m.userId,m.attemptId,m.streamId,Long.toString(retentionMillis)));
    }
    boolean renew(String requestId,String userId,String token,long leaseMillis){
        Long r=redis.execute(renew,Arrays.asList(req(requestId),ACTIVE,userInflight(userId)),requestId,token,Long.toString(leaseMillis));return r!=null&&r==1;
    }
    /** @param token required for an owner's settlement ('' only for MySQL-decided outcomes reached without a Redis lease). */
    boolean settle(String requestId,String userId,String sessionId,String state,String code,String streamId,String token,String aliasOf){
        Long r=redis.execute(settle,Arrays.asList(req(requestId),WAITING,ACTIVE,userInflight(userId),owner(userId,sessionId),STREAM,CFG),
            requestId,state,code==null?"":code,GROUP,streamId==null?"":streamId,token==null?"":token,aliasOf==null?"":aliasOf);
        return r!=null&&r==1;
    }
    void ackDelete(String streamId){redis.execute(ackDel,Collections.singletonList(STREAM),GROUP,streamId);}
    /** @return {redisNow, active, waiting, done} */
    long[] publishCapacity(int cap,int activeLimit,long staleMillis){
        List<String> r=strings(redis.execute(capacity,Arrays.asList(CFG,ACTIVE,WAITING),Integer.toString(cap),Integer.toString(activeLimit),Long.toString(staleMillis)));
        return new long[]{Long.parseLong(r.get(0)),Long.parseLong(r.get(1)),Long.parseLong(r.get(2)),Long.parseLong(r.get(3))};
    }
    void mode(String mode){redis.opsForHash().put(CFG,"mode",mode);}
    String mode(){Object v=redis.opsForHash().get(CFG,"mode");return v==null?null:v.toString();}

    void ensureGroup(){
        redis.execute((RedisCallback<Object>)c->{
            try{return c.execute("XGROUP",b("CREATE"),b(STREAM),b(GROUP),b("0"),b("MKSTREAM"));}
            catch(RuntimeException exists){if(String.valueOf(exists.getMessage()).contains("BUSYGROUP"))return null;throw exists;}
        });
    }
    private static byte[] b(String s){return s.getBytes(StandardCharsets.UTF_8);}

    /** Non-blocking read of new messages (no shared-connection blocking). */
    List<MapRecord<String,Object,Object>> readNew(String consumer,int count){
        List<MapRecord<String,Object,Object>> r=redis.opsForStream().read(Consumer.from(GROUP,consumer),StreamReadOptions.empty().count(count),StreamOffset.create(STREAM,ReadOffset.lastConsumed()));
        return r==null?Collections.emptyList():r;
    }
    /** This consumer's own unacknowledged messages (FULL retries, failures before settlement). */
    List<MapRecord<String,Object,Object>> readOwnPending(String consumer,int count){
        List<MapRecord<String,Object,Object>> r=redis.opsForStream().read(Consumer.from(GROUP,consumer),StreamReadOptions.empty().count(count),StreamOffset.create(STREAM,ReadOffset.from("0")));
        return r==null?Collections.emptyList():r;
    }
    /** Messages of any consumer idle at least minIdle (crashed or stuck owner), claimed by this consumer. */
    List<MapRecord<String,Object,Object>> claimIdle(String consumer,Duration minIdle,int count){
        PendingMessages pending=redis.opsForStream().pending(STREAM,GROUP,Range.unbounded(),Math.max(count*4,16));
        if(pending==null||pending.isEmpty())return Collections.emptyList();
        List<RecordId> idle=new ArrayList<>();
        for(PendingMessage p:pending)if(p.getElapsedTimeSinceLastDelivery().compareTo(minIdle)>=0&&!consumer.equals(p.getConsumerName())){idle.add(p.getId());if(idle.size()>=count)break;}
        if(idle.isEmpty())return Collections.emptyList();
        RecordId[] ids=idle.toArray(new RecordId[0]);
        List<ByteRecord> raw=redis.execute((RedisCallback<List<ByteRecord>>)c->c.streamCommands().xClaim(b(STREAM),GROUP,consumer,RedisStreamCommands.XClaimOptions.minIdle(minIdle).ids(ids)));
        List<MapRecord<String,Object,Object>> out=new ArrayList<>();
        if(raw!=null)for(ByteRecord record:raw){
            Map<Object,Object> fields=new LinkedHashMap<>();
            for(Map.Entry<byte[],byte[]> e:record.getValue().entrySet())fields.put(new String(e.getKey(),StandardCharsets.UTF_8),new String(e.getValue(),StandardCharsets.UTF_8));
            out.add(StreamRecords.newRecord().in(STREAM).withId(record.getId()).ofMap(fields));
        }
        return out;
    }
    /** Re-claim own messages to reset their idle time while the work is alive (heartbeat). */
    void touch(String consumer,Collection<String> streamIds){
        if(streamIds.isEmpty())return;
        RecordId[] ids=streamIds.stream().map(RecordId::of).toArray(RecordId[]::new);
        redis.execute((RedisCallback<Object>)c->c.streamCommands().xClaimJustId(b(STREAM),GROUP,consumer,RedisStreamCommands.XClaimOptions.minIdle(Duration.ZERO).ids(ids)));
    }
    long streamLength(){Long n=redis.opsForStream().size(STREAM);return n==null?0:n;}
    long pendingCount(){PendingMessagesSummary s=redis.opsForStream().pending(STREAM,GROUP);return s==null?0:s.getTotalPendingMessages();}
}
