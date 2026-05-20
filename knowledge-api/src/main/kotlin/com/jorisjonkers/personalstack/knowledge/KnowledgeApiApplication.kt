package com.jorisjonkers.personalstack.knowledge

import com.jorisjonkers.personalstack.knowledge.auth.McpBearerFilter
import com.jorisjonkers.personalstack.knowledge.auth.McpBearerProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.scheduling.annotation.EnableScheduling

// @EnableScheduling activates OllamaChatEndpointResolver.refresh()'s
// `@Scheduled` annotation so the heavy/light Ollama probe re-runs on
// the configured cadence. Without it the resolver only runs once at
// boot and a node coming back online would never get picked up
// without a pod restart.
@SpringBootApplication(scanBasePackages = ["com.jorisjonkers.personalstack"])
@EnableConfigurationProperties(McpBearerProperties::class)
@EnableScheduling
class KnowledgeApiApplication {
    // Plain bean instead of `@Component`: kotlin-common's
    // `ApplicationTracingAspect` CGLIB-proxies every Spring stereotype
    // class under `com.jorisjonkers.personalstack..*`. The proxy can't
    // override `GenericFilterBean.init(FilterConfig)` (it's final), so
    // Tomcat boots the filter with a null inherited `logger` field and
    // NPEs on the first `isDebugEnabled()` call. `@Bean` declaration
    // sidesteps the aspect entirely — same reason kotlin-common's
    // `RequestTimingFilter` / `RequestPipelineSpanFilter` are wired
    // through `TimingAutoConfiguration` instead of `@Component`.
    @Bean
    fun mcpBearerFilter(properties: McpBearerProperties): McpBearerFilter = McpBearerFilter(properties)

    // Pin the filter to `/mcp/*` so it doesn't run on every actuator
    // request just to short-circuit. `shouldNotFilter` does the same
    // thing in code, but URL patterns let Tomcat skip the filter
    // entirely without instantiating the matching logic on every hit.
    @Bean
    fun mcpBearerFilterRegistration(filter: McpBearerFilter): FilterRegistrationBean<McpBearerFilter> =
        FilterRegistrationBean(filter).apply {
            order = MCP_BEARER_FILTER_ORDER
            setUrlPatterns(listOf("/mcp", "/mcp/*"))
        }

    private companion object {
        // After the global timing/tracing filters, before MVC dispatch.
        private const val MCP_BEARER_FILTER_ORDER = 100
    }
}

fun main(args: Array<String>) {
    runApplication<KnowledgeApiApplication>(*args)
}
