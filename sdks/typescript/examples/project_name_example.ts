/**
 * Example: Using projectName with datasets, experiments, and prompts.
 *
 * Demonstrates how to scope SDK entities to a specific project,
 * and how the default project works when projectName is not specified.
 *
 * Run:
 *   cd sdks/typescript && npx tsx examples/project_name_example.ts
 *
 * Requires a running Opik server. Set OPIK_URL_OVERRIDE in .env or env vars.
 */
import { Opik, Prompt } from "opik";

async function main() {
  const timestamp = Date.now();

  // --- Client with a custom default project ---
  const client = new Opik({ projectName: "my-project" });
  console.log(`Client default project: "${client.config.projectName}"\n`);

  // --- Datasets ---
  const datasetName = `example-dataset-${timestamp}`;
  const dataset = await client.createDataset(datasetName, "Example dataset");
  console.log(`Created dataset "${dataset.name}" in project "${dataset.projectName}"`);

  await dataset.insert([
    { input: { question: "What is 2+2?" }, expectedOutput: { answer: "4" } },
    { input: { question: "Capital of France?" }, expectedOutput: { answer: "Paris" } },
  ]);
  console.log("  Inserted 2 items");

  // Retrieve (uses same project by default)
  const retrieved = await client.getDataset(datasetName);
  console.log(`  Retrieved: "${retrieved.name}" (id: ${retrieved.id})`);

  // --- Experiments ---
  const experimentName = `example-experiment-${timestamp}`;
  const experiment = await client.createExperiment({
    name: experimentName,
    datasetName,
    experimentConfig: { model: "gpt-4" },
  });
  console.log(`\nCreated experiment "${experiment.name}" in project "${experiment.projectName}"`);

  // --- Prompts ---
  const promptName = `example-prompt-${timestamp}`;
  const prompt = new Prompt({
    name: promptName,
    prompt: "Answer concisely: {{question}}",
    projectName: "my-project",
  });
  console.log(`\nCreated prompt "${prompt.name}" (commit: ${prompt.commit})`);
  console.log(`  Formatted: "${prompt.format({ question: "What is 2+2?" })}"`);

  // --- Explicit projectName override ---
  const otherDatasetName = `example-other-${timestamp}`;
  const otherDataset = await client.createDataset(
    otherDatasetName,
    "Dataset in another project",
    "other-project" // explicit override
  );
  console.log(`\nCreated dataset in "other-project": "${otherDataset.name}"`);

  // --- Cleanup ---
  console.log("\n--- Cleanup ---");
  // await client.deleteDataset(datasetName);
  // await client.deleteDataset(otherDatasetName, "other-project");
  // await client.deleteExperiment(experiment.id);
  // await client.deletePrompts([prompt.id]);
  await client.flush();
  console.log("Done!\n");
}

main().catch((error) => {
  console.error("Error:", error.message);
  process.exit(1);
});
