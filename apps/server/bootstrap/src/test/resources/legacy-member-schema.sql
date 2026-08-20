CREATE TABLE association (
  id UUID PRIMARY KEY,
  name VARCHAR(200) NOT NULL UNIQUE,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE enterprise (
  id UUID PRIMARY KEY,
  association_id UUID NOT NULL REFERENCES association(id),
  unified_social_credit_code VARCHAR(32),
  name VARCHAR(200) NOT NULL,
  short_name VARCHAR(100),
  description TEXT,
  enterprise_roles JSONB NOT NULL DEFAULT '[]'::jsonb,
  service_scenarios JSONB NOT NULL DEFAULT '[]'::jsonb,
  visibility VARCHAR(32) NOT NULL DEFAULT 'MEMBERS',
  status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
  version BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (association_id, name)
);

INSERT INTO association (id, name)
VALUES ('10000000-0000-0000-0000-000000000001', '北京地下管线协会');

INSERT INTO enterprise (
  id, association_id, unified_social_credit_code, name, short_name,
  description, status, version, created_at, updated_at)
VALUES (
  '20000000-0000-0000-0000-000000000001',
  '10000000-0000-0000-0000-000000000001',
  '91110000LEGACY00001',
  '迁移前存量会员企业',
  '存量企业',
  '迁移前已存在的数据',
  'ACTIVE',
  3,
  '2026-01-01T00:00:00Z',
  '2026-02-01T00:00:00Z');
