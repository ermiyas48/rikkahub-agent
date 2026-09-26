package me.rerere.rikkahub.service

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.telegram.TelegramCallbackQuery
import me.rerere.rikkahub.service.TelegramBotService.Companion.TAG
import org.koin.core.context.GlobalContext
import kotlin.uuid.Uuid

private val toolJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
}

/** Callback prefix for the human control panel (must stay ≤64 bytes total with id). */
internal const val PANEL_CB_PREFIX = "pnl:"

private suspend fun TelegramBotService.resolveLiveTools(): Pair<List<Tool>, String?> {
    val localTools = try {
        GlobalContext.get().get<LocalTools>()
    } catch (e: Throwable) {
        Log.w(TAG, "resolveLiveTools: LocalTools not in Koin", e)
        return emptyList<Tool>() to "LocalTools unavailable: ${e.message}"
    }
    val settings = settingsStore.settingsFlow.value
    val cfg = prefs.current()
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
    return localTools.getTools(assistant.localTools, ctx) to null
}

private suspend fun TelegramBotService.executeToolByName(name: String, argsJson: String): String {
    val (tools, err) = resolveLiveTools()
    if (err != null) return """{"ok":false,"error":"$err"}"""
    val tool = tools.firstOrNull { it.name.equals(name, ignoreCase = true) }
        ?: return """{"ok":false,"error":"unknown or disabled tool","name":"$name","hint":"Enable it under Assistants → Local Tools"}"""
    val input: JsonElement = try {
        toolJson.parseToJsonElement(argsJson)
    } catch (e: Throwable) {
        return """{"ok":false,"error":"invalid JSON args"}"""
    }
    val parts = tool.execute(input)
    val resultText = parts.joinToString("\n") { part ->
        when (part) {
            is UIMessagePart.Text -> part.text
            else -> part.toString()
        }
    }.ifBlank { "{\"ok\":true}" }
    return try {
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
}

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
                    """{"ok":false,"error":"unknown or disabled tool","name":"$name"}""",
                )
                return
            }
            client.sendMessage(
                chatId,
                buildJsonObject {
                    put("ok", true)
                    put("name", tool.name)
                    put("description", tool.description.take(1500))
                }.toString(),
            )
            return
        }
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
        client.sendMessage(
            chatId,
            buildJsonObject {
                put("ok", true)
                put("count", listed.size)
                put("source", if (tools.isNotEmpty()) "live_assistant" else "static_catalog")
                put("open_access", cfgSafe()?.isOpenAccess == true)
                put("direct_execute", true)
                put("tools", arr)
                put("usage", "/tool <name> <json-args>")
                put("human_panel", "/panel")
            }.toString(),
        )
    } catch (e: Throwable) {
        Log.w(TAG, "handleToolsCommand failed", e)
        try {
            client.sendMessage(chatId, """{"ok":false,"error":"${e.message?.replace("\"", "'")?.take(120) ?: "failed"}"}""")
        } catch (_: Throwable) {}
    }
}

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
        val body = executeToolByName(name, argsJson)
        client.sendMessage(chatId, body.take(4000))
    } catch (e: Throwable) {
        Log.w(TAG, "handleToolCommand failed name=$name", e)
        try {
            client.sendMessage(
                chatId,
                """{"ok":false,"tool":"$name","error":"${e.message?.replace("\"", "'")?.take(200) ?: "execute failed"}"}""",
            )
        } catch (_: Throwable) {}
    }
}

/** Human-friendly control panel with tappable buttons. */
internal suspend fun TelegramBotService.handlePanelCommand(chatId: Long, @Suppress("UNUSED_PARAMETER") arg: String) {
    val markup = buildPanelKeyboard()
    val text = buildString {
        appendLine("📱 RH Open Agent — quick controls")
        appendLine()
        appendLine("Tap a button below. Tools must be enabled on the assistant (Local Tools).")
        appendLine()
        appendLine("• Agents/API: /tools  and  /tool <name> {json}")
        appendLine("• Termux: enable Termux tool + allow-external-apps in Termux")
        appendLine("• SMS: enable SMS tools + grant SMS permission on phone")
    }
    try {
        client.sendMessage(chatId, text, replyMarkup = markup)
    } catch (e: Throwable) {
        Log.w(TAG, "handlePanelCommand failed", e)
    }
}

internal fun buildPanelKeyboard(): JsonObject = buildJsonObject {
    put("inline_keyboard", buildJsonArray {
        add(buildJsonArray {
            addJsonObject { put("text", "🔋 Battery"); put("callback_data", "pnl:battery") }
            addJsonObject { put("text", "📸 Screenshot"); put("callback_data", "pnl:shot") }
        })
        add(buildJsonArray {
            addJsonObject { put("text", "🔦 Torch on"); put("callback_data", "pnl:torch1") }
            addJsonObject { put("text", "🔦 Torch off"); put("callback_data", "pnl:torch0") }
        })
        add(buildJsonArray {
            addJsonObject { put("text", "📋 Clipboard"); put("callback_data", "pnl:clip") }
            addJsonObject { put("text", "📍 Location"); put("callback_data", "pnl:loc") }
        })
        add(buildJsonArray {
            addJsonObject { put("text", "🔊 Volume"); put("callback_data", "pnl:vol") }
            addJsonObject { put("text", "📶 Wi‑Fi"); put("callback_data", "pnl:wifi") }
        })
        add(buildJsonArray {
            addJsonObject { put("text", "💬 SMS inbox"); put("callback_data", "pnl:sms") }
            addJsonObject { put("text", "👤 Contacts"); put("callback_data", "pnl:contacts") }
        })
        add(buildJsonArray {
            addJsonObject { put("text", "💻 Termux uname"); put("callback_data", "pnl:termux") }
            addJsonObject { put("text", "🧰 /tools"); put("callback_data", "pnl:tools") }
        })
    })
}

