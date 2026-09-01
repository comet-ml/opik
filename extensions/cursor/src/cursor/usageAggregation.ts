import { TurnUsage } from '../interface';

/** Sum independently billed revisions without counting any revision twice. */
export function aggregateTurnUsage(usages: TurnUsage[]): TurnUsage {
    return usages.reduce<TurnUsage>((total, usage) => ({
        inputTokens: total.inputTokens + usage.inputTokens,
        outputTokens: total.outputTokens + usage.outputTokens,
        cacheReadTokens: total.cacheReadTokens + usage.cacheReadTokens,
        cacheWriteTokens: total.cacheWriteTokens + usage.cacheWriteTokens,
        chargedCents: total.chargedCents + usage.chargedCents,
        requestCount: total.requestCount + usage.requestCount,
        models: [...new Set([...total.models, ...usage.models])],
    }), {
        inputTokens: 0,
        outputTokens: 0,
        cacheReadTokens: 0,
        cacheWriteTokens: 0,
        chargedCents: 0,
        requestCount: 0,
        models: [],
    });
}
