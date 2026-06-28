@file:Suppress("VarCouldBeVal")

package com.jorisjonkers.personalstack.knowledge.recall

import com.jorisjonkers.personalstack.knowledge.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

class RecallMigrationIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var dsl: DSLContext

    @Test
    fun postgresMigrationCreatesRecallFtsIndex() {
        val indexName =
            dsl.fetchValue("SELECT to_regclass('public.kb_notes_fts_english_idx')::text", String::class.java)

        assertThat(indexName).isEqualTo("kb_notes_fts_english_idx")
    }
}
