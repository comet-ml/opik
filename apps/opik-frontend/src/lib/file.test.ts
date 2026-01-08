import { describe, expect, it, vi } from "vitest";
import { validateCsvFile } from "./file";
import { csv2json } from "json-2-csv";

vi.mock("json-2-csv", () => ({
  csv2json: vi.fn(),
}));

describe("validateCsvFile", () => {
  const maxSize = 1; // MB
  const maxItems = 1000;

  it("should return an empty object if file is undefined", async () => {
    const result = await validateCsvFile(undefined, maxSize, maxItems);
    expect(result).toEqual({});
  });

  it("should return an error if file size exceeds the limit", async () => {
    const mockFile = new File(["A".repeat(1.1 * 1024 * 1024)], "test.csv", {
      type: "text/csv",
    });
    const result = await validateCsvFile(mockFile, maxSize, maxItems);
    expect(result).toEqual({
      error: `File exceeds maximum size (${maxSize}MB).`,
    });
  });

  it("should return an error if file is not in .csv format", async () => {
    const mockFile = new File(["content"], "test.txt", { type: "text/plain" });
    const result = await validateCsvFile(mockFile, maxSize, maxItems);
    expect(result).toEqual({
      error: "File must be in .csv format",
    });
  });

  it("should accept .csv file with empty MIME type (Windows behavior)", async () => {
    // On Windows, browsers often return empty string for file.type on CSV files
    const mockFile = new File(["col1,col2\nval1,val2"], "test.csv", {
      type: "",
    });
    const mockData = [{ col1: "val1", col2: "val2" }];
    vi.mocked(csv2json).mockResolvedValue(mockData);
    const result = await validateCsvFile(mockFile, maxSize, maxItems);
    expect(result).toEqual({ data: mockData });
  });

  it("should return an error if CSV parsing fails", async () => {
    const mockFile = new File(["content"], "test.csv", { type: "text/csv" });
    vi.mocked(csv2json).mockRejectedValue(new Error("Parsing error"));
    const result = await validateCsvFile(mockFile, maxSize, maxItems);
    expect(result).toEqual({ error: "Failed to process CSV file." });
  });

  it("should return an error if parsed CSV is not an array", async () => {
    const mockFile = new File(["content"], "test.csv", { type: "text/csv" });
    vi.mocked(csv2json).mockResolvedValue({} as never[]);
    const result = await validateCsvFile(mockFile, maxSize, maxItems);
    expect(result).toEqual({ error: "Invalid CSV format." });
  });

  it("should return an error if the CSV is empty", async () => {
    const mockFile = new File(["content"], "test.csv", { type: "text/csv" });
    vi.mocked(csv2json).mockResolvedValue([]);
    const result = await validateCsvFile(mockFile, maxSize, maxItems);
    expect(result).toEqual({ error: "CSV file is empty." });
  });

  it("should return an error if the number of rows exceeds the limit", async () => {
    const mockFile = new File(["content"], "test.csv", { type: "text/csv" });
    const mockData = new Array(maxItems + 1).fill({
      input: "value",
      output: "value",
    });
    vi.mocked(csv2json).mockResolvedValue(mockData);
    const result = await validateCsvFile(mockFile, maxSize, maxItems);
    expect(result).toEqual({
      error: `File is too large (max. ${maxItems.toLocaleString()} rows)`,
    });
  });
});
