package com.jorisjonkers.personalstack.knowledge.domain

import java.time.Instant

/**
 * Graph edge between two `KbNote` rows. The MCP `find_conflicts`
 * tool surfaces the subset where `predicate in (supersedes,
 * contradicts)`; future tools (graph-walk, citation trail) read the
 * rest.
 */
data class KbRelation(
    val subjectId: String,
    val predicate: String,
    val objectId: String,
    val props: Map<String, Any?>,
    val createdAt: Instant,
)
