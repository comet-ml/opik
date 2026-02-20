import * as vscode from 'vscode';
import { TraceData } from './interface';
import { captureException } from './sentry';

export async function logTracesToOpik(apiKey: string, traces: TraceData[]): Promise<void> {
    if (traces.length === 0) return;
    
    console.log(`📦 Processing ${traces.length} traces using Opik SDK`);
    
    try {
        // Get configuration values
        const config = vscode.workspace.getConfiguration();
        const apiUrl = config.get<string>('opik.apiUrl', 'https://www.comet.com/opik/api');
        const workspace = config.get<string>('opik.workspace', 'default');

        // Dynamically import Opik SDK
        const { Opik } = await import('opik');

        // Initialize Opik client
        const client = new Opik({
            apiKey: apiKey,
            apiUrl: apiUrl,
            workspaceName: workspace
        });
        
        // Process traces using the SDK
        for (const traceData of traces) {
            // Add metadata to indicate source
            const metadata = {
                ...(traceData.metadata || {}),
                created_from: "cursor-extension"
            };
            
            // Create trace using SDK
            const trace = client.trace({
                name: traceData.name,
                projectName: traceData.project_name,
                input: traceData.input,
                output: traceData.output,
                startTime: new Date(traceData.start_time),
                endTime: traceData.end_time ? new Date(traceData.end_time) : undefined,
                tags: traceData.tags,
                metadata: metadata,
                threadId: traceData.thread_id
            });
            
            const startTime = new Date(traceData.start_time);
            const endTime = traceData.end_time ? new Date(traceData.end_time) : undefined;

            if (traceData.spans && traceData.spans.length > 0) {
                const parentSpan = trace.span({
                    name: traceData.name,
                    type: 'general',
                    input: traceData.input,
                    output: traceData.output,
                    startTime,
                    endTime,
                    model: traceData.model,
                    usage: traceData.usage ? {
                        completionTokens: traceData.usage.completion_tokens || 0,
                        promptTokens: traceData.usage.prompt_tokens || 0,
                        totalTokens: traceData.usage.total_tokens || 0
                    } : undefined,
                    tags: traceData.tags,
                    metadata: traceData.metadata,
                });

                for (const spanData of traceData.spans) {
                    const child = parentSpan.span({
                        name: spanData.name,
                        type: spanData.type,
                        input: spanData.input,
                        output: spanData.output,
                        startTime: spanData.start_time ? new Date(spanData.start_time) : startTime,
                        endTime: spanData.end_time ? new Date(spanData.end_time) : endTime,
                        model: spanData.model,
                        provider: spanData.provider,
                        usage: spanData.usage ? {
                            completionTokens: spanData.usage.completion_tokens || 0,
                            promptTokens: spanData.usage.prompt_tokens || 0,
                            totalTokens: spanData.usage.total_tokens || 0
                        } : undefined,
                        metadata: spanData.metadata,
                        tags: spanData.tags,
                    });
                    child.end();
                }
                parentSpan.end();
            } else if (traceData.usage && (traceData.usage.completion_tokens || traceData.usage.prompt_tokens || traceData.usage.total_tokens)) {
                const span = trace.span({
                    name: traceData.name,
                    type: 'llm',
                    input: traceData.input,
                    output: traceData.output,
                    startTime,
                    endTime,
                    model: traceData.model,
                    usage: {
                        completionTokens: traceData.usage.completion_tokens || 0,
                        promptTokens: traceData.usage.prompt_tokens || 0,
                        totalTokens: traceData.usage.total_tokens || 0
                    },
                    tags: traceData.tags,
                    metadata: traceData.metadata
                });
                span.end();
            }
            
            // End the trace
            trace.end();
        }
        
        // Flush all data to Opik
        console.log(`📤 Flushing ${traces.length} traces to Opik`);
        await client.flush();
        
        console.log(`🎉 All ${traces.length} traces processed successfully using Opik SDK!`);
        
    } catch (error) {
        captureException(error);
        console.error('Error processing traces with Opik SDK:', error);
        throw error;
    }
}

