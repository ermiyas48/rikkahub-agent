# Telegram open access + structured tools (this branch)

Branch: `feat/telegram-open-and-tools`

## Already committed on this branch

1. **`TelegramBotConfig.kt`** — `OPEN_ACCESS_CHAT_ID = 0L`, `isOpenAccess` when `0` is in the whitelist.
2. **`TelegramAgentToolCommands.kt`** — `/tools`, `/tools schema <name>`, `/tool <name> <json>` handlers + catalog.
3. **Patches** under `patches/` — exact unified diffs for the remaining two files.
4. **Docs** under `docs/TELEGRAM_STRUCTURED_TOOLS.md` and `docs/IMPLEMENTATION_OPEN_AND_TOOLS.md`.

## Apply remaining patches (required to compile)

From repo root (or Android Studio):

```bash
patch -p1 < patches/telegram-open-and-tools-handlers.patch
# Adjust paths if needed: the patch headers are relative to the service package files

# Or manually apply the 3 hunks in:
#   app/src/main/java/me/rerere/rikkahub/service/TelegramCommandHandlers.kt
#   app/src/main/java/me/rerere/rikkahub/service/TelegramBotService.kt
# using patches/telegram-open-and-tools-service.patch
```

### Enable open mode in the app

Add **`0`** to the Telegram bot whitelist (Settings → Telegram). That means “allow every chat id” (your “000” request).

### Agent usage

```
/tools
/tools schema get_battery_status
/tool get_battery_status {}
```

`/tool` posts a `DIRECT_TOOL_REQUEST` for the LLM pipeline (approvals still apply). Full zero-LLM tool RPC would be a follow-up PR.

### Build

Open the fork in Android Studio / Codespaces, apply patches, build APK, install.
