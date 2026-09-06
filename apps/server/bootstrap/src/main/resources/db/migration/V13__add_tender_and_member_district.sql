-- 招标信息（tender）与推送记录（tender_push），以及会员企业区县（district）字段。
-- 风格与 V1-V12 保持一致：幂等的 IF NOT EXISTS、索引统一命名、CHECK 约束。

CREATE TABLE IF NOT EXISTS tender (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  association_id UUID NOT NULL REFERENCES association(id),
  title VARCHAR(200) NOT NULL,
  purchaser VARCHAR(200) NOT NULL,
  agency VARCHAR(200),
  region VARCHAR(100),
  category VARCHAR(100) NOT NULL,
  keywords JSONB NOT NULL DEFAULT '[]'::jsonb,
  budget BIGINT CHECK (budget >= 0),
  publish_date DATE NOT NULL,
  deadline DATE NOT NULL,
  source VARCHAR(200),
  source_url TEXT,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
  created_by_subject VARCHAR(200),
  updated_by_subject VARCHAR(200),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK (status IN ('ACTIVE', 'CLOSED')),
  CHECK (deadline >= publish_date)
);

CREATE INDEX IF NOT EXISTS tender_status_publish_idx
  ON tender (status, publish_date DESC);
CREATE INDEX IF NOT EXISTS tender_association_status_idx
  ON tender (association_id, status, publish_date DESC);
CREATE INDEX IF NOT EXISTS tender_category_region_idx
  ON tender (category, region);

CREATE TABLE IF NOT EXISTS tender_push (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tender_id UUID NOT NULL REFERENCES tender(id) ON DELETE CASCADE,
  tender_title VARCHAR(200) NOT NULL,
  enterprise_id UUID NOT NULL REFERENCES enterprise(id),
  enterprise_name VARCHAR(200),
  pushed_by_subject VARCHAR(200) NOT NULL,
  pushed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  status VARCHAR(32) NOT NULL DEFAULT 'PUSHED',
  version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (tender_id, enterprise_id),
  CHECK (status IN ('PUSHED', 'READ'))
);

CREATE INDEX IF NOT EXISTS tender_push_enterprise_idx
  ON tender_push (enterprise_id, pushed_at DESC);

ALTER TABLE enterprise
  ADD COLUMN IF NOT EXISTS district VARCHAR(100);
