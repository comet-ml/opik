import { createHash } from 'crypto';

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
    eventKeysByUsageKey: Map<string, string[]>;
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

type PendingAttributionItem = Pick<
    PendingUsage,
    'usageKey' | 'requestKey' | 'turnStartMs' | 'turnEndMs'
>;

function requestChain(item: PendingAttributionItem): string {
    return item.requestKey || item.usageKey;
}

function compareCandidates(left: PendingAttributionItem, right: PendingAttributionItem): number {
    return completionMarker(left) - completionMarker(right) ||
        usageRevision(left.usageKey) - usageRevision(right.usageKey) ||
        requestChain(left).localeCompare(requestChain(right)) ||
        left.usageKey.localeCompare(right.usageKey);
}

function eventSignature(event: UsageEventDisplay): string {
    return JSON.stringify([
        event.timestamp,
        event.model,
        event.chargedCents ?? 0,
        event.tokenUsage?.inputTokens ?? 0,
        event.tokenUsage?.outputTokens ?? 0,
        event.tokenUsage?.cacheReadTokens ?? 0,
        event.tokenUsage?.cacheWriteTokens ?? 0,
    ]);
}

function identifiedEvents(events: UsageEventDisplay[]): Array<{
    event: UsageEventDisplay;
    eventKey: string;
}> {
    const occurrences = new Map<string, number>();
    return [...events]
        .sort((a, b) => Number(a.timestamp) - Number(b.timestamp))
        .map(event => {
            const signature = eventSignature(event);
            const occurrence = occurrences.get(signature) ?? 0;
            occurrences.set(signature, occurrence + 1);
            const eventKey = createHash('sha256')
                .update(`${signature}\u0000${occurrence}`)
                .digest('hex');
            return { event, eventKey };
        });
}

/**
 * Partition Cursor usage events across pending revisions without sharing an
 * event between usage keys. Revision completion times split edits that retain
 * the original user-bubble timestamp. When Cursor exposes no finer identity,
 * chronological assignment preserves the exact total cost once.
 */
export function attributeUsageToPending(
    pending: PendingAttributionItem[],
    turnStartsMs: number[],
    events: UsageEventDisplay[],
    claimedEventOwners: ReadonlyMap<string, string> = new Map()
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
        candidates.sort(compareCandidates);
    }

    const usageByKey = new Map<string, TurnUsage>();
    const eventKeysByUsageKey = new Map<string, string[]>();
    const pendingByUsageKey = new Map(pending.map(item => [item.usageKey, item]));

    for (const { event, eventKey } of identifiedEvents(events)) {
        const claimedOwner = claimedEventOwners.get(eventKey);
        if (claimedOwner) {
            if (pendingByUsageKey.has(claimedOwner)) {
                accumulateUsage(usageByKey, claimedOwner, event);
                const ownedKeys = eventKeysByUsageKey.get(claimedOwner) ?? [];
                ownedKeys.push(eventKey);
                eventKeysByUsageKey.set(claimedOwner, ownedKeys);
            }
            continue;
        }

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

        const usageKey = candidates[targetIndex].usageKey;
        accumulateUsage(usageByKey, usageKey, event);
        const ownedKeys = eventKeysByUsageKey.get(usageKey) ?? [];
        ownedKeys.push(eventKey);
        eventKeysByUsageKey.set(usageKey, ownedKeys);
    }

    const settledKeys = new Set<string>(usageByKey.keys());
    // Collapse only earlier edits from the same durable request chain. An
    // unrelated request sharing the same millisecond must remain pending for
    // its own event instead of being silently settled.
    for (const targetKey of usageByKey.keys()) {
        const target = pendingByUsageKey.get(targetKey);
        if (!target) {
            continue;
        }
        const chain = (candidatesByTurn.get(target.turnStartMs) ?? [])
            .filter(candidate => requestChain(candidate) === requestChain(target))
            .sort(compareCandidates);
        for (const candidate of chain) {
            if (compareCandidates(candidate, target) <= 0) {
                settledKeys.add(candidate.usageKey);
            }
        }
    }

    return { usageByKey, eventKeysByUsageKey, settledKeys };
}
