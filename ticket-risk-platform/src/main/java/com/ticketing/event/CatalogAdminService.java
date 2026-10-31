package com.ticketing.event;
import com.ticketing.infrastructure.Db;
import com.ticketing.inventory.InventoryService;
import com.ticketing.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
@Service
public class CatalogAdminService {
 private final Db db;private final InventoryService inventory;
 public CatalogAdminService(Db db,InventoryService inventory){this.db=db;this.inventory=inventory;}
 @Transactional public void singlePurchaseLimit(String id,int quantity){
  BusinessException.require(inventory.vectorEnabled(),"单次限额配置须先启用兼容库存协议");
  BusinessException.require(quantity>=1&&quantity<=100,"单次购票数量必须为1至100");
  Map<String,Object> identity=db.one("SELECT event_session_id FROM tb_ticket_sku WHERE id=?",id);BusinessException.require(identity!=null,"票档不存在");
  Map<String,Object> assembly=db.one("SELECT state FROM tb_ticket_sale_assembly WHERE session_id=? FOR UPDATE",identity.get("event_session_id"));
  BusinessException.require(assembly==null||Arrays.asList("DRAFT","FAILED").contains(assembly.get("state")),"单次限额须在首次装配前配置");
  Map<String,Object> sku=db.one("SELECT * FROM tb_ticket_sku WHERE id=? FOR UPDATE",id);
  BusinessException.require(((java.sql.Timestamp)sku.get("sale_start_time")).getTime()>System.currentTimeMillis()&&db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_reserve_request WHERE sku_id=?",Long.class,id)==0,"开售或交易后不可变更单次限额");
  db.jdbc.update("UPDATE tb_ticket_sku SET single_purchase_limit=?,version=version+1 WHERE id=?",quantity,id);
 }
 @Transactional public void update(String id,String status,Integer stock){
  BusinessException.require(Arrays.asList("ON_SALE","OFF_SALE").contains(status),"票档状态无效");
  Map<String,Object> sku=db.one("SELECT * FROM tb_ticket_sku WHERE id=? FOR UPDATE",id);BusinessException.require(sku!=null,"票档不存在");
  if(stock!=null){BusinessException.require(stock>=0&&db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_reserve_request WHERE sku_id=?",Long.class,id)==0,"开始交易后不可重设库存");db.jdbc.update("UPDATE tb_ticket_sku SET total_stock=?,remaining_stock=? WHERE id=?",stock,stock,id);}
  db.jdbc.update("UPDATE tb_ticket_sku SET status=?,version=version+1 WHERE id=?",status,id);
  // Refresh Redis sale config after admin change (not on reserve hot path).
  inventory.configure(id);
 }
}
