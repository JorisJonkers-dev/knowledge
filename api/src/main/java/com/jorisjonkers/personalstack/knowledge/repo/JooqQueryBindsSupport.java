package com.jorisjonkers.personalstack.knowledge.repo;

import java.util.List;
import org.jooq.DSLContext;
import org.jooq.ResultQuery;

final class JooqQueryBindsSupport {
    private JooqQueryBindsSupport() {
    }

    static ResultQuery<org.jooq.Record> resultQueryWithBinds(
            DSLContext dsl,
            String sql,
            List<?> binds
    ) {
        return dsl.resultQuery(sql, binds.toArray());
    }
}
