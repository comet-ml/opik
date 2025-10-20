import { describe, it, expect } from "vitest";
import { parseCSV } from "./csv";

describe("CSV parsing", () => {
  it("should parse simple CSV data", () => {
    const csvText = "Name,Age,City\nJohn,25,New York\nJane,30,San Francisco";
    const result = parseCSV(csvText);

    expect(result.headers).toEqual(["Name", "Age", "City"]);
    expect(result.rows).toEqual([
      ["John", "25", "New York"],
      ["Jane", "30", "San Francisco"],
    ]);
  });

  it("should handle CSV with quoted fields", () => {
    const csvText = 'Name,Description,Price\n"Product A","A great product",29.99\n"Product B","Another product, with comma",49.99';
    const result = parseCSV(csvText);

    expect(result.headers).toEqual(["Name", "Description", "Price"]);
    expect(result.rows).toEqual([
      ["Product A", "A great product", "29.99"],
      ["Product B", "Another product, with comma", "49.99"],
    ]);
  });

  it("should handle CSV with escaped quotes", () => {
    const csvText = 'Name,Description\n"John ""The Great""","A person with ""quotes"""\n"Jane Smith","Normal person"';
    const result = parseCSV(csvText);

    expect(result.headers).toEqual(["Name", "Description"]);
    expect(result.rows).toEqual([
      ['John "The Great"', 'A person with "quotes"'],
      ["Jane Smith", "Normal person"],
    ]);
  });

  it("should handle empty CSV", () => {
    const csvText = "";
    const result = parseCSV(csvText);

    expect(result.headers).toEqual([""]);
    expect(result.rows).toEqual([]);
  });

  it("should handle CSV with only headers", () => {
    const csvText = "Name,Age,City";
    const result = parseCSV(csvText);

    expect(result.headers).toEqual(["Name", "Age", "City"]);
    expect(result.rows).toEqual([]);
  });

  it("should handle CSV with whitespace", () => {
    const csvText = " Name , Age , City \n John , 25 , New York \n Jane , 30 , San Francisco ";
    const result = parseCSV(csvText);

    expect(result.headers).toEqual(["Name", "Age", "City"]);
    expect(result.rows).toEqual([
      ["John", "25", "New York"],
      ["Jane", "30", "San Francisco"],
    ]);
  });
});