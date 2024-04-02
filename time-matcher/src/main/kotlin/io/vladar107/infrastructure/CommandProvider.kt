package io.vladar107.infrastructure

import io.ktor.server.application.*
import org.kodein.di.DIAware
import org.kodein.di.direct
import org.kodein.di.instance
import org.kodein.di.ktor.closestDI

class CommandProvider(private val app: Application) : DIAware {
    override val di = closestDI { app }
    suspend inline fun <reified TResult, reified TParam : Command<TResult>> run(command: TParam): TResult {
        val instance = di.direct.instance<CommandHandler<TResult, TParam>>()

        return instance.handle(command)
    }
}

