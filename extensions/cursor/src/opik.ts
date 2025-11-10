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
            
            // Create LLM span with usage data if available
            if (traceData.usage && (traceData.usage.completion_tokens || traceData.usage.prompt_tokens || traceData.usage.total_tokens)) {
                const span = trace.span({
                    name: traceData.name,
                    type: 'llm',
                    input: traceData.input,
                    output: traceData.output,
                    startTime: new Date(traceData.start_time),
                    endTime: traceData.end_time ? new Date(traceData.end_time) : undefined,
                    usage: {
                        completionTokens: traceData.usage.completion_tokens || 0,
                        promptTokens: traceData.usage.prompt_tokens || 0,
                        totalTokens: traceData.usage.total_tokens || 0
                    },
                    tags: traceData.tags,
                    metadata: traceData.metadata
                });
                
                // End the span
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

