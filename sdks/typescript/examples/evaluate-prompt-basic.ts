/**
 * Basic Evaluate Prompt Example
 *
 * A simple example showing the core steps to evaluate a prompt
 * using the Prompt management system with Usefulness and Moderation metrics.
 */
import { Opik } from "../src/opik";
import { evaluatePrompt } from "../src/opik/evaluation/evaluatePrompt";
import { Usefulness, Moderation } from "../src/opik/evaluation/metrics";

async function basicExample() {
  // Initialize client
  const client = new Opik();

  // Step 1: Get or create a dataset
  const dataset = await client.getOrCreateDataset("qa-dataset");

  // Add some sample data
  await dataset.insert([
    {
      question: "What is TypeScript?",
      context: "programming languages",
    },
    {
      question: "How do I install Node.js?",
      context: "development setup",
    },
  ]);

  // Step 2: Create a prompt
  const prompt = await client.createPrompt({
    name: "qa-assistant",
    prompt: "Answer this question: {{question}}\nContext: {{context}}",
    type: "mustache",
  });

  // Step 3: Create metrics
  const usefulnessMetric = new Usefulness();
  const moderationMetric = new Moderation();

  // Step 4: Run evaluation
  const result = await evaluatePrompt({
    dataset,
    messages: [{ role: "user", content: prompt.prompt }],
    model: "gpt-5-nano", // or omit to use default
    scoringMetrics: [usefulnessMetric, moderationMetric],
    experimentName: "qa-evaluation",
    prompts: [prompt], // Link prompt to experiment
  });

  // Step 5: View results
  console.log("Evaluation Results:");
  console.log(`Items evaluated: ${result.totalItems}`);
  console.log("Scores:");
  for (const [metric, score] of Object.entries(result.aggregateScores)) {
    console.log(`  ${metric}: ${score.toFixed(3)}`);
  }
}

basicExample().catch(console.error);
