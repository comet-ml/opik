import * as vscode from 'vscode';
import { PendingUsage, TraceData, TurnUsage } from './interface';
import { captureException } from './sentry';
import { latestUsageModel } from './cursor/usageAggregation';

type LoggedTurn = Omit<PendingUsage, 'attempt' | 'nextAttemptAt'>;

async function createClient(apiKey: string) {
    const config = vscode.workspace.getConfiguration();
    const { Opik } = await import('opik');

    return new Opik({
        apiKey: apiKey,
        apiUrl: config.get<string>('opik.apiUrl', 'https://www.comet.com/opik/api'),
        workspaceName: config.get<string>('opik.workspace', 'default'),
    });
}

function isRevision(trace: TraceData): boolean {
    return trace.upload_kind === 'edit_revision' || trace.upload_kind === 'fork_edit_revision';
}

async function updateTraceRevision(
    client: Awaited<ReturnType<typeof createClient>>,
    traceData: TraceData,
    metadata: Record<string, unknown>
): Promise<void> {
    const projectName = traceData.project_name ?? 'default';

    // Keep the original trace/root start time and UUIDv7 table position. The
    // latest edited input/output and end time replace the visible turn data.
    await client.api.traces.updateTrace(traceData.id, {
        body: {
            projectName,
            name: traceData.name,
            endTime: traceData.end_time ? new Date(traceData.end_time) : undefined,
            input: traceData.input,
            output: traceData.output,
            metadata,
            tags: traceData.tags,
            threadId: traceData.thread_id,
        },
    });

    await client.api.spans.updateSpan(traceData.root_span_id, {
        body: {
            traceId: traceData.id,
            projectName,
            name: 'llm_turn',
            type: 'llm',
            endTime: traceData.end_time ? new Date(traceData.end_time) : undefined,
            model: traceData.model,
            provider: 'cursor',
            input: traceData.input,
            output: traceData.output,
            metadata: traceData.metadata,
            tags: traceData.tags,
        },
    });

    // Revision children are appended as an audit trail. Their UUIDv7 values
    // embed the actual edit-event times, while the root remains a single turn.
    if (traceData.spans && traceData.spans.length > 0) {
        await client.api.spans.createSpans({
            spans: traceData.spans.map(child => ({
                id: child.id,
                traceId: traceData.id,
                parentSpanId: traceData.root_span_id,
                projectName,
                name: child.name,
                type: child.type,
                model: child.model,
                provider: child.provider,
                input: child.input,
                output: child.output,
                errorInfo: child.errorInfo,
                startTime: child.startTime,
                endTime: child.endTime,
                tags: child.tags,
                metadata: {
                    ...(child.metadata ?? {}),
                    edit_revision: traceData.revision,
                    revision_turn_start_ms: traceData.turn_start_ms,
                },
            })),
        });
    }
}

