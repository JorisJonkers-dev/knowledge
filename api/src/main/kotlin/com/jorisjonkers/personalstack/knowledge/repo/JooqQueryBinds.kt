package com.jorisjonkers.personalstack.knowledge.repo

import org.jooq.DSLContext
import org.jooq.ResultQuery

internal fun DSLContext.resultQueryWithBinds(
    sql: String,
    binds: List<Any>,
): ResultQuery<org.jooq.Record> = JooqQueryBindsSupport.resultQueryWithBinds(this, sql, binds)
