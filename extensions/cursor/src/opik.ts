import * as vscode from 'vscode';
import { PendingUsage, TraceData, TurnUsage } from './interface';
import { captureException } from './sentry';

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

            const trace = client.trace({
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

            // The span is created without usage. Cursor only exposes token counts
            // a few seconds later over its usage API, so UsageEnricher patches it.
            const span = trace.span({
                name: traceData.name,
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
            // Deliberately not calling span.end()/trace.end(): both overwrite
            // endTime with the current time, which would replace the real Cursor
            // turn end with the upload time.

            if (traceData.thread_id) {
                loggedTurns.push({
                    composerId: traceData.thread_id,
                    turnStartMs: traceData.turn_start_ms,
                    traceId: trace.data.id,
                    spanId: span.data.id,
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

export async function applyTurnUsage(
    apiKey: string,
    pending: PendingUsage,
    usage: TurnUsage
): Promise<void> {
    const client = await createClient(apiKey);

    await client.api.spans.updateSpan(pending.spanId, {
        body: {
            traceId: pending.traceId,
            projectName: pending.projectName,
            model: usage.models[0],
            provider: 'cursor',
            usage: {
                prompt_tokens: usage.inputTokens,
                completion_tokens: usage.outputTokens,
                total_tokens: usage.inputTokens + usage.outputTokens,
                cache_read_input_tokens: usage.cacheReadTokens,
                cache_creation_input_tokens: usage.cacheWriteTokens,
            },
            totalEstimatedCost: usage.chargedCents / 100,
        },
    });

    console.log(
        `💰 Patched span ${pending.spanId}: ${usage.inputTokens} in / ${usage.outputTokens} out ` +
        `/ ${usage.cacheReadTokens} cache read, ${usage.chargedCents.toFixed(4)}c ` +
        `across ${usage.requestCount} request(s)`
    );
}
