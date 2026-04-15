package com.simplexray.an.common

const val ROUTE_STATS = "stats"
const val ROUTE_CONFIG = "config"
const val ROUTE_LOG = "log"
const val ROUTE_SETTINGS = "settings"
const val ROUTE_MAIN = "main"
const val ROUTE_APP_LIST = "app_list"
const val ROUTE_CONFIG_EDIT = "config_edit"
const val NAVIGATION_DEBOUNCE_DELAY = 500L

/**
 * File name of the temporary SOCKS5 inbound config fragment injected into xray during
 * geosite/geoip downloads and update checks in Xray TUN mode.  Written to [android.content.Context.filesDir]
 * (app-private) and deleted as soon as all in-flight download tasks complete.
 */
const val TEMP_SOCKS_CONFIG_FILENAME = "temp_socks_config.json"