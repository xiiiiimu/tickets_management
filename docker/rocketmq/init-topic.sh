#!/bin/sh
set -eu
NAMESRV=ticket-rmqnamesrv:9876
TOPIC=ticket-events
MQADMIN=/home/rocketmq/rocketmq-4.9.7/bin/mqadmin

attempt=0
while [ "$attempt" -lt 60 ]; do
 if sh "$MQADMIN" clusterList -n "$NAMESRV" 2>/dev/null | grep -q DefaultCluster; then break; fi
 attempt=$((attempt+1))
 sleep 3
done

attempt=0
while [ "$attempt" -lt 60 ]; do
 sh "$MQADMIN" updateTopic -n "$NAMESRV" -t "$TOPIC" -c DefaultCluster -r 8 -w 8 >/dev/null 2>&1 || true
 if sh "$MQADMIN" topicList -n "$NAMESRV" 2>/dev/null | grep -qx "$TOPIC"; then exit 0; fi
 attempt=$((attempt+1))
 sleep 3
done
exit 1
