CREATE TABLE users (
 id CHAR(36) PRIMARY KEY, email VARCHAR(190) NOT NULL UNIQUE, password_hash VARCHAR(100) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
CREATE TABLE teams (
 id CHAR(36) PRIMARY KEY, name VARCHAR(120) NOT NULL, created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
CREATE TABLE memberships (
 team_id CHAR(36) NOT NULL, user_id CHAR(36) NOT NULL, role VARCHAR(20) NOT NULL,
 PRIMARY KEY(team_id,user_id), FOREIGN KEY(team_id) REFERENCES teams(id), FOREIGN KEY(user_id) REFERENCES users(id),
 CHECK (role IN ('OWNER','MAINTAINER','REVIEWER','VIEWER')), INDEX(user_id,team_id)
);
CREATE TABLE projects (
 id CHAR(36) PRIMARY KEY, team_id CHAR(36) NOT NULL, name VARCHAR(120) NOT NULL,
 current_policy INT NOT NULL DEFAULT 1, created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(team_id) REFERENCES teams(id), UNIQUE(team_id,name), INDEX(team_id,id)
);
CREATE TABLE policies (
 project_id CHAR(36) NOT NULL, version INT NOT NULL, blocked_severities VARCHAR(100) NOT NULL,
 require_review BOOLEAN NOT NULL, evidence_hours INT NOT NULL, exception_days INT NOT NULL,
 created_by CHAR(36) NOT NULL, created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(project_id,version), FOREIGN KEY(project_id) REFERENCES projects(id), FOREIGN KEY(created_by) REFERENCES users(id)
);
CREATE TABLE releases (
 id CHAR(36) PRIMARY KEY, project_id CHAR(36) NOT NULL, tag VARCHAR(120) COLLATE utf8mb4_bin NOT NULL, sbom_hash CHAR(64) NOT NULL,
 raw_sbom LONGBLOB NOT NULL, root_ref VARCHAR(255) COLLATE utf8mb4_bin NOT NULL, created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE(project_id,tag), UNIQUE(project_id,sbom_hash), FOREIGN KEY(project_id) REFERENCES projects(id), INDEX(project_id,id)
);
CREATE TABLE components (
 id CHAR(36) PRIMARY KEY, release_id CHAR(36) NOT NULL, bom_ref VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
 group_name VARCHAR(255) NOT NULL, name VARCHAR(255) NOT NULL, version VARCHAR(255) NOT NULL, purl VARCHAR(1200) NOT NULL,
 UNIQUE(release_id,bom_ref), FOREIGN KEY(release_id) REFERENCES releases(id), INDEX(release_id,id)
);
CREATE TABLE dependency_edges (
 release_id CHAR(36) NOT NULL, from_ref VARCHAR(255) COLLATE utf8mb4_bin NOT NULL, to_ref VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
 PRIMARY KEY(release_id,from_ref,to_ref), FOREIGN KEY(release_id) REFERENCES releases(id)
);
CREATE TABLE scans (
 id CHAR(36) PRIMARY KEY, release_id CHAR(36) NOT NULL, ordinal INT NOT NULL, policy_version INT NOT NULL,
 status VARCHAR(20) NOT NULL, report_hash CHAR(64), evidence_at TIMESTAMP(6) NULL,
 started_at TIMESTAMP(6) NULL, completed_at TIMESTAMP(6) NULL, created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE(release_id,ordinal), FOREIGN KEY(release_id) REFERENCES releases(id), INDEX(release_id,id),
 CHECK(status IN ('QUEUED','RUNNING','SUCCEEDED','PARTIAL','FAILED','CANCELLED'))
);
CREATE TABLE scan_batches (
 id CHAR(36) PRIMARY KEY, scan_id CHAR(36) NOT NULL, ordinal INT NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'QUEUED', attempts INT NOT NULL DEFAULT 0, lease_token CHAR(36),
 lease_until TIMESTAMP(6) NULL, available_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 error TEXT, UNIQUE(scan_id,ordinal), FOREIGN KEY(scan_id) REFERENCES scans(id), INDEX(status,available_at,lease_until)
);
CREATE TABLE batch_components (
 batch_id CHAR(36) NOT NULL, component_id CHAR(36) NOT NULL,
 PRIMARY KEY(batch_id,component_id), FOREIGN KEY(batch_id) REFERENCES scan_batches(id), FOREIGN KEY(component_id) REFERENCES components(id)
);
CREATE TABLE component_results (
 scan_id CHAR(36) NOT NULL, component_id CHAR(36) NOT NULL, source VARCHAR(20) NOT NULL,
 status VARCHAR(20) NOT NULL, queried_at TIMESTAMP(6) NOT NULL, evidence_json LONGTEXT NOT NULL, error TEXT,
 PRIMARY KEY(scan_id,component_id), FOREIGN KEY(scan_id) REFERENCES scans(id), FOREIGN KEY(component_id) REFERENCES components(id)
);
CREATE TABLE findings (
 id CHAR(36) PRIMARY KEY, scan_id CHAR(36) NOT NULL, component_id CHAR(36) NOT NULL,
 vulnerability_id VARCHAR(160) COLLATE utf8mb4_bin NOT NULL, severity VARCHAR(20) NOT NULL, summary TEXT NOT NULL,
 evidence_json LONGTEXT NOT NULL, path_json LONGTEXT NOT NULL, source_url VARCHAR(1000) NOT NULL, queried_at TIMESTAMP(6) NOT NULL,
 UNIQUE(scan_id,component_id,vulnerability_id), FOREIGN KEY(scan_id) REFERENCES scans(id),
 FOREIGN KEY(component_id) REFERENCES components(id), INDEX(scan_id,id)
);
CREATE TABLE outbox (
 id CHAR(36) PRIMARY KEY, batch_id CHAR(36) NOT NULL, available_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 sent_at TIMESTAMP(6) NULL, attempts INT NOT NULL DEFAULT 0, last_error TEXT,
 FOREIGN KEY(batch_id) REFERENCES scan_batches(id), INDEX(sent_at,available_at)
);
CREATE TABLE idempotency_keys (
 project_id CHAR(36) NOT NULL, operation VARCHAR(20) NOT NULL, request_key VARCHAR(120) COLLATE utf8mb4_bin NOT NULL,
 request_hash CHAR(64) NOT NULL, resource_id CHAR(36) NOT NULL,
 PRIMARY KEY(project_id,operation,request_key), FOREIGN KEY(project_id) REFERENCES projects(id)
);
CREATE TABLE reviews (
 id CHAR(36) PRIMARY KEY, release_id CHAR(36) NOT NULL, sbom_hash CHAR(64) NOT NULL,
 scan_id CHAR(36) NOT NULL UNIQUE, report_hash CHAR(64) NOT NULL, policy_version INT NOT NULL,
 decision VARCHAR(20) NOT NULL DEFAULT 'PENDING', version INT NOT NULL DEFAULT 0,
 decided_by CHAR(36), reason TEXT, decided_at TIMESTAMP(6) NULL,
 FOREIGN KEY(release_id) REFERENCES releases(id), FOREIGN KEY(scan_id) REFERENCES scans(id), FOREIGN KEY(decided_by) REFERENCES users(id),
 CHECK(decision IN ('PENDING','APPROVED','REJECTED'))
);
CREATE TABLE exceptions (
 id CHAR(36) PRIMARY KEY, scan_id CHAR(36) NOT NULL, finding_id CHAR(36) NOT NULL,
 requested_by CHAR(36) NOT NULL, reason TEXT NOT NULL, expires_at TIMESTAMP(6) NOT NULL,
 decision VARCHAR(20) NOT NULL DEFAULT 'PENDING', version INT NOT NULL DEFAULT 0,
 decided_by CHAR(36), decision_reason TEXT, decided_at TIMESTAMP(6) NULL,
 FOREIGN KEY(scan_id) REFERENCES scans(id), FOREIGN KEY(finding_id) REFERENCES findings(id),
 FOREIGN KEY(requested_by) REFERENCES users(id), FOREIGN KEY(decided_by) REFERENCES users(id),
 INDEX(scan_id,finding_id,decision,expires_at), CHECK(decision IN ('PENDING','APPROVED','REJECTED'))
);
CREATE TABLE ci_credentials (
 id CHAR(36) PRIMARY KEY, project_id CHAR(36) NOT NULL, name VARCHAR(120) NOT NULL,
 token_hash CHAR(64) NOT NULL UNIQUE, created_by CHAR(36) NOT NULL,
 revoked_at TIMESTAMP(6) NULL, created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(project_id) REFERENCES projects(id), FOREIGN KEY(created_by) REFERENCES users(id), INDEX(project_id,id)
);
CREATE TABLE audit_events (
 id CHAR(36) PRIMARY KEY, project_id CHAR(36) NOT NULL, actor_id CHAR(36) NOT NULL,
 action VARCHAR(60) NOT NULL, resource_id CHAR(36) NOT NULL, detail_json TEXT NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), FOREIGN KEY(project_id) REFERENCES projects(id), INDEX(project_id,id)
);
