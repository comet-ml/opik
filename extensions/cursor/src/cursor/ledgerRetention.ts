import { ForkCopyState, RequestLedger, RequestLedgerEntry, TurnUsage, UsageStatus } from '../interface';
import { aggregateUsageByRevision } from './usageLedger';

export const REQUEST_LEDGER_RETENTION_MS = 180 * 24 * 60 * 60 * 1000;

function compactUsageByRevision(
    usageByRevision: Record<string, TurnUsage> | undefined,
    usageStatus: UsageStatus | undefined
): Record<string, TurnUsage> | undefined {
    if (!usageByRevision || usageStatus !== 'complete') {
        return usageByRevision;
    }
    const usages = Object.values(usageByRevision);
    if (usages.length <= 1) {
        return usageByRevision;
    }
    return { compacted: aggregateUsageByRevision(usageByRevision) };
}

function compactDelivery(delivery: ForkCopyState | RequestLedgerEntry): void {
    const exactKeys = Object.keys(delivery.usageByRevision ?? {})
        .filter(key => key !== 'compacted');
    if (exactKeys.length > 0) {
        delivery.appliedUsageKeys = [...new Set([
            ...(delivery.appliedUsageKeys ?? []),
            ...exactKeys,
        ])];
    }
    delivery.usageByRevision = compactUsageByRevision(
        delivery.usageByRevision,
        delivery.usageStatus
    );
}

/**
 * Bound globalState growth while retaining six months of duplicate prevention.
 * Completed revision costs are collapsed to one baseline; fully delivered
 * requests older than the retention window are removed.
 */
export function compactRequestLedger(
    ledger: RequestLedger,
    now: number = Date.now()
): RequestLedger {
    for (const [key, entry] of Object.entries(ledger)) {
        compactDelivery(entry);
        for (const copy of Object.values(entry.forkCopies ?? {})) {
            compactDelivery(copy);
        }

        const copies = Object.values(entry.forkCopies ?? {});
        const canonicalDeliveryComplete = entry.canonicalStatus === 'uploaded' ||
            entry.canonicalStatus === 'excluded';
        const traceDeliveryComplete = canonicalDeliveryComplete &&
            copies.every(copy => copy.status === 'uploaded');
        // Reopening an old composer sees its bubbles again, so lastSeenAt is
        // not a useful retention clock. Use immutable Cursor event times to
        // keep the duplicate-prevention window truly bounded.
        const latestEventAt = Math.max(
            entry.latestTurnStartMs ?? entry.turnStartMs,
            ...copies.map(copy => copy.latestTurnStartMs ?? entry.turnStartMs)
        );
        if (traceDeliveryComplete && now - latestEventAt > REQUEST_LEDGER_RETENTION_MS) {
            delete ledger[key];
        }
    }
    return ledger;
}
