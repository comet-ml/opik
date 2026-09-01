import * as vscode from 'vscode';
import { executeQuery } from './sqlite';
import { getPendingUsage, getRequestLedger, updatePendingUsage } from '../state';
import { PendingUsage, TurnUsage } from '../interface';
import { debugLog } from '../utils';
import { mergePendingUsage, shouldKeepPendingUsage } from './usageQueue';
import { isCursorComposerId } from './composerIdentity';
import {
    attributeUsageToPending,
    PendingUsageAttribution,
    UsageEventDisplay,
} from './usageAttribution';
import { appliedUsageEventOwners } from './usageLedger';

export { attributeUsageToTurns } from './usageAttribution';

const API_HOST = 'https://api2.cursor.sh';
const SERVICE = 'aiserver.v1.DashboardService';

const POLL_SCHEDULE_MS = [5000, 5000, 10000, 30000, 60000, 300000, 300000];

async function readAccessToken(stateDbPath: string): Promise<string | undefined> {
    const rows = await executeQuery(
        stateDbPath,
        `SELECT value FROM ItemTable WHERE key = 'cursorAuth/accessToken'`
    );
    const raw = rows[0]?.value;
    return typeof raw === 'string' && raw.trim() ? raw.trim() : undefined;
}

async function rpc<T>(method: string, token: string, body: unknown): Promise<T> {
    const response = await fetch(`${API_HOST}/${SERVICE}/${method}`, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            'Authorization': `Bearer ${token}`,
            'connect-protocol-version': '1',
        },
        body: JSON.stringify(body ?? {}),
    });

    if (!response.ok) {
        throw new Error(`${method} failed: ${response.status} ${await response.text()}`);
    }
    return await response.json() as T;
}

async function fetchUserId(token: string): Promise<number | undefined> {
    const me = await rpc<{ userId?: number }>('GetMe', token, {});
    return me.userId;
}

async function fetchUsageByConversation(
    token: string,
    userId: number,
    startMs: number,
    endMs: number
): Promise<Map<string, UsageEventDisplay[]>> {
    const byConversation = new Map<string, UsageEventDisplay[]>();

    for (let page = 1; page <= 20; page++) {
        const result = await rpc<{
            totalUsageEventsCount?: number;
            usageEventsDisplay?: UsageEventDisplay[];
        }>('GetFilteredUsageEvents', token, {
            userId,
            startDate: String(startMs),
            endDate: String(endMs),
            page,
            pageSize: 100,
        });

        const events = result.usageEventsDisplay ?? [];
        for (const event of events) {
            if (!event.conversationId) {
                continue;
            }
            const list = byConversation.get(event.conversationId) ?? [];
            list.push(event);
            byConversation.set(event.conversationId, list);
        }

        if (events.length === 0 || page * 100 >= (result.totalUsageEventsCount ?? 0)) {
            break;
        }
    }
    return byConversation;
}

/**
 * Real start time of every user turn in a conversation, oldest first.
 *
 * Attribution needs the complete and stable set of turn boundaries. Deriving
 * them from the pending list instead is wrong, because that list shrinks as
 * turns get patched, which moves the boundaries and re-attributes events that
 * were already counted.
 */
async function readUserTurnStarts(stateDbPath: string, composerId: string): Promise<number[]> {
    if (!isCursorComposerId(composerId)) {
        debugLog('[usage] invalid Cursor composer id', composerId);
        return [];
    }
    const rows = await executeQuery(
        stateDbPath,
        `SELECT json_extract(value, '$.createdAt') AS createdAt FROM cursorDiskKV
         WHERE key >= 'bubbleId:${composerId}:' AND key < 'bubbleId:${composerId};'
         AND json_extract(value, '$.type') = 1`
    );

    const starts = rows
        .map(row => Date.parse(row.createdAt))
        .filter(value => !Number.isNaN(value));

    return [...new Set(starts)].sort((a, b) => a - b);
}

export class UsageEnricher {
    private userId: number | undefined;
    private userIdToken: string | undefined;
    private volatilePending = new Map<string, Omit<PendingUsage, 'attempt' | 'nextAttemptAt'>>();

    constructor(
        private context: vscode.ExtensionContext,
        private applyUsage: (
            pending: PendingUsage,
            usage: TurnUsage,
            eventKeys: string[]
        ) => Promise<void>
    ) {}

    isEnabled(): boolean {
        return vscode.workspace.getConfiguration().get<boolean>('opik.usageEnrichment.enabled', true);
    }

