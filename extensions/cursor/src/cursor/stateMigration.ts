export function resolveAutomaticTraceCutoff(
    lastSyncedAt: number | undefined,
    legacyLastSyncTime: number | null,
    now: number
): number {
    return lastSyncedAt ?? legacyLastSyncTime ?? now;
}
