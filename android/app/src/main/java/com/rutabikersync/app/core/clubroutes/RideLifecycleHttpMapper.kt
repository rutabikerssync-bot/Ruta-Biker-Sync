package com.rutabikersync.app.core.clubroutes

import com.rutabikersync.app.core.network.ClubApiClient

/** Traducción única de respuestas HTTP del backend al resultado del outbox. */
object RideLifecycleHttpMapper {

    fun fromResponse(statusCode: Int, errorBody: String?): RideLifecycleSendResult = when {
        statusCode in 200..299 -> RideLifecycleSendResult.Accepted
        statusCode == 403 || statusCode == 409 ->
            RideLifecycleSendResult.Rejected(errorBody ?: "HTTP_$statusCode")
        statusCode in 400..499 && statusCode !in listOf(401, 429) ->
            RideLifecycleSendResult.Rejected(errorBody ?: "HTTP_$statusCode")
        else -> RideLifecycleSendResult.Error(errorBody ?: "HTTP_$statusCode")
    }

    fun fromResult(result: Result<ClubApiClient.ApiResponse>): RideLifecycleSendResult =
        result.fold(
            onSuccess = { response ->
                val error = response.body?.get("error")?.toString()?.trim('"')
                fromResponse(response.statusCode, error)
            },
            onFailure = { error ->
                RideLifecycleSendResult.Error(error.message ?: "fallo de red")
            }
        )
}
