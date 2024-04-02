package io.vladar107.infrastructure

interface Command<TResult>

interface CommandHandler<TResult, TParam: Command<TResult>> {
    suspend fun handle(command: TParam): TResult
}
