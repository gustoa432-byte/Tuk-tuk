package com.blink.dtn.auth

/**
 * Identity provider for onboarding. Real OAuth SDKs plug in later via [SocialAuthGateway].
 * Until then every social provider returns [AuthResult.Deferred].
 */
enum class AuthProvider(val wireId: String) {
    OFFLINE("offline"),
    TELEGRAM("telegram"),
    VK("vk"),
    GOOGLE("google"),
    YANDEX("yandex");

    companion object {
        fun fromWire(id: String?): AuthProvider =
            entries.firstOrNull { it.wireId == id } ?: OFFLINE
    }
}

sealed class AuthResult {
    data class Success(
        val providerUserId: String,
        val suggestedName: String? = null,
        val suggestedNick: String? = null
    ) : AuthResult()

    /** Provider chosen in UI; OAuth not wired yet. */
    data class Deferred(val provider: AuthProvider) : AuthResult()

    data class Failed(val reason: String) : AuthResult()
}

/**
 * Future OAuth entry point. Stub gateways never open network / SDK flows.
 */
fun interface SocialAuthGateway {
    suspend fun beginSignIn(): AuthResult
}

object StubSocialAuth {
    fun gateway(provider: AuthProvider): SocialAuthGateway = SocialAuthGateway {
        when (provider) {
            AuthProvider.OFFLINE -> AuthResult.Success(providerUserId = "offline")
            else -> AuthResult.Deferred(provider)
        }
    }
}
