import { PendingUsage, TurnUsage } from '../interface';

// The usage event is stamped when the request completes, so it always falls
// after its own user bubble and before the next one. A grace window wider than
// the gap between two turns makes a later turn swallow an earlier turn's event.
const CLOCK_GRACE_MS = 0;

export interface UsageEventDisplay {
    timestamp: string;
    model: string;
    conversationId?: string;
    chargedCents?: number;
    tokenUsage?: {
        inputTokens?: number;
        outputTokens?: number;
        cacheReadTokens?: number;
        cacheWriteTokens?: number;
    };
}

export interface PendingUsageAttribution {
    usageByKey: Map<string, TurnUsage>;
    settledKeys: Set<string>;
}

function accumulateUsage(
    attributed: Map<string, TurnUsage>,
    key: string,
    event: UsageEventDisplay
): void {
    const accumulated = attributed.get(key) ?? {
        inputTokens: 0,
        outputTokens: 0,
        cacheReadTokens: 0,
        cacheWriteTokens: 0,
        chargedCents: 0,
        requestCount: 0,
        models: [],
    };
    const usage = event.tokenUsage ?? {};
    accumulated.inputTokens += usage.inputTokens ?? 0;
    accumulated.outputTokens += usage.outputTokens ?? 0;
    accumulated.cacheReadTokens += usage.cacheReadTokens ?? 0;
    accumulated.cacheWriteTokens += usage.cacheWriteTokens ?? 0;
    accumulated.chargedCents += event.chargedCents ?? 0;
    accumulated.requestCount += 1;
    if (event.model && !accumulated.models.includes(event.model)) {
        accumulated.models.push(event.model);
    }
    attributed.set(key, accumulated);
}

export function attributeUsageToTurns(
    turnStartsMs: number[],
    events: UsageEventDisplay[]
): Map<number, TurnUsage> {
    const byKey = new Map<string, TurnUsage>();
    const sorted = [...events].sort((a, b) => Number(a.timestamp) - Number(b.timestamp));

    for (const event of sorted) {
        const at = Number(event.timestamp) + CLOCK_GRACE_MS;
        let turn = -1;
        for (let index = 0; index < turnStartsMs.length; index++) {
            if (turnStartsMs[index] > at) {
                break;
            }
            turn = index;
        }
        if (turn >= 0) {
            accumulateUsage(byKey, String(turn), event);
        }
    }

    return new Map([...byKey].map(([key, usage]) => [Number(key), usage]));
}

function usageRevision(usageKey: string): number {
    const parsed = Number(usageKey.split('\u0000').at(-1));
    return Number.isFinite(parsed) ? parsed : 1;
}

function completionMarker(item: Pick<PendingUsage, 'turnStartMs' | 'turnEndMs'>): number {
    return item.turnEndMs !== undefined && Number.isFinite(item.turnEndMs)
        ? item.turnEndMs
        : item.turnStartMs;
}

/**
 * Partition Cursor usage events across pending revisions without sharing an
 * event between usage keys. Revision completion times split edits that retain
 * the original user-bubble timestamp. When Cursor exposes no finer identity,
 * chronological assignment preserves the exact total cost once.
 */
export function attributeUsageToPending(
    pending: Pick<PendingUsage, 'usageKey' | 'turnStartMs' | 'turnEndMs'>[],
    turnStartsMs: number[],
    events: UsageEventDisplay[]
): PendingUsageAttribution {
    const starts = [...new Set([
        ...turnStartsMs,
        ...pending.map(item => item.turnStartMs),
    ])].sort((a, b) => a - b);
    const candidatesByTurn = new Map<number, typeof pending>();
    for (const item of pending) {
        const candidates = candidatesByTurn.get(item.turnStartMs) ?? [];
        candidates.push(item);
        candidatesByTurn.set(item.turnStartMs, candidates);
    }
    for (const candidates of candidatesByTurn.values()) {
        candidates.sort((left, right) =>
            completionMarker(left) - completionMarker(right) ||
            usageRevision(left.usageKey) - usageRevision(right.usageKey) ||
            left.usageKey.localeCompare(right.usageKey)
        );
    }

    const usageByKey = new Map<string, TurnUsage>();
    const latestAttributedIndexByTurn = new Map<number, number>();
    const sortedEvents = [...events].sort((a, b) => Number(a.timestamp) - Number(b.timestamp));

    for (const event of sortedEvents) {
        const at = Number(event.timestamp) + CLOCK_GRACE_MS;
        let turnStart: number | undefined;
        for (const start of starts) {
            if (start > at) {
                break;
            }
            turnStart = start;
        }
        if (turnStart === undefined) {
            continue;
        }

        const candidates = candidatesByTurn.get(turnStart) ?? [];
        let targetIndex = -1;
        for (let index = 0; index < candidates.length; index++) {
            if (completionMarker(candidates[index]) <= at) {
                targetIndex = index;
            }
        }
        if (targetIndex < 0) {
            continue;
        }

        accumulateUsage(usageByKey, candidates[targetIndex].usageKey, event);
        latestAttributedIndexByTurn.set(
            turnStart,
            Math.max(latestAttributedIndexByTurn.get(turnStart) ?? -1, targetIndex)
        );
    }

    const settledKeys = new Set<string>(usageByKey.keys());
    // If a later revision already has an event, earlier pending revisions with
    // no separately identifiable event cannot be allowed to consume the same
    // bucket on a later tick. Settle them without adding cost; the later event
    // already preserves the exact composer total once.
    for (const [turnStart, latestIndex] of latestAttributedIndexByTurn) {
        const candidates = candidatesByTurn.get(turnStart) ?? [];
        for (let index = 0; index <= latestIndex; index++) {
            settledKeys.add(candidates[index].usageKey);
        }
    }

    return { usageByKey, settledKeys };
}
