-- One logical database per service.
--
-- A single Postgres container is a local convenience. Separate databases
-- keep it from becoming shared-database coupling: no service can read
-- another's tables, which is the constraint that makes the services
-- independently deployable later (architecture.md). In production these
-- are separate RDS instances or at minimum separate databases with
-- separate credentials.
--
-- Schema itself is NOT created here. Each service owns its schema and
-- applies it with Flyway migrations at startup, so the schema is versioned
-- in the service's own source tree rather than in an init script that only
-- ever runs on a fresh volume.
--
-- This file runs ONLY on first initialization of an empty data directory.
-- After that Postgres skips /docker-entrypoint-initdb.d entirely. To pick
-- up a change here:  docker compose -f infra/docker-compose.yml down -v

CREATE DATABASE rule_service;
CREATE DATABASE deployment_service;
CREATE DATABASE audit_service;
CREATE DATABASE analytics_service;

COMMENT ON DATABASE rule_service IS
  'Rule Management Service: rules and immutable rule_versions (Phase 1), outbox (Phase 2)';
COMMENT ON DATABASE deployment_service IS
  'Deployment Service: deployments, processed_events for idempotency (Phase 2)';
COMMENT ON DATABASE audit_service IS
  'Audit Service: audit_log of who changed what (Phase 2)';
COMMENT ON DATABASE analytics_service IS
  'Analytics Service: traffic aggregates (Phase 3)';
