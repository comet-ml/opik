interface ConversationHeader {
    bubbleId?: string;
}

/**
 * Put the bubbles of a composer into conversation order.
 *
 * fullConversationHeadersOnly is the order Cursor shows. It is deliberately
 * incomplete: a composer keeps bubbles from branches the user edited away, and
 * those are left out. One real composer holds 833 bubbles for 748 headers.
 * Splicing all of them back in scrambles the order, so the headers decide what
 * belongs to the conversation.
 *
 * The one exception is the error bubbles that record a failed request. Cursor
 * never lists them, and sorting them to the end would attach them to the last
 * turn, so they go in by time. A bubble outside the time range of the headers
 * belongs to a turn that no longer exists and is dropped.
 */
export function orderBubbles(bubbles: any[], headers: ConversationHeader[] | undefined): any[] {
    if (!Array.isArray(headers) || headers.length === 0) {
        return bubbles;
    }

    const orderMap = new Map<string, number>();
    headers.forEach((header, index) => {
        if (header.bubbleId) {
            orderMap.set(header.bubbleId, index);
        }
    });

    const ordered = bubbles
        .filter(bubble => orderMap.has(bubble.id))
        .sort((a, b) => orderMap.get(a.id)! - orderMap.get(b.id)!);

    const anchors = ordered
        .map(bubble => Date.parse(bubble.createdAt))
        .map((time, position) => ({ time, position }))
        .filter(anchor => !Number.isNaN(anchor.time));

    if (anchors.length === 0) {
        return ordered;
    }

    const earliest = anchors[0].time;
    const latest = anchors[anchors.length - 1].time;

    const strays = bubbles.filter(bubble => {
        if (orderMap.has(bubble.id) || !bubble.errorDetails?.message) {
            return false;
        }
        const time = Date.parse(bubble.createdAt);
        return !Number.isNaN(time) && time >= earliest && time <= latest;
    });

    if (strays.length === 0) {
        return ordered;
    }

    // Insert from the back so that the positions found above stay valid.
    const insertions = strays
        .map(bubble => {
            const time = Date.parse(bubble.createdAt);
            let position = 0;
            for (const anchor of anchors) {
                if (anchor.time <= time && anchor.position >= position) {
                    position = anchor.position;
                }
            }
            return { bubble, position };
        })
        .sort((a, b) => b.position - a.position);

    for (const insertion of insertions) {
        ordered.splice(insertion.position + 1, 0, insertion.bubble);
    }

    return ordered;
}
