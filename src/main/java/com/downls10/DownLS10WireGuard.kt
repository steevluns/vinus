package com.downls10

import android.content.Context
import android.net.Uri
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Local WireGuard tunnel wrapper.
 * Configuration is stored only in the app-private files directory and never in source control.
 */
object DownLS10WireGuard {
    private const val CONFIG_FILE = "wireguard.conf"
    private var backend: GoBackend? = null
    private var activeTunnel: Tunnel? = null
    private var activeConfig: Config? = null

    @Synchronized
    fun initialize(context: Context) {
        val appContext = context.applicationContext
        if (backend == null) backend = GoBackend(appContext)
        if (activeTunnel == null) {
            activeTunnel = object : Tunnel {
                override fun getName(): String = "DownLS10"
                override fun onStateChange(newState: Tunnel.State) = Unit
            }
        }
        // Restore the user's imported configuration after the app process is restarted.
        if (activeConfig == null) {
            val file = appContext.getFileStreamPath(CONFIG_FILE)
            if (file.isFile) {
                activeConfig = runCatching {
                    file.inputStream().use { Config.parse(it) }
                }.getOrNull()
            }
        }
    }

    @Synchronized
    fun importConfig(context: Context, uri: Uri) {
        val appContext = context.applicationContext
        val bytes = appContext.contentResolver.openInputStream(uri).use { stream: InputStream? ->
            requireNotNull(stream) { "Cannot open WireGuard configuration" }
            stream.readBytes()
        }
        // Validate before replacing the previously saved configuration.
        val parsed = ByteArrayInputStream(bytes).use { Config.parse(it) }
        appContext.openFileOutput(CONFIG_FILE, Context.MODE_PRIVATE).use { it.write(bytes) }
        activeConfig = parsed
        ensureTunnel()
    }

    private fun ensureTunnel() {
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