    async track(turns: Omit<PendingUsage, 'attempt' | 'nextAttemptAt'>[]): Promise<void> {
        if (!this.isEnabled() || turns.length === 0) {
            return;
        }
        const nextAttemptAt = Date.now() + POLL_SCHEDULE_MS[0];
        const pending = mergePendingUsage(getPendingUsage(this.context), turns, nextAttemptAt);
        try {
            await updatePendingUsage(this.context, pending);
            for (const turn of turns) {
                this.volatilePending.delete(turn.usageKey);
            }
        } catch (error) {
            // Trace delivery has already succeeded. Keep the cost work in
            // memory and retry persistence from tick() without re-uploading it.
            for (const turn of turns) {
                this.volatilePending.set(turn.usageKey, turn);
            }
            debugLog('[usage] failed to persist pending usage, will retry', String(error));
        }
    }

    // Keyed by token so that switching Cursor accounts re-resolves instead of
    // pairing a stale user id with a new token.
    private async resolveUserId(token: string): Promise<number | undefined> {
        if (this.userId !== undefined && this.userIdToken === token) {
            return this.userId;
        }

        try {
            const userId = await fetchUserId(token);
            if (userId !== undefined) {
                this.userId = userId;
                this.userIdToken = token;
            }
            return userId;
        } catch (error) {
            debugLog('[usage] GetMe failed, will retry', String(error));
            return undefined;
        }
    }

    async tick(stateDbPath: string): Promise<void> {
        if (!this.isEnabled()) {
            return;
        }

        const pending = mergePendingUsage(
            getPendingUsage(this.context),
            [...this.volatilePending.values()],
            Date.now()
        );
        const now = Date.now();
        const due = pending.filter(item => item.nextAttemptAt <= now);
        if (due.length === 0) {
            return;
        }

        // Read once per tick and reuse, so the user id and the token always come
        // from the same sign-in.
        const token = await readAccessToken(stateDbPath);
        if (!token) {
            // Cursor may not have written its token yet, for example when the
            // extension activates before sign-in completes. Retrying on the next
            // tick is correct; latching a failure here would disable enrichment
            // for the rest of the session.
            debugLog('[usage] no Cursor access token available yet, will retry');
            return;
        }

        const userId = await this.resolveUserId(token);
        if (userId === undefined) {
            return;
        }

        const from = Math.min(...due.map(item => item.turnStartMs)) - 60000;
        let byConversation: Map<string, UsageEventDisplay[]>;
        try {
            byConversation = await fetchUsageByConversation(token, userId, from, now + 60000);
        } catch (error) {
            debugLog('[usage] GetFilteredUsageEvents failed', String(error));
            for (const item of due) {
                item.nextAttemptAt = now + 30000;
            }
            await updatePendingUsage(this.context, pending);
            return;
        }

        const startsCache = new Map<string, number[]>();
        const attributionCache = new Map<string, PendingUsageAttribution>();
        const remaining: PendingUsage[] = [];

        for (const item of pending) {
            if (item.nextAttemptAt > now) {
                remaining.push(item);
                continue;
            }

            let attribution = attributionCache.get(item.composerId);
            if (!attribution) {
                let currentStarts = startsCache.get(item.composerId);
                if (!currentStarts) {
                    currentStarts = await readUserTurnStarts(stateDbPath, item.composerId);
                    startsCache.set(item.composerId, currentStarts);
                }
                const composerPending = pending.filter(candidate =>
                    candidate.composerId === item.composerId
                );
                const starts = [...new Set([
                    ...currentStarts,
                    ...composerPending.flatMap(candidate =>
                        candidate.turnStartsMs ?? [candidate.turnStartMs]
                    ),
                ])].sort((a, b) => a - b);
                attribution = attributeUsageToPending(
                    composerPending,
                    starts,
                    byConversation.get(item.composerId) ?? [],
                    appliedUsageEventOwners(getRequestLedger(this.context), item.composerId)
                );
                attributionCache.set(item.composerId, attribution);
            }

            const usage = attribution.usageByKey.get(item.usageKey);
            let applyFailed = false;

            if (usage) {
                try {
                    await this.applyUsage(
                        item,
                        usage,
                        attribution.eventKeysByUsageKey.get(item.usageKey) ?? []
                    );
                    continue;
                } catch (error) {
                    applyFailed = true;
                    debugLog('[usage] failed to patch span', String(error));
                }
            }

            if (!shouldKeepPendingUsage(
                applyFailed,
                attribution.settledKeys.has(item.usageKey)
            )) {
                continue;
            }

            item.attempt += 1;
            const delayIndex = Math.min(item.attempt, POLL_SCHEDULE_MS.length - 1);
            item.nextAttemptAt = now + POLL_SCHEDULE_MS[delayIndex];
            remaining.push(item);
        }
        await updatePendingUsage(this.context, remaining);
        this.volatilePending.clear();
    }
}
