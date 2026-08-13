package com.blink.dtn.auth

import android.content.Context
import com.blink.dtn.net.AuthApi
import com.blink.dtn.net.AuthResponse

/**
 * Identity provider for onboarding.
 * Mesh nodeId stays RSA-derived; provider only tracks how the account was linked online.
 */
enum class AuthProvider(val wireId: String) {
    OFFLINE("offline"),
    EMAIL("email"),
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
        val suggestedNick: String? = null,
        /** Present when signed in via VPS (`/auth/...`). */
        val session: AuthResponse? = null
    ) : AuthResult()

    /** Provider UI selected but flow not available (e.g. VK/Google). */
    data class Deferred(val provider: AuthProvider) : AuthResult()

    data class Failed(val reason: String) : AuthResult()
}

fun interface SocialAuthGateway {
    suspend fun beginSignIn(): AuthResult
}

/** Email OTP against VPS `/auth/email/send` and `/auth/email/verify`. */
class EmailOtpAuth(
    private val context: Context,
    private val email: String,
    private val otp: String? = null,
    private val rebindPrimary: Boolean = false
) : SocialAuthGateway {
    private val api = AuthApi(context)

    /** Step 1: request OTP mail. Returns Success(providerUserId=email) when send OK. */
    override suspend fun beginSignIn(): AuthResult {
        if (otp.isNullOrBlank()) {
            val send = api.sendEmailOtp(email)
            return send.fold(
                onSuccess = { AuthResult.Success(providerUserId = email.trim()) },
                onFailure = { AuthResult.Failed(it.message ?: "send_failed") }
            )
        }
        val verified = api.verifyEmailOtp(email, otp, rebindPrimary = rebindPrimary)
        return verified.fold(
            onSuccess = { resp ->
                AuthSessionStore.saveVpsSession(context, resp)
                AuthResult.Success(
                    providerUserId = resp.authId.ifBlank { email },
                    session = resp
                )
            },
            onFailure = { AuthResult.Failed(it.message ?: "verify_failed") }
        )
    }
}

/**
 * Telegram WebApp `initData` → VPS `/auth/telegram`.
 * Native TG Login Widget / Mini App must supply [initData]; without it returns Failed.
 */
class TelegramAuth(
    private val context: Context,
    private val initData: String,
    private val rebindPrimary: Boolean = false
) : SocialAuthGateway {
    private val api = AuthApi(context)

    override suspend fun beginSignIn(): AuthResult {
        if (initData.isBlank()) {
            return AuthResult.Failed("telegram_init_data_required")
        }
        return api.telegramAuth(initData, rebindPrimary = rebindPrimary).fold(
            onSuccess = { resp ->
                AuthSessionStore.saveVpsSession(context, resp)
                AuthResult.Success(
                    providerUserId = resp.authId,
                    session = resp
                )
            },
            onFailure = { AuthResult.Failed(it.message ?: "telegram_auth_failed") }
        )
    }
}

/** Offline / non-wired social buttons (VK, Google, Yandex). */
object DeferredSocialAuth {
    fun gateway(provider: AuthProvider): SocialAuthGateway = SocialAuthGateway {
        when (provider) {
            AuthProvider.OFFLINE -> AuthResult.Success(providerUserId = "offline")
            AuthProvider.EMAIL, AuthProvider.TELEGRAM ->
                AuthResult.Failed("use_dedicated_gateway")
            else -> AuthResult.Deferred(provider)
        }
    }
}
