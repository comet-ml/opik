import { ForkCopyState, RequestLedger, RequestLedgerEntry, TurnUsage } from '../interface';
import { aggregateTurnUsage } from './usageAggregation';

export type UsageDelivery = ForkCopyState | RequestLedgerEntry;

const COMPACTED_KEY = 'compacted';

function revisionOrder(key: string): number {
    if (key === COMPACTED_KEY) {
        return Number.NEGATIVE_INFINITY;
    }
    const revision = Number(key.split('\u0000').at(-1));
    return Number.isFinite(revision) ? revision : 0;
}

export function aggregateUsageByRevision(
    usageByRevision: Record<string, TurnUsage>
): TurnUsage {
    const ordered = Object.entries(usageByRevision)
        .sort(([left], [right]) =>
            revisionOrder(left) - revisionOrder(right) || left.localeCompare(right)
        )
        .map(([, usage]) => usage);
    return aggregateTurnUsage(ordered);
}

function exactUsageKeys(delivery: UsageDelivery): string[] {
    return Object.keys(delivery.usageByRevision ?? {}).filter(key => key !== COMPACTED_KEY);
}

export function hasAppliedUsage(delivery: UsageDelivery, usageKey: string): boolean {
    if (delivery.appliedUsageKeys?.includes(usageKey)) {
        return true;
    }
    if (delivery.usageByRevision?.[usageKey]) {
        return true;
    }

    // Builds briefly shipped during development with a compacted aggregate but
    // without the exact acknowledgement keys. A completed delivery plus that
    // aggregate means a surviving queue entry is a removal retry, not new cost.
    return delivery.usageStatus === 'complete' &&
        delivery.appliedUsageKeys === undefined &&
        delivery.usageByRevision?.[COMPACTED_KEY] !== undefined;
}

export function recordUsageEventOwnership(
    delivery: UsageDelivery,
    usageKey: string,
    eventKeys: string[]
): boolean {
    delivery.appliedUsageEvents ??= {};
    let changed = false;
    for (const eventKey of eventKeys) {
        if (delivery.appliedUsageEvents[eventKey] !== usageKey) {
            delivery.appliedUsageEvents[eventKey] = usageKey;
            changed = true;
        }
    }
    return changed;
}

export function recordAppliedUsage(
    delivery: UsageDelivery,
    usageKey: string,
    usage: TurnUsage,
    eventKeys: string[] = []
): TurnUsage {
    delivery.usageByRevision ??= {};
    delivery.appliedUsageKeys = [...new Set([
        ...(delivery.appliedUsageKeys ?? []),
        ...exactUsageKeys(delivery),
        usageKey,
    ])];
    recordUsageEventOwnership(delivery, usageKey, eventKeys);
    delivery.usageByRevision[usageKey] = usage;
    return aggregateUsageByRevision(delivery.usageByRevision);
}

export function aggregateUsageWithRevision(
    delivery: UsageDelivery,
    usageKey: string,
    usage: TurnUsage
): TurnUsage {
    return aggregateUsageByRevision({
        ...(delivery.usageByRevision ?? {}),
        [usageKey]: usage,
    });
}

export function appliedUsageEventOwners(
    ledger: RequestLedger,
    composerId: string
): Map<string, string> {
    const owners = new Map<string, string>();
    for (const entry of Object.values(ledger)) {
        const delivery = entry.ownerComposerId === composerId
            ? entry
            : entry.forkCopies?.[composerId];
        for (const [eventKey, usageKey] of Object.entries(delivery?.appliedUsageEvents ?? {})) {
            owners.set(eventKey, usageKey);
        }
    }
    return owners;
}
