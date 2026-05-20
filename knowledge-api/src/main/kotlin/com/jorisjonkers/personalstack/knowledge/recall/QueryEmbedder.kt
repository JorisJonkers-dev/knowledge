package com.jorisjonkers.personalstack.knowledge.recall

/**
 * Driven port: turns a recall query string into a dense vector that
 * can be ANN-searched against `kb_notes.embedding`. Implementation
 * default is Ollama; the port exists so unit + integration tests can
 * inject deterministic embedders without spinning up Ollama, and so a
 * future swap (cohere, vertex, local rerank-via-embed) is one bean.
 *
 * Contract:
 *  - Vectors must be the same dimensionality (and produced by the
 *    same model) that the curator uses when embedding notes at
 *    promote time. Cross-model embeddings are silently meaningless;
 *    `EmbeddingRepository` does not validate dimensionality at query
 *    time — the JDBC driver will surface a `vector(1024)` mismatch.
 *  - Implementations MAY throw on remote failure; callers MUST treat
 *    a thrown call as "vector leg degraded" and continue with the
 *    FTS leg only.
 */
fun interface QueryEmbedder {
    fun embed(query: String): FloatArray
}
