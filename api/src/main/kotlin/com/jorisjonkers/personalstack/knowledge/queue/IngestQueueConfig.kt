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
 * ingest worker is the eventual consumer; declaring the exchange + queues
 * here means the worker can come up to a pre-bound topology rather than
 * racing to declare it on first boot.
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
 *
 * Dead-letter topology:
 *   knowledge.ingest  →  x-dead-letter-exchange: knowledge.dlx
 *                         x-dead-letter-routing-key: knowledge.ingest.dlq
 *   knowledge.dlx (TopicExchange) →  knowledge.ingest.dlq
 *
 * The main queue sets `x-dead-letter-routing-key` to `knowledge.ingest.dlq`,
 * so the DLQ binding uses that exact key. Keeping DLX as a TopicExchange
 * avoids a PRECONDITION_FAILED error when rolling out onto a broker that
 * already has `knowledge.dlx` declared as a topic exchange.
 *
 * Both parse failures (nacked by the Python worker) and handler errors
 * route to `knowledge.ingest.dlq` for visibility and manual replay.
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

    /**
     * Bind the DLQ to the DLX using the exact routing key the main queue
     * stamps on dead-lettered messages, so nacked deliveries are stored
     * rather than dropped by the broker.
     */
    @Bean
    fun knowledgeDlqBinding(
        knowledgeIngestDlq: Queue,
        knowledgeDlxExchange: TopicExchange,
    ): Binding = BindingBuilder.bind(knowledgeIngestDlq).to(knowledgeDlxExchange).with(DLQ)
}
