import * as vscode from 'vscode';
import { PendingUsage, TraceData, TurnUsage } from './interface';
import { captureException } from './sentry';
import { latestUsageModel } from './cursor/usageAggregation';
import { deliverTracesWithClient, LoggedTurn } from './cursor/traceDelivery';

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
    try {
        const client = await createClient(apiKey);
        console.log(`📤 Flushing ${traces.length} traces to Opik`);
        const loggedTurns = await deliverTracesWithClient(client, traces);
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
