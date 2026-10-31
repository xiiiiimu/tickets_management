package com.ticketing.event;
import com.ticketing.infrastructure.Db;
import org.springframework.stereotype.Service;
import java.util.*;
@Service public class LocalEventCatalogProvider implements EventCatalogProvider {
 private final Db db;public LocalEventCatalogProvider(Db db){this.db=db;}
 public List<Map<String,Object>> events(){return db.jdbc.queryForList("SELECT * FROM tb_event WHERE status='ON_SALE' ORDER BY name");}
}
