# Nested spans for the Cursor extension

Sections 1 and 2 record the data this design is based on. Sections 3 and 4
describe what was built.

## 1. Where we were

The extension writes one trace and one span for each user turn.

- `src/cursor/sessionManager.ts` groups the bubbles of a composer into turns.
  One turn is one user bubble plus all the assistant bubbles that follow it.
- `createTraceFromBubbleGroup()` joins the text of the assistant bubbles into a
  single string. It strips the markers `⛢Thought☤`, `⛢Action☤` and
  `⛢RawAction☤`.
- `src/opik.ts` `logTracesToOpik()` creates the trace, then one child span with
  `type: 'llm'`.
- `src/cursor/usage.ts` `UsageEnricher` patches that span later with the token
  counts and the cost from the Cursor usage API.

The raw bubbles are copied into `metadata.userMessages` and
`metadata.aiMessages`. Nothing in the UI reads them, and they can be very large.

**Result:** you see the question and the final answer. You do not see which
tools ran, what they returned, how long each step took, or where the agent
failed.

## 2. What the real data contains

I scanned the local Cursor database:
`~/Library/Application Support/Cursor/User/globalStorage/state.vscdb`.
It holds **19,691 bubbles**. The numbers below come from that scan.

### 2.1 Field coverage

| Field | Bubbles | Share | Meaning |
| --- | --- | --- | --- |
| `createdAt` | 19,690 | 100% | ISO time, millisecond precision |
| `type` | 19,690 | 100% | `1` = user, `2` = assistant |
| `capabilityType` | 12,332 | 62.6% | `15` = tool call, `30` = thinking |
| `toolFormerData` | 11,139 | 56.6% | the tool call record |
| `text` | 6,949 | 35.3% | message text |
| `usageUuid` | 5,912 | 30.0% | groups all bubbles of one turn |
| `thinking` | 2,183 | 11.1% | reasoning text |
| `thinkingDurationMs` | 2,177 | 11.1% | exact duration of the reasoning |
| `requestId` | 2,131 | 10.8% | request id of the turn |
| `modelInfo` | 2,131 | 10.8% | `{ modelName }` |
| `codeBlocks` | 1,225 | 6.2% | code the model proposed |
| `contextWindowStatusAtCreation` | 931 | 4.7% | tokens used and token limit |
| `timingInfo` | 666 | 3.4% | client start, send, settle and end time |
| `todos` | 136 | 0.7% | the to-do list state |
| `webCitations` | 30 | 0.2% | web sources |
| `errorDetails` | 26 | 0.1% | message and stack trace of a failure |
| `turnDurationMs` | 32 | 0.2% | duration of the whole turn |

`tokenCount` is present on 97.5% of the bubbles but is `0/0` almost everywhere.
Only the last bubble of a turn carries the real value, for example
`{"inputTokens": 91311, "outputTokens": 4017}`.

### 2.2 The shape of `toolFormerData`

| Key | Present | Content |
| --- | --- | --- |
| `tool` | always | numeric tool id, stable across Cursor versions |
| `name` | almost always | tool name, **not** stable across versions |
| `toolCallId` | always | provider tool call id, for example `toolu_…` |
| `modelCallId` | always | id of the model call that made this tool call |
| `toolIndex` | always | position of the call inside the model call |
| `status` | always | see below |
| `rawArgs` | 99.6% | the arguments the model sent, as JSON |
| `params` | 99.6% | the arguments after Cursor parsed them |
| `result` | 89.6% | the tool result, as JSON |
| `additionalData` | 70% | review data, sandbox policy, match counts |
| `userDecision` | 12.7% | `accepted` (1,334) or `rejected` (10) |
| `error` | rare | the error object |

Status values across all tool calls:
`completed` 9,269, `loading` 320, `error` 179, `cancelled` 134.

### 2.3 Tool names by numeric id

The `name` changed between Cursor versions. The numeric `tool` id did not.
Use the id as the source of truth and the name as the label.

