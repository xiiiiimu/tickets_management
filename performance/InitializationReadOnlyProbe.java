import java.sql.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
/** Read-only current-state precheck. Does not create schemas or change MySQL settings. */
public class InitializationReadOnlyProbe {
 public static void main(String[] args)throws Exception {
  long begin=System.nanoTime();Map<String,Object> out=new LinkedHashMap<>();
  String url="jdbc:mysql://127.0.0.1:13306/mysql?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true&connectTimeout=5000&socketTimeout=10000";
  try(Connection c=DriverManager.getConnection(url,"root",System.getenv("CONTROLLED_MYSQL_ROOT_PASSWORD"))){
   out.put("connect_ms",(System.nanoTime()-begin)/1e6);
   String[] queries={"SELECT NOW(3) server_time,@@version version,@@performance_schema performance_schema,@@innodb_flush_log_at_trx_commit flush_at_commit,@@sync_binlog sync_binlog",
    "SELECT ID,USER,DB,COMMAND,TIME,STATE FROM information_schema.PROCESSLIST WHERE ID<>CONNECTION_ID()",
    "SELECT OBJECT_SCHEMA,OBJECT_NAME,LOCK_TYPE,LOCK_STATUS FROM performance_schema.metadata_locks WHERE LOCK_STATUS='PENDING'",
    "SELECT COUNT(*) lock_wait_rows FROM performance_schema.data_lock_waits",
    "SELECT VARIABLE_NAME,VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME IN ('Uptime','Threads_running','Threads_connected','Innodb_log_waits','Innodb_row_lock_waits','Innodb_row_lock_time','Innodb_data_pending_fsyncs','Innodb_data_pending_reads','Innodb_data_pending_writes')",
    "SELECT EVENT_NAME,COUNT_STAR,SUM_TIMER_WAIT,MAX_TIMER_WAIT FROM performance_schema.file_summary_by_event_name WHERE EVENT_NAME LIKE 'wait/io/file/innodb/%'",
    "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME LIKE 'credit_risk_controlled_%'",
    "SELECT NAME,ENABLED FROM performance_schema.setup_consumers WHERE NAME LIKE 'events_statements%'"};
   List<Object> results=new ArrayList<>();for(String sql:queries){Map<String,Object> item=new LinkedHashMap<>();item.put("query",sql);long start=System.nanoTime();try(Statement s=c.createStatement()){s.setQueryTimeout(10);try(ResultSet r=s.executeQuery(sql)){List<Object> rows=new ArrayList<>();ResultSetMetaData m=r.getMetaData();while(r.next()){Map<String,Object> row=new LinkedHashMap<>();for(int i=1;i<=m.getColumnCount();i++)row.put(m.getColumnLabel(i),r.getString(i));rows.add(row);}item.put("rows",rows);}}catch(SQLException e){item.put("error",e.getMessage());}item.put("client_ms",(System.nanoTime()-start)/1e6);results.add(item);}out.put("queries",results);
  }
  new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(new java.io.File(args[0]),out);
 }
}
