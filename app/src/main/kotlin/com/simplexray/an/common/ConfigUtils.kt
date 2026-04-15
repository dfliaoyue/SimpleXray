package com.simplexray.an.common

import android.util.Log
import com.simplexray.an.prefs.Preferences
import org.json.JSONException
import org.json.JSONObject

object ConfigUtils {
    private const val TAG = "ConfigUtils"

    fun extractTunMtu(configContent: String): Int? {
        try {
            val jsonObject = JSONObject(configContent)
            val inbounds = jsonObject.optJSONArray("inbounds") ?: return null
            for (i in 0 until inbounds.length()) {
                val inbound = inbounds.optJSONObject(i) ?: continue
                if (inbound.optString("protocol") == "tun") {
                    return inbound.optJSONObject("settings")?.optInt("MTU", -1)
                        ?.takeIf { it > 0 }
                }
            }
        } catch (e: JSONException) {
            Log.e(TAG, "Error parsing JSON for TUN MTU extraction", e)
        }
        return null
    }

    @Throws(JSONException::class)
    fun formatConfigContent(content: String): String {
        val jsonObject = JSONObject(content)
        (jsonObject["log"] as? JSONObject)?.apply {
            if (has("access") && optString("access") != "none") {
                remove("access")
                Log.d(TAG, "Removed log.access")
            }
            if (has("error") && optString("error") != "none") {
                remove("error")
                Log.d(TAG, "Removed log.error")
            }
        }
        var formattedContent = jsonObject.toString(2)
        formattedContent = formattedContent.replace("\\/", "/")
        return formattedContent
    }

    @Throws(JSONException::class)
    fun injectStatsService(prefs: Preferences, configContent: String): String {
        val jsonObject = JSONObject(configContent)

        val apiObject = JSONObject()
        apiObject.put("tag", "api")
        apiObject.put("listen", "${prefs.apiAddress}:${prefs.apiPort}")
        val servicesArray = org.json.JSONArray()
        servicesArray.put("StatsService")
        apiObject.put("services", servicesArray)

        jsonObject.put("api", apiObject)
        jsonObject.put("stats", JSONObject())

        val policyObject = JSONObject()
        val systemObject = JSONObject()
        systemObject.put("statsOutboundUplink", true)
        systemObject.put("statsOutboundDownlink", true)
        policyObject.put("system", systemObject)

        jsonObject.put("policy", policyObject)

        return jsonObject.toString(2)
    }

    fun extractPortsFromJson(jsonContent: String): Set<Int> {
        val ports = mutableSetOf<Int>()
        try {
            val jsonObject = JSONObject(jsonContent)
            extractPortsRecursive(jsonObject, ports)
        } catch (e: JSONException) {
            Log.e(TAG, "Error parsing JSON for port extraction", e)
        }
        Log.d(TAG, "Extracted ports: $ports")
        return ports
    }

    private fun extractPortsRecursive(jsonObject: JSONObject, ports: MutableSet<Int>) {
        for (key in jsonObject.keys()) {
            when (val value = jsonObject.get(key)) {
                is Int -> {
                    if (value in 1..65535) {
                        ports.add(value)
                    }
                }

                is JSONObject -> {
                    extractPortsRecursive(value, ports)
                }

                is org.json.JSONArray -> {
                    for (i in 0 until value.length()) {
                        val item = value.get(i)
                        if (item is JSONObject) {
                            extractPortsRecursive(item, ports)
                        }
                    }
                }
            }
        }
    }

    /**
     * Builds a minimal Xray JSON config fragment containing a single SOCKS5 inbound
     * bound to 127.0.0.1 with password authentication.  This fragment is written to
     * [TEMP_SOCKS_CONFIG_FILENAME] and merged into the running config by [TProxyService]
     * whenever Xray TUN mode is active and a network task (rule-file download, update check)
     * needs to reach the internet through the proxy chain.
     *
     * The resulting JSON is **not** a complete Xray config – it only contains the
     * `"inbounds"` array with the single temporary inbound.
     */
    fun buildTempSocksConfigJson(
        port: Int,
        tag: String,
        username: String,
        password: String,
    ): String {
        val account = JSONObject()
        account.put("user", username)
        account.put("pass", password)
        val accountsArray = org.json.JSONArray()
        accountsArray.put(account)

        val settings = JSONObject()
        settings.put("auth", "password")
        settings.put("udp", false)
        settings.put("accounts", accountsArray)

        val inbound = JSONObject()
        inbound.put("tag", tag)
        inbound.put("port", port)
        inbound.put("listen", "127.0.0.1")
        inbound.put("protocol", "socks")
        inbound.put("settings", settings)

        val inboundsArray = org.json.JSONArray()
        inboundsArray.put(inbound)

        val root = JSONObject()
        root.put("inbounds", inboundsArray)
        return root.toString(2)
    }

    /**
     * Merges the `"inbounds"` array from [additionalConfig] into [baseConfig].
     * All other top-level keys in [additionalConfig] are ignored – only inbound
     * entries are appended to the base config's inbounds list.
     *
     * Returns [baseConfig] unchanged if [additionalConfig] contains no inbounds.
     *
     * @throws JSONException if either config string is not valid JSON.
     */
    @Throws(JSONException::class)
    fun mergeAdditionalInbounds(baseConfig: String, additionalConfig: String): String {
        val base = JSONObject(baseConfig)
        val additional = JSONObject(additionalConfig)
        val additionalInbounds = additional.optJSONArray("inbounds")
            ?: return baseConfig
        val baseInbounds = base.optJSONArray("inbounds") ?: org.json.JSONArray()
        for (i in 0 until additionalInbounds.length()) {
            baseInbounds.put(additionalInbounds.get(i))
        }
        base.put("inbounds", baseInbounds)
        return base.toString(2)
    }
}

