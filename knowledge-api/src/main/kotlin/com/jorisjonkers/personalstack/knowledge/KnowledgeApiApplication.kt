package com.jorisjonkers.personalstack.knowledge

import com.jorisjonkers.personalstack.knowledge.auth.McpBearerProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

@SpringBootApplication(scanBasePackages = ["com.jorisjonkers.personalstack"])
@EnableConfigurationProperties(McpBearerProperties::class)
class KnowledgeApiApplication

fun main(args: Array<String>) {
    runApplication<KnowledgeApiApplication>(*args)
}
