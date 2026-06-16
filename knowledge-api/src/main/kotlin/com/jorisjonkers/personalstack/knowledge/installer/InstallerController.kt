package com.jorisjonkers.personalstack.knowledge.installer

import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.ClassPathResource
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.nio.charset.StandardCharsets

/**
 * Serves the knowledge-system installer for Claude Code clients.
 *
 * The installers are self-contained bash scripts bundled as
 * classpath resources (`installer/install.sh` for the base
 * knowledge-system install, `installer/install-agents.sh` for the
 * full agents-system install that also registers the knowledge MCP);
 * this controller substitutes the deployed `SERVICE_VERSION` and the
 * externally-resolved knowledge-api URL at request time so the script
 * that lands on the operator's workstation matches the running server.
 *
 * The path lives outside `/mcp` so the existing [com.jorisjonkers.
 * personalstack.knowledge.auth.McpBearerFilter] does not gate it.
 * That filter scopes itself to `/mcp**` exactly so the installer
 * stays publicly fetchable — the script itself doesn't carry any
 * secrets (it reads `KB_BEARER_TOKEN` from the operator's shell at
 * install time).
 *
 * Content-Type is `text/x-shellscript` so a `curl … | bash` pipe
 * works cleanly and a browser hits a download prompt rather than
 * accidentally rendering as HTML.
 */
@RestController
class InstallerController(
    @param:Value("\${knowledge.installer.kb-url:https://kb.jorisjonkers.dev}")
    private val kbUrl: String,
) {
    private val installerScript: String by lazy { load("installer/install.sh") }
    private val installAgentsScript: String by lazy { load("installer/install-agents.sh") }

    @GetMapping("/install.sh", produces = [INSTALLER_CONTENT_TYPE])
    fun installSh(): ResponseEntity<String> = serve(installerScript)

    @GetMapping("/install-agents.sh", produces = [INSTALLER_CONTENT_TYPE])
    fun installAgentsSh(): ResponseEntity<String> = serve(installAgentsScript)

    private fun serve(script: String): ResponseEntity<String> {
        val rendered =
            script
                .replace(VERSION_TOKEN, System.getenv(SERVICE_VERSION_ENV) ?: "unknown")
                .replace(KB_URL_TOKEN, kbUrl)
        return ResponseEntity
            .ok()
            .header("Cache-Control", "no-cache")
            .contentType(MediaType.parseMediaType(INSTALLER_CONTENT_TYPE))
            .body(rendered)
    }

    private fun load(name: String): String =
        ClassPathResource(name)
            .inputStream
            .use { it.readBytes().toString(StandardCharsets.UTF_8) }

    private companion object {
        const val INSTALLER_CONTENT_TYPE = "text/x-shellscript;charset=UTF-8"
        const val VERSION_TOKEN = "@VERSION@"
        const val KB_URL_TOKEN = "@KB_URL@"
        const val SERVICE_VERSION_ENV = "SERVICE_VERSION"
    }
}
