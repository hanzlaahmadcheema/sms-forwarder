package com.smsforwarder.app.core.util

sealed class AppResult<out T> {
    data class Success<out T>(val data: T) : AppResult<T>()
    data class Failure(val error: DomainError) : AppResult<Nothing>()

    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Failure

    fun getOrNull(): T? = when (this) {
        is Success -> data
        is Failure -> null
    }

    inline fun <R> map(transform: (T) -> R): AppResult<R> = when (this) {
        is Success -> Success(transform(data))
        is Failure -> Failure(error)
    }

    inline fun onSuccess(action: (T) -> Unit): AppResult<T> {
        if (this is Success) action(data)
        return this
    }

    inline fun onFailure(action: (DomainError) -> Unit): AppResult<T> {
        if (this is Failure) action(error)
        return this
    }
}

sealed class DomainError(val message: String, val cause: Throwable? = null) {
    class DatabaseError(message: String, cause: Throwable? = null) : DomainError(message, cause)
    class SecurityVaultError(message: String, cause: Throwable? = null) : DomainError(message, cause)
    class NetworkError(message: String, cause: Throwable? = null) : DomainError(message, cause)
    class ValidationError(message: String) : DomainError(message)
    class NotFoundError(message: String) : DomainError(message)
    class UnexpectedError(message: String, cause: Throwable? = null) : DomainError(message, cause)
}