| id | Names seen in the data |
| --- | --- |
| 8 | `file_search` |
| 9 | `semantic_search_full`, `codebase_search` |
| 11 | `delete_file` |
| 15 | `run_terminal_cmd`, `run_terminal_command_v2`, `bash` |
| 18 | `web_search` |
| 19 | `mcp_<server>_<tool>` (all MCP tools) |
| 30 | `read_lints` |
| 35 | `todo_write` |
| 38 | `edit_file_v2`, `search_replace`, `apply_patch`, `write` |
| 39 | `list_dir`, `list_dir_v2` |
| 40 | `read_file_v2`, `read_file`, `read` |
| 41 | `ripgrep_raw_search`, `grep`, `rg` |
| 42 | `glob_file_search`, `glob` |
| 43 | `create_plan` |
| 48 | `task_v2` (sub-agent) |
| 51 | `ask_question` |
| 57 | `web_fetch` |
| 90 | ACP tools: `execute`, `read`, `edit` |

**Trap:** 1,237 ordinary bubbles carry
`toolFormerData: {"additionalData": {"status": "error"}}` and nothing else.
These are **not** tool calls. Treat a bubble as a tool call only when
`toolFormerData.tool` or `toolFormerData.name` is present.

### 2.4 A real turn

This is one turn from composer `9bb91e1f`, model `gpt-5.2-codex`:

```
00:24:06.778  USER + TEXT     req=c401a2ba
00:24:06.912  cap=30  THINK 2129 ms
00:24:11.383  cap=--  TEXT
00:24:12.281  cap=15  TOOL read_file
00:24:12.281  cap=30  THINK 3781 ms
00:24:18.948  cap=--  TEXT
00:24:19.513  cap=15  TOOL write
00:24:19.513  cap=30  THINK 1032 ms
00:24:49.478  cap=15  TOOL run_terminal_cmd
00:24:56.769  cap=15  TOOL read_lints
00:24:59.947  cap=15  TOOL read_file
00:25:04.536  cap=--  TEXT
00:25:05.396  cap=15  TOOL run_terminal_cmd
00:26:15.680  cap=--  TEXT
00:27:08.353  cap=15  TOOL todo_write
00:27:12.616  cap=--  TEXT   tok=91311/4017
```

Three facts follow from this.

1. Cursor writes a bubble when its step **finishes**. So `createdAt` is the end
   time of the step, not the start time.
2. A thinking bubble and the tool bubble beside it share the same `createdAt`.
   Cursor flushes them together.
3. The token count of the whole turn lands on the last bubble.

### 2.5 Payload size

Median bubble size is 2.8 KB. The tail is long.

| Tool | Calls | Median result | p90 result | Max result |
| --- | --- | --- | --- | --- |
| `mcp_…_browser_take_screenshot` | 120 | 157 KB | 238 KB | 905 KB |
| `semantic_search_full` | 178 | 132 KB | 165 KB | 185 KB |
| `search_replace` | 669 | 13 KB | 42 KB | 290 KB |
| `apply_patch` | 180 | 9 KB | 96 KB | 99 KB |
| `write` | 151 | 8 KB | 30 KB | 57 KB |
| `read_file` | 1,276 | 3.5 KB | 20 KB | 585 KB |
| `run_terminal_cmd` | 1,035 | 0.5 KB | 4 KB | 24 KB |

The largest single bubble is **13.5 MB**. The TypeScript SDK caps a batch at
20 MB. Truncation is not optional.

### 2.6 Can we get token usage for each LLM call?

**No. Cursor does not record it.** Three independent checks say the same thing.

1. **The bubbles.** Only **477 of 19,691** bubbles (2.4%) carry a non-zero
   `tokenCount`. There is exactly **one for each turn**, and it always sits on
   the last bubble of that turn. Composer `9bb91e1f` has 41 turns and 38 such
   bubbles.

2. **The values are turn totals, not call totals.** For composer `9bb91e1f`:

   | Turn | Bubbles | Position of the token bubble | `inputTokens` | `outputTokens` |
   | --- | --- | --- | --- | --- |
   | 4 | 9 | 9 of 9 | 66,309 | 1,099 |
   | 5 | 26 | 26 of 26 | 91,311 | 4,017 |
   | 6 | 48 | 48 of 48 | 124,201 | 14,976 |
   | 13 | 60 | 60 of 60 | 170,431 | 11,518 |
   | 15 | 6 | 6 of 6 | 67,946 | 939 |

   `inputTokens` grows with the conversation and drops at turn 15, which is
   where Cursor compacted the context. It is the prompt size of the last model
   call in the turn, not a sum over the calls.

