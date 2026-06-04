package com.jorisjonkers.personalstack.knowledge.repo

import org.assertj.core.api.Assertions.assertThat
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test

class JooqQueryBindsTest {
    @Test
    fun `resultQueryWithBinds preserves positional bind order`() {
        val query =
            DSL
                .using(SQLDialect.POSTGRES)
                .resultQueryWithBinds("SELECT ? AS first, ? AS second", listOf("alpha", 2))

        assertThat(query.bindValues).containsExactly("alpha", 2)
    }
}
