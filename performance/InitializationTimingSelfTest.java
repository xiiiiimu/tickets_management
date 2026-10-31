import java.lang.reflect.*;
import java.sql.*;
public class InitializationTimingSelfTest {
 public static void main(String[] args)throws Exception {
  Statement statement=(Statement)Proxy.newProxyInstance(Statement.class.getClassLoader(),new Class<?>[]{Statement.class},(p,m,a)->{
   if(m.getName().equals("execute")){if(a[0].equals("FAIL"))throw new SQLException("injected");return true;}return null;
  });
  Connection connection=(Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->m.getName().equals("createStatement")?statement:null);
  Statement timed=ComparisonDatabase.measured(connection,"offline").createStatement();
  if(!timed.execute("OK"))throw new AssertionError("result changed");
  try{timed.execute("FAIL");throw new AssertionError("exception lost");}catch(SQLException e){if(!e.getMessage().equals("injected"))throw e;}
  System.out.println("TIMING_PROXY_OK: result and SQLException preserved; no database used");
 }
}
