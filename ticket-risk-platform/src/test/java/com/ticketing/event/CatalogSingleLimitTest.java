package com.ticketing.event;
import com.ticketing.infrastructure.Db;
import com.ticketing.inventory.InventoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class CatalogSingleLimitTest {
 Db db;InventoryService inventory;CatalogAdminService service;TransactionTemplate tx;
 @BeforeEach void setup(){DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:h2:mem:single-"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");db=new Db(new JdbcTemplate(ds),new ObjectMapper());tx=new TransactionTemplate(new DataSourceTransactionManager(ds));inventory=mock(InventoryService.class);when(inventory.vectorEnabled()).thenReturn(true);service=new CatalogAdminService(db,inventory);db.jdbc.execute("CREATE TABLE tb_ticket_sku(id VARCHAR PRIMARY KEY,event_session_id VARCHAR,sale_start_time TIMESTAMP,single_purchase_limit INT,remaining_stock INT,version INT DEFAULT 0)");db.jdbc.execute("CREATE TABLE tb_ticket_sale_assembly(session_id VARCHAR PRIMARY KEY,state VARCHAR)");db.jdbc.execute("CREATE TABLE tb_reserve_request(sku_id VARCHAR)");db.jdbc.update("INSERT INTO tb_ticket_sku VALUES('sku','session',?,100,16,0)",new java.sql.Timestamp(System.currentTimeMillis()+3600000));}
 @Test void disabledProtocolCannotAdvertiseAnUnenforcedLimit(){when(inventory.vectorEnabled()).thenReturn(false);assertThrows(RuntimeException.class,()->tx.executeWithoutResult(s->service.singlePurchaseLimit("sku",3)));assertEquals(100,db.jdbc.queryForObject("SELECT single_purchase_limit FROM tb_ticket_sku",Integer.class));}
 @Test void beforeAssemblyLimitChangesWithoutTouchingStockOrRedis(){tx.executeWithoutResult(s->service.singlePurchaseLimit("sku",3));assertEquals(3,db.jdbc.queryForObject("SELECT single_purchase_limit FROM tb_ticket_sku",Integer.class));assertEquals(16,db.jdbc.queryForObject("SELECT remaining_stock FROM tb_ticket_sku",Integer.class));verify(inventory,never()).configure(anyString());}
 @Test void readyOrTradedSkuCannotChangeLimit(){db.jdbc.update("INSERT INTO tb_ticket_sale_assembly VALUES('session','READY')");assertThrows(RuntimeException.class,()->tx.executeWithoutResult(s->service.singlePurchaseLimit("sku",3)));db.jdbc.update("DELETE FROM tb_ticket_sale_assembly");db.jdbc.update("INSERT INTO tb_reserve_request VALUES('sku')");assertThrows(RuntimeException.class,()->tx.executeWithoutResult(s->service.singlePurchaseLimit("sku",3)));assertEquals(100,db.jdbc.queryForObject("SELECT single_purchase_limit FROM tb_ticket_sku",Integer.class));}
 @Test void quantityBoundsAreEnforced(){for(int n:new int[]{0,101})assertThrows(RuntimeException.class,()->tx.executeWithoutResult(s->service.singlePurchaseLimit("sku",n)));}
}
