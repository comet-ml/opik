/**
 * Evaluate Prompt with Metrics Example
 *
 * This example demonstrates a comprehensive prompt engineering workflow where you:
 * 1. Create or retrieve a dataset for testing
 * 2. Use the Prompt management system to create and version prompts
 * 3. Evaluate multiple prompt versions using the evaluatePrompt function
 * 4. Apply multiple metrics (Usefulness and Moderation) to assess quality
 * 5. Compare different prompt versions to identify the best performing one
 *
 * This is particularly valuable for:
 * - A/B testing different prompt approaches
 * - Iteratively improving prompt performance
 * - Tracking prompt version history and performance changes
 * - Making data-driven decisions about which prompts to deploy
 * - Understanding how prompt changes affect different types of inputs
 *
 * The example shows creating an initial prompt, evaluating it, then creating an
 * improved version and comparing the results to demonstrate measurable improvements.
 */
import { Opik } from "../src/opik";
import { evaluatePrompt } from "../src/opik/evaluation/evaluatePrompt";
import { Usefulness, Moderation } from "../src/opik/evaluation/metrics";

// Initialize the Opik client
const client = new Opik({
  apiKey: "your-api-key", // Replace with your API key or set OPIK_API_KEY env var
});

// Define the dataset item type for customer support
type CustomerSupportDataset = {
  customer_query: string;
  customer_context?: string;
  expected_tone: string;
  category: string;
};