3. **The usage API is turn-granular too.** `composerData.usageData` for that
   composer reads
   `{"claude-4.5-opus-high-thinking": {"costInCents": 2463, "amount": 46}}`.
   That is **46 billed requests for 41 turns**, while the same composer made
   several hundred model calls. `GetFilteredUsageEvents` bills one request for
   one user turn, not one for each model call.

So an `llm` span for each model call is possible, but only **one span in each
turn can carry real usage**. Do not divide the turn total across the calls.
That invents numbers.

## 3. What was built

### 3.1 Shape

The trace keeps the same `input` and `output`. One `llm_turn` span sits under it
and carries the token usage of the whole turn. Everything else nests under
`llm_turn`, in time order.

```
trace  "cursor-chat"                    input = the question, output = the answer
└── span  llm_turn   type=llm           same input and output, carries the usage
    ├── span  assistant      type=llm       model call 1: reasoning + text
    ├── span  read_file      type=tool
    ├── span  assistant      type=llm       model call 2
    ├── span  search_replace type=tool
    ├── span  search_replace type=tool      dispatched in parallel
    ├── span  assistant      type=llm       model call 3
    └── span  cursor-error   type=general   from errorDetails
```

A real turn from composer `9bb91e1f`, printed by the replay script:

```
── turn 2  (9 spans)  "What about: https://ai-sdk.dev/docs/reference/…"
   llm     assistant          +   0s    4523ms    1.0KB
   tool    read_file          +   5s    1306ms    6.3KB
   llm     assistant          +   6s    3855ms    3.7KB
   tool    search_replace     +  10s     725ms    4.4KB
   tool    search_replace     +  10s     725ms    4.1KB
   tool    search_replace     +  11s    4698ms    4.1KB
   tool    search_replace     +  15s    3927ms    6.8KB
   llm     assistant          +  22s    5344ms     332B
   tool    run_terminal_cmd   +  27s     648ms    2.1KB  ERROR
```

Keeping `llm_turn` costs one span and buys three things: `UsageEnricher` needs
no change at all, the turn total has one honest home, and the trace still reads
as a single question and answer when the child spans are collapsed.

### 3.2 Where one LLM call starts and ends

Cursor records no group id for a model call. `usageUuid` is one for each **turn**
(measured: always exactly 1 per turn). `serverBubbleId` is one for each
**bubble**, not for each call. `modelCallId` exists only on tool bubbles.

The structure itself is regular. This is one real turn from composer
`9bb91e1f`, in header order:

```
00:24:06.778  USER
00:24:06.912  THINK   2129 ms      ┐ model call 1
00:24:11.383  TEXT                 │
00:24:12.281  TOOL read_file       ┘
00:24:12.281  THINK   3781 ms      ┐ model call 2
00:24:18.948  TEXT                 │
00:24:19.513  TOOL write           ┘
```

Cursor flushes the tool bubble and the reasoning of the **next** call together,
which is why they share a timestamp to within 25 ms.

**Cut rule** (`splitIntoModelCalls` in `src/cursor/modelCalls.ts`). Walk the turn
in order and keep a current group. Start a new group when either of these is
true:

- the bubble is `thinking` or `message`, and the current group already holds a
  tool bubble;
- the bubble is a tool call whose `modelCallId` differs from the one already in
  the current group, **and** its timestamp differs too. The timestamp test keeps
  tools dispatched in parallel together.

This covers both shapes in the data: reasoning models (`9bb91e1f`, 275 thinking
bubbles) cut at each `thinking`, and non-reasoning models (`32f1ff14`, 0 thinking
bubbles) cut at each `text`.

A model call with no reasoning and no text left no record of its own duration,
so no `llm` span is emitted for it. Its tool spans still carry `model_call_id`.

### 3.3 Timing

Cursor writes a bubble when its step **finishes**, so `createdAt` is an end time
and there is no start time anywhere (`assignWindows` in `modelCalls.ts`):

- `end` = `createdAt`.
- `start` = the end of the step before it.
- Bubbles that share a timestamp were dispatched together and share a start.
- A `thinking` bubble uses its exact `thinkingDurationMs`, clamped so a negative
  value (the data contains `-1000`) collapses to zero instead of running
  backwards.
