package me.rerere.rikkahub.service

import android.util.Log
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.service.TelegramBotService.Companion.TAG
import org.koin.core.context.GlobalContext
import kotlin.uuid.Uuid

private val toolJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
}

/**
 * Resolve [LocalTools] + the Telegram-bound assistant's enabled local-tool options,
 * then build the same [Tool] list the in-app agent uses for that assistant.
 */
private suspend fun TelegramBotService.resolveLiveTools(): Pair<List<Tool>, String?> {
    val localTools = try {
        GlobalContext.get().get<LocalTools>()
    } catch (e: Throwable) {
        Log.w(TAG, "resolveLiveTools: LocalTools not in Koin", e)
        return emptyList<Tool>() to "LocalTools unavailable: ${e.message}"
    }
    val settings = settingsStore.settingsFlow.first()
    val cfg = prefs.config()
    val assistant = when {
        !cfg.assistantId.isNullOrBlank() -> {
            val id = runCatching { Uuid.parse(cfg.assistantId!!) }.getOrNull()
            settings.assistants.firstOrNull { it.id == id } ?: settings.getCurrentAssistant()
        }
        else -> settings.getCurrentAssistant()
    }
    val ctx = ToolInvocationContext(
        callerAssistantId = assistant.id.toString(),
        callerConversationId = null,
        isHeadless = true,
        modelCanSeeImages = true,
    )
    val tools = localTools.getTools(assistant.localTools, ctx)
    return tools to null
}

/**
 * `/tools` — live catalog of tools enabled on the Telegram-bound assistant.
 * `/tools schema <name>` — description for one tool.
 */
internal suspend fun TelegramBotService.handleToolsCommand(chatId: Long, arg: String) {
    val raw = arg.trim()
    val schemaPrefix = "schema"
    try {
        val (tools, err) = resolveLiveTools()
        if (err != null) {
            client.sendMessage(chatId, """{"ok":false,"error":"$err"}""")
            return
        }
        if (raw.lowercase().startsWith(schemaPrefix)) {
            val name = raw.substring(schemaPrefix.length).trim()
            if (name.isBlank()) {
                client.sendMessage(chatId, """{"ok":false,"error":"usage: /tools schema <tool_name>"}""")
                return
            }
            val tool = tools.firstOrNull { it.name.equals(name, ignoreCase = true) }
            if (tool == null) {
                client.sendMessage(
                    chatId,
                    """{"ok":false,"error":"unknown or disabled tool","name":"$name","hint":"Enable it under Assistants → Local Tools, then /tools"}""",
                )
                return
            }
            val json = buildJsonObject {
                put("ok", true)
                put("name", tool.name)
                put("description", tool.description.take(1500))
                put("note", "Invoke with /tool ${tool.name} <json-args>. Same path as in-app agent (no LLM).")
            }
            client.sendMessage(chatId, json.toString())
            return
        }
        // Prefer live list; fall back to static catalog names if assistant has zero tools on.
        val listed = if (tools.isNotEmpty()) {
            tools.map { it.name to it.description.take(120) }
        } else {
            TELEGRAM_TOOL_CATALOG
        }
        val arr = buildJsonArray {
            listed.forEach { (name, desc) ->
                addJsonObject {
                    put("name", name)
                    put("description", desc)
                }
            }
        }
        val payload = buildJsonObject {
            put("ok", true)
            put("count", listed.size)
            put("source", if (tools.isNotEmpty()) "live_assistant" else "static_catalog")
            put("open_access", cfgSafe()?.isOpenAccess == true)
            put("direct_execute", true)
            put("tools", arr)
            put("usage", "/tool <name> <json-args>")
            put("example_termux", """/tool termux_run_command {"command":"uname -a"}""")
            put("example_battery", "/tool get_battery_status {}")
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
 * `/tool <name> <json-args>` — **direct** tool execution (same [Tool.execute] as in-app AI).
 * No LLM in the middle. HARDLINE guards inside individual tools still apply.
 * Approvals are skipped for this structured path so external agents can automate.
 */
internal suspend fun TelegramBotService.handleToolCommand(chatId: Long, arg: String) {
    val trimmed = arg.trim()
    if (trimmed.isBlank()) {
        try {
            client.sendMessage(
                chatId,
                """{"ok":false,"error":"usage: /tool <name> <json-args>","example":"/tool termux_run_command {\"command\":\"echo hi\"}"}""",
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

    try {
        val (tools, err) = resolveLiveTools()
        if (err != null) {
            client.sendMessage(chatId, """{"ok":false,"error":"$err"}""")
            return
        }
        val tool = tools.firstOrNull { it.name.equals(name, ignoreCase = true) }
        if (tool == null) {
            client.sendMessage(
                chatId,
                buildJsonObject {
                    put("ok", false)
                    put("error", "unknown or disabled tool")
                    put("name", name)
                    put("hint", "Enable the tool group under Assistants → Local Tools, then send /tools")
                }.toString(),
            )
            return
        }

        val input: JsonElement = try {
            toolJson.parseToJsonElement(argsJson)
        } catch (e: Throwable) {
            client.sendMessage(
                chatId,
                """{"ok":false,"error":"invalid JSON args","detail":"${e.message?.replace("\"", "'")?.take(80)}"}""",
            )
            return
        }

        // Direct execute — same callable the ChatService tool loop uses.
        val parts = tool.execute(input)
        val resultText = parts.joinToString("\n") { part ->
            when (part) {
                is UIMessagePart.Text -> part.text
                else -> part.toString()
            }
        }.ifBlank { "{\"ok\":true}" }

        // Prefer embedding raw tool JSON if it already looks like JSON.
        val body = try {
            val el = toolJson.parseToJsonElement(resultText)
            buildJsonObject {
                put("ok", true)
                put("tool", tool.name)
                put("direct", true)
                put("result", el)
            }.toString()
        } catch (_: Throwable) {
            buildJsonObject {
                put("ok", true)
                put("tool", tool.name)
                put("direct", true)
                put("result", resultText.take(3500))
            }.toString()
        }

        // Telegram message limit ~4096; truncate safely.
        client.sendMessage(chatId, body.take(4000))
    } catch (e: Throwable) {
        Log.w(TAG, "handleToolCommand direct execute failed name=$name", e)
        try {
            client.sendMessage(
                chatId,
                """{"ok":false,"tool":"$name","error":"${e.message?.replace("\"", "'")?.take(200) ?: "execute failed"}"}""",
            )
        } catch (_: Throwable) {}
    }
}

/**
 * Static fallback catalog (names match real Tool.name values). Used only when the
 * assistant has no local tools enabled yet so discovery still teaches callers the API.
 */
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
    "tap" to "Tap screen coordinates",
    "swipe" to "Swipe gesture",
    "launch_app" to "Launch app by package",
    "list_installed_apps" to "List installed packages",
    "send_sms" to "Send SMS",
    "read_sms" to "Read SMS inbox",
    "get_contacts" to "Read contacts",
    "get_location" to "GPS location",
    "web_fetch" to "Fetch a URL",
    "web_extract" to "Extract article text",
    "eval_javascript" to "Run JS in sandbox",
    // Shell / elevated
    "termux_run_command" to "Run a command in Termux (stdout/stderr/exit). Needs Termux + allow-external-apps",
    "shizuku_exec" to "Run shell via Shizuku (ADB-level privileges)",
    "workspace_run" to "Run command in Linux workspace",
    "workspace_run_background" to "Start background workspace task",
    "ssh_exec" to "SSH one-shot exec",
)
