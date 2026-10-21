package com.downls10

import android.content.Context
import android.net.Uri
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.InputStream

/**
 * Local WireGuard tunnel wrapper.
 *
 * Configurations are read from a user-selected URI and are never embedded in source control.
 * The Activity must call VpnService.prepare(context) and obtain system approval before start().
 */
object DownLS10WireGuard {
    private var backend: GoBackend? = null
    private var activeTunnel: Tunnel? = null
    private var activeConfig: Config? = null

    @Synchronized
    fun initialize(context: Context) {
        if (backend == null) backend = GoBackend(context.applicationContext)
    }

    @Synchronized
    fun importConfig(context: Context, uri: Uri) {
        val config = context.contentResolver.openInputStream(uri).use { stream: InputStream? ->
            requireNotNull(stream) { "Cannot open WireGuard configuration" }
            Config.parse(stream)
        }
        activeConfig = config
        if (activeTunnel == null) {
            activeTunnel = object : Tunnel {
                override fun getName(): String = "DownLS10"
                override fun onStateChange(newState: Tunnel.State) = Unit
            }
        }
    }

    @Synchronized
    fun hasConfig(): Boolean = activeConfig != null

    @Synchronized
    fun connect(context: Context) {
        initialize(context)
        val tunnel = requireNotNull(activeTunnel) { "Import a WireGuard .conf file first" }
        val config = requireNotNull(activeConfig) { "Import a WireGuard .conf file first" }
        requireNotNull(backend).setState(tunnel, Tunnel.State.UP, config)
    }

    @Synchronized
    fun disconnect() {
        val tunnel = activeTunnel ?: return
        backend?.setState(tunnel, Tunnel.State.DOWN, null)
    }

    @Synchronized
    fun isConnected(): Boolean = try {
        activeTunnel?.let { backend?.getState(it) == Tunnel.State.UP } ?: false
    } catch (_: Exception) {
        false
    }
}
