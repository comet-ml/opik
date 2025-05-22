/**
 * Dataset Interactions Example
 *
 */
import { Opik } from "../src/opik";

// Initialize the Opik client
const opik = new Opik({
  apiKey: "your-api-key", // Replace with your API key
});

// Step 1: Create a new dataset
const createdDataset = await opik.createDataset(
  "example-dataset",
  "A dataset created for example purposes"
);
console.log(
  `Created dataset with ID: ${createdDataset.id} and name: ${createdDataset.name}`
);

// Step 2: Retrieve the existing dataset by name
const retrievedDataset = await opik.getDataset("example-dataset");
console.log(`Retrieved dataset: ${retrievedDataset.name}`);
console.log(`Dataset ID: ${retrievedDataset.id}`);
console.log(`Dataset Description: ${retrievedDataset.description}`);

// Step 3: Get or create a dataset
const dataset = await opik.getOrCreateDataset(
  "example-dataset-2",
  "This dataset will be created if it doesn't already exist"
);
console.log(`Dataset ${dataset.name} ${dataset.id} is ready to use`);

// Step 4: List all datasets
const datasets = await opik.getDatasets(50);
console.log(`Retrieved ${datasets.length} datasets:`);
datasets.forEach((dataset, index) => {
  console.log(`${index + 1}. ${dataset.name} (${dataset.id})`);
});

// Step 5: Add items to a dataset
const itemsToAdd = [
  {
    input: "What is machine learning?",
    output:
      "Machine learning is a type of artificial intelligence that enables systems to learn and improve from experience without being explicitly programmed.",
    metadata: { category: "AI basics", difficulty: "beginner" },
  },
  {
    input: "Explain neural networks",
    output:
      "Neural networks are computing systems inspired by the biological neural networks in animal brains. They consist of artificial neurons that can learn to perform tasks by analyzing examples.",
    metadata: { category: "AI architecture", difficulty: "intermediate" },
  },
];
await retrievedDataset.insert(itemsToAdd);
console.log(`Added ${itemsToAdd.length} items to the dataset`);

// Step 6: Retrieve items from the dataset
const firstBatch = await retrievedDataset.getItems(10);
console.log(`Retrieved ${firstBatch.length} items from dataset`);
if (firstBatch.length === 10) {
  const lastItemId = firstBatch[firstBatch.length - 1].id;
  const nextBatch = await retrievedDataset.getItems(10, lastItemId);
  console.log(`Retrieved ${nextBatch.length} more items`);
}

// Step 7: Update existing items in the dataset
const itemsToUpdate = await retrievedDataset.getItems(2);
if (itemsToUpdate.length > 0) {
  const updatedItems = itemsToUpdate.map((item) => ({
    ...item,
    metadata: {
      ...item.metadata,
      updated_at: new Date().toISOString(),
      version: ((item.metadata?.version as number) || 0) + 1,
    },
  }));
  await retrievedDataset.update(updatedItems);
  console.log(`Updated ${updatedItems.length} items in the dataset`);
}

// Step 8: Delete items from the dataset
const itemsToDelete = await retrievedDataset.getItems(2);
if (itemsToDelete.length > 0) {
  const itemIdsToDelete = itemsToDelete.map((item) => item.id);
  await retrievedDataset.delete(itemIdsToDelete);
  console.log(`Deleted ${itemIdsToDelete.length} items from the dataset`);
}

// Step 9: Clear all items from the dataset
await retrievedDataset.clear();
console.log("Cleared all items from the dataset");

// Step 10: Import items from JSON
const jsonData = JSON.stringify([
  {
    query: "What is the capital of France?",
    answer: "Paris",
    tags: ["geography", "europe"],
    difficulty: "easy",
    ignore_me: "some value to ignore",
  },
  {
    query: "What is the largest planet in our solar system?",
    answer: "Jupiter",
    tags: ["astronomy", "science"],
    difficulty: "medium",
    ignore_me: "another value to ignore",
  },
]);
const keysMapping = {
  query: "input",
  answer: "output",
  tags: "metadata.tags",
  difficulty: "metadata.difficulty",
};
const ignoreKeys = ["ignore_me"];
await retrievedDataset.insertFromJson(jsonData, keysMapping, ignoreKeys);
console.log("Successfully imported items from JSON");

// Step 11: Export dataset to JSON
const exportKeysMapping = { input: "question", output: "answer" };
const exportedJsonData = await retrievedDataset.toJson(exportKeysMapping);
console.log("Dataset exported to JSON:");
console.log(exportedJsonData.substring(0, 200) + "...");

// Step 12: Delete the dataset
await opik.deleteDataset("example-dataset");
console.log("Dataset deleted successfully");
