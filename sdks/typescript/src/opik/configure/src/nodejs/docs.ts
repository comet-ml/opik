import { OPIK_ENV_VARS } from '../lib/env-constants';

export const getNodejsDocumentation = ({
  language,
}: {
  language: 'typescript' | 'javascript';
}) => {
  const apiKeyText = `process.env.${OPIK_ENV_VARS.API_KEY}`;
  const hostText = `process.env.${OPIK_ENV_VARS.URL_OVERRIDE}`;
  const ext = language === 'typescript' ? 'ts' : 'js';

  return `
╔════════════════════════════════════════════════════════════════════════════╗
║                  Opik TypeScript SDK Integration Guide                     ║
║                     Production-Ready LLM Observability                      ║
╚════════════════════════════════════════════════════════════════════════════╝

This guide provides comprehensive integration patterns for the Opik TypeScript SDK,
covering tracing, monitoring, and debugging LLM applications in production.

┌────────────────────────────────────────────────────────────────────────────┐
│ ARCHITECTURE OVERVIEW                                                       │
└────────────────────────────────────────────────────────────────────────────┘

Opik uses a non-blocking, batched write architecture:
  • Trace/span creation returns immediately (non-blocking)
  • Data is buffered and sent asynchronously via batch queues
  • Updates await pending creates; deletes await both creates and updates
  • Configurable batching behavior via OpikConfig

⚠️  CRITICAL: For short-lived processes (scripts, serverless, CLI tools):
    Always call \`await client.flush()\` or \`await flushAll()\` before exit.

┌────────────────────────────────────────────────────────────────────────────┐
│ CONFIGURATION & INITIALIZATION                                              │
└────────────────────────────────────────────────────────────────────────────┘

Configuration precedence: Explicit options → Env vars → ~/.opik.config → Defaults

Environment Variables:
--------------------------------------------------
# Required
export ${OPIK_ENV_VARS.API_KEY}="your-api-key"

# Required: Choose Cloud or Local deployment
export ${
    OPIK_ENV_VARS.URL_OVERRIDE
  }="https://www.comet.com/opik/api"  # For Opik Cloud
# export ${
    OPIK_ENV_VARS.URL_OVERRIDE
  }="http://localhost:5173/api"    # For Local deployment

# Required: Your workspace name
export ${OPIK_ENV_VARS.WORKSPACE}="your-workspace-name"

# Optional: Defaults to "Default" if not specified
export ${OPIK_ENV_VARS.PROJECT_NAME}="your-project-name"
--------------------------------------------------

Programmatic Configuration:
--------------------------------------------------
import { Opik } from 'opik';

const opik = new Opik({
  apiKey: ${apiKeyText},                    // Required
  apiUrl: ${hostText},                      // Required (Cloud or Local URL)
  workspaceName: 'my-workspace',                   // Required
  projectName: 'my-project',                       // Optional (defaults to "Default")
  batchDelayMs: 100,                               // Optional: Default 100ms
  holdUntilFlush: false,                           // Optional: Default false
});
--------------------------------------------------

┌────────────────────────────────────────────────────────────────────────────┐
│ INTEGRATION PATTERN 1: track() Decorator (Recommended)                     │
└────────────────────────────────────────────────────────────────────────────┘

Best for: Automatic instrumentation with minimal code changes
Use when: You want seamless tracing without manual span management

FILE: src/services/llm-service.${ext}
--------------------------------------------------
import { Opik, track, getTrackContext } from 'opik';${
    language === 'typescript'
      ? "\nimport type { TrackOptions } from 'opik/decorators/track';"
      : ''
  }

// Initialize once, reuse across your application
const opik = new Opik({
  apiKey: ${apiKeyText},
  apiUrl: ${hostText},
  projectName: 'my-llm-app',
});

// Nested functions automatically create child spans
@track()
async function retrieveContext(query${
    language === 'typescript' ? ': string' : ''
  })${language === 'typescript' ? ': Promise<string>' : ''} {
  // This creates a child span under the parent trace
  const docs = await vectorDB.search(query);
  return docs.join('\\n');
}

@track({ type: 'llm' })
async function generateResponse(prompt${
    language === 'typescript' ? ': string' : ''
  }, context${language === 'typescript' ? ': string' : ''})${
    language === 'typescript' ? ': Promise<string>' : ''
  } {
  const response = await openai.chat.completions.create({
    model: 'gpt-4',
    messages: [
      { role: 'system', content: context },
      { role: 'user', content: prompt }
    ],
  });
  return response.choices[0].message.content;
}

@track({ 
  captureInput: true,
  captureOutput: true,
  metadata: { version: '1.0' }
})
async function processUserQuery(query${
    language === 'typescript' ? ': string' : ''
  })${language === 'typescript' ? ': Promise<string>' : ''} {
  // Top-level function creates the trace
  // Nested calls automatically create child spans
  const context = await retrieveContext(query);
  const response = await generateResponse(query, context);
  
  // Access current trace context for metadata
  const trackContext = getTrackContext();
  if (trackContext?.trace) {
    trackContext.trace.update({ 
      metadata: { success: true, queryLength: query.length } 
    });
  }
  
  return response;
}

// Usage in your application
async function main() {
  try {
    const result = await processUserQuery('What is machine learning?');
    console.log('Response:', result);
  } catch (error) {
    console.error('Error:', error);
  } finally {
    // CRITICAL: Flush before exit
    await opik.flush();
  }
}

main().catch(console.error);
--------------------------------------------------

Advanced: Custom track options
--------------------------------------------------
@track({
  name: 'custom-span-name',           // Override default function name
  type: 'llm',                         // Span type: 'llm', 'tool', 'retriever', etc.
  projectName: 'specific-project',     // Override default project
  captureInput: true,                  // Capture function arguments (default: true)
  captureOutput: true,                 // Capture return value (default: true)
  metadata: { version: '2.0' },        // Static metadata
  tags: ['production', 'critical'],    // Tags for filtering
})
async function advancedFunction(input${
    language === 'typescript' ? ': any' : ''
  }) {
  // Your logic here
}
--------------------------------------------------

┌────────────────────────────────────────────────────────────────────────────┐
│ INTEGRATION PATTERN 2: Manual Trace & Span Management                      │
└────────────────────────────────────────────────────────────────────────────┘

Best for: Fine-grained control over tracing lifecycle
Use when: You need explicit control over timing, metadata, or error handling

FILE: src/services/manual-tracing.${ext}
--------------------------------------------------
import { Opik } from 'opik';${
    language === 'typescript'
      ? "\nimport type { Trace, Span } from 'opik';"
      : ''
  }

const opik = new Opik({
  apiKey: ${apiKeyText},
  apiUrl: ${hostText},
});

async function processComplexWorkflow(input${
    language === 'typescript' ? ': string' : ''
  })${language === 'typescript' ? ': Promise<string>' : ''} {
  // Create a trace for the entire workflow
  const trace = opik.trace({
    name: 'complex_workflow',
    input: { query: input },
    metadata: { 
      userId: 'user-123',
      environment: 'production',
      version: '1.0'
    },
    tags: ['workflow', 'llm'],
  });

  try {
    // Step 1: Context retrieval span
    const retrievalSpan = trace.span({
      name: 'context_retrieval',
      type: 'retriever',
      input: { query: input },
      metadata: { method: 'vector_search' },
    });

    const context = await retrieveContext(input);
    
    retrievalSpan.update({ 
      output: { context },
      metadata: { documentsFound: context.length }
    });
    retrievalSpan.end();

    // Step 2: LLM generation span
    const llmSpan = trace.span({
      name: 'llm_generation',
      type: 'llm',
      input: { prompt: input, context },
      metadata: { 
        model: 'gpt-4',
        temperature: 0.7,
        maxTokens: 1000
      },
    });

    const startTime = Date.now();
    const response = await generateResponse(input, context);
    const duration = Date.now() - startTime;

    llmSpan.update({
      output: { response },
      metadata: { 
        durationMs: duration,
        tokensUsed: response.usage?.totalTokens || 0
      },
    });
    llmSpan.end();

    // Step 3: Post-processing span
    const processingSpan = trace.span({
      name: 'post_processing',
      type: 'tool',
      input: { rawResponse: response },
    });

    const processedResult = await postProcess(response);
    
    processingSpan.update({ output: { result: processedResult } });
    processingSpan.end();

    // Update trace with final result
    trace.update({
      output: { result: processedResult },
      metadata: { 
        success: true,
        totalDurationMs: Date.now() - trace.startTime
      },
    });
    trace.end();

    return processedResult;

  } catch (error${language === 'typescript' ? ': any' : ''}) {
    // Record error in trace
    trace.update({
      metadata: { 
        error: error.message,
        errorType: error.constructor.name,
        success: false
      },
    });
    trace.end();
    throw error;
  }
}

// Usage with proper error handling and flushing
async function main() {
  try {
    const result = await processComplexWorkflow('What is quantum computing?');
    console.log('Result:', result);
  } catch (error) {
    console.error('Workflow failed:', error);
  } finally {
    // CRITICAL: Flush before exit
    await opik.flush();
  }
}

main().catch(console.error);
--------------------------------------------------

Advanced: Nested Spans and Parallel Operations
--------------------------------------------------
async function parallelProcessing(queries${
    language === 'typescript' ? ': string[]' : ''
  }) {
  const trace = opik.trace({
    name: 'parallel_batch_processing',
    input: { queries },
    metadata: { batchSize: queries.length },
  });

  try {
    // Create spans for parallel operations
    const results = await Promise.all(
      queries.map(async (query, index) => {
        const span = trace.span({
          name: \`process_query_\${index}\`,
          input: { query, index },
        });

        try {
          const result = await processQuery(query);
          span.update({ output: { result } });
          span.end();
          return result;
        } catch (error${language === 'typescript' ? ': any' : ''}) {
          span.update({ 
            metadata: { error: error.message } 
          });
          span.end();
          throw error;
        }
      })
    );

    trace.update({ 
      output: { results },
      metadata: { successCount: results.length }
    });
    trace.end();

    return results;
  } catch (error${language === 'typescript' ? ': any' : ''}) {
    trace.update({ 
      metadata: { error: 'Batch processing failed' } 
    });
    trace.end();
    throw error;
  }
}
--------------------------------------------------

┌────────────────────────────────────────────────────────────────────────────┐
│ LLM PROVIDER INTEGRATIONS                                                   │
└────────────────────────────────────────────────────────────────────────────┘

Opik provides ready-to-use integrations for popular LLM providers:

  • OpenAI (JS/TS)         - trackOpenAI(client, { opik })
  • LangChain (JS/TS)      - new OpikTracer({ opik })
  • Vercel AI SDK          - new OpikVercelExporter({ opik })
  • Cloudflare Workers AI  - Manual tracing patterns

For detailed integration examples and usage, visit:
https://www.comet.com/docs/opik/tracing/integrations_overview

┌────────────────────────────────────────────────────────────────────────────┐
│ ADVANCED CONFIGURATION                                                      │
└────────────────────────────────────────────────────────────────────────────┘

OpikConfig Options (comprehensive):
--------------------------------------------------
${
  language === 'typescript'
    ? `interface OpikConfig {
  // Required
  apiKey: string;                    // Your Opik API key
  apiUrl: string;                    // Server URL (Cloud or Local)
  workspaceName: string;             // Workspace identifier
  
  // Optional
  projectName?: string;              // Default project for traces (defaults to "Default")
  
  // Batching Configuration (Optional)
  batchDelayMs?: number;             // Debounce delay (default: 100ms)
  holdUntilFlush?: boolean;          // Wait for explicit flush (default: false)
  batchSize?: number;                // Max items per batch (default: 100)
  
  // HTTP Configuration (Optional)
  timeout?: number;                  // Request timeout in ms (default: 30000)
  retries?: number;                  // Max retry attempts (default: 3)
  
  // Logging (Optional)
  logLevel?: 'silent' | 'error' | 'warn' | 'info' | 'debug';
}`
    : `const opik = new Opik({
  // Required
  apiKey: string,                    // Your Opik API key
  apiUrl: string,                    // Server URL (Cloud or Local)
  workspaceName: string,             // Workspace identifier
  
  // Optional
  projectName: string,               // Default project for traces (defaults to "Default")
  
  // Batching Configuration (Optional)
  batchDelayMs: number,              // Debounce delay (default: 100ms)
  holdUntilFlush: boolean,           // Wait for explicit flush (default: false)
  batchSize: number,                 // Max items per batch (default: 100)
  
  // HTTP Configuration (Optional)
  timeout: number,                   // Request timeout in ms (default: 30000)
  retries: number,                   // Max retry attempts (default: 3)
  
  // Logging (Optional)
  logLevel: 'silent' | 'error' | 'warn' | 'info' | 'debug',
});`
}

Example with all options:
--------------------------------------------------
const opik = new Opik({
  // Required
  apiKey: ${apiKeyText},
  apiUrl: ${hostText},               // Cloud or Local URL
  workspaceName: 'my-workspace',
  
  // Optional
  projectName: 'production-llm-app', // Defaults to "Default"
  
  // Performance Tuning (Optional)
  batchDelayMs: 200,                 // Longer delay = larger batches
  batchSize: 150,                    // More items per batch
  holdUntilFlush: false,             // Auto-flush enabled
  
  // Network (Optional)
  timeout: 60000,                    // 60 second timeout
  retries: 5,                        // Retry up to 5 times
  
  // Debugging (Optional)
  logLevel: 'info',                  // Log info and above
});
--------------------------------------------------

┌────────────────────────────────────────────────────────────────────────────┐
│ PRODUCTION BEST PRACTICES                                                   │
└────────────────────────────────────────────────────────────────────────────┘

1. BATCHING ARCHITECTURE
   • Opik uses non-blocking, batched writes for optimal performance
   • Trace/span creation returns immediately (synchronous interface)
   • Data is buffered in memory and sent asynchronously via batch queues
   • Updates wait for pending creates; deletes wait for both

   Performance Impact:
   - Single trace:     ~0.1ms (non-blocking)
   - Batch of 100:     ~200ms (one HTTP request)
   - Traditional sync: ~100ms per trace × 100 = 10s

2. FLUSHING REQUIREMENTS
   For short-lived processes, ALWAYS flush before exit:

   • Scripts & CLI:        await client.flush();
   • Serverless Functions: await client.flush(); (in finally block)
   • Express Endpoints:    await client.flush(); (if process exits soon)
   • Long-running Apps:    Automatic flushing works fine

   Multiple Clients:
   --------------------------------------------------
   import { flushAll } from 'opik';
   
   // At application shutdown
   process.on('SIGTERM', async () => {
     await flushAll();  // Flushes ALL Opik clients
     process.exit(0);
   });
   --------------------------------------------------

3. ERROR HANDLING STRATEGY
   Opik is designed to never crash your application:

   • Network failures are logged, not thrown
   • Failed batches are retried with exponential backoff
   • Circuit breaker prevents cascade failures
   • Errors are tracked internally for monitoring

   Manual Error Handling:
   --------------------------------------------------
   try {
     const trace = opik.trace({ name: 'my-operation' });
     // Your logic
     trace.end();
   } catch (error) {
     // Your business logic errors
     // Opik tracing errors won't reach here
   } finally {
     await opik.flush();
   }
   --------------------------------------------------

4. PERFORMANCE OPTIMIZATION
   
   Batching Configuration:
   - High throughput:   batchSize=200, batchDelayMs=50
   - Low latency:       batchSize=50,  batchDelayMs=10
   - Balanced:          batchSize=100, batchDelayMs=100 (default)
   
   Memory Management:
   - Each trace:        ~1-5KB in memory (before flush)
   - 1000 traces:       ~1-5MB buffered
   - Automatic flush:   When buffer exceeds batchSize
   
   Recommended Limits:
   - Max traces/sec:    10,000+ (with batching)
   - Max spans/trace:   1,000 (practical limit)
   - Max metadata size: 100KB per trace

5. CONCURRENCY & THREAD SAFETY
   
   • OpikClient is thread-safe and can be shared
   • Create ONE client per application (singleton pattern)
   • Traces/spans are independent and isolated
   • No locking or blocking in the public API
   
   Multi-threaded Usage:
   --------------------------------------------------
   // Create once, use everywhere
   export const opik = new Opik({ /* config */ });
   
   // Safe to use from multiple threads/requests
   async function handler1() {
     const trace1 = opik.trace({ name: 'operation1' });
     // ...
   }
   
   async function handler2() {
     const trace2 = opik.trace({ name: 'operation2' });
     // ...
   }
   --------------------------------------------------

6. DEBUGGING & MONITORING
   
   Enable Debug Logging:
   --------------------------------------------------
   const opik = new Opik({
     apiKey: ${apiKeyText},
     logLevel: 'debug',  // 'silent' | 'error' | 'warn' | 'info' | 'debug'
   });
   --------------------------------------------------
   
   Monitor Batch Queue Status:
   --------------------------------------------------
   import { logger, setLoggerLevel } from 'opik';
   
   // Programmatically control logging
   setLoggerLevel('debug');
   
   // Logs will show:
   // - Batch queue sizes
   // - HTTP request/response details
   // - Retry attempts
   // - Flush operations
   --------------------------------------------------

┌────────────────────────────────────────────────────────────────────────────┐
│ DEPLOYMENT CONSIDERATIONS                                                   │
└────────────────────────────────────────────────────────────────────────────┘

Serverless (AWS Lambda, Vercel, Netlify):
--------------------------------------------------
export async function handler(event${
    language === 'typescript' ? ': any' : ''
  }) {
  const opik = new Opik({
    apiKey: process.env.${OPIK_ENV_VARS.API_KEY},
    holdUntilFlush: true,  // Prevent auto-flush
  });

  try {
    // Your logic with tracing
    const trace = opik.trace({ /* ... */ });
    // ...
    trace.end();
    
    return { statusCode: 200, body: 'Success' };
  } finally {
    // CRITICAL: Flush before function exits
    await opik.flush();
  }
}
--------------------------------------------------

Docker/Kubernetes:
--------------------------------------------------
// Graceful shutdown
process.on('SIGTERM', async () => {
  console.log('SIGTERM received, flushing telemetry...');
  await opik.flush();
  process.exit(0);
});

process.on('SIGINT', async () => {
  console.log('SIGINT received, flushing telemetry...');
  await opik.flush();
  process.exit(0);
});
--------------------------------------------------

CI/CD & Testing:
--------------------------------------------------
// Disable tracing in tests
const opik = new Opik({
  apiKey: process.env.CI ? 'mock-key' : process.env.${OPIK_ENV_VARS.API_KEY},
  logLevel: process.env.CI ? 'silent' : 'info',
});
--------------------------------------------------

═══════════════════════════════════════════════════════════════════════════════
QUICK REFERENCE
═══════════════════════════════════════════════════════════════════════════════

Installation:
  npm install opik

Configuration:
  export ${
    OPIK_ENV_VARS.API_KEY
  }="your-api-key"                        # Required
  export ${
    OPIK_ENV_VARS.URL_OVERRIDE
  }="https://www.comet.com/opik/api" # Required (Cloud or Local)
  export ${
    OPIK_ENV_VARS.WORKSPACE
  }="your-workspace-name"                # Required
  export ${
    OPIK_ENV_VARS.PROJECT_NAME
  }="your-project-name"               # Optional (defaults to "Default")

Basic Usage:
  const opik = new Opik();
  const trace = opik.trace({ name: 'operation', input: {} });
  trace.span({ name: 'step', type: 'llm' }).end();
  trace.end();
  await opik.flush();

Tracing Patterns:
  • Decorator:    @track() on functions
  • Manual:       opik.trace() and trace.span()

Documentation:
  https://www.comet.com/docs/opik/reference/typescript-sdk/overview

Support:
  GitHub: https://github.com/comet-ml/opik
  Slack:  https://www.comet.com/site/slack/

═══════════════════════════════════════════════════════════════════════════════
`;
};
