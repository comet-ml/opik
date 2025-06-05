import { csv2json } from "json-2-csv";

interface CsvValidationResult {
  data?: Record<string, unknown>[];
  error?: string;
}

export async function validateCsvFile(
  file: File | undefined,
  maxSize: number,
  maxItems: number,
): Promise<CsvValidationResult> {
  if (!file) return {};

  if (file.size > maxSize * 1024 * 1024) {
    return { error: `File exceeds maximum size (${maxSize}MB).` };
  }

  if (!file.type || !file.type.includes("text/csv")) {
    return { error: "File must be in .csv format" };
  }

  try {
    const text = await file.text();
    const parsed = await csv2json(text, {
      excelBOM: true,
      trimHeaderFields: true,
      trimFieldValues: true,
    });

    if (!Array.isArray(parsed)) {
      return { error: "Invalid CSV format." };
    }

    if (parsed.length === 0) {
      return { error: "CSV file is empty." };
    }

    if (parsed.length > maxItems) {
      return {
        error: `File is too large (max. ${maxItems.toLocaleString()} rows)`,
      };
    }

    const headers = Object.keys(parsed[0] as object);
    if (
      headers.length !== 2 ||
      !headers.includes("input") ||
      !headers.includes("output")
    ) {
      return {
        error: `File must have only two columns named 'input' and 'output'. Instead, the file has the following columns: ${headers
          .slice(0, 5)
          .map((h) => `"${h}"`)
          .join(",")}`,
      };
    }

    return { data: parsed as Record<string, unknown>[] };
  } catch (err) {
    console.error(err);
    return { error: "Failed to process CSV file." };
  }
}
