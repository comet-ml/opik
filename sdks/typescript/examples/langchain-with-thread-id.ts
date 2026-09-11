import { OpenAI } from "@langchain/openai";
import { PromptTemplate } from "@langchain/core/prompts";
import { OpikCallbackHandler } from "opik-langchain";

// Example demonstrating thread_id support in OpikCallbackHandler

async function main() {
  // Initialize the model
  const model = new OpenAI({
    temperature: 0.9,
  });

  const prompt = PromptTemplate.fromTemplate(
    "What is a good name for a company that makes {product}?"
  );

  const chain = prompt.pipe(model);

  // Flow 1: Log a thread with 2 traces (same thread_id)
  console.log("\n=== Flow 1: Thread with 2 traces ===");
  
  const threadOpikHandler = new OpikCallbackHandler({
    threadId: "conversation-thread-123",
    tags: ["langchain", "thread-demo"],
    metadata: { "use-case": "multi-trace-thread" },
  });

  console.log("Running first trace in thread...");
  const result1 = await chain.invoke(
    { product: "colorful socks" },
    { callbacks: [threadOpikHandler] }
  );
  console.log("First trace result:", result1);

  // Wait a moment between traces
  await new Promise(resolve => setTimeout(resolve, 1000));

  console.log("Running second trace in same thread...");
  const result2 = await chain.invoke(
    { product: "eco-friendly water bottles" },
    { callbacks: [threadOpikHandler] }
  );
  console.log("Second trace result:", result2);

  // Flow 2: Log a trace without thread attribute
  console.log("\n=== Flow 2: Trace without thread ===");
  
  const noThreadOpikHandler = new OpikCallbackHandler({
    tags: ["langchain", "no-thread"],
    metadata: { "use-case": "standalone-trace" },
    // No threadId specified
  });

  console.log("Running trace without thread_id...");
  const result3 = await chain.invoke(
    { product: "smart home devices" },
    { callbacks: [noThreadOpikHandler] }
  );
  console.log("Standalone trace result:", result3);

  // Flush to ensure all data is sent
  console.log("\n=== Flushing data ===");
  await threadOpikHandler.flushAsync();
  await noThreadOpikHandler.flushAsync();
  
  console.log("✅ All traces logged successfully!");
  console.log("Check your Opik dashboard to see:");
  console.log("- Two traces with threadId 'conversation-thread-123'");
  console.log("- One standalone trace without a threadId");
}

main().catch(console.error);