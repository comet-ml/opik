import { SpanData } from '../interface';
import { classifyBubble, toolName } from './bubbleKinds';
import { assignWindows, BubbleWindow, ModelCall, splitIntoModelCalls, timestampOf } from './modelCalls';

export interface SpanBuildOptions {
    maxPayloadChars: number;
    maxSpansPerTurn: number;
}

export const DEFAULT_SPAN_OPTIONS: SpanBuildOptions = {
    maxPayloadChars: 10000,
    maxSpansPerTurn: 200,
};

// Long unbroken base64 runs are screenshots. A single browser screenshot result
// has a median size of 157 KB and a maximum of 905 KB in a real database.
const BASE64_RUN = /^(?:data:[a-z+/-]+;base64,)?[A-Za-z0-9+/\s]{1000,}={0,2}$/;

function stripBinary(value: unknown, depth = 0): unknown {
    if (typeof value === 'string') {
        return value.length > 1000 && BASE64_RUN.test(value)
            ? `[binary, ${value.length} bytes]`
            : value;
    }
    if (Array.isArray(value)) {
        return depth < 12 ? value.map(item => stripBinary(item, depth + 1)) : value;
    }
    if (value && typeof value === 'object' && depth < 12) {
        const result: Record<string, unknown> = {};
        for (const [key, item] of Object.entries(value)) {
            result[key] = stripBinary(item, depth + 1);
        }
        return result;
    }
    return value;
}

export interface Truncated {
    value: unknown;
    truncated: boolean;
    originalLength: number;
}

export function truncateForSpan(value: unknown, limit: number): Truncated {
    if (value === undefined || value === null) {
        return { value: undefined, truncated: false, originalLength: 0 };
    }

    const cleaned = stripBinary(value);
    const json = JSON.stringify(cleaned) ?? '';
    if (json.length <= limit) {
        return { value: cleaned, truncated: false, originalLength: json.length };
    }

    const head = Math.floor(limit * 0.6);
    const tail = limit - head;
    const text =
        json.slice(0, head) +
        `\n…[truncated ${json.length - limit} characters]…\n` +
        json.slice(json.length - tail);

    return { value: text, truncated: true, originalLength: json.length };
}

function safeJsonParse(raw: unknown): unknown {
    if (typeof raw !== 'string') {
        return raw ?? undefined;
    }
    const trimmed = raw.trim();
    if (!trimmed) {
        return undefined;
    }
    try {
        return JSON.parse(trimmed);
    } catch {
        return trimmed;
    }
}

function applyPayload(
    span: SpanData,
    field: 'input' | 'output',
    value: unknown,
    limit: number
): void {
    const { value: payload, truncated, originalLength } = truncateForSpan(value, limit);
    if (payload === undefined) {
        return;
    }
    span[field] = payload;
    if (truncated) {
        span.metadata = {
            ...span.metadata,
            [`${field}_truncated`]: true,
            [`${field}_original_length`]: originalLength,
        };
    }
}

function modelOf(call: ModelCall, conversation: any): string | undefined {
    for (const window of [...call.output, ...call.steps]) {
        const name = window.bubble.modelInfo?.modelName;
        if (typeof name === 'string' && name) {
            return name;
        }
    }
    return conversation?.model;
}

function buildLlmSpan(call: ModelCall, conversation: any, options: SpanBuildOptions): SpanData {
    const thinking = call.output
        .filter(window => window.kind === 'thinking')
        .map(window => window.bubble.thinking.text)
        .join('\n\n');

    const text = call.output
        .filter(window => window.kind === 'message')
        .map(window => window.bubble.text)
        .join('\n\n');

    const toolCalls = call.steps
        .filter(window => window.kind === 'tool')
        .map(window => ({
            name: toolName(window.bubble.toolFormerData),
            arguments: safeJsonParse(window.bubble.toolFormerData?.rawArgs)
                ?? safeJsonParse(window.bubble.toolFormerData?.params),
        }));

    const span: SpanData = {
        name: 'assistant',
        type: 'llm',
        model: modelOf(call, conversation),
        provider: 'cursor',
        startTime: new Date(call.startMs),
        endTime: new Date(call.endMs),
        tags: ['assistant'],
    };

    const thinkingMs = call.output
        .filter(window => window.kind === 'thinking')
        .reduce((total, window) => total + Math.max(0, window.bubble.thinkingDurationMs ?? 0), 0);

    const contextStatus = call.output
        .map(window => window.bubble.contextWindowStatusAtCreation)
        .find(status => status?.tokenLimit);

    const metadata: Record<string, unknown> = {};
    if (thinkingMs > 0) {
        metadata.thinking_duration_ms = thinkingMs;
    }
    if (contextStatus) {
        metadata.context_tokens_used = contextStatus.tokensUsed;
        metadata.context_token_limit = contextStatus.tokenLimit;
    }
    if (toolCalls.length > 0) {
        metadata.tool_call_count = toolCalls.length;
    }
    if (Object.keys(metadata).length > 0) {
        span.metadata = metadata;
    }

    // No input: Cursor never stores the prompt it sent, and a reconstruction
    // would be a guess.
    applyPayload(
        span,
        'output',
        {
            ...(thinking ? { thinking } : {}),
            ...(text ? { text } : {}),
            ...(toolCalls.length > 0 ? { tool_calls: toolCalls } : {}),
        },
        options.maxPayloadChars
    );

    return span;
}

