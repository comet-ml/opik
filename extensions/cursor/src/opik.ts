import axios from 'axios';
import { v7 as uuidv7 } from 'uuid';
import { TraceData } from './interface';
import { Sentry } from './sentry';

interface OpikTrace extends Omit<TraceData, 'usage'> {
    id: string;
}

interface OpikSpan {
    id: string;
    trace_id: string;
    name: string;
    type: 'llm';
    project_name?: string;
    start_time: string;
    end_time?: string;
    input: any;
    output: any;
    usage?: {
        completion_tokens?: number;
        prompt_tokens?: number;
        total_tokens?: number;
    };
    tags?: string[];
    metadata?: any;
}

export async function logTracesToOpik(apiKey: string, traces: TraceData[]): Promise<void> {
    if (traces.length === 0) return;
    
    const BATCH_SIZE = 10;
    const TIMEOUT = 30000; // 30 seconds timeout
    
    // Separate traces with and without usage
    const tracesWithUsage: TraceData[] = [];
    const tracesWithoutUsage: TraceData[] = [];
    
    traces.forEach(trace => {
        if (trace.usage && (trace.usage.completion_tokens || trace.usage.prompt_tokens || trace.usage.total_tokens)) {
            tracesWithUsage.push(trace);
        } else {
            tracesWithoutUsage.push(trace);
        }
    });
    
    console.log(`📦 Processing ${traces.length} traces: ${tracesWithoutUsage.length} without usage, ${tracesWithUsage.length} with usage`);
    
    // Process traces without usage (standard traces)
    if (tracesWithoutUsage.length > 0) {
        await uploadTraces(apiKey, tracesWithoutUsage, BATCH_SIZE, TIMEOUT);
    }
    
    // Process traces with usage (traces + LLM spans)
    if (tracesWithUsage.length > 0) {
        await uploadTracesWithSpans(apiKey, tracesWithUsage, BATCH_SIZE, TIMEOUT);
    }
    
    console.log(`🎉 All ${traces.length} traces processed successfully!`);
}

async function uploadTraces(apiKey: string, traces: TraceData[], batchSize: number, timeout: number): Promise<void> {
    // Generate trace IDs and convert to OpikTrace format
    const opikTraces: OpikTrace[] = traces.map(trace => {
        const { usage, ...traceWithoutUsage } = trace;
        traceWithoutUsage.metadata = {
            ...(traceWithoutUsage.metadata || {}),
            created_from: "cursor-extension"
        };
        return {
            ...traceWithoutUsage,
            id: generateTraceId(trace.start_time)
        };
    });
    
    const batches = createBatches(opikTraces, batchSize);
    console.log(`📤 Uploading ${opikTraces.length} standard traces in ${batches.length} batches`);
    
    for (let i = 0; i < batches.length; i++) {
        await uploadTraceBatch(apiKey, batches[i], i + 1, batches.length, timeout);
    }
}

async function uploadTracesWithSpans(apiKey: string, traces: TraceData[], batchSize: number, timeout: number): Promise<void> {
    // Generate traces and corresponding LLM spans
    const opikTraces: OpikTrace[] = [];
    const opikSpans: OpikSpan[] = [];
    
    traces.forEach(trace => {
        const traceId = generateTraceId(trace.start_time);
        
        // Create trace without usage
        const { usage, ...traceWithoutUsage } = trace;
        traceWithoutUsage.metadata = {
            ...(traceWithoutUsage.metadata || {}),
            created_from: "cursor-extension"
        };
        opikTraces.push({
            ...traceWithoutUsage,
            id: traceId
        });
        
        // Create LLM span with usage
        opikSpans.push({
            id: generateSpanId(trace.start_time),
            trace_id: traceId,
            name: trace.name,
            type: 'llm',
            project_name: trace.project_name,
            start_time: trace.start_time,
            end_time: trace.end_time,
            input: trace.input,
            output: trace.output,
            usage: trace.usage,
            tags: trace.tags,
            metadata: trace.metadata
        });
    });
    
    console.log(`📤 Uploading ${opikTraces.length} traces with ${opikSpans.length} LLM spans`);
    
    // Upload traces first
    const traceBatches = createBatches(opikTraces, batchSize);
    for (let i = 0; i < traceBatches.length; i++) {
        await uploadTraceBatch(apiKey, traceBatches[i], i + 1, traceBatches.length, timeout);
    }
    
    // Then upload spans
    const spanBatches = createBatches(opikSpans, batchSize);
    for (let i = 0; i < spanBatches.length; i++) {
        await uploadSpanBatch(apiKey, spanBatches[i], i + 1, spanBatches.length, timeout);
    }
}

async function uploadTraceBatch(apiKey: string, batch: OpikTrace[], batchNum: number, totalBatches: number, timeout: number): Promise<void> {
    console.log(`📤 Sending trace batch ${batchNum}/${totalBatches} (${batch.length} traces)`);
    
    try {
        await axios.post(
            "https://www.comet.com/opik/api/v1/private/traces/batch",
            { traces: batch },
            {
                headers: {
                    'Content-Type': 'application/json',
                    'authorization': `${apiKey}`,
                    'Comet-Workspace': 'jacques-comet'
                },
                timeout
            }
        );
        console.log(`✅ Trace batch ${batchNum} sent successfully`);
    } catch (error) {
        Sentry.captureException(error);
        console.error(`Error sending trace batch ${batchNum}:`, error);
        throw error;
    }
}

async function uploadSpanBatch(apiKey: string, batch: OpikSpan[], batchNum: number, totalBatches: number, timeout: number): Promise<void> {
    console.log(`📤 Sending span batch ${batchNum}/${totalBatches} (${batch.length} spans)`);
    
    try {
        await axios.post(
            "https://www.comet.com/opik/api/v1/private/spans/batch",
            { spans: batch },
            {
                headers: {
                    'Content-Type': 'application/json',
                    'authorization': `${apiKey}`,
                    'Comet-Workspace': 'jacques-comet'
                },
                timeout
            }
        );
        console.log(`✅ Span batch ${batchNum} sent successfully`);
    } catch (error) {
        Sentry.captureException(error);
        console.error(`Error sending span batch ${batchNum}:`, error);
        throw error;
    }
}

function generateTraceId(startTime: string): string {
    // Generate UUID v7 based on the start time timestamp
    const timestamp = new Date(startTime).getTime();
    return uuidv7({ msecs: timestamp });
}

function generateSpanId(startTime: string): string {
    // Generate UUID v7 with slight offset to ensure uniqueness from trace ID
    const timestamp = new Date(startTime).getTime() + 1;
    return uuidv7({ msecs: timestamp });
}

function createBatches<T>(items: T[], batchSize: number): T[][] {
    const batches: T[][] = [];
    for (let i = 0; i < items.length; i += batchSize) {
        batches.push(items.slice(i, i + batchSize));
    }
    return batches;
}
