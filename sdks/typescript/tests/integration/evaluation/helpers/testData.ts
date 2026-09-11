import { Opik } from "@/index";
import { Dataset } from "@/dataset/Dataset";

/**
 * Test data for Q&A evaluations
 */
export const QA_TEST_DATA = [
  {
    question: "What is the capital of France?",
    expected_answer: "Paris",
    context: [
      "France is a country in Europe.",
      "Paris is the capital city of France.",
    ],
  },
  {
    question: "What is TypeScript?",
    expected_answer: "TypeScript is a typed superset of JavaScript",
    context: [
      "TypeScript adds static typing to JavaScript.",
      "TypeScript compiles to JavaScript.",
    ],
  },
  {
    question: "What is the primary function of photosynthesis?",
    expected_answer: "To convert light energy into chemical energy",
    context: [
      "Photosynthesis occurs in plants.",
      "It converts sunlight into glucose.",
    ],
  },
];

/**
 * Test data for simple evaluation tasks
 */
export const SIMPLE_TEST_DATA = [
  {
    input: "Hello",
    output: "Hi there!",
  },
  {
    input: "How are you?",
    output: "I'm doing well, thank you!",
  },
];

/**
 * Test data with input/output/context for metrics
 */
export const EVALUATION_TEST_DATA = [
  {
    input: "What is the capital of France?",
    output: "The capital of France is Paris.",
    context: [
      "France is a country in Europe.",
      "Paris is the capital city of France.",
    ],
  },
  {
    input: "What is the capital of France?",
    output: "The capital of France is London.",
    context: [
      "France is a country in Europe.",
      "Paris is the capital city of France.",
    ],
  },
  {
    input: "What is TypeScript?",
    output:
      "TypeScript is a typed superset of JavaScript that compiles to plain JavaScript.",
    context: [
      "TypeScript adds static typing to JavaScript.",
      "TypeScript was developed by Microsoft.",
    ],
  },
];

/**
 * Creates a test dataset with Q&A items
 */
export async function createQADataset(
  client: Opik,
  name?: string
): Promise<Dataset> {
  const datasetName = name || `test-qa-dataset-${Date.now()}`;
  const dataset = await client.createDataset(datasetName);

  // Ensure dataset is created in backend before inserting items
  await client.datasetBatchQueue.flush();

  await dataset.insert(QA_TEST_DATA);

  return dataset;
}

/**
 * Creates a test dataset with input/output/context for evaluation
 */
export async function createEvaluationDataset(
  client: Opik,
  name?: string
): Promise<Dataset> {
  const datasetName = name || `test-eval-dataset-${Date.now()}`;
  const dataset = await client.createDataset(datasetName);

  // Ensure dataset is created in backend before inserting items
  await client.datasetBatchQueue.flush();

  await dataset.insert(EVALUATION_TEST_DATA);

  return dataset;
}

/**
 * Creates a simple dataset with input/output pairs
 */
export async function createSimpleDataset(
  client: Opik,
  name?: string,
  data?: Array<Record<string, unknown>>
): Promise<Dataset> {
  const datasetName = name || `test-simple-dataset-${Date.now()}`;
  const dataset = await client.createDataset(datasetName);

  // Ensure dataset is created in backend before inserting items
  await client.datasetBatchQueue.flush();

  await dataset.insert(data || SIMPLE_TEST_DATA);

  return dataset;
}

/**
 * Cleanup helper for datasets
 */
export async function cleanupDatasets(
  client: Opik,
  datasetNames: string[]
): Promise<void> {
  for (const name of datasetNames) {
    try {
      await client.deleteDataset(name);
    } catch (error) {
      console.warn(`Failed to cleanup dataset "${name}":`, error);
    }
  }
}

/**
 * Cleanup helper for prompts
 */
export async function cleanupPrompts(
  client: Opik,
  promptIds: string[]
): Promise<void> {
  if (promptIds.length === 0) return;

  try {
    await client.deletePrompts(promptIds);
  } catch (error) {
    console.warn(`Failed to cleanup prompts:`, error);
  }
}
