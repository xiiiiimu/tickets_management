package com.ticketing.event;
import com.ticketing.infrastructure.Db;
import com.ticketing.common.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/api/events")
public class EventController {
 private final EventCatalogProvider catalog;private final Db db;
 public EventController(EventCatalogProvider catalog,Db db){this.catalog=catalog;this.db=db;}
 @GetMapping public Result list(){return Result.ok(catalog.events());}
 @GetMapping("/{id}") public Result detail(@PathVariable String id){Map<String,Object> event=db.one("SELECT * FROM tb_event WHERE id=? AND status='ON_SALE'",id);if(event==null)throw new BusinessException(404,"演出不存在");event.put("sessions",db.jdbc.queryForList("SELECT * FROM tb_event_session WHERE event_id=? ORDER BY starts_at",id));event.put("skus",db.jdbc.queryForList("SELECT s.* FROM tb_ticket_sku s JOIN tb_event_session e ON e.id=s.event_session_id WHERE e.event_id=?",id));return Result.ok(event);}
}