async function main() {
  // Step 1: Create or get a dataset for customer support evaluations
  const dataset = await client.getOrCreateDataset<CustomerSupportDataset>(
    "customer-support-qa",
    "Dataset for evaluating customer support responses"
  );

  // Add sample items to the dataset
  await dataset.insert([
    {
      customer_query:
        "My order hasn't arrived yet. It's been 2 weeks since I placed it.",
      customer_context: "Order #12345, Premium customer",
      expected_tone: "empathetic and solution-focused",
      category: "shipping",
    },
    {
      customer_query: "How do I reset my password?",
      customer_context: "Free tier user",
      expected_tone: "clear and instructional",
      category: "technical",
    },
    {
      customer_query:
        "I'm very disappointed with the product quality. I want a refund!",
      customer_context: "Order #67890, First-time customer",
      expected_tone: "apologetic and accommodating",
      category: "returns",
    },
  ]);

  console.log(`Dataset ready with items`);

  // Step 2: Create a prompt using the Prompt management system
  const prompt = await client.createPrompt({
    name: "customer-support-assistant",
    prompt: `You are a helpful customer support assistant. 

Customer Query: {{customer_query}}

{{#customer_context}}
Customer Context: {{customer_context}}
{{/customer_context}}

Please provide a professional and helpful response that addresses the customer's concern. 
Your response should be {{expected_tone}}.`,
    description: "Prompt for generating customer support responses",
    tags: ["customer-support", "production"],
    type: "mustache", // Using Mustache template engine
  });

  console.log(
    `Created prompt: ${prompt.name} (version: ${prompt.commit?.slice(0, 8)})`
  );

  // Step 3: Set up evaluation metrics
  // Usefulness metric - evaluates how helpful and relevant the response is
  const usefulnessMetric = new Usefulness({
    model: "gpt-4o", // Default model, can be customized
    temperature: 0.3, // Lower temperature for more consistent evaluations
    seed: 42, // For reproducible results
  });

  // Moderation metric - checks for content safety and policy violations
  const moderationMetric = new Moderation({
    model: "gpt-4o",
    temperature: 0.2, // Even lower for stricter moderation
    seed: 42,
  });

  // Step 4: Evaluate the prompt against the dataset
  console.log("\nStarting evaluation...");

  const result = await evaluatePrompt({
    dataset,
    messages: [
      {
        role: "user",
        content: prompt.prompt, // Using the prompt template from Prompt management
      },
    ],
    model: "gpt-4o", // Model used for generating responses
    scoringMetrics: [usefulnessMetric, moderationMetric],
    experimentName: "customer-support-v1-evaluation",
    projectName: "customer-support-assistant",
    prompts: [prompt], // Link the prompt to the experiment for tracking
    experimentConfig: {
      prompt_version: prompt.commit,
      evaluation_date: new Date().toISOString(),
      model_temperature: 0.7,
    },
  });

  // Step 5: Display evaluation results
  console.log("\n=== Evaluation Results ===");
  console.log(`Experiment Name: ${result.experimentName}`);
  console.log(`Total Items Evaluated: ${result.totalItems}`);
  console.log(`\nAggregate Scores:`);

  // Display metric scores
  for (const [metricName, score] of Object.entries(result.aggregateScores)) {
    console.log(`  ${metricName}: ${score.toFixed(3)}`);
  }

  // Optional: Retrieve and examine individual results
  console.log("\n=== Sample Individual Results ===");
  const items = await dataset.getItems(1);
  if (items.length > 0) {
    console.log(`Query: "${items[0].customer_query}"`);
    console.log(`Expected tone: ${items[0].expected_tone}`);
    console.log("(Check Opik UI for detailed scores and generated responses)");
  }

  // Step 6: Advanced usage - Create a new version of the prompt
  console.log("\n=== Creating Improved Prompt Version ===");

  const improvedPrompt = await client.createPrompt({
    name: "customer-support-assistant", // Same name = new version
    prompt: `You are a helpful and empathetic customer support assistant with expertise in problem-solving.

Customer Query: {{customer_query}}

{{#customer_context}}
Customer Context: {{customer_context}}
{{/customer_context}}

Please provide a professional, helpful, and {{expected_tone}} response that:
1. Acknowledges the customer's concern
2. Provides a clear solution or next steps
3. Maintains a friendly and professional tone
4. Offers additional help if needed`,
    changeDescription: "Added structure and empathy focus",
    tags: ["customer-support", "production", "v2"],
  });

  console.log(
    `Created improved prompt version: ${improvedPrompt.commit?.slice(0, 8)}`
  );

  // Step 7: Re-evaluate with the improved prompt
  const improvedResult = await evaluatePrompt({
    dataset,
    messages: [
      {
        role: "user",
        content: improvedPrompt.prompt,
      },
    ],
    model: "gpt-4o",
    scoringMetrics: [usefulnessMetric, moderationMetric],
    experimentName: "customer-support-v2-evaluation",
    projectName: "customer-support-assistant",
    prompts: [improvedPrompt],
    experimentConfig: {
      prompt_version: improvedPrompt.commit,
      evaluation_date: new Date().toISOString(),
      previous_version: prompt.commit,
    },
  });

  console.log("\n=== Improved Version Results ===");
  console.log(`Total Items Evaluated: ${improvedResult.totalItems}`);
  console.log(`\nAggregate Scores:`);

  for (const [metricName, score] of Object.entries(
    improvedResult.aggregateScores
  )) {
    const previousScore = result.aggregateScores[metricName];
    const improvement = ((score - previousScore) / previousScore) * 100;
    console.log(
      `  ${metricName}: ${score.toFixed(3)} (${improvement > 0 ? "+" : ""}${improvement.toFixed(1)}%)`
    );
  }

  // Step 8: Retrieve prompt by name and version
  console.log("\n=== Retrieving Prompts ===");

  // Get latest version
  const latestPrompt = await client.getPrompt({
    name: "customer-support-assistant",
  });
  console.log(`Latest version commit: ${latestPrompt?.commit?.slice(0, 8)}`);

  // Get specific version
  const originalPrompt = await client.getPrompt({
    name: "customer-support-assistant",
    commit: prompt.commit,
  });
  console.log(
    `Original version commit: ${originalPrompt?.commit?.slice(0, 8)}`
  );

  // Step 9: Format a prompt manually (without evaluation)
  if (latestPrompt) {
    const formatted = latestPrompt.format({
      customer_query: "How do I track my order?",
      customer_context: "Order #11111",
      expected_tone: "clear and helpful",
    });
    console.log("\n=== Manually Formatted Prompt ===");
    console.log(formatted);
  }

  console.log(
    "\n✅ Evaluation complete! Check the Opik UI for detailed results."
  );
}

// Run the example
main().catch((error) => {
  console.error("Error running evaluation:", error);
  process.exit(1);
});
