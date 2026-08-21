# AI adapter: policy RAG foundation

This module provides the first production-oriented policy retrieval and cited-answering slice.

## Runtime modes

- `guanxian.business.repository=postgres` (default): uses the V5
  `knowledge_document`, `knowledge_document_version`, `knowledge_chunk`,
  `retrieval_trace`, `qa_citation`, and `model_execution` tables.
- `guanxian.business.repository=memory`: deterministic in-memory adapter for tests and local demos.
- `guanxian.ai.provider.enabled=false` (default): no network model call is made. The answer is a
  deterministic summary of retrieved chunks and still includes citations.
- `guanxian.ai.provider.enabled=true`: uses an OpenAI-compatible chat-completions endpoint.

An enabled external provider fails application startup unless its endpoint is HTTPS, the API key is
non-empty and non-placeholder, the model is configured, and all timeout/token/price values are valid.

## Main services

`KnowledgeIngestionService.ingest` normalizes text, computes a SHA-256 content hash, creates an
overlapping sentence-aware chunk set, and stores a new immutable document version.

`PolicyRagService.ask`:

1. rejects oversized, sensitive, or prompt-injection-like questions;
2. retrieves only public documents or documents belonging to the caller's association;
3. drops unsafe retrieved chunks;
4. enforces input, output, and estimated-cost limits;
5. returns an answer together with document name, document version, chunk ID/index, source URL,
   quote, and retrieval score for every citation;
6. records the retrieval trace and citations, and records external model executions.

A successful answer never silently switches from a failing enabled provider to local mode. This is
intentional: configuration/provider failures remain visible. Local cited summaries are used only
when the external provider is explicitly disabled.

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

The unit suite covers Chinese chunking, default-disabled/fail-closed provider behavior, cost
calculation, prompt-injection and secret checks, cited local answers, association visibility, and
cross-association update rejection.
