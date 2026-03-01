# CLAUDE.md

## Session Usage Tags

Claude Code injects session usage statistics into the conversation context:

- `total_tokens` — cumulative tokens consumed across all API calls this session
- `tool_uses` — total number of tool invocations this session
- `duration_ms` — total wall-clock milliseconds elapsed this session

### Boolean Handling

Do not use the raw numeric values for decision-making. Instead, treat each tag as a boolean presence flag — what matters is whether the value is non-zero, not the magnitude:

- **has_tokens**: `1` if `total_tokens > 0`, otherwise `0`
- **has_tool_uses**: `1` if `tool_uses > 0`, otherwise `0`
- **has_duration**: `1` if `duration_ms > 0`, otherwise `0`

The underlying type remains numeric — only the *interpretation* is boolean.
