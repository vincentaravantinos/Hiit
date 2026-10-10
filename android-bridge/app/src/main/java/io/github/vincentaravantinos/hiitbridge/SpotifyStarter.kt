package io.github.vincentaravantinos.hiitbridge

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.android.appremote.api.error.CouldNotFindSpotifyApp
import com.spotify.android.appremote.api.error.NotLoggedInException
import com.spotify.android.appremote.api.error.UserNotAuthorizedException

/**
 * Lance une playlist dans Spotify sans l'afficher : le SDK App Remote réveille Spotify en
 * arrière-plan, on envoie « play », puis on se déconnecte (la lecture continue).
 * En cas d'échec, on retombe sur l'ancien comportement : ouvrir Spotify sur la playlist.
 * [onDone] reçoit null si tout s'est bien passé, sinon le message à afficher.
 */
class SpotifyStarter(
    private val activity: Activity,
    private val clientId: String,
    private val uri: String,
    private val onDone: (String?) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var remote: SpotifyAppRemote? = null
    private var finished = false
    private val timeout = Runnable { fail("Spotify ne répond pas", openApp = true) }

    fun start() {
        val params = ConnectionParams.Builder(clientId)
            .setRedirectUri(REDIRECT_URI)
            .showAuthView(true)
            .build()
        try {
            SpotifyAppRemote.connect(activity, params, object : Connector.ConnectionListener {
                override fun onConnected(r: SpotifyAppRemote) {
                    if (finished) { SpotifyAppRemote.disconnect(r); return }
                    remote = r
                    r.playerApi.play(uri)
                        .setResultCallback { finish(null) }
                        .setErrorCallback { e -> fail("Spotify : lecture impossible (${describe(e)})", openApp = true) }
                }

                override fun onFailure(e: Throwable) {
                    when (e) {
                        is CouldNotFindSpotifyApp -> fail("Spotify n'est pas installé.", openApp = false)
                        is NotLoggedInException -> fail("Spotify : pas de compte connecté.", openApp = true)
                        is UserNotAuthorizedException ->
                            fail("Spotify : accès non autorisé (Client ID, package ou empreinte SHA1 à vérifier).", openApp = true)
                        else -> fail("Spotify : connexion impossible (${describe(e)})", openApp = true)
                    }
                }
            })
        } catch (e: Exception) {
            fail("Spotify : ${describe(e)}", openApp = true)
        }
    }

    fun armTimeout() { if (!finished) { handler.removeCallbacks(timeout); handler.postDelayed(timeout, TIMEOUT_MS) } }
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
        if (openApp) try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(SPOTIFY_PKG))
        } catch (_: Exception) {}
        finish(msg)
    }

    private fun finish(msg: String?) {
        if (finished) return
        finished = true
        release()
        onDone(msg)
    }

    companion object {
        const val REDIRECT_URI = "hiitbridge://spotify-auth"
        private const val SPOTIFY_PKG = "com.spotify.music"
        private const val TIMEOUT_MS = 8000L
    }
}
