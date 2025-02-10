import { openai } from "@ai-sdk/openai";
import { getNodeAutoInstrumentations } from "@opentelemetry/auto-instrumentations-node";
import { NodeSDK } from "@opentelemetry/sdk-node";
import { generateText } from "ai";
import { OpikExporter } from "opik/vercel";

const sdk = new NodeSDK({
  traceExporter: new OpikExporter(),
  instrumentations: [getNodeAutoInstrumentations()],
});

sdk.start();

const { text } = await generateText({
  model: openai("gpt-4o-mini"),
  prompt: "What is love? Describe it in 10 words or less.",
  experimental_telemetry: {
    isEnabled: true,
  },
});

console.log("Result:", text);

await sdk.shutdown();
