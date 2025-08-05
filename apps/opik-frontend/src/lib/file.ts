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

    const normalizedText = text.replace(/\r\n|\r/g, "\n");

    const parsed = await csv2json(normalizedText, {
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

    return { data: parsed as Record<string, unknown>[] };
  } catch (err) {
    console.error(err);
    return { error: "Failed to process CSV file." };
  }
}
