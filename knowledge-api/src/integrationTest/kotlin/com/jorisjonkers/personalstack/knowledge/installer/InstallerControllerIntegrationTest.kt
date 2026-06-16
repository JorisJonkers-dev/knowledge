package com.jorisjonkers.personalstack.knowledge.installer

import com.jorisjonkers.personalstack.knowledge.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

/**
 * Drives the `GET /install.sh` route end-to-end through the
 * controller. The route is intentionally outside `/mcp**` so the
 * bearer filter does not gate it — the installer reads
 * `KB_BEARER_TOKEN` from the operator's shell at install time, not
 * from the script body, so the script itself carries no secret.
 *
 * The endpoint also has to land on a Content-Type that pipes
 * cleanly into `bash`. `text/x-shellscript` is the conventional
 * choice; asserted here so a future refactor doesn't accidentally
 * regress to `text/plain` or `application/octet-stream`.
 */
@TestPropertySource(
    properties = [
        "knowledge.installer.kb-url=https://kb.example.test",
    ],
)
class InstallerControllerIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var context: WebApplicationContext

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build()
    }

    @Test
    fun `install_sh serves a bash script templated with the configured kb url`() {
        val result = mockMvc.get("/install.sh").andReturn()

        assertThat(result.response.status).isEqualTo(200)
        assertThat(result.response.contentType).startsWith("text/x-shellscript")

        val body = result.response.contentAsString
        // Shebang lets `curl … | bash` pipes work, and lets the user
        // also save + chmod the file as a standalone executable.
        assertThat(body).startsWith("#!/usr/bin/env bash")
        // The kb-url token gets substituted into the script body in
        // multiple places (KB_URL constant + help text). One match is
        // enough to prove the substitution ran.
        assertThat(body).contains("https://kb.example.test")
        // Negative assertion: every placeholder is gone. A leftover
        // `@KB_URL@` or `@VERSION@` is a sign the templater missed a
        // site.
        assertThat(body).doesNotContain("@KB_URL@")
        assertThat(body).doesNotContain("@VERSION@")
        // The managed paths must be present so the operator's uninstall
        // path knows what to remove. The install script references them
        // via `${SKILLS_DIR}/<name>/SKILL.md` shell expansion, so we
        // assert on the trailing path segment only.
        assertThat(body).contains("user-prompt-submit-recall.sh")
        assertThat(body).contains("topics/SKILL.md")
        assertThat(body).contains("audit/SKILL.md")
        assertThat(body).contains("kb-first/SKILL.md")
        assertThat(body).contains("token-economy/SKILL.md")
        assertThat(body).contains("agent-session-bootstrap/SKILL.md")
        assertThat(body).contains("KB_RECALL_HOOK_MODE")
        assertThat(body).contains("KB_DIGEST_MAX_CHARS")
        assertThat(body).contains("KB_DIGEST_DEDUPE_SCORE")
        assertThat(body).contains("KB_MCP_URL")
    }

    @Test
    fun `install-agents_sh serves a bash script templated with the configured kb url`() {
        val result = mockMvc.get("/install-agents.sh").andReturn()

        assertThat(result.response.status).isEqualTo(200)
        assertThat(result.response.contentType).startsWith("text/x-shellscript")

        val body = result.response.contentAsString
        assertThat(body).startsWith("#!/usr/bin/env bash")
        assertThat(body).contains("https://kb.example.test")
        assertThat(body).doesNotContain("@KB_URL@")
        assertThat(body).doesNotContain("@VERSION@")
        // This installer is a thin wrapper that delegates the base
        // install by fetching the sibling install.sh from the same KB.
        assertThat(body).contains("/install.sh")
        // The new capability over the base installer: registering the
        // knowledge MCP server with each agent.
        assertThat(body).contains("mcp_servers.knowledge")
    }
}
