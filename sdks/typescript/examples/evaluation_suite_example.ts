/**
 * Example: Running an EvaluationSuite with projectName.
 *
 * Creates a suite with assertions and execution policy,
 * adds test items, runs an evaluation task, and prints results.
 *
 * Run:
 *   cd sdks/typescript && npx tsx examples/evaluation_suite_example.ts
 *
 * Requires a running Opik server. Set OPIK_URL_OVERRIDE in .env or env vars.
 */
import { Opik, EvaluationSuite } from "opik";

async function main() {
  const timestamp = Date.now();
  const client = new Opik({ projectName: "my-project" });

  // --- Create a suite with assertions ---
  const suiteName = `example-suite-${timestamp}`;
  console.log(`Creating evaluation suite "${suiteName}"...`);

  const suite = await EvaluationSuite.create(client, {
    name: suiteName,
    assertions: ["Response answers the question"],
    executionPolicy: { runsPerItem: 1, passThreshold: 1 },
    projectName: "my-project",
  });

  // --- Add test items ---
  await suite.addItem({ input: "What is 2+2?", expected: "4" });
  await suite.addItem({ input: "Capital of France?", expected: "Paris" });
  await suite.addItem(
    { input: "Explain gravity briefly", expected: "A force of attraction" },
    { assertions: ["Response is concise"] }
  );
  console.log("Added 3 test items");

  // Wait for items to be available
  await new Promise((resolve) => setTimeout(resolve, 2000));

  const items = await suite.getItems();
  console.log(`Suite has ${items.length} items`);

  // --- Define the task to evaluate ---
  const task = async (item: Record<string, unknown>) => ({
    input: item.input,
    output: `Answer: ${item.expected}`,
  });

  // --- Run the evaluation ---
  console.log("\nRunning evaluation...");
  const result = await suite.run(task, {
    experimentName: `example-run-${timestamp}`,
    projectName: "my-project",
  });

  console.log(`\nResults:`);
  console.log(`  Experiment ID: ${result.experimentId}`);
  console.log(`  Items total: ${result.itemsTotal}`);
  console.log(`  Items passed: ${result.itemsPassed}`);
  console.log(`  All passed: ${result.allItemsPassed}`);
  console.log(`  Pass rate: ${result.passRate !== undefined ? (result.passRate * 100).toFixed(1) + "%" : "N/A"}`);

  // --- Cleanup ---
  // await client.deleteDataset(suiteName);
  await client.flush();
  console.log("\nDone!");
}

main().catch((error) => {
  console.error("Error:", error.message);
  process.exit(1);
});
