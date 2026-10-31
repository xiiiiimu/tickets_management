import java.sql.*;
public class InspectComparisonCleanup {
 public static void main(String[] args)throws Exception {
  try(Connection c=DriverManager.getConnection("jdbc:mysql://127.0.0.1:13306/mysql?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true","root",System.getenv("CONTROLLED_MYSQL_ROOT_PASSWORD")); Statement s=c.createStatement()) {
   if(args.length==1 && args[0].equals("cleanup-owned")) {
    String db="credit_risk_controlled_20261030_051537_c83f";
    try(ResultSet r=s.executeQuery("SELECT owner FROM "+db+".tb_controlled_owner")){if(!r.next()||!r.getString(1).matches("[a-f0-9]{32}")||r.next())throw new IllegalStateException("Invalid owner");}
    for(String table:new String[]{"tb_reservation_job","tb_ticket_order","tb_reserve_request"})try(ResultSet r=s.executeQuery("SELECT COUNT(*) FROM "+db+"."+table)){r.next();if(r.getInt(1)!=0)throw new IllegalStateException("Nonempty business table");}
    s.execute("DROP DATABASE "+db);s.execute("DROP USER 'crctrl_d2e48cb2fe0441f0'@'%'");System.out.println("OWNED_EMPTY_SCHEMA_AND_ACCOUNT_REMOVED");
   }
   try(ResultSet r=s.executeQuery("SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME LIKE 'credit_risk_controlled_20261030_0515%'")){while(r.next())System.out.println("SCHEMA "+r.getString(1));}
   try(ResultSet r=s.executeQuery("SELECT GRANTEE,TABLE_SCHEMA FROM information_schema.SCHEMA_PRIVILEGES WHERE TABLE_SCHEMA LIKE 'credit_risk_controlled_20261030_0515%' GROUP BY GRANTEE,TABLE_SCHEMA")){while(r.next())System.out.println("GRANT "+r.getString(1)+" "+r.getString(2));}
  }
 }
}
