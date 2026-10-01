export interface ComposerHeaderIdentity {
    composerId: string;
    composerCreatedAt: number;
    headers: unknown[];
}

interface ComposerHeader {
    bubbleId?: string;
    type?: number;
    createdAt?: string;
}

export interface CanonicalBubbleOwner {
    composerId: string;
    turnStartMs: number;
    composerCreatedAt: number;
}

const CURSOR_COMPOSER_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export function isCursorComposerId(value: unknown): value is string {
    return typeof value === 'string' && CURSOR_COMPOSER_ID.test(value);
}

export function composerIdFromKey(value: unknown): string | undefined {
    if (typeof value !== 'string' || !value.startsWith('composerData:')) {
        return undefined;
    }
    const composerId = value.slice('composerData:'.length);
    return isCursorComposerId(composerId) ? composerId : undefined;
}

function isComposerHeader(value: unknown): value is ComposerHeader {
    return typeof value === 'object' && value !== null;
}

/**
 * Cursor keeps the inherited user-bubble header in every fork. The earliest
 * composer carrying that header is the durable owner of the logical request,
 * even when only a newer fork is present in the incremental polling window.
 */
export function resolveCanonicalBubbleOwners(
    composers: ComposerHeaderIdentity[]
): Map<string, CanonicalBubbleOwner> {
    const owners = new Map<string, CanonicalBubbleOwner>();

    for (const composer of composers) {
        if (!isCursorComposerId(composer.composerId)) {
            continue;
        }
        for (const header of composer.headers) {
            if (!isComposerHeader(header) ||
                header.type !== 1 ||
                typeof header.bubbleId !== 'string' ||
                !header.bubbleId) {
                continue;
            }
            const parsed = Date.parse(header.createdAt ?? '');
            const turnStartMs = Number.isNaN(parsed) ? composer.composerCreatedAt : parsed;
            const candidate = {
                composerId: composer.composerId,
                turnStartMs,
                composerCreatedAt: composer.composerCreatedAt,
            };
            const current = owners.get(header.bubbleId);
            if (!current) {
                owners.set(header.bubbleId, candidate);
                continue;
            }
            const earlierComposer = candidate.composerCreatedAt < current.composerCreatedAt ||
                (candidate.composerCreatedAt === current.composerCreatedAt &&
                    candidate.composerId < current.composerId)
                ? candidate
                : current;
            owners.set(header.bubbleId, {
                ...earlierComposer,
                turnStartMs: Math.min(current.turnStartMs, candidate.turnStartMs),
            });
        }
    }

    return owners;
}
