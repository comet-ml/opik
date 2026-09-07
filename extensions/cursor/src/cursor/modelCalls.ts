import { BubbleKind, classifyBubble } from './bubbleKinds';

export interface BubbleWindow {
    bubble: any;
    kind: BubbleKind;
    startMs: number;
    endMs: number;
}

export interface ModelCall {
    /** thinking and message bubbles that the model produced */
    output: BubbleWindow[];
    /** tool calls the model requested, plus any error bubble that followed */
    steps: BubbleWindow[];
    startMs: number;
    /** end of the generation itself, which is before the tools run */
    endMs: number;
}

export function timestampOf(bubble: any): number | undefined {
    if (typeof bubble?.resolvedTimestamp === 'number') {
        return bubble.resolvedTimestamp;
    }
    const parsed = Date.parse(bubble?.createdAt ?? '');
    return Number.isNaN(parsed) ? undefined : parsed;
}

/**
 * Cursor writes a bubble when its step finishes, so createdAt is an end time and
 * there is no start time anywhere. The start of a step is therefore the end of
 * the previous step. Bubbles that share a timestamp were dispatched together and
 * all get the same start.
 */
export function assignWindows(bubbles: any[], turnStartMs: number): BubbleWindow[] {
    const windows: BubbleWindow[] = [];
    // End of the last bubble with a strictly earlier timestamp. Tools dispatched
    // together share a timestamp and must all start here.
    let previousDistinctEnd = turnStartMs;
    // End of the bubble immediately before this one. Cursor flushes a tool
    // bubble and the reasoning of the next call at the same millisecond, and
    // that reasoning cannot have started before the tool finished.
    let previousEnd = turnStartMs;
    let currentEnd: number | undefined;

    for (const bubble of bubbles) {
        const kind = classifyBubble(bubble);
        if (kind === 'skip') {
            continue;
        }

        const end = timestampOf(bubble) ?? previousEnd;

        // An error bubble records a moment, not an interval, and Cursor keeps it
        // out of the conversation order. Giving it a width would invent a
        // duration, and letting it move the cursor would squash the next step.
        if (kind === 'error') {
            windows.push({ bubble, kind, startMs: end, endMs: end });
            continue;
        }

        if (currentEnd === undefined || end > currentEnd) {
            previousDistinctEnd = currentEnd ?? previousDistinctEnd;
            currentEnd = end;
        }

        const floor = kind === 'tool' ? previousDistinctEnd : previousEnd;
        let start = floor;

        // timingInfo is the only exact pair Cursor records. It is on 3.4% of bubbles.
        const sendTime = bubble.timingInfo?.clientRpcSendTime;
        if (typeof sendTime === 'number' && sendTime > 0 && sendTime <= end) {
            start = Math.max(floor, sendTime);
        } else if (kind === 'thinking' && typeof bubble.thinkingDurationMs === 'number') {
            // The duration is exact, but the data also contains negative values.
            start = Math.max(floor, Math.min(end, end - bubble.thinkingDurationMs));
        }

        windows.push({
            bubble,
            kind,
            startMs: Math.max(turnStartMs, Math.min(start, end)),
            endMs: Math.max(turnStartMs, end),
        });
        previousEnd = end;
    }

    return windows;
}

/**
 * Cursor records no group id for a model call. usageUuid is one per turn,
 * serverBubbleId is one per bubble, and modelCallId exists only on tool bubbles.
 * The structure is regular though: the model emits reasoning and text, then
 * tool calls, then the loop repeats. So a new call starts wherever reasoning or
 * text follows a tool call.
 */
export function splitIntoModelCalls(windows: BubbleWindow[]): ModelCall[] {
    const calls: ModelCall[] = [];
    let current: ModelCall | undefined;
    let currentToolCallId: string | undefined;
    let currentToolEnd: number | undefined;

    const open = (window: BubbleWindow) => {
        current = { output: [], steps: [], startMs: window.startMs, endMs: window.startMs };
        currentToolCallId = undefined;
        currentToolEnd = undefined;
        calls.push(current);
    };

    for (const window of windows) {
        if (window.kind === 'tool') {
            const modelCallId = window.bubble.toolFormerData?.modelCallId;
            const isNewCall =
                current !== undefined &&
                currentToolCallId !== undefined &&
                modelCallId !== currentToolCallId &&
                window.endMs !== currentToolEnd;

            if (current === undefined || isNewCall) {
                open(window);
            }
            currentToolCallId = typeof modelCallId === 'string' ? modelCallId : currentToolCallId;
            currentToolEnd = window.endMs;
            current!.steps.push(window);
            continue;
        }

        if (window.kind === 'error') {
            if (current === undefined) {
                open(window);
            }
            current!.steps.push(window);
            continue;
        }

        // thinking or message
        if (current === undefined || current.steps.length > 0) {
            open(window);
        }
        current!.output.push(window);
        current!.endMs = Math.max(current!.endMs, window.endMs);
    }

    for (const call of calls) {
        if (call.output.length === 0) {
            call.endMs = call.startMs;
        }
    }

    return calls;
}