function buildToolSpan(window: BubbleWindow, options: SpanBuildOptions): SpanData {
    const data = window.bubble.toolFormerData ?? {};
    const status = typeof data.status === 'string' ? data.status : undefined;
    const result = safeJsonParse(data.result);

    const span: SpanData = {
        name: toolName(data),
        type: 'tool',
        startTime: new Date(window.startMs),
        endTime: new Date(window.endMs),
        tags: ['tool', toolName(data), ...(status ? [status] : [])],
        metadata: {
            status,
            tool_id: data.tool,
            tool_call_id: data.toolCallId,
            model_call_id: data.modelCallId,
            ...(data.userDecision ? { user_decision: data.userDecision } : {}),
            ...(typeof (result as any)?.exitCodeV2 === 'number'
                ? { exit_code: (result as any).exitCodeV2 }
                : {}),
        },
    };

    if (status === 'error' || status === 'cancelled') {
        const message = safeJsonParse(data.error);
        span.errorInfo = {
            exceptionType: `cursor-tool-${status}`,
            message: typeof message === 'string' ? message : JSON.stringify(message ?? status),
            traceback: '',
        };
    }

    applyPayload(
        span,
        'input',
        safeJsonParse(data.rawArgs) ?? safeJsonParse(data.params),
        options.maxPayloadChars
    );
    applyPayload(span, 'output', result, options.maxPayloadChars);

    return span;
}

function buildErrorSpan(window: BubbleWindow, options: SpanBuildOptions): SpanData {
    const details = window.bubble.errorDetails ?? {};
    const stack = truncateForSpan(details.stackTrace, options.maxPayloadChars);

    return {
        name: 'cursor-error',
        type: 'general',
        startTime: new Date(window.startMs),
        endTime: new Date(window.endMs),
        tags: ['error'],
        errorInfo: {
            exceptionType: 'cursor-request-error',
            message: String(details.message ?? 'Cursor reported an error'),
            traceback: typeof stack.value === 'string' ? stack.value : '',
        },
        metadata: {
            request_id: details.requestId,
            ...(stack.value !== undefined ? { stack_trace: stack.value } : {}),
        },
    };
}

/**
 * The assistant side of a turn as one readable string: the messages it wrote,
 * with the name of every tool call in between, in the order they happened.
 * Consecutive tool calls stay on adjacent lines so a long run stays compact.
 *
 *   Let me check the file.
 *
 *   [read_file]
 *   [grep]
 *
 *   The import is missing.
 *
 *   [search_replace]
 */
export function buildTurnOutput(aiMessages: any[]): string {
    const blocks: string[] = [];
    let toolRun: string[] = [];

    const flushTools = () => {
        if (toolRun.length > 0) {
            blocks.push(toolRun.join('\n'));
            toolRun = [];
        }
    };

    for (const bubble of aiMessages) {
        const kind = classifyBubble(bubble);

        if (kind === 'tool') {
            toolRun.push(`[${toolName(bubble.toolFormerData)}]`);
            continue;
        }

        if (kind === 'message') {
            const text = (bubble.text || bubble.content || bubble.rawText || '').trim();
            if (text) {
                flushTools();
                blocks.push(text);
            }
        }
    }

    flushTools();
    return blocks.join('\n\n');
}

/**
 * Children of the llm_turn span, in time order: one llm span for each model call
 * that produced reasoning or text, then the tool and error spans that followed it.
 */
export function buildSpans(
    group: { userMessages: any[]; aiMessages: any[] },
    conversation: any,
    options: SpanBuildOptions = DEFAULT_SPAN_OPTIONS
): SpanData[] {
    const turnStartMs =
        timestampOf(group.userMessages[0]) ?? conversation?.createdAt ?? Date.now();

    const windows = assignWindows(group.aiMessages, turnStartMs);
    const calls = splitIntoModelCalls(windows);
    const spans: SpanData[] = [];

    for (const call of calls) {
        // A call with no reasoning and no text left no record of its own
        // duration, so a zero width span would only add noise. Its tool spans
        // still carry model_call_id.
        if (call.output.length > 0) {
            spans.push(buildLlmSpan(call, conversation, options));
        }
        for (const step of call.steps) {
            spans.push(
                step.kind === 'error'
                    ? buildErrorSpan(step, options)
                    : buildToolSpan(step, options)
            );
        }
    }

    const cap = Math.max(2, options.maxSpansPerTurn);
    if (spans.length <= cap) {
        return spans;
    }

    const keep = Math.floor(cap / 2);
    const head = spans.slice(0, keep);
    const tail = spans.slice(spans.length - keep);
    const dropped = spans.length - head.length - tail.length;

    head.push({
        name: 'truncated-steps',
        type: 'general',
        startTime: head[head.length - 1].endTime,
        endTime: tail[0].startTime,
        tags: ['truncated'],
        metadata: { dropped_span_count: dropped, total_span_count: spans.length },
    });

    return [...head, ...tail];
}

export { classifyBubble };
