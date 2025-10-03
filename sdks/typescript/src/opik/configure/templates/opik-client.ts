/**
 * Opik client configuration for your TypeScript/Node.js application.
 *
 * This file initializes the Opik client with your configuration.
 * Import and use this client throughout your application for tracing.
 *
 * For more information, visit:
 * https://www.comet.com/docs/opik/reference/typescript-sdk/overview
 */

import { Opik } from 'opik';

// Initialize Opik client
export const client = new Opik({
  // Configuration is automatically loaded from:
  // 1. Environment variables (OPIK_API_KEY, OPIK_WORKSPACE_NAME, etc.)
  // 2. ~/.opik.config file
  // 3. Constructor options (see below for examples)
});

// Re-export Opik for convenience
export { Opik };

// Basic usage examples:
//
// 1. Manual tracing:
// const trace = client.trace({
//   name: "my_llm_call",
//   input: { prompt: "What is LLM tracing?" },
//   output: { response: "LLM tracing is..." },
// });
//
// 2. Using the @track decorator (recommended):
// import { track } from "opik/decorators";
//
// class MyService {
//   @track()
//   async myLlmFunction(prompt: string): Promise<string> {
//     // Your LLM logic here
//     const response = await callLlm(prompt);
//     return response;
//   }
// }
//
// 3. Creating spans for nested operations:
// const span = trace.span({
//   name: "data_processing",
//   input: { data: inputData },
// });
// // Your processing logic here
// span.end({ output: { result: processedData } });
//
// 4. For short-lived scripts, flush before exit:
// async function main() {
//   const trace = client.trace({ name: "script_run" });
//   // ... your logic
//   await client.flush(); // Ensure all data is sent
// }
//
// 5. Integration with OpenAI:
// import { trackOpenAI } from "opik/integrations/opik-openai";
// import OpenAI from "openai";
//
// const openai = new OpenAI({ apiKey: process.env.OPENAI_API_KEY });
// const trackedOpenAI = trackOpenAI(openai, { opik: client });
//
// 6. Integration with LangChain:
// import { OpikTracer } from "opik/integrations/opik-langchain";
//
// const tracer = new OpikTracer({ opik: client });
// // Pass tracer to your LangChain components