- `timingInfo` wins when present. It is the only exact pair Cursor records, on
  3.4% of bubbles.
- A reasoning bubble flushed at the same millisecond as the tool before it
  starts when that tool **ended**, never when it started. Without this rule the
  `llm` span overlaps the tool span exactly.
- An error bubble is a point event: `start` equals `end`, and it does not move
  the cursor for the step after it.

### 3.4 Bubble order

`fullConversationHeadersOnly` is the order Cursor shows, and it is deliberately
incomplete. Composer `9bb91e1f` holds 833 bubbles for 748 headers: the extra
ones come from branches the user edited away.

`orderBubbles` in `src/cursor/bubbleOrder.ts` therefore lets the headers decide
what belongs to the conversation, with one exception. Cursor never lists the
error bubbles that record a failed request, and the old code sorted every
unlisted bubble to the end, which attached them to the last turn. Those go in by
time instead, and one outside the time range of the headers is dropped, because
it belongs to a turn that no longer exists.

### 3.5 Field mapping

**`llm` span** — one for each model call:

| Span field | Source |
| --- | --- |
| `name` | `assistant` |
| `model` | `modelInfo.modelName`, else `composerData.modelConfig.modelName` |
| `provider` | `cursor` |
| `output` | `{ thinking, text, tool_calls: [{ name, arguments }] }` |
| `metadata.thinking_duration_ms` | sum of `thinkingDurationMs` |
| `metadata.context_tokens_used` | `contextWindowStatusAtCreation.tokensUsed` |
| `metadata.context_token_limit` | `contextWindowStatusAtCreation.tokenLimit` |

There is no `input`. Cursor never stores the prompt it sent, and a
reconstruction would be a guess.

**`tool` span** — one for each tool bubble:

| Span field | Source |
| --- | --- |
| `name` | `toolFormerData.name`, else `TOOL_ID_NAMES[tool]` |
| `input` | `JSON.parse(rawArgs)`, else `params` |
| `output` | `JSON.parse(result)` |
| `errorInfo` | set when `status` is `error` or `cancelled` |
| `metadata` | `status`, `tool_id`, `tool_call_id`, `model_call_id`, `user_decision`, `exit_code` |

**`general` span** — one for each bubble that carries `errorDetails`. The
messages are real failures: `PING timed out`, `Network disconnected`,
`Request higher limits to continue using Cursor`, `Model name is not valid: "auto"`.

### 3.6 Where the usage goes

The `llm_turn` span carries it, and only it.

Cursor records tokens once per turn and never per model call, so no other
placement is honest. The backend sums span usage into the trace
(`sumMap(s.usage)` in `TraceDAO.java`), so the trace total is right either way.

`PendingUsage.spanId` still names the `llm_turn` span, so `UsageEnricher` and
`applyTurnUsage()` are untouched.

### 3.7 Truncation

`truncateForSpan` caps each `input` and `output` at
`opik.detailedSpans.maxPayloadChars` (default 10,000). Longer payloads keep the
first 60% and the last 40%, joined by a marker, and the span records
`input_truncated` / `output_truncated` with the original length.

Any base64 run longer than 1,000 characters is replaced by `[binary, N bytes]`
**before** the size check. Browser screenshot results have a median size of
157 KB and a maximum of 905 KB, and one bubble in the database is 13.5 MB.

### 3.8 Trace output

The trace output is the messages the assistant wrote with the name of every tool
call in between, in the order they happened (`buildTurnOutput` in
`spanBuilder.ts`). Consecutive tool calls stay on adjacent lines so a long run
stays compact. Reasoning is left out. The `llm_turn` span shows the same string.

```
Good catch! Looking at the AI SDK documentation…

[read_file]

I see! The property is now `inputSchema` instead of `parameters`.

[search_replace]
[search_replace]
[search_replace]
[search_replace]

Now let me run the tests to validate the fix:

[run_terminal_cmd]
```

The name comes from `toolFormerData.name`, or from `TOOL_ID_NAMES` when a Cursor
version has dropped it.

This also removes the old reason for dropping a turn. A turn that ends on a tool
call with no closing message is common, and it used to be discarded for having
an empty output. It now reads `[read_file]\n[grep]`.

### 3.9 Trace metadata

