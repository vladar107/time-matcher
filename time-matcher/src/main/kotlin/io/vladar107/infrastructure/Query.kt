package io.vladar107.infrastructure

interface Query<out TResult>

interface QueryHandler<TResult, TParam: Query<TResult>> {
    suspend fun handle(query: TParam): TResult
}
