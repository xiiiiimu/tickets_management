import com.fasterxml.jackson.databind.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.io.*;
import java.sql.*;
import java.util.*;
import java.lang.reflect.*;

/** Persistent localhost JDBC control channel; no container API, no production-schema writes. */
public class ComparisonDatabase {
    static Connection measured(Connection connection,String phase){
        final int[] count={0};
        return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
            try{
                Object value=method.invoke(connection,args);
                if(!method.getName().equals("createStatement"))return value;
                Statement statement=(Statement)value;
                return Proxy.newProxyInstance(Statement.class.getClassLoader(),new Class<?>[]{Statement.class},(p,m,a)->{
                    if(!m.getName().equals("execute")){try{return m.invoke(statement,a);}catch(InvocationTargetException e){throw e.getCause();}}
                    long begin=System.nanoTime();int index=++count[0];boolean success=false;
                    try{Object result=m.invoke(statement,a);success=true;return result;}catch(InvocationTargetException e){throw e.getCause();}
                    finally{System.err.println("INITIALIZATION_SQL phase="+phase+" index="+index+" java_jdbc_ms="+(System.nanoTime()-begin)/1e6+" success="+success);}
                });
            }catch(InvocationTargetException e){throw e.getCause();}
        });
    }
    public static void main(String[] args)throws Exception{
        ObjectMapper json=new ObjectMapper();String schema=System.getenv("COMPARISON_SCHEMA"),owner=System.getenv("COMPARISON_OWNER"),user=System.getenv("COMPARISON_USER");
        if(!schema.matches("credit_risk_controlled_[a-z0-9_]+")||!owner.matches("[a-f0-9]+")||!user.matches("crctrl_[a-z0-9]+"))throw new IllegalArgumentException("Explicit owned namespace required");
        long connecting=System.nanoTime();
        String url="jdbc:mysql://127.0.0.1:13306/?useSSL=false&serverTimezone=UTC&useLegacyDatetimeCode=false&sessionVariables=time_zone='%2B00:00'&allowPublicKeyRetrieval=true&connectTimeout=5000&socketTimeout=30000";
        try(Connection c=DriverManager.getConnection(url,"root",System.getenv("CONTROLLED_MYSQL_ROOT_PASSWORD"));BufferedReader in=new BufferedReader(new InputStreamReader(System.in,"UTF-8"))){
            System.out.println("COMPARISON_JSON "+json.writeValueAsString(Collections.singletonMap("ready_connect_ms",(System.nanoTime()-connecting)/1e6)));System.out.flush();
            String line;while((line=in.readLine())!=null){Map<String,Object> response=new LinkedHashMap<>();
                long operationStart=System.nanoTime();
                try{JsonNode command=json.readTree(line);String op=command.get("op").asText();
                    response.put("id",command.get("id").asText());response.put("operation",op);
                    if(op.equals("create")){
                        try(Statement s=c.createStatement()){
                            s.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4");s.execute("CREATE TABLE "+schema+".tb_controlled_owner(owner VARCHAR(80) PRIMARY KEY)");s.execute("INSERT INTO "+schema+".tb_controlled_owner VALUES('"+owner+"')");
                            String password=System.getenv("COMPARISON_PASSWORD");if(!password.matches("[a-f0-9]+"))throw new IllegalStateException("Generated test password required");
                            s.execute("CREATE USER '"+user+"'@'%' IDENTIFIED BY '"+password+"'");s.execute("GRANT ALL ON "+schema+".* TO '"+user+"'@'%'");
                        }c.setCatalog(schema);
                    }else if(op.equals("initialize-schema")||op.equals("initialize-data")){
                        try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT owner FROM "+schema+".tb_controlled_owner")){if(!r.next()||!owner.equals(r.getString(1))||r.next())throw new IllegalStateException("Owner mismatch");}
                        c.setCatalog(schema);
                        String path="ticket-risk-platform/src/main/resources/"+(op.equals("initialize-schema")?"schema.sql":"data.sql");
                        ScriptUtils.executeSqlScript(measured(c,op),new FileSystemResource(path));
                    }else if(op.equals("inspect-environment")){
                        Map<String,List<String>> found=new LinkedHashMap<>();
                        String[][] checks={{"schemas","SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME LIKE 'credit\\_risk\\_controlled\\_%'"},{"accounts","SELECT user FROM mysql.user WHERE user LIKE 'crctrl\\_%'"}};
                        for(String[] check:checks){List<String> values=new ArrayList<>();try(Statement s=c.createStatement();ResultSet r=s.executeQuery(check[1])){while(r.next())values.add(r.getString(1));}found.put(check[0],values);}response.put("resources",found);
                    }else if(op.equals("cleanup")){
                        boolean exists;try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='"+schema+"'")){r.next();exists=r.getInt(1)==1;}
                        if(!exists){try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT COUNT(*) FROM mysql.user WHERE user='"+user+"' AND host='%'")){r.next();if(r.getInt(1)!=0)throw new IllegalStateException("Schema absent but account exists: manual ownership review required");}}
                        else {
                        try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT owner FROM "+schema+".tb_controlled_owner")){if(!r.next()||!owner.equals(r.getString(1))||r.next())throw new IllegalStateException("Owner mismatch");}
                        c.setCatalog("mysql");try(Statement s=c.createStatement()){s.execute("DROP USER IF EXISTS '"+user+"'@'%'");s.execute("DROP DATABASE "+schema);}
                        }
                        try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT (SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='"+schema+"')+(SELECT COUNT(*) FROM mysql.user WHERE user='"+user+"' AND host='%')")){r.next();if(r.getInt(1)!=0)throw new IllegalStateException("Owned resources remain after cleanup");}response.put("verified_absent",true);
                    }else if(op.equals("query")||op.equals("execute")){
                        c.setCatalog(schema);String sql=command.get("sql").asText();String verb=sql.trim().split("\\s+",2)[0].toUpperCase(Locale.ROOT);
                        if(op.equals("query")&&!Arrays.asList("SELECT","SHOW","EXPLAIN").contains(verb))throw new IllegalArgumentException("Read-only query required");
                        if(op.equals("execute")&&!Arrays.asList("INSERT","UPDATE").contains(verb))throw new IllegalArgumentException("Fixture INSERT/UPDATE only");
                        try(Statement s=c.createStatement()){boolean result=s.execute(sql);if(result){List<Map<String,Object>> rows=new ArrayList<>();try(ResultSet r=s.getResultSet()){ResultSetMetaData meta=r.getMetaData();while(r.next()){Map<String,Object> row=new LinkedHashMap<>();for(int i=1;i<=meta.getColumnCount();i++){Object value=r.getObject(i);row.put(meta.getColumnLabel(i),value instanceof java.util.Date?((java.util.Date)value).getTime():value);}rows.add(row);}}response.put("rows",rows);}else response.put("updated",s.getUpdateCount());}
                    }else throw new IllegalArgumentException("Unknown operation");
                    response.put("ok",true);
                }catch(Exception e){response.put("ok",false);response.put("error",e.getClass().getSimpleName()+": "+e.getMessage());}
                response.put("operation_ms",(System.nanoTime()-operationStart)/1e6);
                System.out.println("COMPARISON_JSON "+json.writeValueAsString(response));System.out.flush();
            }
        }
    }
}
