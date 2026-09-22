import { PendingUsage } from '../interface';

type UsageTurn = Omit<PendingUsage, 'attempt' | 'nextAttemptAt'>;

function fallbackIdentity(item: Pick<PendingUsage, 'composerId' | 'turnStartMs'>): string {
    return `${item.composerId}:${item.turnStartMs}`;
}

function nonEmptyString(value: unknown): value is string {
    return typeof value === 'string' && value.length > 0;
}

function hasDurableUsageKey(value: unknown): value is string {
    return nonEmptyString(value) &&
        !value.startsWith('legacy\u0000') &&
        value.split('\u0000').length >= 5;
}

function legacyCompatibilityKey(item: Partial<PendingUsage>): string | undefined {
    if (!nonEmptyString(item.composerId) ||
        !Number.isFinite(item.turnStartMs) ||
        !nonEmptyString(item.traceId) ||
        !nonEmptyString(item.spanId)) {
        return undefined;
    }
    return [
        'legacy',
        item.projectName ?? 'default',
        item.composerId,
        String(item.turnStartMs),
        item.traceId,
        item.spanId,
    ].join('\u0000');
}

function isLegacyCompatibilityItem(item: Partial<PendingUsage>): boolean {
    return item.usageKey?.startsWith('legacy\u0000') === true ||
        !hasDurableUsageKey(item.usageKey) ||
        item.requestKey?.startsWith('legacy\u0000') === true;
}

function normalizeLegacyPending(item: PendingUsage, now: number): PendingUsage {
    const compatibilityKey = legacyCompatibilityKey(item);
    if (!compatibilityKey) {
        return item;
    }
    return {
        ...item,
        usageKey: hasDurableUsageKey(item.usageKey)
            ? item.usageKey
            : `${compatibilityKey}\u00001`,
        requestId: nonEmptyString(item.requestId) ? item.requestId : compatibilityKey,
        requestKey: nonEmptyString(item.requestKey) ? item.requestKey : compatibilityKey,
        turnStartsMs: Array.isArray(item.turnStartsMs) && item.turnStartsMs.length > 0
            ? item.turnStartsMs
            : [item.turnStartMs],
        attempt: Number.isFinite(item.attempt) ? item.attempt : 0,
        nextAttemptAt: Number.isFinite(item.nextAttemptAt) ? item.nextAttemptAt : now,
    };
}

export function canAttributePendingUsage(
    item: Partial<PendingUsage>
): item is PendingUsage {
    return nonEmptyString(item.usageKey) &&
        nonEmptyString(item.requestKey) &&
        nonEmptyString(item.composerId) &&
        Number.isFinite(item.turnStartMs) &&
        nonEmptyString(item.traceId) &&
        nonEmptyString(item.spanId) &&
        Number.isFinite(item.attempt) &&
        Number.isFinite(item.nextAttemptAt);
}

/** Merge new work while upgrading legacy records that predate usageKey. */
export function mergePendingUsage(
    pending: PendingUsage[],
    turns: UsageTurn[],
    now: number
): PendingUsage[] {
    const merged = [...pending];

    for (const turn of turns) {
        const index = merged.findIndex(item =>
            (item.usageKey && item.usageKey === turn.usageKey) ||
            (isLegacyCompatibilityItem(item) &&
                fallbackIdentity(item) === fallbackIdentity(turn))
        );
        if (index >= 0) {
            const previous = merged[index];
            // Fill the request-ledger fields on upgraded records so usage is
            // aggregated through the same delivery rather than patched twice.
            merged[index] = {
                ...previous,
                ...turn,
                attempt: previous.attempt ?? 0,
                nextAttemptAt: previous.nextAttemptAt ?? now,
            };
            continue;
        }
        merged.push({
            ...turn,
            attempt: 0,
            nextAttemptAt: now,
        });
    }

    return merged.map(item => normalizeLegacyPending(item, now));
}

export function shouldKeepPendingUsage(applyFailed: boolean, settled: boolean): boolean {
    return applyFailed || !settled;
}
