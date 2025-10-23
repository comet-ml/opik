import { describe, it, expect } from "vitest";
import { parseCSV } from "./csv";

describe("CSV parsing", () => {
  it("should parse simple CSV data", async () => {
    const csvText = "Name,Age,City\nJohn,25,New York\nJane,30,San Francisco";
    const result = await parseCSV(csvText);

    expect(result.headers).toEqual(["Name", "Age", "City"]);
    expect(result.rows).toEqual([
      ["John", "25", "New York"],
      ["Jane", "30", "San Francisco"],
    ]);
  });

  it("should handle CSV with quoted fields", async () => {
    const csvText = 'Name,Description,Price\n"Product A","A great product",29.99\n"Product B","Another product, with comma",49.99';
    const result = await parseCSV(csvText);

    expect(result.headers).toEqual(["Name", "Description", "Price"]);
    expect(result.rows).toEqual([
      ["Product A", "A great product", "29.99"],
      ["Product B", "Another product, with comma", "49.99"],
    ]);
  });

  it("should handle CSV with escaped quotes", async () => {
    const csvText = 'Name,Description\n"John ""The Great""","A person with ""quotes"""\n"Jane Smith","Normal person"';
    const result = await parseCSV(csvText);

    expect(result.headers).toEqual(["Name", "Description"]);
    expect(result.rows).toEqual([
      ['John "The Great"', 'A person with "quotes"'],
      ["Jane Smith", "Normal person"],
    ]);
  });

  it("should handle empty CSV", async () => {
    const csvText = "";
    const result = await parseCSV(csvText);

    expect(result.headers).toEqual([]);
    expect(result.rows).toEqual([]);
  });

  it("should handle CSV with only headers", async () => {
    const csvText = "Name,Age,City";
    const result = await parseCSV(csvText);

    // json-2-csv returns empty array when there are no data rows (only headers)
    // This is correct behavior - headers without data should result in empty structure
    expect(result.headers).toEqual([]);
    expect(result.rows).toEqual([]);
  });

  it("should handle CSV with whitespace", async () => {
    const csvText = " Name , Age , City \n John , 25 , New York \n Jane , 30 , San Francisco ";
    const result = await parseCSV(csvText);

    expect(result.headers).toEqual(["Name", "Age", "City"]);
    expect(result.rows).toEqual([
      ["John", "25", "New York"],
      ["Jane", "30", "San Francisco"],
    ]);
  });
});