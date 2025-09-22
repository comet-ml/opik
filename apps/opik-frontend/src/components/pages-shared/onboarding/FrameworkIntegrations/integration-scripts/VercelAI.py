# Vercel AI SDK integration with Opik is a TypeScript/JavaScript integration
#
# For Node.js/TypeScript setup:
#
# 1. Install dependencies:
#    npm install opik ai @ai-sdk/openai @opentelemetry/sdk-node @opentelemetry/auto-instrumentations-node
#
# 2. Setup instrumentation:
#    import { OpikExporter } from "opik/vercel";
#    import { NodeSDK } from "@opentelemetry/sdk-node";
#
#    const sdk = new NodeSDK({
#      traceExporter: new OpikExporter(),
#      instrumentations: [getNodeAutoInstrumentations()],
#    });
#    sdk.start();
#
# 3. Use with generateText:
#    const result = await generateText({
#      model: openai("gpt-4o"),
#      prompt: "What is love?",
#      experimental_telemetry: OpikExporter.getSettings({
#        name: "opik-nodejs-example",
#      }),
#    });
#
# This integration requires TypeScript/JavaScript, not Python.
# Please refer to the Opik TypeScript SDK documentation.

print("Vercel AI SDK integration requires TypeScript/JavaScript, not Python.")
