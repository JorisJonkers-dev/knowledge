package com.jorisjonkers.personalstack.knowledge.queue

import com.jorisjonkers.personalstack.knowledge.domain.KbNote
import org.slf4j.LoggerFactory
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.stereotype.Component

/**
 * Hands captured / ingested notes off to the Python worker via the
 * `knowledge` topic exchange. The Kotlin side has already persisted
 * the canonical row in `kb_notes` before publishing; the worker reads
 * the row by id and layers chunks / embeddings / entities on top.
 *
 * Messages serialize through Spring's Jackson2JsonMessageConverter,
 * which kotlin-commons-messaging already wires in.
 */
@Component
class IngestPublisher(
    private val rabbitTemplate: RabbitTemplate,
) {
    private val log = LoggerFactory.getLogger(IngestPublisher::class.java)

    fun publishCapturedNote(note: KbNote) {
        val routingKey =
            when (note.type) {
                com.jorisjonkers.personalstack.knowledge.domain.KbNoteType.LESSON ->
                    IngestQueueConfig.ROUTING_LESSON
                com.jorisjonkers.personalstack.knowledge.domain.KbNoteType.DECISION ->
                    IngestQueueConfig.ROUTING_DECISION
                else -> IngestQueueConfig.ROUTING_INGEST
            }
        publish(routingKey, capturedNotePayload(note))
    }

    fun publishIngestRequest(payload: Map<String, Any?>) {
        publish(IngestQueueConfig.ROUTING_INGEST, payload)
    }

    private fun publish(
        routingKey: String,
        payload: Map<String, Any?>,
    ) {
        log.info(
            "publishing knowledge job routingKey={} id={} scope={}",
            routingKey,
            payload["id"],
            payload["scope"],
        )
        rabbitTemplate.convertAndSend(IngestQueueConfig.EXCHANGE, routingKey, payload)
    }

    private fun capturedNotePayload(note: KbNote): Map<String, Any?> =
        mapOf(
            "id" to note.id,
            "type" to note.type.wire,
            "scope" to note.scope,
            "source" to note.source,
            "captured_at" to note.capturedAt.toString(),
            "session_id" to note.sessionId,
            "confidence" to note.confidence,
            "title" to note.title,
            "body" to note.body,
            "vault_path" to note.vaultPath,
            "tags" to note.tags.toList(),
        )
}