`metadata.userMessages` and `metadata.aiMessages` are gone. The spans now hold
that data in a readable form, and the raw copy was the largest part of the
payload. The trace instead carries `mode`, `isAgentic`, `createdOnBranch`,
`contextTokensUsed`, `contextTokenLimit`, `filesChangedCount`,
`totalLinesAdded` and `totalLinesRemoved` from the composer record.

## 4. Files

| File | Role |
| --- | --- |
| `src/cursor/bubbleKinds.ts` | classify a bubble; map a numeric tool id to a name |
| `src/cursor/bubbleOrder.ts` | conversation order, including the unlisted error bubbles |
| `src/cursor/modelCalls.ts` | time windows and the model call cut rule |
| `src/cursor/spanBuilder.ts` | build the span list; truncation |
| `src/cursor/sessionManager.ts` | call the builder; trimmed trace metadata |
| `src/opik.ts` | `llm_turn` span plus its children |
| `src/interface.ts` | `SpanData`, and `spans` on `TraceData` |
| `scripts/test-spans.js` | 33 unit tests (`npm run test-spans`) |
| `scripts/replay-composer.js` | print the span tree for a real composer, no upload |

### Settings

- `opik.detailedSpans.enabled`, default `true`.
- `opik.detailedSpans.maxPayloadChars`, default `10000`.
- `opik.detailedSpans.maxSpansPerTurn`, default `200`. Above the cap the first
  and last spans are kept and a `truncated-steps` span records the number
  dropped.

## 5. Risks and how they are handled

| Risk | Handling |
| --- | --- |
| A very large tool result blows the batch limit | `truncateForSpan` (3.7). Verified against the browser screenshot tools, whose results drop from 157 KB to 9.9 KB |
| Secrets inside terminal output or file content | Truncation reduces the volume but does not redact. `opik.detailedSpans.enabled` is the off switch |
| The cut rule for model calls is a heuristic | Cursor gives no group id. Unit tested against both conversation shapes, and checked with the replay script |
| Only the `llm_turn` span carries usage | This matches what Cursor records. The trace total is still right, because the backend sums the span usage |
| A turn is still running when the sync fires | The composer query already skips composers that are not `completed` and were touched in the last 5 minutes |
| A new Cursor version renames a tool | `TOOL_ID_NAMES` keeps the label correct from the numeric id |
| More spans mean more upload volume | Measured: the span payload is smaller than the raw bubble copy it replaces. See 6 |

## 6. What was verified

**Unit tests** — `npm run test-spans`, 33 tests, all passing. They cover the
placeholder `toolFormerData` trap, both forms of the `type` field, the tool id
map, the timing rules (parallel dispatch, a negative thinking duration, the
tool-and-reasoning timestamp collision, the error point event), both cut
shapes, truncation, the binary strip, the span cap, all four bubble ordering
cases, and the interleaved trace output.

**Replay against real conversations** — `node scripts/replay-composer.js <id>`
builds the trace and the spans from the local database and prints them without
uploading. It flags a negative duration, a span that starts before its turn, and
a span that starts before the span above it.

Run over the 30 largest conversations in the database, **9,330 spans**, the
result is **2 warnings**, both in one composer where Cursor's own bubble order
goes backwards by 6 s and 2 s. No negative durations anywhere.

Payload, per composer:

| Composer | Spans | Span payload | Raw bubbles the old metadata carried |
| --- | --- | --- | --- |
| `9bb91e1f` | 613 (286 llm, 324 tool, 3 general) | 1.97 MB | 6.20 MB |
| `32f1ff14` | 844 (366 llm, 477 tool, 1 general) | 1.92 MB | 15.98 MB |
| `8f6dd4ee` | 296 (73 llm, 223 tool) | 1.13 MB | 8.04 MB |
| `5ea1babd` | 340 (143 llm, 197 tool) | 0.67 MB | 1.90 MB |

Dropping `metadata.userMessages` / `metadata.aiMessages` more than pays for the
new spans.

**Still to do, in a live session:** confirm in the Opik UI that the span tree
renders as a waterfall and that the `UsageEnricher` patch lands on `llm_turn`.
Also read the `across N request(s)` line that `applyTurnUsage()` logs. If `N` is
1 almost every time, the design in 3.6 is final. If `N` is often greater than 1,
the turn usage could be split across the child `llm` spans by matching each
event timestamp to a span window.
