package com.jorisjonkers.personalstack.knowledge.repo

import org.jooq.DSLContext
import org.jooq.ResultQuery

@Suppress("SpreadOperator")
internal fun DSLContext.resultQueryWithBinds(
    sql: String,
    binds: List<Any>,
): ResultQuery<org.jooq.Record> = resultQuery(sql, *binds.toTypedArray())
