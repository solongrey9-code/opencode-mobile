package ai.opencode.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ai.opencode.mobile.data.ConnectionProfile
import ai.opencode.mobile.data.ConnectionStore
import ai.opencode.mobile.data.OpenCodeApi

class BridgeProvisionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CONFIGURE) return
        val url = intent.getStringExtra(EXTRA_URL) ?: return
        val token = intent.getStringExtra(EXTRA_TOKEN) ?: return
        val serverUrl = intent.getStringExtra(EXTRA_SERVER_URL)
        val normalized = runCatching { OpenCodeApi.normalizeBaseUrl(url) }.getOrNull() ?: return
        val normalizedServer = serverUrl?.let {
            runCatching { OpenCodeApi.normalizeBaseUrl(it) }.getOrNull() ?: return
        }
        if (token.isBlank()) return

        val store = ConnectionStore(context.applicationContext)
        val profile = store.load() ?: ConnectionProfile()
        store.save(
            profile.copy(
                baseUrl = normalizedServer ?: profile.baseUrl,
                bridgeBaseUrl = normalized,
                bridgeToken = token,
            ),
        )
    }

    companion object {
        const val ACTION_CONFIGURE = "ai.opencode.mobile.CONFIGURE_BRIDGE"
        const val EXTRA_SERVER_URL = "opencode_server_url"
        const val EXTRA_URL = "opencode_bridge_url"
        const val EXTRA_TOKEN = "opencode_bridge_token"
    }
}
