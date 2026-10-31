import com.fasterxml.jackson.databind.*;
import java.sql.*;
import java.util.*;

/** Explicitly-owned schema provisioning over localhost JDBC; no Docker API or business DB. */
public class ControlledTestSql {
    public static void main(String[] args)throws Exception{
        ObjectMapper json=new ObjectMapper();JsonNode n=json.readTree(System.in);
        String schema=n.get("schema").asText(),user=n.get("user").asText(),owner=n.get("owner").asText();
        if(!schema.matches("credit_risk_controlled_[a-z0-9_]+")||!user.matches("crctrl_[a-z0-9]+")||!owner.matches("[a-f0-9]+"))throw new IllegalArgumentException("Owned test identifiers required");
        String url=n.get("url").asText();if(!url.startsWith("jdbc:mysql://127.0.0.1:13306/"))throw new IllegalArgumentException("Project localhost test server required");
        try(Connection c=DriverManager.getConnection(url,"root",System.getenv("CONTROLLED_MYSQL_ROOT_PASSWORD"));Statement s=c.createStatement()){
            if("create".equals(n.get("mode").asText())){
                String password=n.get("password").asText();if(!password.matches("[a-f0-9]+"))throw new IllegalArgumentException("Test password must be generated hex");
                s.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4");
                s.execute("CREATE TABLE "+schema+".tb_controlled_owner(owner VARCHAR(80) PRIMARY KEY)");
                s.execute("INSERT INTO "+schema+".tb_controlled_owner VALUES('"+owner+"')");
                s.execute("CREATE USER '"+user+"'@'%' IDENTIFIED BY '"+password+"'");
                s.execute("GRANT ALL ON "+schema+".* TO '"+user+"'@'%'");
            }else if("cleanup".equals(n.get("mode").asText())){
                try(ResultSet row=s.executeQuery("SELECT owner FROM "+schema+".tb_controlled_owner")){
                    if(!row.next()||!owner.equals(row.getString(1))||row.next())throw new IllegalStateException("Ownership mismatch; refuse cleanup");
                }
                s.execute("DROP DATABASE "+schema);s.execute("DROP USER '"+user+"'@'%'");
            }else throw new IllegalArgumentException("Unknown operation");
        }
        System.out.println("CONTROLLED_RESOURCE_OK");
    }
}
