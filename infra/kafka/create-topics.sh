#!/usr/bin/env bash
#
# Creates the topics from docs/architecture.md against the local broker.
#
# Idempotent: --if-not-exists means a re-run on an existing cluster is a
# no-op, so `docker compose up` can be run repeatedly without error.
#
# Topic naming follows <domain>.<entity>.<event-type>. Partitioning is by
# the entity the event concerns (rule ID, request target), which is what
# gives per-entity ordering -- Kafka only orders within a partition.

set -euo pipefail

BOOTSTRAP_SERVER="${BOOTSTRAP_SERVER:-kafka:29092}"
KAFKA_TOPICS="/opt/kafka/bin/kafka-topics.sh"

# Control-plane events are low volume and keyed by rule ID. Three partitions
# is enough parallelism for local work while still being >1, so that any
# ordering bug that only appears across partitions shows up locally rather
# than first appearing in production.
CONTROL_PARTITIONS=3

# Traffic events are the high-volume stream -- one per proxied request.
TRAFFIC_PARTITIONS=6

# 7 days for control-plane events: long enough to replay a consumer from the
# start of a week's work.
CONTROL_RETENTION_MS=$((7 * 24 * 60 * 60 * 1000))

# 24 hours for traffic events. Local disk is finite and a load test can
# produce a lot of these; analytics aggregates them promptly anyway.
TRAFFIC_RETENTION_MS=$((24 * 60 * 60 * 1000))

create_topic() {
  local topic="$1"
  local partitions="$2"
  local retention_ms="$3"

  echo "  creating ${topic} (partitions=${partitions}, retention=${retention_ms}ms)"
  "${KAFKA_TOPICS}" \
    --bootstrap-server "${BOOTSTRAP_SERVER}" \
    --create \
    --if-not-exists \
    --topic "${topic}" \
    --partitions "${partitions}" \
    --replication-factor 1 \
    --config "retention.ms=${retention_ms}" \
    --config min.insync.replicas=1
}

echo "Waiting for broker at ${BOOTSTRAP_SERVER}..."
# The broker healthcheck already gates this container, but that check runs
# against the broker's own listener. This confirms reachability over the
# Docker network specifically, which is the address these commands use.
for attempt in $(seq 1 30); do
  if "${KAFKA_TOPICS}" --bootstrap-server "${BOOTSTRAP_SERVER}" --list >/dev/null 2>&1; then
    break
  fi
  if [ "${attempt}" -eq 30 ]; then
    echo "ERROR: broker at ${BOOTSTRAP_SERVER} not reachable after 30 attempts" >&2
    exit 1
  fi
  sleep 2
done

echo "Creating control-plane topics..."
create_topic "control.rule.created"          "${CONTROL_PARTITIONS}" "${CONTROL_RETENTION_MS}"
create_topic "control.rule.updated"          "${CONTROL_PARTITIONS}" "${CONTROL_RETENTION_MS}"
create_topic "control.rule.deleted"          "${CONTROL_PARTITIONS}" "${CONTROL_RETENTION_MS}"
create_topic "control.deployment.succeeded"  "${CONTROL_PARTITIONS}" "${CONTROL_RETENTION_MS}"
create_topic "control.deployment.failed"     "${CONTROL_PARTITIONS}" "${CONTROL_RETENTION_MS}"

echo "Creating traffic topics..."
create_topic "traffic.request.allowed"       "${TRAFFIC_PARTITIONS}" "${TRAFFIC_RETENTION_MS}"
create_topic "traffic.request.throttled"     "${TRAFFIC_PARTITIONS}" "${TRAFFIC_RETENTION_MS}"

# Dead-letter topics. A message that cannot be processed after its retries
# are exhausted goes here rather than blocking the partition behind it or
# being dropped. Retention is longer than the source topic: a DLQ nobody
# read before it expired provides no diagnostic value.
echo "Creating dead-letter topics..."
create_topic "control.rule.created.dlt"      "${CONTROL_PARTITIONS}" "${CONTROL_RETENTION_MS}"
create_topic "control.rule.updated.dlt"      "${CONTROL_PARTITIONS}" "${CONTROL_RETENTION_MS}"
create_topic "control.rule.deleted.dlt"      "${CONTROL_PARTITIONS}" "${CONTROL_RETENTION_MS}"

echo
echo "Topics now present:"
"${KAFKA_TOPICS}" --bootstrap-server "${BOOTSTRAP_SERVER}" --list | sed 's/^/  /'
echo
echo "Topic provisioning complete."
