package com.jorisjonkers.personalstack.knowledge.recall

import com.jorisjonkers.personalstack.knowledge.domain.RecallHit

/**
 * Optional graph/document retrieval leg for deep recall. Implementations
 * must be best-effort: failures return an empty list so `deep` can
 * degrade to hybrid + rerank.
 */
interface GraphRetriever {
    fun retrieve(
        query: String,
        scope: String?,
        limit: Int,
    ): List<RecallHit>
}
