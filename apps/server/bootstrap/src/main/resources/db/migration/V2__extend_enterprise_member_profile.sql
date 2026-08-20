ALTER TABLE enterprise ADD COLUMN IF NOT EXISTS category VARCHAR(100);
ALTER TABLE enterprise ADD COLUMN IF NOT EXISTS address VARCHAR(300);
ALTER TABLE enterprise ADD COLUMN IF NOT EXISTS contact_name VARCHAR(50);
ALTER TABLE enterprise ADD COLUMN IF NOT EXISTS contact_phone VARCHAR(50);
ALTER TABLE enterprise ADD COLUMN IF NOT EXISTS capabilities JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE enterprise ADD COLUMN IF NOT EXISTS products JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE enterprise ADD COLUMN IF NOT EXISTS cooperation_needs JSONB NOT NULL DEFAULT '[]'::jsonb;

UPDATE enterprise
SET category = COALESCE(NULLIF(BTRIM(short_name), ''), '未分类')
WHERE category IS NULL OR BTRIM(category) = '';

ALTER TABLE enterprise ALTER COLUMN category SET DEFAULT '未分类';
ALTER TABLE enterprise ALTER COLUMN category SET NOT NULL;

INSERT INTO association (id, name, status)
VALUES ('00000000-0000-0000-0000-000000000106', '北京地下管线协会', 'ACTIVE')
ON CONFLICT (name) DO NOTHING;
