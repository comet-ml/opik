import * as vscode from 'vscode';
import { executeQuery } from './sqlite';
import { getPendingUsage, updatePendingUsage } from '../state';
import { PendingUsage, TurnUsage } from '../interface';
import { debugLog } from '../utils';

const API_HOST = 'https://api2.cursor.sh';
const SERVICE = 'aiserver.v1.DashboardService';

const POLL_SCHEDULE_MS = [5000, 5000, 10000, 30000, 60000, 300000, 300000];

// The usage event is stamped when the request completes, so it always falls
// after its own user bubble and before the next one. A grace window wider than
// the gap between two turns makes a later turn swallow an earlier turn's event.
const CLOCK_GRACE_MS = 0;

interface UsageEventDisplay {
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

export async function getCursorUserId(stateDbPath: string): Promise<number | undefined> {
    const token = await readAccessToken(stateDbPath);
    if (!token) {
        return undefined;
    }
    const me = await rpc<{ userId?: number }>('GetMe', token, {});
    return me.userId;
}

async function fetchUsageByConversation(
    stateDbPath: string,
    userId: number,
    startMs: number,
    endMs: number
): Promise<Map<string, UsageEventDisplay[]>> {
    const token = await readAccessToken(stateDbPath);
    const byConversation = new Map<string, UsageEventDisplay[]>();
    if (!token) {
        return byConversation;
    }

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

export function attributeUsageToTurns(
    turnStartsMs: number[],
    events: UsageEventDisplay[]
): Map<number, TurnUsage> {
    const sorted = [...events].sort((a, b) => Number(a.timestamp) - Number(b.timestamp));
    const attributed = new Map<number, TurnUsage>();

    for (const event of sorted) {
        const at = Number(event.timestamp) + CLOCK_GRACE_MS;

        let turn = -1;
        for (let i = 0; i < turnStartsMs.length; i++) {
            if (turnStartsMs[i] > at) {
                break;
            }
            turn = i;
        }
        if (turn < 0) {
            continue;
        }

        const accumulated = attributed.get(turn) ?? {
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
        attributed.set(turn, accumulated);
    }
    return attributed;
}

export class UsageEnricher {
    private userId: number | undefined;
    private userIdLookupFailed = false;

    constructor(
        private context: vscode.ExtensionContext,
        private applyUsage: (pending: PendingUsage, usage: TurnUsage) => Promise<void>
    ) {}

    isEnabled(): boolean {
        return vscode.workspace.getConfiguration().get<boolean>('opik.usageEnrichment.enabled', true);
    }

    track(turns: Omit<PendingUsage, 'attempt' | 'nextAttemptAt'>[]) {
        if (!this.isEnabled() || turns.length === 0) {
            return;
        }
        const pending = getPendingUsage(this.context);
        const known = new Set(pending.map(item => `${item.composerId}:${item.turnStartMs}`));

        for (const turn of turns) {
            if (known.has(`${turn.composerId}:${turn.turnStartMs}`)) {
                continue;
            }
            pending.push({ ...turn, attempt: 0, nextAttemptAt: Date.now() + POLL_SCHEDULE_MS[0] });
        }
        updatePendingUsage(this.context, pending);
    }

    async tick(stateDbPath: string): Promise<void> {
        if (!this.isEnabled()) {
            return;
        }

        const pending = getPendingUsage(this.context);
        const now = Date.now();
        const due = pending.filter(item => item.nextAttemptAt <= now);
        if (due.length === 0) {
            return;
        }

        if (this.userId === undefined) {
            if (this.userIdLookupFailed) {
                return;
            }
            try {
                this.userId = await getCursorUserId(stateDbPath);
            } catch (error) {
                debugLog('[usage] GetMe failed', String(error));
            }
            if (this.userId === undefined) {
                this.userIdLookupFailed = true;
                return;
            }
        }

        const from = Math.min(...due.map(item => item.turnStartMs)) - 60000;
        let byConversation: Map<string, UsageEventDisplay[]>;
        try {
            byConversation = await fetchUsageByConversation(stateDbPath, this.userId, from, now + 60000);
        } catch (error) {
            debugLog('[usage] GetFilteredUsageEvents failed', String(error));
            for (const item of due) {
                item.nextAttemptAt = now + 30000;
            }
            updatePendingUsage(this.context, pending);
            return;
        }

        const startsCache = new Map<string, number[]>();
        const remaining: PendingUsage[] = [];

        for (const item of pending) {
            if (item.nextAttemptAt > now) {
                remaining.push(item);
                continue;
            }

            let starts = startsCache.get(item.composerId);
            if (!starts) {
                starts = await readUserTurnStarts(stateDbPath, item.composerId);
                startsCache.set(item.composerId, starts);
            }

            const index = starts.indexOf(item.turnStartMs);
            const usage = index < 0
                ? undefined
                : attributeUsageToTurns(starts, byConversation.get(item.composerId) ?? []).get(index);

            if (index < 0) {
                debugLog('[usage] turn start not found in conversation', {
                    composerId: item.composerId,
                    turnStartMs: item.turnStartMs,
                    starts,
                });
            }

            if (usage) {
                try {
                    await this.applyUsage(item, usage);
                    continue;
                } catch (error) {
                    debugLog('[usage] failed to patch span', String(error));
                }
            }

            item.attempt += 1;
            if (item.attempt < POLL_SCHEDULE_MS.length) {
                item.nextAttemptAt = now + POLL_SCHEDULE_MS[item.attempt];
                remaining.push(item);
            }
        }
        updatePendingUsage(this.context, remaining);
    }
}
