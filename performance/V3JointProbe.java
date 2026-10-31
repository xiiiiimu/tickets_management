import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.*;
import java.io.*;
import java.util.*;

/** One read-only persistent JDBC observer; never starts Spring or changes business state. */
public class V3JointProbe {
 public static void main(String[] args)throws Exception {
  String schema=args[0],owner=args[1],session=args[2];
  if(!schema.matches("credit_risk_v3_[a-f0-9]{8}")||!owner.matches("[a-f0-9]{32}")||!session.matches("v3-load-[a-f0-9]{8}-[a-f0-9]{8}"))throw new IllegalArgumentException("Owned V3 scope required");
  String url="jdbc:mysql://127.0.0.1:13316/"+schema+"?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&useLegacyDatetimeCode=false&useLocalSessionState=true&useLocalTransactionState=true&connectTimeout=3000&socketTimeout=5000&enableQueryTimeouts=false";
  ObjectMapper json=new ObjectMapper();
  try(Connection c=DriverManager.getConnection(url,"v3bench",System.getenv("V3_PROBE_PASSWORD"));BufferedReader in=new BufferedReader(new InputStreamReader(System.in,"UTF-8"))) {
   c.setAutoCommit(true);c.setReadOnly(true);
   try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT owner FROM tb_v3_bench_owner")){if(!r.next()||!owner.equals(r.getString(1))||r.next())throw new IllegalStateException("Owner mismatch");}
   String sku="SELECT id FROM tb_ticket_sku WHERE event_session_id='"+session+"'";
   String sql="SELECT CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED) db_epoch_ms,j.*,r.*,b.*,a.* FROM "+
     "(SELECT COUNT(*) jobs,COALESCE(SUM(processing_started_at IS NOT NULL),0) started,COALESCE(SUM(state='PROCESSING'),0) processing,COALESCE(SUM(state='QUEUE_TIMEOUT'),0) queue_timeout,COALESCE(SUM(state IN ('FAILED','REJECTED','EXPIRED','SOLD_OUT')),0) failed,COALESCE(SUM(state='RESERVED'),0) reserved FROM tb_reservation_job WHERE session_id='"+session+"') j CROSS JOIN "+
     "(SELECT COUNT(*) held_orders,COALESCE(SUM(quantity),0) held_tickets,COUNT(DISTINCT user_id) held_buyers FROM tb_reservation WHERE sku_id IN ("+sku+") AND status='HELD') r CROSS JOIN "+
     "(SELECT COALESCE(SUM(total_stock),0) total_stock,COALESCE(SUM(remaining_stock),0) mysql_remaining,COALESCE(MIN(remaining_stock),0) min_bucket FROM tb_ticket_stock_bucket WHERE sku_id IN ("+sku+")) b CROSS JOIN "+
     "(SELECT COALESCE(SUM(quantity),0) allocated FROM tb_stock_bucket_allocation WHERE sku_id IN ("+sku+") AND released=0) a";
   System.out.println("V3_PROBE "+json.writeValueAsString(Collections.singletonMap("ready",true)));System.out.flush();
   String command;
   try(PreparedStatement p=c.prepareStatement(sql)) {
    while((command=in.readLine())!=null){
     Map<String,Object> out=new LinkedHashMap<>();out.put("id",command);out.put("begin_epoch_ms",System.currentTimeMillis());long begin=System.nanoTime();
     try(ResultSet r=p.executeQuery()){
      if(!r.next())throw new IllegalStateException("No aggregate snapshot");
      ResultSetMetaData m=r.getMetaData();for(int i=1;i<=m.getColumnCount();i++)out.put(m.getColumnLabel(i),r.getLong(i));
      out.put("ok",true);
     }catch(Exception e){out.put("ok",false);out.put("error",e.getClass().getName());}
     out.put("query_ns",System.nanoTime()-begin);out.put("end_epoch_ms",System.currentTimeMillis());
     System.out.println("V3_PROBE "+json.writeValueAsString(out));System.out.flush();
    }
   }
  }
 }
}
