import { PendingUsage, TraceData } from '../interface';

export type LoggedTurn = Omit<PendingUsage, 'attempt' | 'nextAttemptAt'>;

export interface TraceDeliveryClient {
    api: {
        traces: {
            updateTrace(id: string, request: any): Promise<unknown>;
        };
        spans: {
            updateSpan(id: string, request: any): Promise<unknown>;
            createSpans(request: any): Promise<unknown>;
        };
    };
    trace(data: any): {
        span(data: any): unknown;
    };
    flush(): Promise<void>;
}

function isRevision(trace: TraceData): boolean {
    return trace.upload_kind === 'edit_revision' || trace.upload_kind === 'fork_edit_revision';
}

async function updateTraceRevision(
    client: TraceDeliveryClient,
    traceData: TraceData,
    metadata: Record<string, unknown>
): Promise<void> {
    const projectName = traceData.project_name ?? 'default';

    // Keep the original trace/root start time and UUIDv7 table position. The
    // latest edited input/output and end time replace the visible turn data.
    await client.api.traces.updateTrace(traceData.id, {
        body: {
            projectName,
            name: traceData.name,
            endTime: traceData.end_time ? new Date(traceData.end_time) : undefined,
            input: traceData.input,
            output: traceData.output,
            metadata,
            tags: traceData.tags,
            threadId: traceData.thread_id,
        },
    });

    await client.api.spans.updateSpan(traceData.root_span_id, {
        body: {
            traceId: traceData.id,
            projectName,
            name: 'llm_turn',
            type: 'llm',
            endTime: traceData.end_time ? new Date(traceData.end_time) : undefined,
            model: traceData.model,
            provider: 'cursor',
            input: traceData.input,
            output: traceData.output,
            metadata: traceData.metadata,
            tags: traceData.tags,
        },
    });

    // Revision children are appended as an audit trail. Their UUIDv7 values
    // embed the actual edit-event times, while the root remains a single turn.
    if (traceData.spans && traceData.spans.length > 0) {
        await client.api.spans.createSpans({
            spans: traceData.spans.map(child => ({
                id: child.id,
                traceId: traceData.id,
                parentSpanId: traceData.root_span_id,
                projectName,
                name: child.name,
                type: child.type,
                model: child.model,
                provider: child.provider,
                input: child.input,
                output: child.output,
                errorInfo: child.errorInfo,
                startTime: child.startTime,
                endTime: child.endTime,
                tags: child.tags,
                metadata: {
                    ...(child.metadata ?? {}),
                    edit_revision: traceData.revision,
                    revision_turn_start_ms: traceData.turn_start_ms,
                },
            })),
        });
    }
}

export async function deliverTracesWithClient(
    client: TraceDeliveryClient,
    traces: TraceData[]
): Promise<LoggedTurn[]> {
    const loggedTurns: LoggedTurn[] = [];

    for (const traceData of traces) {
        const metadata = {
            ...(traceData.metadata || {}),
            created_from: 'cursor-extension',
        };

        if (isRevision(traceData)) {
            await updateTraceRevision(client, traceData, metadata);
        } else {
            const trace = client.trace({
                id: traceData.id,
                name: traceData.name,
                projectName: traceData.project_name,
                input: traceData.input,
                output: traceData.output,
                startTime: new Date(traceData.start_time),
                endTime: traceData.end_time ? new Date(traceData.end_time) : undefined,
                tags: traceData.tags,
                metadata,
                threadId: traceData.thread_id,
            });

            // The root carries the whole turn usage because Cursor bills once
            // per turn rather than once per model-call child span.
            trace.span({
                id: traceData.root_span_id,
                name: 'llm_turn',
                type: 'llm',
                model: traceData.model,
                provider: 'cursor',
                input: traceData.input,
                output: traceData.output,
                startTime: new Date(traceData.start_time),
                endTime: traceData.end_time ? new Date(traceData.end_time) : undefined,
                tags: traceData.tags,
                metadata: traceData.metadata,
            });

            for (const child of traceData.spans ?? []) {
                trace.span({
                    id: child.id,
                    parentSpanId: traceData.root_span_id,
                    name: child.name,
                    type: child.type,
                    model: child.model,
                    provider: child.provider,
                    input: child.input,
                    output: child.output,
                    errorInfo: child.errorInfo,
                    startTime: child.startTime,
                    endTime: child.endTime,
                    tags: child.tags,
                    metadata: child.metadata,
                });
            }

            // Do not call span.end()/trace.end(): both replace the Cursor end
            // timestamp with the upload time.
        }

        // Plain fork copies are cost-excluded. Canonical turns and edited fork
        // revisions own the usage generated in their own composer.
        if (traceData.thread_id && traceData.cost_owner) {
            loggedTurns.push({
                usageKey: traceData.usage_key,
                requestId: traceData.request_id,
                requestKey: traceData.request_key,
                composerId: traceData.thread_id,
                turnStartMs: traceData.turn_start_ms,
                turnEndMs: traceData.end_time
                    ? new Date(traceData.end_time).getTime()
                    : undefined,
                turnStartsMs: traceData.turn_starts_ms,
                traceId: traceData.id,
                spanId: traceData.root_span_id,
                projectName: traceData.project_name ?? 'default',
            });
        }
    }

    await client.flush();
    return loggedTurns;
}