export async function logTracesToOpik(apiKey: string, traces: TraceData[]): Promise<LoggedTurn[]> {
    if (traces.length === 0) {
        return [];
    }

    console.log(`📦 Processing ${traces.length} traces using Opik SDK`);
    const loggedTurns: LoggedTurn[] = [];

    try {
        const client = await createClient(apiKey);

        for (const traceData of traces) {
            const metadata = {
                ...(traceData.metadata || {}),
                created_from: "cursor-extension"
            };

            if (isRevision(traceData)) {
                await updateTraceRevision(client, traceData, metadata);
            } else {
                const trace = client.trace({
                    id: traceData.id,
                    name: traceData.name,
                    projectName: traceData.project_name,
                    input: traceData.input,
                    output: traceData.output,
                    startTime: new Date(traceData.start_time),
                    endTime: traceData.end_time ? new Date(traceData.end_time) : undefined,
                    tags: traceData.tags,
                    metadata: metadata,
                    threadId: traceData.thread_id
                });

                // The span is created without usage. Cursor only exposes token
                // counts a few seconds later, so UsageEnricher patches it.
                // This root carries the whole turn usage because Cursor bills
                // once per turn rather than once per model-call child span.
                const span = trace.span({
                    id: traceData.root_span_id,
                    name: 'llm_turn',
                    type: 'llm',
                    model: traceData.model,
                    provider: 'cursor',
                    input: traceData.input,
                    output: traceData.output,
                    startTime: new Date(traceData.start_time),
                    endTime: traceData.end_time ? new Date(traceData.end_time) : undefined,
                    tags: traceData.tags,
                    metadata: traceData.metadata
                });

                for (const child of traceData.spans ?? []) {
                    trace.span({
                        id: child.id,
                        parentSpanId: traceData.root_span_id,
                        name: child.name,
                        type: child.type,
                        model: child.model,
                        provider: child.provider,
                        input: child.input,
                        output: child.output,
                        errorInfo: child.errorInfo,
                        startTime: child.startTime,
                        endTime: child.endTime,
                        tags: child.tags,
                        metadata: child.metadata
                    });
                }
            }
            // Deliberately not calling span.end()/trace.end(): both overwrite
            // endTime with the current time, which would replace the real Cursor
            // turn end with the upload time.

            // Plain fork copies are cost-excluded. Canonical turns and edited
            // fork revisions own the usage generated in their own composer.
            if (traceData.thread_id && traceData.cost_owner) {
                loggedTurns.push({
                    usageKey: traceData.usage_key,
                    requestId: traceData.request_id,
                    requestKey: traceData.request_key,
                    composerId: traceData.thread_id,
                    turnStartMs: traceData.turn_start_ms,
                    turnStartsMs: traceData.turn_starts_ms,
                    traceId: traceData.id,
                    spanId: traceData.root_span_id,
                    projectName: traceData.project_name ?? 'default',
                });
            }
        }

        console.log(`📤 Flushing ${traces.length} traces to Opik`);
        await client.flush();
        console.log(`🎉 All ${traces.length} traces processed successfully using Opik SDK!`);

        return loggedTurns;
    } catch (error) {
        captureException(error);
        console.error('Error processing traces with Opik SDK:', error);
        throw error;
    }
}

/**
 * Cursor reports inputTokens excluding cache, the same way Anthropic does, so
 * prompt_tokens has to add the cache tokens back in. See the equivalent in the
 * Python SDK, llm_usage/anthropic_usage.py get_billable_tokens(). Reporting the
 * cache figures separately as well is the house convention, not double counting.
 *
 * Exported so that scripts/verify-usage.js asserts this exact mapping rather
 * than a copy of it.
 */
export function toSpanUsage(usage: TurnUsage): Record<string, number> {
    const promptTokens = usage.inputTokens + usage.cacheReadTokens + usage.cacheWriteTokens;

    return {
        prompt_tokens: promptTokens,
        completion_tokens: usage.outputTokens,
        total_tokens: promptTokens + usage.outputTokens,
        cache_read_input_tokens: usage.cacheReadTokens,
        cache_creation_input_tokens: usage.cacheWriteTokens,
    };
}

export async function applyTurnUsage(
    apiKey: string,
    pending: PendingUsage,
    usage: TurnUsage
): Promise<void> {
    const client = await createClient(apiKey);

    const spanUsage = toSpanUsage(usage);

    await client.api.spans.updateSpan(pending.spanId, {
        body: {
            traceId: pending.traceId,
            projectName: pending.projectName,
            model: latestUsageModel(usage),
            provider: 'cursor',
            usage: spanUsage,
            totalEstimatedCost: usage.chargedCents / 100,
        },
    });

    console.log(
        `💰 Patched span ${pending.spanId}: ${spanUsage.prompt_tokens} prompt ` +
        `(${usage.inputTokens} fresh + ${usage.cacheReadTokens} cache read + ${usage.cacheWriteTokens} cache write) ` +
        `/ ${usage.outputTokens} completion, ${usage.chargedCents.toFixed(4)}c ` +
        `across ${usage.requestCount} request(s)`
    );
}
