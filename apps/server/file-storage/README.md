# Attachment storage module

This module provides the first production-ready attachment vertical slice.

## Runtime modes

- Object bytes default to the in-memory adapter for tests and local development.
- Set `guanxian.storage.backend=minio` to enable the MinIO adapter.
- Metadata follows `guanxian.business.repository`: `memory` for tests or `postgres` for production.
- Redis rate limiting is disabled unless `guanxian.storage.rate-limit.enabled=true`.
  When enabled, a Redis failure rejects attachment write operations with HTTP 503 (fail closed).

Production MinIO configuration:

| Property | Purpose |
| --- | --- |
| `guanxian.storage.endpoint` | MinIO HTTP(S) origin; HTTPS is mandatory in a production profile |
| `guanxian.storage.bucket` | Private bucket name |
| `guanxian.storage.access-key` | MinIO access key |
| `guanxian.storage.secret-key` | MinIO secret key |
| `guanxian.storage.max-size-bytes` | Maximum upload size; default 20 MiB, hard ceiling 100 MiB |
| `guanxian.storage.redis-url` | Redis endpoint used only when limiting is enabled; `rediss://` is mandatory in production |
| `guanxian.storage.rate-limit-per-minute` | Per-subject write limit; default 30 |
| `guanxian.storage.scan-mode` | `content-only` for development, mandatory `clamav` in production |
| `guanxian.storage.scan-host` / `scan-port` / `scan-timeout` | Private ClamAV INSTREAM endpoint and fail-closed timeout |

In a production profile the bucket must already exist and the endpoint must use HTTPS. The
application refuses to start when the bucket is missing; automatic bucket creation is limited to
non-production environments. Grant the application account only the object operations required by
this private bucket. Production also refuses to start unless the MinIO backend and Redis-backed
attachment write rate limiter and ClamAV scanning are enabled; Redis or scanner outages fail attachment writes closed.

V7 extends `object_file` with lifecycle status, optimistic version, update time and deleting
subject. Upload/delete/restore actions are written to `audit_log`; Redis decisions are written
to `rate_limit_audit`.

## API

Base path: `/api/v1/attachments`.

- `POST /`: multipart upload; requires `ENTERPRISE_WRITE`.
- `GET /`: scoped, paginated metadata; requires `MEMBER_READ`.
- `GET /{id}`: scoped metadata with a strong version ETag.
- `GET /{id}/content`: content-validation-gated, integrity-checked download with safe content-disposition headers.
- `DELETE /{id}`: soft delete; requires `If-Match` and `ENTERPRISE_WRITE`.
- `PUT /{id}/restore`: restore; requires `If-Match` and `ENTERPRISE_WRITE`.

Enterprise administrators are forced into their JWT-bound enterprise. Association staff are
limited to their association. System administrators must explicitly choose the target association.
Private files are readable only inside their enterprise (plus association staff); association files
are readable inside that association.

Allowed types are PDF, DOCX, XLSX, JPEG, PNG, TXT and CSV. Before a new upload is accepted, the
service validates filename, extension, declared media type, file signature, byte count and configured
size limits, then sends the bytes to the configured scanner before recording metadata as `VALIDATED`;
SHA-256 is checked again on download. Content
and knowledge ingestion are rejected unless this status is `VALIDATED`. V20 changes historical
`PENDING` rows to `REQUIRES_REUPLOAD` because metadata alone cannot prove validation; users must
re-upload those files. Soft deletion intentionally retains object bytes so restoration is possible.
The development `content-only` scanner performs structural validation only. Production refuses to
start unless `scan-mode=clamav`; malware, timeout, protocol error or unavailable scanner reject the
upload before it becomes downloadable or eligible for knowledge ingestion. Physical purge remains
separate operational work.

## Verification

Run:

```powershell
mvn -pl file-storage -am test
mvn -pl bootstrap -am dependency:tree "-Dincludes=org.postgresql:postgresql"
mvn test
```

The module tests cover lifecycle/versioning, validation/scanner-gated and integrity-preserving download,
fail-closed legacy content, path traversal and content spoof rejection, enterprise isolation,
association visibility and stale-version rejection. PostgreSQL migration tests verify the V20
`PENDING` to `REQUIRES_REUPLOAD` transition when Docker is available.
