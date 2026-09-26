package me.rerere.rikkahub.service

import android.util.Log
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.service.TelegramBotService.Companion.TAG

/**
 * `/tools` — machine-readable catalog for other agents.
 * `/tools schema <name>` — detail for one tool name from the bundled catalog.
 */
internal suspend fun TelegramBotService.handleToolsCommand(chatId: Long, arg: String) {
    val raw = arg.trim()
    val schemaPrefix = "schema"
    try {
        if (raw.lowercase().startsWith(schemaPrefix)) {
            val name = raw.substring(schemaPrefix.length).trim()
            if (name.isBlank()) {
                client.sendMessage(chatId, """{"ok":false,"error":"usage: /tools schema <tool_name>"}""")
                return
            }
            val entry = TELEGRAM_TOOL_CATALOG.firstOrNull { it.first.equals(name, ignoreCase = true) }
            if (entry == null) {
                client.sendMessage(
                    chatId,
                    """{"ok":false,"error":"unknown tool","name":"$name","hint":"Send /tools for the full list"}""",
                )
                return
            }
            val json = buildJsonObject {
                put("ok", true)
                put("name", entry.first)
                put("description", entry.second)
                put("note", "Availability depends on Settings → Assistants → Local Tools. Invoke with /tool <name> <json-args>.")
            }
            client.sendMessage(chatId, json.toString())
            return
        }
        val arr = buildJsonArray {
            TELEGRAM_TOOL_CATALOG.forEach { (name, desc) ->
                addJsonObject {
                    put("name", name)
                    put("description", desc)
                }
            }
        }
        val payload = buildJsonObject {
            put("ok", true)
            put("count", TELEGRAM_TOOL_CATALOG.size)
            put("open_access", cfgSafe()?.isOpenAccess == true)
            put("tools", arr)
            put("usage", "/tool <name> <json-args>")
            put("example", "/tool get_battery_status {}")
        }
        client.sendMessage(chatId, payload.toString())
    } catch (e: Throwable) {
        Log.w(TAG, "handleToolsCommand failed", e)
        try {
            client.sendMessage(chatId, """{"ok":false,"error":"${e.message?.replace("\"", "'")?.take(120) ?: "failed"}"}""")
        } catch (_: Throwable) {}
    }
}

/**
 * `/tool <name> <json-args>` — structured tool request for other agents.
 * Posts a precise instruction the model should treat as a single tool call.
 * Approvals still apply for mutating tools.
 */
internal suspend fun TelegramBotService.handleToolCommand(chatId: Long, arg: String) {
    val trimmed = arg.trim()
    if (trimmed.isBlank()) {
        try {
            client.sendMessage(
                chatId,
                """{"ok":false,"error":"usage: /tool <name> <json-args>","example":"/tool get_battery_status {}"}""",
            )
        } catch (_: Throwable) {}
        return
    }
    val sp = trimmed.indexOfFirst { it.isWhitespace() }
    val name: String
    val argsJson: String
    if (sp < 0) {
        name = trimmed
        argsJson = "{}"
    } else {
        name = trimmed.substring(0, sp).trim()
        argsJson = trimmed.substring(sp).trim().ifBlank { "{}" }
    }
    val prompt = buildString {
        append("DIRECT_TOOL_REQUEST: Call the device tool `")
        append(name)
        append("` with JSON arguments exactly: ")
        append(argsJson)
        append(". Then reply with ONLY one JSON object ")
        append("""{"ok":true|false,"tool":"""")
        append(name)
        append("""","result":...,"error":...}""")
        append(" and no markdown fences.")
    }
    try {
        client.sendMessage(
            chatId,
            """{"ok":true,"status":"accepted","tool":"$name","hint":"Send the next line as a normal chat message if the agent does not auto-run."}""",
        )
        client.sendMessage(chatId, prompt)
    } catch (e: Throwable) {
        Log.w(TAG, "handleToolCommand failed", e)
        try {
            client.sendMessage(chatId, """{"ok":false,"error":"${e.message?.replace("\"", "'")?.take(120) ?: "failed"}"}""")
        } catch (_: Throwable) {}
    }
}

/** Bundled catalog (subset) for agent discovery over Telegram. */
internal val TELEGRAM_TOOL_CATALOG: List<Pair<String, String>> = listOf(
    "get_battery_status" to "Battery percent, charging, temperature",
    "get_audio_info" to "Audio mode, headphones, ringer",
    "get_wifi_info" to "SSID, IP, signal",
    "get_telephony_info" to "SIM/network/signal",
    "get_storage_info" to "Free/used storage",
    "get_time_info" to "Local date/time/timezone",
    "list_sensors" to "List device sensors",
    "read_sensor" to "Sample a sensor",
    "set_torch" to "Flashlight on/off",
    "vibrate" to "Vibrate pattern or duration",
    "get_brightness" to "Screen brightness",
    "set_brightness" to "Set brightness 1..255",
    "get_volume" to "Volume levels",
    "set_volume" to "Set stream volume",
    "show_toast" to "Short on-screen toast",
    "post_notification" to "Post a system notification",
    "clipboard_tool" to "Read/write clipboard",
    "list_files" to "List directory files",
    "find_files" to "Search files by name",
    "read_file" to "Read a file",
    "write_text_file" to "Write a text file",
    "take_screenshot" to "Capture the screen",
    "global_action" to "HOME/BACK/RECENTS etc.",
    "launch_app" to "Launch app by package",
    "list_installed_apps" to "List installed packages",
    "send_sms" to "Send SMS (approval)",
    "read_sms" to "Read SMS inbox",
    "get_contacts" to "Read contacts",
    "get_location" to "GPS location",
    "web_fetch" to "Fetch a URL",
    "web_extract" to "Extract article text",
    "eval_javascript" to "Run JS in sandbox",
)
