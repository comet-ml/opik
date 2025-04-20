import { Opik } from "opik";

const client = new Opik();

// Create a new dataset
const dataset = await client.getOrCreateDataset("my-dataset");

// Insert some data into the dataset
await dataset.insert([{ name: "John" }, { name: "Jane" }])

// Get the items in the dataset
const items = await dataset.getItems();
console.log(items);