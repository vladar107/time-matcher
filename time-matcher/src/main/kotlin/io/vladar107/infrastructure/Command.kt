package io.vladar107.infrastructure

interface Command<out TResult>

interface CommandHandler<TResult, in TParam: Command<TResult>> {
    suspend fun handle(command: TParam): TResult
}
