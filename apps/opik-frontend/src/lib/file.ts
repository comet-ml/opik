import { csv2json } from "json-2-csv";

interface CsvValidationResult {
  data?: Record<string, unknown>[];
  error?: string;
}

export const convertFileToBase64 = (file: File): Promise<string> => {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => {
      if (typeof reader.result === "string") {
        resolve(reader.result);
      } else {
        reject(new Error("Failed to convert file to base64"));
      }
    };
    reader.onerror = () => {
      reject(new Error("Failed to read file"));
    };
    reader.readAsDataURL(file);
  });
};

export async function validateCsvFile(
  file: File | undefined,
  maxSize: number,
  maxItems: number,
): Promise<CsvValidationResult> {
  if (!file) return {};

  if (file.size > maxSize * 1024 * 1024) {
    return { error: `File exceeds maximum size (${maxSize}MB).` };
  }

  if (!file.name.toLowerCase().endsWith(".csv")) {
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

export interface FileValidationResult {
  valid: boolean;
  error?: string;
}

/**
 * Validates if adding files would exceed the maximum allowed count
 */
export const validateFileCount = (
  currentCount: number,
  newFilesCount: number,
  maxCount: number,
): FileValidationResult => {
  const availableSlots = maxCount - currentCount;

  if (newFilesCount > availableSlots) {
    return {
      valid: false,
      error: `You can only add ${availableSlots} more file${
        availableSlots !== 1 ? "s" : ""
      }`,
    };
  }

  return { valid: true };
};

/**
 * Validates if files exceed the specified size limit in MB
 */
export const validateFileSize = (
  files: File[],
  maxSizeMB: number,
): FileValidationResult => {
  const maxSizeBytes = maxSizeMB * 1024 * 1024;
  const oversizedFiles = files.filter((file) => file.size > maxSizeBytes);

  if (oversizedFiles.length > 0) {
    return {
      valid: false,
      error: `File${
        oversizedFiles.length > 1 ? "s" : ""
      } must be smaller than ${maxSizeMB}MB. Please use a smaller file or add it as a URL.`,
    };
  }

  return { valid: true };
};

/**
 * Extracts filename without the extension
 */
export const getCsvFilenameWithoutExtension = (filename: string): string => {
  return filename.replace(/\.csv$/i, "");
};
