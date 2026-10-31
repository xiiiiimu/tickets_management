package com.ticketing;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
@org.mybatis.spring.annotation.MapperScan({"com.ticketing.user.mapper","com.ticketing.audit.mapper","com.ticketing.workflow.mapper"})
@EnableScheduling
@SpringBootApplication
public class TicketPlatformApplication {
 public static void main(String[] args) { SpringApplication.run(TicketPlatformApplication.class,args); }
}
