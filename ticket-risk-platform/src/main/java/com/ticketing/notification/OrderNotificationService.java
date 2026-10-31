package com.ticketing.notification;
import com.ticketing.common.BusinessException;
import com.ticketing.infrastructure.Db;
import com.ticketing.order.TicketOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;

/** Reliable in-app inbox. AVAILABLE means delivered to inbox; READ is a separate recipient acknowledgement. */
@Service
@ConditionalOnProperty(name="ticketing.reservations-v2.enabled",havingValue="true")
public class OrderNotificationService {
    private final Db db;
    public OrderNotificationService(Db db){this.db=db;}
    public void enqueue(TicketOrder order){
        requireTx();Map<String,Object> payload=new LinkedHashMap<>();payload.put("orderId",order.getId());payload.put("kind","TICKET_ISSUED");payload.put("quantity",order.getQuantity());
        db.jdbc.update("INSERT INTO tb_order_notification(id,order_id,user_id,kind,status,body) VALUES(?,?,?,'TICKET_ISSUED','PENDING',?) ON DUPLICATE KEY UPDATE order_id=order_id",Db.id(),order.getId(),order.getUserId(),db.json(payload));
    }
    public void deliver(String order){
        requireTx();Map<String,Object> notice=db.one("SELECT * FROM tb_order_notification WHERE order_id=? AND kind='TICKET_ISSUED' FOR UPDATE",order);
        BusinessException.require(notice!=null,"出票通知尚未可靠创建");
        if(!"PENDING".equals(notice.get("status")))return;
        db.jdbc.update("UPDATE tb_order_notification SET status='AVAILABLE',delivery_attempts=delivery_attempts+1,delivered_at=CURRENT_TIMESTAMP(3) WHERE id=?",notice.get("id"));
    }
    public List<Map<String,Object>> list(long user,boolean unread){
        return db.jdbc.queryForList("SELECT id,order_id,kind,status,body,created_at,delivered_at,read_at FROM tb_order_notification WHERE user_id=? AND "
                +(unread?"status='AVAILABLE' ORDER BY created_at,id":"status IN ('AVAILABLE','READ') ORDER BY created_at DESC,id DESC")+" LIMIT 100",user);
    }
    @Transactional public Map<String,Object> read(String id,long user){
        Map<String,Object> notice=db.one("SELECT * FROM tb_order_notification WHERE id=? FOR UPDATE",id);
        if(notice==null)throw new BusinessException(404,"通知不存在");
        if(Db.number(notice,"user_id")!=user)throw new BusinessException(403,"无权读取通知");
        BusinessException.require(!"PENDING".equals(notice.get("status")),"通知尚未投递");
        if(!"READ".equals(notice.get("status")))db.jdbc.update("UPDATE tb_order_notification SET status='READ',read_at=CURRENT_TIMESTAMP(3) WHERE id=?",id);
        return db.one("SELECT id,status,read_at FROM tb_order_notification WHERE id=?",id);
    }
    private void requireTx(){if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Notification must join source/consume transaction");}
}
