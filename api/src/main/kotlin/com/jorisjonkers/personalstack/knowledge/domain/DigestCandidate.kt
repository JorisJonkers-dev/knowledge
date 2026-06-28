package com.jorisjonkers.personalstack.knowledge.domain

/**
 * One candidate capture produced by a Reflexion-style digest pass
 * over a session transcript. The hook script that consumes these
 * decides which to emit — this row is a *suggestion*, not a write.
 *
 * Confidence is the model's self-rated lesson-worthiness on
 * [0.0, 1.0]. A confidence-floor on the consumer's side translates
 * the spec-2023 MemGPT "should-i-promote-this-to-long-term-memory"
 * reflection-token decision into a tunable knob.
 *
 * `suggestedTopic` resolves against the closed vocabulary the
 * curator already maintains; `null` means the model couldn't pick
 * one and the consumer should let the capture land in `_inbox`
 * for the curator's classifier to assign properly.
 */
data class DigestCandidate(
    val kind: String,
    val title: String,
    val body: String,
    val suggestedTopic: String?,
    val suggestedTags: List<String>,
    val confidence: Double,
    val relevantExcerpts: List<String>,
)
