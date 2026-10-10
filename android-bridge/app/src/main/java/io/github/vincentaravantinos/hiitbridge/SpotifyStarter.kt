package io.github.vincentaravantinos.hiitbridge

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.android.appremote.api.error.AuthenticationFailedException
import com.spotify.android.appremote.api.error.CouldNotFindSpotifyApp
import com.spotify.android.appremote.api.error.NotLoggedInException
import com.spotify.android.appremote.api.error.UserNotAuthorizedException
import com.spotify.sdk.android.auth.AuthorizationClient
import com.spotify.sdk.android.auth.AuthorizationRequest
import com.spotify.sdk.android.auth.AuthorizationResponse

/**
 * Lance une playlist dans Spotify sans l'afficher : le SDK App Remote réveille Spotify en
 * arrière-plan, on envoie « play », puis on se déconnecte (la lecture continue).
 *
 * Spotify ne peut pas afficher lui-même son écran de consentement depuis l'arrière-plan
 * (Android récent) : la connexion reste alors sans réponse. Si la première tentative échoue
 * ou n'aboutit pas, on ouvre donc l'autorisation au premier plan (bibliothèque auth), ce qui
 * réveille aussi Spotify, puis on réessaie une fois.
 *
 * En dernier recours, on retombe sur l'ancien comportement : ouvrir Spotify sur la playlist.
 */
class SpotifyStarter(
    private val activity: Activity,
    private val clientId: String,
    private val uri: String,
    private val onDone: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var remote: SpotifyAppRemote? = null
    private var finished = false
    private var authTried = false
    private var authOpen = false
    private var attempt = 0
    private val timeout = Runnable {
        if (!authTried) authorize() else fail("Spotify ne répond pas.", openApp = true)
    }

    fun start() = connect()

    private fun connect() {
        val mine = ++attempt
        val params = ConnectionParams.Builder(clientId)
            .setRedirectUri(REDIRECT_URI)
            .showAuthView(false)
            .build()
        try {
            SpotifyAppRemote.connect(activity, params, object : Connector.ConnectionListener {
                override fun onConnected(r: SpotifyAppRemote) {
                    if (finished || mine != attempt) { SpotifyAppRemote.disconnect(r); return }
                    remote = r
                    r.playerApi.play(uri)
                        .setResultCallback { finish() }
                        .setErrorCallback { e -> fail("Spotify : lecture impossible (${describe(e)})", openApp = true) }
                }

                override fun onFailure(e: Throwable) {
                    if (finished || mine != attempt) return
                    when (e) {
                        is CouldNotFindSpotifyApp -> fail("Spotify n'est pas installé.", openApp = false)
                        is NotLoggedInException -> fail("Spotify : pas de compte connecté.", openApp = true)
                        is UserNotAuthorizedException, is AuthenticationFailedException ->
                            if (!authTried) authorize()
                            else fail("Spotify : accès refusé (${describe(e)})", openApp = true)
                        else ->
                            if (!authTried) authorize()
                            else fail("Spotify : connexion impossible (${describe(e)})", openApp = true)
                    }
                }
            })
        } catch (e: Exception) {
            fail("Spotify : ${describe(e)}", openApp = true)
        }
    }

    /** Ouvre l'autorisation Spotify au premier plan (sans écran si elle a déjà été donnée). */
    private fun authorize() {
        if (finished) return
        authTried = true
        authOpen = true
        attempt++ // la tentative en cours ne compte plus
        handler.removeCallbacks(timeout)
        try {
            val request = AuthorizationRequest.Builder(clientId, AuthorizationResponse.Type.TOKEN, REDIRECT_URI)
                .setScopes(arrayOf("app-remote-control"))
                .build()
            AuthorizationClient.openLoginActivity(activity, AUTH_REQUEST, request)
        } catch (e: Exception) {
            authOpen = false
            fail("Spotify : autorisation impossible (${describe(e)})", openApp = true)
        }
    }

    fun onAuthResult(resultCode: Int, data: Intent?) {
        if (finished) return
        authOpen = false
        val response = AuthorizationClient.getResponse(resultCode, data)
        when (response.type) {
            AuthorizationResponse.Type.TOKEN, AuthorizationResponse.Type.CODE -> { connect(); armTimeout() }
            AuthorizationResponse.Type.ERROR ->
                fail("Spotify : autorisation refusée (${response.error})", openApp = true)
            else -> fail("Spotify : autorisation annulée.", openApp = false)
        }
    }

    fun armTimeout() {
        if (finished || authOpen) return
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, if (authTried) RETRY_TIMEOUT_MS else FIRST_TIMEOUT_MS)
    }
    fun disarmTimeout() { handler.removeCallbacks(timeout) }

    /** L'activité se ferme : on libère la connexion sans rappeler [onDone]. */
    fun cancel() { finished = true; release() }

    private fun describe(e: Throwable) = e.javaClass.simpleName + (e.message?.let { " : $it" } ?: "")

    private fun release() {
        handler.removeCallbacks(timeout)
        remote?.let { SpotifyAppRemote.disconnect(it) }
        remote = null
    }

    private fun fail(msg: String, openApp: Boolean) {
        if (finished) return
        // Le message d'abord, tant que le pont est au premier plan (sinon Android peut le masquer).
        Toast.makeText(activity.applicationContext, msg, Toast.LENGTH_LONG).show()
        if (openApp) try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(SPOTIFY_PKG))
        } catch (_: Exception) {}
        finish()
    }

    private fun finish() {
        if (finished) return
        finished = true
        release()
        onDone()
    }

    companion object {
        const val REDIRECT_URI = "hiitbridge://spotify-auth"
        const val AUTH_REQUEST = 4711
        private const val SPOTIFY_PKG = "com.spotify.music"
        private const val FIRST_TIMEOUT_MS = 4000L
        private const val RETRY_TIMEOUT_MS = 8000L
    }
}
