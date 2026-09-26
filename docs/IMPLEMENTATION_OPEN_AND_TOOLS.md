# Implementation patches (apply in Android Studio or Codespaces)

Config change is already on this branch (`TelegramBotConfig.isOpenAccess`).

## 1. TelegramBotService.kt — whitelist gates

Replace:
```kotlin
if (sender !in cfg.whitelist && m.chatId !in cfg.whitelist) {
```
with:
```kotlin
if (!cfg.isOpenAccess && sender !in cfg.whitelist && m.chatId !in cfg.whitelist) {
```

Replace callback gate:
```kotlin
if (sender == null || (sender !in cfg.whitelist && cq.chatId !in cfg.whitelist)) {
```
with:
```kotlin
if (sender == null || (!cfg.isOpenAccess && sender !in cfg.whitelist && cq.chatId !in cfg.whitelist)) {
```

Add to `BUILT_IN_COMMANDS`:
```kotlin
"tools" to "JSON catalog of device tools. Usage: /tools or /tools schema <name>",
"tool" to "Run a device tool directly. Usage: /tool <name> <json-args>",
```

## 2. TelegramCommandHandlers.kt — dispatch

In `handleBuiltInCommand` when:
```kotlin
"/tools" -> { handleToolsCommand(m.chatId, arg); true }
"/tool" -> { handleToolCommand(m.chatId, arg); true }
```

In help icons map add `"tools" to "🧰"` and `"tool" to "🔧"`.

In `/status` whitelist label:
```kotlin
val whitelistLabel = when {
    cfg?.isOpenAccess == true -> "OPEN (0 = allow all chats)"
    whitelistCount == 1 -> "1 chat"
    else -> "$whitelistCount chats"
}
```

## 3. Enable open mode in the app

Add `0` to the Telegram bot whitelist (UI number field). That sets `isOpenAccess = true`.

## Status on this branch

- [x] `TelegramBotConfig.kt` — `OPEN_ACCESS_CHAT_ID = 0`, `isOpenAccess`
- [ ] Service + handlers body (large files; use patches above or local apply)
