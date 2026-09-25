# Structured Telegram tool API + open chat-id mode

## Problem

RikkaHub Agent's Telegram bot only accepts **natural language**. Other agents/bots cannot call the 80+ device tools programmatically the way the in-app AI does.

## Goals

1. **Direct tool calls** over Telegram (`/tool`, JSON payloads)
2. **Tool discovery** (`/tools`, `/tools schema <name>`)
3. **Open allowlist**: acceptable chat ID `0` / `000` means accept any chat
4. Optional **agent auth** (shared secret) for `/tool`
5. Keep per-tool approval + HARDLINE safety

## Proposed Telegram commands

| Command | Behavior |
|---------|----------|
| `/tools` | JSON list of enabled tools: `[{ "name", "description" }]` |
| `/tools schema <name>` | JSON parameter schema for one tool |
| `/tool <name> <json-args>` | Execute tool; reply with JSON result |
| (optional) message body is pure JSON `{"tool","args"}` | Same as `/tool` |

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

## Open chat-id mode

- If configured allowed chat ID is `0`, `000`, empty with flag `allow_all=true`, or a dedicated setting **Open access**:
  - Accept messages from **any** `chat.id`
- Default: keep current allowlist behavior
- Still enforce rate limits, approvals, HARDLINE

## Auth (recommended)

- Setting: `telegram.tool_api_token` (optional)
- For `/tool` and `/tools schema` (mutating or all structured commands):
  - Require header-like first line or arg: `token=<secret>`
  - Or only allow structured tools from chats that previously registered with the token

## Implementation sketch (Kotlin)

1. Find Telegram inbound handler + allowlist check
2. Special-case allowed id `0`/`000` → skip deny
3. Parse commands before NLP path:
   - If message starts with `/tools` or `/tool` → structured path
   - Else → existing LLM chat path
4. Structured path:
   - Resolve tool from same registry as local agent
   - Validate args against InputSchema
   - Run approval gate if configured
   - Execute, serialize result to JSON, send as Telegram text/document
5. UI: settings for Open access + Tool API token

## Security

- Open + direct tools is high risk; document in README
- Mutating tools: keep Yes/No unless "trusted agent" mode
- Never log bot token or tool API token

## Alternatives if this is not implemented

| Project | Direct tools? | Notes |
|---------|---------------|-------|
| [Aster](https://aster.matterwardlabs.com/) | Yes (MCP) | 49 tools, any MCP client |
| PocketMCP | Yes (MCP) | On-phone MCP server |
| droid-mcp / phone-mcp-server | Yes | MCP/HTTP |

## Status

- Fork: https://github.com/ermiyas48/rikkahub-agent
- Upstream: https://github.com/ExTV/rikkahub-agent
- Issues disabled on this fork; track work in this doc + PRs
