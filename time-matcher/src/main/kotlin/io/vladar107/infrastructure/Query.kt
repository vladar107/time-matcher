package io.vladar107.infrastructure

interface Query<out TResult>

interface QueryHandler<TParam: Query<TResult>, TResult> {
    suspend fun handle(query: TParam): TResult
}
