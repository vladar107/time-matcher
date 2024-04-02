package io.vladar107.infrastructure

import io.ktor.server.application.*
import org.kodein.di.DIAware
import org.kodein.di.direct
import org.kodein.di.instance
import org.kodein.di.ktor.closestDI

class QueryProvider(private val app: Application) : DIAware {
    override val di = closestDI { app }
    suspend inline fun <reified TResult, reified TParam : Query<TResult>> query(query: TParam): TResult {
        val instance = di.direct.instance<QueryHandler<TResult, TParam>>()

        return instance.handle(query)
    }
}
