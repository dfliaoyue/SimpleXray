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
        // Only the stats service is needed; inbound management is done via in-memory merge.
        servicesArray.put("StatsService")
        apiObject.put("services", servicesArray)

        val policyObject = JSONObject()
        val systemObject = JSONObject()
        systemObject.put("statsOutboundUplink", true)
        systemObject.put("statsOutboundDownlink", true)
        policyObject.put("system", systemObject)

        jsonObject.put("api", apiObject)
        jsonObject.put("stats", JSONObject())
        jsonObject.put("policy", policyObject)

        var result = jsonObject.toString(2)
        result = result.replace("\\/", "/")
        return result
    }

    /**
     * Merges a supplementary inbounds-only config fragment (e.g., the temporary SOCKS5
     * inbound for Xray TUN mode) into an existing config string by appending its inbounds
     * to the `"inbounds"` array already present in [baseConfig].
     *
     * Both [baseConfig] and [extraInboundsJson] must be valid JSON objects.
     * [extraInboundsJson] must contain an `"inbounds"` array.
     *
     * @throws JSONException if either string cannot be parsed as a JSON object.
     */
    @Throws(JSONException::class)
    fun mergeAdditionalInbounds(baseConfig: String, extraInboundsJson: String): String {
        val base = JSONObject(baseConfig)
        val extra = JSONObject(extraInboundsJson)

        val baseInbounds = base.optJSONArray("inbounds") ?: org.json.JSONArray()
        val extraInbounds = extra.optJSONArray("inbounds") ?: return baseConfig

        for (i in 0 until extraInbounds.length()) {
            baseInbounds.put(extraInbounds.get(i))
        }
        base.put("inbounds", baseInbounds)

        var result = base.toString(2)
        result = result.replace("\\/", "/")
        return result
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
     * Builds a minimal Xray JSON fragment containing a single SOCKS5 inbound bound to
     * [listenAddress] (a random 127.x.x.x address) without password authentication.
     *
     * No-auth is safe here because:
     *  - The listen address is randomised across the 127.x.x.x/8 range.
     *  - The port is a random ephemeral value.
     *  - The inbound is short-lived (minimum [TEMP_SOCKS_MIN_LIFETIME_MS] after last use).
     *  - The entire config is piped to Xray via stdin and never written to a new disk file.
     *
     * The resulting JSON is an `"inbounds"`-only fragment suitable for merging into the
     * main config via [mergeAdditionalInbounds] before being sent to Xray.
     */
    fun buildTempSocksConfigJson(listenAddress: String, port: Int, tag: String): String {
        require(port in 1..65535) { "port must be in 1..65535, got $port" }

        val settings = JSONObject()
        settings.put("auth", "noauth")
        settings.put("udp", false)

        val inbound = JSONObject()
        inbound.put("tag", tag)
        inbound.put("port", port)
        inbound.put("listen", listenAddress)
        inbound.put("protocol", "socks")
        inbound.put("settings", settings)

        val inboundsArray = org.json.JSONArray()
        inboundsArray.put(inbound)

        val root = JSONObject()
        root.put("inbounds", inboundsArray)
        return root.toString(2)
    }
}

