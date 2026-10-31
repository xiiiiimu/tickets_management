package com.ticketing.ticket;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="ticketing.attempts.mysql",matches="true")
class VectorMigrationMysqlTest extends ReservationAttemptMysqlTest {
    @BeforeAll static void migrateVector(){DriverManagerDataSource ds=new DriverManagerDataSource(System.getenv("SPRING_DATASOURCE_URL"),System.getenv("SPRING_DATASOURCE_USERNAME"),System.getenv("SPRING_DATASOURCE_PASSWORD"));JdbcTemplate jdbc=new JdbcTemplate(ds);assertEquals(System.getenv("CONTROLLED_TEST_OWNER"),jdbc.queryForObject("SELECT owner FROM tb_controlled_owner",String.class));new ResourceDatabasePopulator(new ClassPathResource("db/migration/V111__qualification_lua_order.sql"),new ClassPathResource("db/migration/V112__attempt_quick_review_mode.sql")).execute(ds);jdbc.execute("CREATE TABLE tb_ticket_sku(id VARCHAR(36) PRIMARY KEY,total_stock INT)");jdbc.update("INSERT INTO tb_ticket_sku VALUES('old-sku',16)");new ResourceDatabasePopulator(new ClassPathResource("db/migration/V113__vector_stock_allocations.sql")).execute(ds);assertEquals(16,jdbc.queryForObject("SELECT total_stock FROM tb_ticket_sku WHERE id='old-sku'",Integer.class));assertEquals(100,jdbc.queryForObject("SELECT single_purchase_limit FROM tb_ticket_sku WHERE id='old-sku'",Integer.class));}
    @Test void additiveMigrationLeavesOldRowsAndModeDisabled(){assertEquals(0,db.jdbc.queryForObject("SELECT vector_inventory_enabled FROM tb_reservation_dispatch",Integer.class));assertEquals(0,db.jdbc.queryForObject("SELECT COUNT(*) FROM tb_stock_bucket_allocation_item",Integer.class));}
    @Test void mixedProtocolNodesAreRejected(){db.jdbc.update("UPDATE tb_reservation_dispatch SET vector_inventory_enabled=1,ordered_lua_enabled=1");try{assertThrows(IllegalStateException.class,first::initialize);MockEnvironment active=new MockEnvironment().withProperty("spring.datasource.url",System.getenv("SPRING_DATASOURCE_URL")).withProperty("ticketing.reservations-v2.attempts.ordered-lua","true").withProperty("ticketing.inventory.vector-reservations","true");new ReservationAttemptStore(db,queue,manager,metrics,active).initialize();}finally{db.jdbc.update("UPDATE tb_reservation_dispatch SET vector_inventory_enabled=0,ordered_lua_enabled=0");}}
}
