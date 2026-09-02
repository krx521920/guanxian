-- The application has always performed size, extension, media-type and signature checks before
-- accepting new uploads. New code records that synchronous result explicitly as VALIDATED.
-- Historical PENDING rows cannot be proven from metadata alone, so fail closed and require re-upload.
UPDATE object_file
SET scan_status = 'REQUIRES_REUPLOAD',
    version = version + 1,
    updated_at = now()
WHERE scan_status = 'PENDING';

ALTER TABLE object_file
    ALTER COLUMN scan_status SET DEFAULT 'PENDING';
