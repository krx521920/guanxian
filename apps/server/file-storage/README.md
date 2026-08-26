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

V7 extends `object_file` with lifecycle status, optimistic version, update time and deleting
subject. Upload/delete/restore actions are written to `audit_log`; Redis decisions are written
to `rate_limit_audit`.

## API

Base path: `/api/v1/attachments`.

- `POST /`: multipart upload; requires `ENTERPRISE_WRITE`.
- `GET /`: scoped, paginated metadata; requires `MEMBER_READ`.
- `GET /{id}`: scoped metadata with a strong version ETag.
- `GET /{id}/content`: integrity-checked download with safe content-disposition headers.
- `DELETE /{id}`: soft delete; requires `If-Match` and `ENTERPRISE_WRITE`.
- `PUT /{id}/restore`: restore; requires `If-Match` and `ENTERPRISE_WRITE`.

Enterprise administrators are forced into their JWT-bound enterprise. Association staff are
limited to their association. System administrators must explicitly choose the target association.
Private files are readable only inside their enterprise (plus association staff); association files
are readable inside that association.

Allowed types are PDF, DOCX, XLSX, JPEG, PNG, TXT and CSV. The service validates filename,
extension, declared media type, file signature, byte count, SHA-256 on download and configured size
limits. Soft deletion intentionally retains object bytes so restoration is possible. Physical purge
and malware scanning remain separate operational work.

## Verification

Run:

```powershell
mvn -pl file-storage -am test
mvn -pl bootstrap -am dependency:tree "-Dincludes=org.postgresql:postgresql"
mvn test
```

The module tests cover lifecycle/versioning, integrity-preserving download, path traversal and
content spoof rejection, enterprise isolation, association visibility and stale-version rejection.
