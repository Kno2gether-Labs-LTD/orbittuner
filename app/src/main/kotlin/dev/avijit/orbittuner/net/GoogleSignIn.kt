package dev.avijit.orbittuner.net

import android.content.Context
import android.util.Log
import dev.avijit.orbittuner.data.UnsyncedChanges
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom

/** What the Google account picker produced. */
sealed interface IdTokenResult {
    data class Token(val idToken: String) : IdTokenResult {
        override fun toString() = "Token(…)" // never print the token
    }
    /** The person closed the picker: change nothing, say nothing. */
    object Cancelled : IdTokenResult
    /** [message] is shown as is. */
    data class Failed(val message: String) : IdTokenResult
}

/** Gets a Google ID token for the service. [context] must be an Activity (the picker is shown over it). */
interface GoogleIdTokenProvider {
    suspend fun requestIdToken(context: Context): IdTokenResult
}

/**
 * Credential Manager: "Sign in with Google" for any Google account on the device (not only previously authorised
 * ones), never auto-selected, with the service's web client ID as the audience. A fresh random nonce is sent with
 * each request.
 */
class CredentialManagerIdTokenProvider(private val serverClientId: String) : GoogleIdTokenProvider {
    override suspend fun requestIdToken(context: Context): IdTokenResult {
        // TODO: the service doesn't verify nonces yet. When it does, get the nonce from the service (or send this one
        //  with the ID token) instead of only generating it here.
        val nonce = newNonce()
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(serverClientId)
            .setAutoSelectEnabled(false)
            .setNonce(nonce)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        return try {
            val credential = CredentialManager.create(context).getCredential(context, request).credential
            if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                IdTokenResult.Token(GoogleIdTokenCredential.createFrom(credential.data).idToken)
            } else {
                IdTokenResult.Failed(GENERIC)
            }
        } catch (e: GetCredentialCancellationException) {
            IdTokenResult.Cancelled
        } catch (e: NoCredentialException) {
            IdTokenResult.Failed("No Google account is set up on this device. Add one in Android Settings, then try again.")
        } catch (e: GetCredentialException) {
            Log.w("GoogleSignIn", "credential request failed: ${e.type}")
            IdTokenResult.Failed(GENERIC)
        } catch (e: GoogleIdTokenParsingException) {
            IdTokenResult.Failed(GENERIC)
        }
    }

    private fun newNonce(): String {
        val bytes = ByteArray(16).also(SecureRandom()::nextBytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private companion object { const val GENERIC = "Google sign-in didn't work. Please try again." }
}

sealed interface GoogleSignInOutcome {
    /** [switchedAccount]: this device joined the account that already had this Google identity (local data re-pulled). */
    data class SignedIn(val email: String?, val switchedAccount: Boolean) : GoogleSignInOutcome
    object Cancelled : GoogleSignInOutcome
    /**
     * [changes] local changes were rejected by the service and would be lost if this Google account already has
     * OrbitTuner data (the merge wipes this device's local copy). Ask, then [GoogleSignIn.confirm] or drop it.
     */
    class NeedsConfirmation internal constructor(val changes: Int, internal val idToken: String) : GoogleSignInOutcome {
        override fun toString() = "NeedsConfirmation(changes=$changes)"
    }
    data class Failed(val message: String) : GoogleSignInOutcome
}

/** Clear copy for a failed POST /v1/accounts/google. */
fun googleErrorMessage(e: ApiError): String = when {
    e is ApiError.Offline -> "You're offline. Connect to the internet and try again."
    e is ApiError.AccountDisabled -> "The OrbitTuner account for this Google account is disabled. Contact OrbitTuner support."
    e is ApiError.Http && e.error == "invalid_google_token" -> "Google couldn't confirm your sign-in. Please try again."
    e is ApiError.Http && e.error == "google_already_linked" ->
        "This account is already linked to a different Google account. Sign out of this device first, then sign in with this Google account."
    e is ApiError.Http && e.error == "google_linked_elsewhere" ->
        "That Google account is already linked to another OrbitTuner account, and this account can't be merged into it."
    e is ApiError.Http && e.error == "admin_use_portal" -> "This Google account is for OrbitTuner staff. Sign in on the OrbitTuner portal instead."
    e is ApiError.Http && e.error == "google_not_configured" -> "Google sign-in isn't available right now. Please try again later."
    e is ApiError.Http && e.error == "google_unavailable" -> "Couldn't reach Google just now. Try again in a moment."
    e is ApiError.Http && e.code == 429 -> "Too many sign-in attempts. Wait a minute, then try again."
    else -> "Google sign-in didn't work. Please try again."
}

/**
 * "Sign in with Google": picker → push of local edits (so they reach the current account before a possible
 * server-side merge, which only moves server rows, and after which this device's local copy is wiped) → POST
 * /v1/accounts/google via [AccountManager.linkGoogle]. Cancelling the picker changes nothing.
 *
 * Nothing local is lost silently: if pushable edits remain after the push (offline, timed out) the sign-in stops;
 * if only edits the service rejected remain, it returns [GoogleSignInOutcome.NeedsConfirmation].
 */
class GoogleSignIn(
    private val provider: GoogleIdTokenProvider,
    private val accounts: AccountManager,
    /** Pushes dirty favourites/playlists (LibraryRepository.sync in production). Its errors are tolerated: [unsynced] decides. */
    private val pushLocal: suspend () -> Unit,
    /** LibraryRepository.unsyncedChanges in production. */
    private val unsynced: suspend () -> UnsyncedChanges,
    private val pushTimeoutMs: Long = 20_000,
) {
    suspend fun signIn(context: Context): GoogleSignInOutcome {
        if (!accounts.ensureAccount()) {
            return GoogleSignInOutcome.Failed(
                if (accounts.state.value == AccountState.Disabled) "This account is disabled, so it can't be linked to Google."
                else "Your account isn't set up yet. Connect to the internet and try again.",
            )
        }
        val idToken = when (val r = provider.requestIdToken(context)) {
            IdTokenResult.Cancelled -> return GoogleSignInOutcome.Cancelled
            is IdTokenResult.Failed -> return GoogleSignInOutcome.Failed(r.message)
            is IdTokenResult.Token -> r.idToken
        }
        push()
        val left = unsynced()
        if (left.pending > 0) return GoogleSignInOutcome.Failed(UNSYNCED)
        if (left.rejected > 0) return GoogleSignInOutcome.NeedsConfirmation(left.rejected, idToken)
        return link(idToken)
    }

    /** Continues after the person accepted losing the rejected changes of [pending]. */
    suspend fun confirm(pending: GoogleSignInOutcome.NeedsConfirmation): GoogleSignInOutcome {
        // Something may have been edited while the dialog was up: push it, and stop if it can't be.
        push()
        if (unsynced().pending > 0) return GoogleSignInOutcome.Failed(UNSYNCED)
        return link(pending.idToken)
    }

    private suspend fun push() {
        try {
            withTimeoutOrNull(pushTimeoutMs) { pushLocal() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Checked by the caller: whatever didn't reach the service is still dirty.
        }
    }

    private suspend fun link(idToken: String): GoogleSignInOutcome {
        return try {
            val r = accounts.linkGoogle(idToken)
            GoogleSignInOutcome.SignedIn(r.email, r.switchedAccount)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            GoogleSignInOutcome.Failed(googleErrorMessage(e))
        }
    }

    private companion object {
        const val UNSYNCED = "Some changes haven't reached your account yet. Connect to the internet and try again."
    }
}
