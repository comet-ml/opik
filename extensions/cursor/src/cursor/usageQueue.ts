import { PendingUsage } from '../interface';

type UsageTurn = Omit<PendingUsage, 'attempt' | 'nextAttemptAt'>;

function fallbackIdentity(item: Pick<PendingUsage, 'composerId' | 'turnStartMs'>): string {
    return `${item.composerId}:${item.turnStartMs}`;
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
            ((!item.usageKey || item.usageKey.split('\u0000').length < 5) &&
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

    return merged;
}
