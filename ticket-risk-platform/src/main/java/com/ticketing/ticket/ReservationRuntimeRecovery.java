package com.ticketing.ticket;
import com.ticketing.infrastructure.Db;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.sql.Timestamp;
import java.util.*;
/** Adapter for the existing recovery job: queue row is locked before the intent row. */
@Service
@ConditionalOnProperty(name="ticketing.reservations-v2.enabled",havingValue="true")
public class ReservationRuntimeRecovery {
    private final Db db;private final ReservationDispatch queue;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private ReservationQualificationOrder qualificationOrder;
    public ReservationRuntimeRecovery(Db db,ReservationDispatch queue){this.db=db;this.queue=queue;}
    public static void assertOwned(Map<String,Object> job,ReservationIntent intent){
        com.ticketing.common.BusinessException.require(intent!=null
                &&Objects.equals(job.get("request_id"),intent.getRequestId())
                &&Objects.equals(job.get("fingerprint"),intent.getFingerprint())
                &&Objects.equals(job.get("reservation_id"),intent.getReservationId())
                &&Objects.equals(job.get("order_id"),intent.getOrderId()),"调度请求与预占意图不一致，保留现场等待核对");
    }
    public Map<String,Object> lock(String request){return db.one("SELECT * FROM tb_reservation_job WHERE request_id=? FOR UPDATE",request);}
    public boolean active(Map<String,Object> job){return Arrays.asList("CLAIMED","PROCESSING").contains(job.get("state"))&&job.get("lease_until")!=null&&((Timestamp)job.get("lease_until")).getTime()>System.currentTimeMillis();}
    public void failed(Map<String,Object> job){
        // Caller has already confirmed abortRuntime's tombstone in the same recovery transaction.
        if(qualificationOrder!=null)qualificationOrder.aborted(job);
        queue.audit(job,"RECOVERY",String.valueOf(job.get("decision")),"RECOVERED_ABORT",Collections.emptyMap());
        db.jdbc.update("UPDATE tb_reservation_job SET state='FAILED',result_code='RECOVERED_ABORT',active_owner=NULL,lease_token=NULL,lease_until=NULL WHERE id=?",job.get("id"));
    }
}
