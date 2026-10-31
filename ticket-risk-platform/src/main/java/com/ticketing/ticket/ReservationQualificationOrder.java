package com.ticketing.ticket;

import com.ticketing.infrastructure.Db;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.Map;

/**
 * Durable publication order, scoped to one SKU and one assembly version. Never a global mutex.
 *
 * <p>Two modes, chosen once per deployment and persisted in tb_reservation_dispatch.redis_lane_enabled:
 * <ul>
 *   <li><b>SQL cursor</b> (default): qualification publication order in MySQL defines the Lua order; the SKU turn is
 *       a MySQL row lock held across the Lua call and its commit.</li>
 *   <li><b>Redis arrival</b> ({@code attempts.redis-ordered-lane=true}): the order in which the atomic reserve Lua
 *       reaches Redis defines the order. The intent is still committed before Lua, lua_turn_finished is still committed
 *       after a successful Lua, and abort still writes the Redis tombstone first; only the per-SKU MySQL cursor (and
 *       therefore the MySQL work inside the SKU-serial section) disappears. A request that finished qualification
 *       earlier may reserve later; this ordering definition was explicitly accepted.</li>
 * </ul>
 */
@Service
@ConditionalOnProperty(prefix="ticketing.reservations-v2",name={"enabled","attempts.enabled","attempts.ordered-lua"},havingValue="true")
public class ReservationQualificationOrder {
    public static final class Pending extends RuntimeException {
        Pending(){super("An earlier qualification still owns the SKU Lua turn");}
    }
    private final Db db;
    private final boolean redisArrival;
    public ReservationQualificationOrder(Db db){this(db,false);}
    @org.springframework.beans.factory.annotation.Autowired
    public ReservationQualificationOrder(Db db,org.springframework.core.env.Environment env){
        this(db,env.getProperty("ticketing.reservations-v2.attempts.redis-ordered-lane",Boolean.class,false));
    }
    ReservationQualificationOrder(Db db,boolean redisArrival){this.db=db;this.redisArrival=redisArrival;}
    public boolean redisArrival(){return redisArrival;}
    private void transaction(){
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Qualification order requires an owned transaction");
    }
    /** Called last in intent preparation: sequence and reliable intent commit or roll back together. */
    public void publish(Map<String,Object> job){
        transaction();
        if(!ReservationAttemptStore.isAttempt(job)||!"PROCESSING".equals(job.get("state"))||job.get("admission")==null
                ||!("PASS".equals(job.get("decision"))||"REVIEW".equals(job.get("decision"))))
            throw new IllegalStateException("Qualification publication requires a durable allowed admission");
        if(job.get("qualification_seq")!=null)return;
        // Redis arrival: no MySQL cursor and no per-SKU row lock; the reserve Lua itself is the linearization point.
        if(redisArrival)return;
        db.jdbc.update("INSERT INTO tb_reservation_sku_order(sku_id,sale_version,published_seq,next_lua_seq) VALUES(?,?,0,1) ON DUPLICATE KEY UPDATE sku_id=sku_id",job.get("sku_id"),job.get("sale_version"));
        Map<String,Object> lane=lane(job);long seq=Db.number(lane,"published_seq")+1;
        db.jdbc.update("UPDATE tb_reservation_sku_order SET published_seq=? WHERE sku_id=? AND sale_version=?",seq,job.get("sku_id"),job.get("sale_version"));
        if(db.jdbc.update("UPDATE tb_reservation_job SET qualification_seq=? WHERE id=? AND qualification_seq IS NULL AND state='PROCESSING' AND lease_token=?",seq,job.get("id"),job.get("lease_token"))!=1)
            throw new IllegalStateException("Qualification publication lost ownership");
    }
    private Map<String,Object> lane(Map<String,Object> job){
        Map<String,Object> row=db.one("SELECT * FROM tb_reservation_sku_order WHERE sku_id=? AND sale_version=? FOR UPDATE",job.get("sku_id"),job.get("sale_version"));
        if(row==null)throw new IllegalStateException("Missing durable SKU order; do not reconstruct a trading cursor");return row;
    }
    /** Job and intent must already be locked, before taking the per-SKU turn. */
    public void requireTurn(Map<String,Object> job){
        transaction();
        if(redisArrival)return; // never Pending: whoever reaches Redis first reserves first
        if(job.get("qualification_seq")==null)throw new IllegalStateException("Missing qualification publication");
        long head=Db.number(lane(job),"next_lua_seq"),seq=Db.number(job,"qualification_seq");
        if(head<seq)throw new Pending();
        if(head>seq)throw new IllegalStateException("Unrecorded Lua outcome behind the SKU cursor");
    }
    /** Advance only after a successful Lua result OR a confirmed abort tombstone. Same transaction. */
    public void passed(Map<String,Object> job){
        requireTurn(job);
        if(redisArrival){
            if(db.jdbc.update("UPDATE tb_reservation_job SET lua_turn_finished=1 WHERE id=? AND lua_turn_finished=0",job.get("id"))!=1)
                throw new IllegalStateException("Lua turn was already completed");
            return;
        }
        db.jdbc.update("UPDATE tb_reservation_sku_order SET next_lua_seq=next_lua_seq+1 WHERE sku_id=? AND sale_version=? AND next_lua_seq=?",job.get("sku_id"),job.get("sale_version"),job.get("qualification_seq"));
        if(db.jdbc.update("UPDATE tb_reservation_job SET lua_turn_finished=1 WHERE id=? AND lua_turn_finished=0",job.get("id"))!=1)
            throw new IllegalStateException("Lua turn was already completed");
    }
    /** Recovery can tombstone an out-of-turn request, but must not skip its predecessor. */
    public void aborted(Map<String,Object> job){
        transaction();if(job.get("qualification_seq")==null||finished(job))return;
        // Only reachable in Redis-arrival mode for a job published before activation, which the startup drain forbids.
        if(redisArrival)throw new IllegalStateException("SQL-cursor turn found in Redis-arrival mode; drain before activation");
        requireTurn(job);passed(job);
    }
    public static boolean published(Map<String,Object> job){return job!=null&&job.get("qualification_seq")!=null;}
    public static boolean finished(Map<String,Object> job){return job!=null&&job.get("lua_turn_finished") instanceof Number&&Db.number(job,"lua_turn_finished")==1;}
}
