/**
 * Dataset Interactions Example
 *
 */
import { Opik } from "../src/opik";

// Initialize the Opik client
const opik = new Opik({
  apiKey: "your-api-key", // Replace with your API key
});

// Define the dataset item type
type DatasetItemData = {
  input: string;
  output: string;
  metadata: {
    category: string;
    difficulty: string;
    version: number;
  };
};

// Create a new dataset
await opik.createDataset<DatasetItemData>(
  "example-dataset",
  "A dataset created for example purposes"
);

// Retrieve the existing dataset by name
const retrievedDataset =
  await opik.getDataset<DatasetItemData>("example-dataset");

// Get or create a dataset
const dataset = await opik.getOrCreateDataset<DatasetItemData>(
  "example-dataset-2",
  "This dataset will be created if it doesn't already exist"
);
console.log(`Dataset ${dataset.name} ${dataset.id} is ready to use`);

// List all datasets
const datasets = await opik.getDatasets<DatasetItemData>(50);
console.log(`Retrieved ${datasets.length} datasets:`);
datasets.forEach((dataset, index) => {
  console.log(`${index + 1}. ${dataset.name} (${dataset.id})`);
});

// Add items to a dataset
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
  // duplicate item would be ignored
  {
    input: "What is machine learning?",
    output:
      "Machine learning is a type of artificial intelligence that enables systems to learn and improve from experience without being explicitly programmed.",
    metadata: { category: "AI basics", difficulty: "beginner" },
  },
];
await retrievedDataset.insert(itemsToAdd);
console.log(`Added ${itemsToAdd.length} items to the dataset`);

// Retrieve items from the dataset
const firstBatch = await retrievedDataset.getItems(10);
console.log(`Retrieved ${firstBatch.length} items from dataset`);
if (firstBatch.length === 10) {
  const lastItemId = firstBatch[firstBatch.length - 1].id;
  const nextBatch = await retrievedDataset.getItems(10, lastItemId);
  console.log(`Retrieved ${nextBatch.length} more items`);
}

// Update existing items in the dataset
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

// Delete items from the dataset
const itemsToDelete = await retrievedDataset.getItems(2);
const itemIdsToDelete = itemsToDelete.map((item) => item.id);
await retrievedDataset.delete(itemIdsToDelete);
console.log(`Deleted ${itemIdsToDelete.length} items from the dataset`);

// Clear all items from the dataset
await retrievedDataset.clear();
console.log("Cleared all items from the dataset");

// Import items from JSON
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

// Export dataset to JSON
const exportKeysMapping = { input: "question", output: "answer" };
const exportedJsonData = await retrievedDataset.toJson(exportKeysMapping);
console.log("Dataset exported to JSON:");
console.log(exportedJsonData.substring(0, 200) + "...");

// Delete the dataset
await opik.deleteDataset("example-dataset");
console.log("Dataset deleted successfully");
