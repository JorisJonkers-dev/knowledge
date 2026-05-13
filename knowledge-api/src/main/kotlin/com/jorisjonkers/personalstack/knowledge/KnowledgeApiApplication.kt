package com.jorisjonkers.personalstack.knowledge

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication(scanBasePackages = ["com.jorisjonkers.personalstack"])
class KnowledgeApiApplication

fun main(args: Array<String>) {
    runApplication<KnowledgeApiApplication>(*args)
}
