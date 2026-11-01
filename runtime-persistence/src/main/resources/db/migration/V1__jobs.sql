CREATE TABLE jobs (
  id uuid PRIMARY KEY,
  attempt_id uuid NOT NULL UNIQUE,
  tenant_id text NOT NULL,
  idempotency_key text NOT NULL,
  request_hash text NOT NULL,
  agent text NOT NULL,
  input_json text NOT NULL,
  status text NOT NULL,
  submission_status text NOT NULL,
  execution_status text NOT NULL DEFAULT 'UNKNOWN',
  backend_id text NOT NULL,
  plan_revision text NOT NULL,
  plan_payload text NOT NULL,
  execution_handle text,
  error text,
  lease_until timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, idempotency_key)
);
CREATE INDEX jobs_recovery_idx ON jobs (lease_until, created_at)
  WHERE submission_status IN ('PENDING', 'UNKNOWN');
CREATE INDEX jobs_inspection_idx ON jobs (lease_until, updated_at)
  WHERE submission_status = 'SUBMITTED' AND status IN ('STARTING', 'RUNNING');
