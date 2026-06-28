@file:Suppress("DEPRECATION")

package com.jorisjonkers.personalstack.knowledge.queue

import org.springframework.amqp.core.Binding
import org.springframework.amqp.core.BindingBuilder
import org.springframework.amqp.core.Queue
import org.springframework.amqp.core.QueueBuilder
import org.springframework.amqp.core.TopicExchange
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * RabbitMQ topology owned by knowledge-api's write path. The Python
 * ingest worker (Phase 5) is the eventual consumer; declaring the
 * exchange + queues here means the worker can come up to a pre-bound
 * topology rather than racing to declare it on first boot.
 *
 * Routing keys (topic exchange):
 *   knowledge.lesson    — capture_lesson tool
 *   knowledge.decision  — capture_decision tool
 *   knowledge.ingest    — generic ingest_note tool (URL / raw text)
 *
 * One shared queue (`knowledge.ingest`) consumes the entire
 * `knowledge.*` namespace via wildcard binding. The worker can split
 * into per-type queues later if throughput or backoff semantics
 * diverge — the routing key is already on the message envelope.
 */
@Configuration
class IngestQueueConfig {
    companion object {
        const val EXCHANGE = "knowledge"
        const val QUEUE = "knowledge.ingest"
        const val DLQ = "knowledge.ingest.dlq"
        const val DLX = "knowledge.dlx"
        const val ROUTING_LESSON = "knowledge.lesson"
        const val ROUTING_DECISION = "knowledge.decision"
        const val ROUTING_INGEST = "knowledge.ingest"
    }

    @Bean
    fun knowledgeExchange(): TopicExchange = TopicExchange(EXCHANGE, true, false)

    @Bean
    fun knowledgeDlxExchange(): TopicExchange = TopicExchange(DLX, true, false)

    @Bean
    fun knowledgeIngestQueue(): Queue =
        QueueBuilder
            .durable(QUEUE)
            .withArgument("x-dead-letter-exchange", DLX)
            .withArgument("x-dead-letter-routing-key", DLQ)
            .build()

    @Bean
    fun knowledgeIngestDlq(): Queue = QueueBuilder.durable(DLQ).build()

    @Bean
    fun knowledgeIngestBinding(
        knowledgeIngestQueue: Queue,
        knowledgeExchange: TopicExchange,
    ): Binding = BindingBuilder.bind(knowledgeIngestQueue).to(knowledgeExchange).with("knowledge.*")
}
