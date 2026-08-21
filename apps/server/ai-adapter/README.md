# AI adapter: policy RAG foundation

This module provides the first production-oriented policy retrieval and cited-answering slice.

## Runtime modes

- `guanxian.business.repository=postgres` (default): uses the V5
  `knowledge_document`, `knowledge_document_version`, `knowledge_chunk`,
  `retrieval_trace`, `qa_citation`, and `model_execution` tables.
- `guanxian.business.repository=memory`: deterministic in-memory adapter for tests and local demos.
- `guanxian.ai.provider.enabled=false` (default): no network model call is made. The answer is a
  deterministic summary of retrieved chunks and still includes citations.
- `guanxian.ai.provider.enabled=true`: configures an OpenAI-compatible chat-completions endpoint.
- `guanxian.ai.rag.external-model-data-egress-enabled=false` (default): retrieved document text
  cannot leave the platform. The service returns a deterministic cited summary even when a provider
  is configured. Both switches must be true before retrieved text is sent to the provider.

An enabled external provider fails application startup unless its endpoint is HTTPS, the API key is
non-empty and non-placeholder, the model is configured, and all timeout/token/price values are valid.

## Main services

`KnowledgeIngestionService.ingest` applies the same prompt-injection and sensitive-data checks to
the document title, source URL, and body, then normalizes text, computes a SHA-256 content hash,
creates an overlapping sentence-aware chunk set, and stores a new immutable document version.

`PolicyRagService.ask`:

1. rejects oversized, sensitive, or prompt-injection-like questions;
2. applies actor-aware visibility: public documents are global, association documents require the
   same association, and private documents require the creator or association staff/system identity;
3. drops rows whose title, source URL, or retrieved content is unsafe;
4. enforces input, output, and estimated-cost limits;
5. returns an answer together with document name, document version, chunk ID/index, source URL,
   quote, and retrieval score for every citation;
6. records the retrieval trace and citations, and records external model executions.

When both external-provider and data-egress switches are enabled, a provider failure is surfaced and
is not silently converted to a local answer. When either switch is disabled, only the deterministic
cited summary path runs and no retrieved document text is sent outside the platform.

## Current retrieval boundary

PostgreSQL retrieval is deterministic keyword/substring ranking over the V5 chunk store. The schema
retains `embedding_provider`, `embedding_model`, and `vector_store_key` for a subsequent vector
adapter. Until that adapter is configured, this module must not be described as semantic/vector
retrieval.

## Verification

Run:

```powershell
mvn -pl ai-adapter -am clean test
```

The unit and API suites cover Chinese chunking, default-disabled/fail-closed provider behavior,
default-deny data egress, cost calculation, prompt-injection and secret checks across all ingested
fields, cited local answers, private/association visibility, and cross-association update rejection.
The PostgreSQL isolation test runs under Testcontainers and is skipped automatically when Docker is
not available.
