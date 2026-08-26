DO $$
BEGIN
  IF EXISTS (
    SELECT 1
      FROM ecosystem_match
     WHERE deleted_at IS NULL
     GROUP BY demand_id, candidate_enterprise_id
    HAVING count(*) > 1
  ) THEN
    RAISE EXCEPTION
      'ecosystem_match contains duplicate active demand/candidate rows; resolve duplicates before V6';
  END IF;
END
$$;

CREATE UNIQUE INDEX IF NOT EXISTS ecosystem_match_active_candidate_uq
  ON ecosystem_match (demand_id, candidate_enterprise_id)
  WHERE deleted_at IS NULL;
