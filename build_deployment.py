from build_ticketing import put,root
put('ticket-risk-platform/Dockerfile','''
FROM maven:3.9-eclipse-temurin-8 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -B package -DskipTests
FROM eclipse-temurin:8-jre
WORKDIR /app
COPY --from=build /app/target/ticket-risk-platform-*.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
''')
put('ticket-risk-web/nginx/default.conf','''
server {
 listen 80;
 server_name localhost;
 root /usr/share/nginx/html;
 index index.html;
 location / { try_files $uri $uri/ /index.html; }
 location /api/ {
  proxy_pass http://ticket-risk-platform:8082/api/;
  proxy_set_header Host $host;
  proxy_set_header X-Forwarded-For "";
  proxy_set_header X-Real-IP "";
 }
}
''')
put('docker/rocketmq/broker.conf','''
brokerClusterName=DefaultCluster
brokerName=ticket-broker
brokerId=0
deleteWhen=04
fileReservedTime=48
brokerRole=ASYNC_MASTER
flushDiskType=SYNC_FLUSH
brokerIP1=ticket-rmqbroker
listenPort=10911
autoCreateTopicEnable=false
''')
put('docker/rocketmq/init-topic.sh','''
#!/bin/sh
set -eu
attempt=0
while [ "$attempt" -lt 60 ]; do
 if sh /home/rocketmq/rocketmq-4.9.7/bin/mqadmin updateTopic -n ticket-rmqnamesrv:9876 -t ticket-events -c DefaultCluster -r 8 -w 8; then exit 0; fi
 attempt=$((attempt+1))
 sleep 3
done
exit 1
''')
put('docker-compose.yml','''
services:
  ticket-mysql:
    image: mysql:8.0
    environment:
      MYSQL_DATABASE: ticketing
      MYSQL_USER: ticketing
      MYSQL_PASSWORD: ${DB_PASSWORD:-ticketing-local}
      MYSQL_ROOT_PASSWORD: ${DB_ROOT_PASSWORD:-root-local-only}
    command: --default-authentication-plugin=mysql_native_password
    ports: ["127.0.0.1:3307:3306"]
    volumes:
      - ticket_mysql_data:/var/lib/mysql
      - ./ticket-risk-platform/src/main/resources/schema.sql:/docker-entrypoint-initdb.d/01-schema.sql:ro
      - ./ticket-risk-platform/src/main/resources/data.sql:/docker-entrypoint-initdb.d/02-data.sql:ro
    healthcheck:
      test: ["CMD-SHELL", "mysqladmin ping -h localhost -u root -p$$MYSQL_ROOT_PASSWORD"]
      interval: 5s
      retries: 30
  ticket-redis:
    image: redis:7-alpine
    command: redis-server --appendonly yes --maxmemory-policy noeviction
    ports: ["127.0.0.1:6379:6379"]
    volumes: ["ticket_redis_data:/data"]
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      retries: 20
  ticket-rmqnamesrv:
    image: apache/rocketmq:4.9.7
    command: sh mqnamesrv
    environment:
      JAVA_OPT_EXT: -Xms256m -Xmx256m
  ticket-rmqbroker:
    image: apache/rocketmq:4.9.7
    depends_on: [ticket-rmqnamesrv]
    environment:
      NAMESRV_ADDR: ticket-rmqnamesrv:9876
      JAVA_OPT_EXT: -Xms512m -Xmx512m -XX:MaxDirectMemorySize=256m
    volumes:
      - ./docker/rocketmq/broker.conf:/home/rocketmq/conf/broker.conf:ro
      - ticket_mq_data:/home/rocketmq/store
    command: sh mqbroker -n ticket-rmqnamesrv:9876 -c /home/rocketmq/conf/broker.conf
  ticket-rmq-init:
    image: apache/rocketmq:4.9.7
    depends_on: [ticket-rmqbroker]
    volumes: ["./docker/rocketmq/init-topic.sh:/init-topic.sh:ro"]
    entrypoint: ["sh", "/init-topic.sh"]
    restart: "no"
  risk-agent:
    build: ./risk-agent
    environment:
      INTERNAL_API_KEY: ${INTERNAL_API_KEY:-local-risk-key-change-me}
      OPENAI_API_KEY: ${OPENAI_API_KEY:-}
      LLM_MODEL: ${LLM_MODEL:-gpt-4.1-mini}
  ticket-risk-platform:
    build: ./ticket-risk-platform
    depends_on:
      ticket-mysql: {condition: service_healthy}
      ticket-redis: {condition: service_healthy}
      ticket-rmq-init: {condition: service_completed_successfully}
    environment:
      DB_URL: jdbc:mysql://ticket-mysql:3306/ticketing?useSSL=false&serverTimezone=UTC&characterEncoding=utf8
      DB_USER: ticketing
      DB_PASSWORD: ${DB_PASSWORD:-ticketing-local}
      REDIS_HOST: ticket-redis
      MQ_NAMESERVER: ticket-rmqnamesrv:9876
      RISK_AGENT_URL: http://risk-agent:8090
      INTERNAL_API_KEY: ${INTERNAL_API_KEY:-local-risk-key-change-me}
      DEMO_LOGIN_ENABLED: "true"
    ports: ["127.0.0.1:8082:8082"]
  ticket-risk-web:
    build: ./ticket-risk-web
    depends_on: [ticket-risk-platform]
    ports: ["127.0.0.1:8080:80"]
  prometheus:
    image: prom/prometheus:v2.53.0
    volumes: ["./docker/prometheus.yml:/etc/prometheus/prometheus.yml:ro"]
    ports: ["127.0.0.1:9090:9090"]
volumes:
  ticket_mysql_data:
  ticket_redis_data:
  ticket_mq_data:
''')
put('docker/prometheus.yml','''
global:
  scrape_interval: 15s
scrape_configs:
  - job_name: ticketing
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['ticket-risk-platform:8082']
''')
put('.env.example','''
DB_PASSWORD=ticketing-local
DB_ROOT_PASSWORD=root-local-only
INTERNAL_API_KEY=replace-with-a-shared-local-secret
OPENAI_API_KEY=
LLM_MODEL=gpt-4.1-mini
''')
