# Structured Telegram tool API + open chat-id mode

## Problem

RikkaHub Agent's Telegram bot only accepts **natural language**. Other agents/bots cannot call the 80+ device tools programmatically the way the in-app AI does.

## Goals

1. **Direct tool calls** over Telegram (`/tool`, JSON payloads)
2. **Tool discovery** (`/tools`, `/tools schema <name>`)
3. **Open allowlist**: whitelist contains `0` (or dedicated `allowAll`) → accept any chat/sender
4. Optional **agent auth** (shared secret) for `/tool`
5. Keep per-tool approval + HARDLINE safety

## Proposed Telegram commands

| Command | Behavior |
|---------|----------|
| `/tools` | JSON list of enabled tools: `[{ "name", "description" }]` |
| `/tools schema <name>` | JSON parameter schema for one tool |
| `/tool <name> <json-args>` | Execute tool; reply with JSON result |
| (optional) pure JSON body `{"tool","args"}` | Same as `/tool` |

### Example

```
/tool take_screenshot {}
```

Reply:

```json
{
  "ok": true,
  "tool": "take_screenshot",
  "result": { "path": "...", "mime": "image/png" }
}
```

## Exact source map (fork master)

| Concern | File |
|---------|------|
| Config (token, enabled, **whitelist**, defaultChatId) | `app/src/main/java/me/rerere/rikkahub/data/telegram/TelegramBotConfig.kt` |
| DataStore persist whitelist as comma-separated Longs | `.../data/telegram/TelegramBotPreferences.kt` (`K_WHITELIST`, `parseWhitelist`) |
| **Strict whitelist gate** (sender OR chatId must be in set; empty = nobody) | `.../service/TelegramBotService.kt` ~lines 575–581 and callback path ~1786 |
| Built-in slash commands (no LLM) | `.../service/TelegramCommandHandlers.kt` (`handleBuiltInCommand`) |
| Bot UI settings | `.../ui/pages/setting/SettingTelegramPage.kt` |
| Local device tools registry | under `.../data/ai/tools/local/` (and related tool builders) |

### Current whitelist logic (must change for open mode)

```kotlin
// TelegramBotService.handleIncoming — conceptual
if (sender !in cfg.whitelist && m.chatId !in cfg.whitelist) {
    // drop message
}
```

**Proposed open mode:** if `0L in cfg.whitelist` **or** new `cfg.allowAll == true`, skip this reject.

User request "acceptable chat id = 000" maps cleanly to putting **`0`** in the whitelist set (or a UI toggle that sets `allowAll`).

### Where to add `/tools` and `/tool`

In `TelegramCommandHandlers.handleBuiltInCommand` `when (cmd)`:

- `/tools` → list enabled tools from the same registry the assistant uses; reply JSON
- `/tool` → parse name + JSON args, run tool execute path, reply JSON

Register names in `TelegramBotService.BUILT_IN_COMMANDS` so they appear in `/help` and BotFather menu refresh.

## Open chat-id mode (detail)

- **Option A (minimal):** treat `0` in whitelist as "allow all senders/chats"
- **Option B:** add `allowAll: Boolean` to `TelegramBotConfig` + Settings toggle "Open access"
- Keep rate limits, tool approval keyboards, HARDLINE
- Document that open + direct `/tool` is high risk

## Auth (recommended)

- Optional `toolApiToken` in config
- Require `token=<secret>` on `/tool` (and optionally `/tools schema`) when set

## Implementation order

1. Open mode (`0` in whitelist or `allowAll`) — small change in `TelegramBotService`
2. `/tools` + `/tools schema` — discovery only, read-only
3. `/tool` dispatch through existing tool execute + approval
4. Settings UI + README security notes
5. Optional shared secret

## Security

- Open + direct tools is high risk; default remains strict whitelist
- Mutating tools: keep Yes/No unless "trusted agent" mode
- Never log bot token or tool API token

## Alternatives (already free, direct tools today)

| Project | Direct tools? | Notes |
|---------|---------------|-------|
| [Aster](https://aster.matterwardlabs.com/) | Yes (MCP) | 49 tools, any MCP client |
| PocketMCP | Yes (MCP) | On-phone MCP server |
| droid-mcp / phone-mcp-server | Yes | MCP/HTTP |

## Status

- **Fork:** https://github.com/ermiyas48/rikkahub-agent
- **Upstream:** https://github.com/ExTV/rikkahub-agent
- Issues disabled on this fork (upstream policy); track work in this doc + branches/PRs
- Full Kotlin implementation of `/tool` needs Android Studio / local build; this doc is the implementation brief for Copilot or a PR