/** Handle panel button taps. Always answerCallbackQuery so Telegram stops the spinner. */
internal suspend fun TelegramBotService.handlePanelCallback(cq: TelegramCallbackQuery) {
    val id = cq.data.removePrefix(PANEL_CB_PREFIX)
    try {
        when (id) {
            "battery" -> {
                client.answerCallbackQuery(cq.callbackQueryId, "Battery…")
                client.sendMessage(cq.chatId, executeToolByName("get_battery_status", "{}").take(4000))
            }
            "shot" -> {
                client.answerCallbackQuery(cq.callbackQueryId, "Screenshot…")
                client.sendMessage(cq.chatId, executeToolByName("take_screenshot", "{}").take(4000))
            }
            "torch1" -> {
                client.answerCallbackQuery(cq.callbackQueryId, "Torch on")
                client.sendMessage(cq.chatId, executeToolByName("set_torch", """{"enabled":true}""").take(4000))
            }
            "torch0" -> {
                client.answerCallbackQuery(cq.callbackQueryId, "Torch off")
                client.sendMessage(cq.chatId, executeToolByName("set_torch", """{"enabled":false}""").take(4000))
            }
            "clip" -> {
                client.answerCallbackQuery(cq.callbackQueryId, "Clipboard…")
                // Tool name varies; try common ones
                val r = executeToolByName("clipboard_tool", """{"action":"read"}""")
                client.sendMessage(cq.chatId, r.take(4000))
            }
            "loc" -> {
                client.answerCallbackQuery(cq.callbackQueryId, "Location…")
                client.sendMessage(cq.chatId, executeToolByName("get_location", "{}").take(4000))
            }
            "vol" -> {
                client.answerCallbackQuery(cq.callbackQueryId, "Volume…")
                client.sendMessage(cq.chatId, executeToolByName("get_volume", "{}").take(4000))
            }
            "wifi" -> {
                client.answerCallbackQuery(cq.callbackQueryId, "Wi‑Fi…")
                client.sendMessage(cq.chatId, executeToolByName("get_wifi_info", "{}").take(4000))
            }
            "sms" -> {
                client.answerCallbackQuery(cq.callbackQueryId, "SMS…")
                val r = executeToolByName("list_sms_inbox", """{"limit":5}""")
                // fallback name
                val body = if ("unknown or disabled" in r) executeToolByName("read_sms", """{"limit":5}""") else r
                client.sendMessage(cq.chatId, body.take(4000))
            }
            "contacts" -> {
                client.answerCallbackQuery(cq.callbackQueryId, "Contacts…")
                val r = executeToolByName("list_contacts", """{"limit":10}""")
                val body = if ("unknown or disabled" in r) executeToolByName("get_contacts", "{}") else r
                client.sendMessage(cq.chatId, body.take(4000))
            }
            "termux" -> {
                client.answerCallbackQuery(cq.callbackQueryId, "Termux…")
                client.sendMessage(
                    cq.chatId,
                    executeToolByName("termux_run_command", """{"command":"uname -a"}""").take(4000),
                )
            }
            "tools" -> {
                client.answerCallbackQuery(cq.callbackQueryId)
                handleToolsCommand(cq.chatId, "")
            }
            else -> {
                client.answerCallbackQuery(cq.callbackQueryId, "Unknown button")
            }
        }
    } catch (e: Throwable) {
        Log.w(TAG, "handlePanelCallback failed id=$id", e)
        runCatching { client.answerCallbackQuery(cq.callbackQueryId, "Error") }
        runCatching {
            client.sendMessage(cq.chatId, """{"ok":false,"error":"${e.message?.replace("\"", "'")?.take(120)}"}""")
        }
    }
}

internal val TELEGRAM_TOOL_CATALOG: List<Pair<String, String>> = listOf(
    "get_battery_status" to "Battery percent, charging, temperature",
    "get_audio_info" to "Audio mode, headphones, ringer",
    "get_wifi_info" to "SSID, IP, signal",
    "get_telephony_info" to "SIM/network/signal",
    "get_storage_info" to "Free/used storage",
    "get_time_info" to "Local date/time/timezone",
    "set_torch" to "Flashlight on/off",
    "vibrate" to "Vibrate",
    "get_brightness" to "Screen brightness",
    "set_brightness" to "Set brightness",
    "get_volume" to "Volume levels",
    "set_volume" to "Set volume",
    "list_files" to "List files",
    "read_file" to "Read a file",
    "write_text_file" to "Write a text file",
    "take_screenshot" to "Screenshot",
    "tap" to "Tap coordinates",
    "swipe" to "Swipe",
    "launch_app" to "Launch app",
    "send_sms" to "Send SMS",
    "list_sms_inbox" to "Read SMS",
    "get_location" to "GPS",
    "termux_run_command" to "Termux shell command",
    "shizuku_exec" to "Shizuku shell",
    "ssh_exec" to "SSH exec",
)
