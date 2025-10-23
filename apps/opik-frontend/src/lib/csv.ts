import { csv2json } from "json-2-csv";

/**
 * Represents parsed CSV data structure.
 */
interface CSVData {
  /** Column headers from the first row of the CSV */
  headers: string[];
  /** Data rows, each row is an array of string values */
  rows: string[][];
}

/**
 * Parses CSV text into structured data with headers and rows using json-2-csv library.
 * 
 * @param csvText - The raw CSV text to parse
 * @returns Promise that resolves to an object containing headers array and rows array (array of arrays)
 * 
 * @example
 * ```typescript
 * const csv = 'Name,Age,City\nJohn,25,"New York"\nJane,30,Boston';
 * const result = await parseCSV(csv);
 * // result.headers: ['Name', 'Age', 'City']
 * // result.rows: [['John', '25', 'New York'], ['Jane', '30', 'Boston']]
 * ```
 * 
 * **Features (via json-2-csv):**
 * - Robust CSV parsing with proper RFC 4180 compliance
 * - Support for quoted fields with embedded commas and newlines
 * - Escaped quotes within quoted fields
 * - Automatic BOM detection and removal
 * - Configurable trimming of header fields and values
 * - Support for different line endings (CRLF, LF, CR)
 * - Proper handling of empty fields and malformed CSVs
 * 
 * **Configuration:**
 * - Automatic whitespace trimming enabled for headers and values
 * - Excel BOM handling enabled for compatibility
 * - All values returned as strings (consistent with CSV format)
 * 
 * @throws {Error} When CSV parsing fails due to malformed content
 */
export const parseCSV = async (csvText: string): Promise<CSVData> => {
  if (!csvText || csvText.trim().length === 0) {
    return { headers: [], rows: [] };
  }

  try {
    // Normalize line endings for consistent parsing
    const normalizedText = csvText.replace(/\r\n|\r/g, "\n");
    
    // Parse CSV using json-2-csv with same configuration as existing file validation
    const parsed = await csv2json(normalizedText, {
      excelBOM: true,
      trimHeaderFields: true,
      trimFieldValues: true,
    });

    if (!Array.isArray(parsed) || parsed.length === 0) {
      return { headers: [], rows: [] };
    }

    // Type assertion for the parsed data as json-2-csv returns Record<string, unknown>[]
    const parsedData = parsed as Record<string, unknown>[];
    
    // Extract headers from the first object's keys
    const headers = Object.keys(parsedData[0]);
    
    // Convert objects array back to rows array (array of arrays)
    const rows = parsedData.map((row) => 
      headers.map((header) => {
        const value = row[header];
        // Convert all values to strings for consistency
        return value == null ? "" : String(value);
      })
    );

    return { headers, rows };
  } catch (error) {
    // Re-throw with more context for debugging
    throw new Error(`Failed to parse CSV: ${error instanceof Error ? error.message : 'Unknown error'}`);
  }
};

export type { CSVData };