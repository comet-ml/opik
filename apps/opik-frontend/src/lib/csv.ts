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
 * Parses CSV text into structured data with headers and rows.
 * 
 * @param csvText - The raw CSV text to parse
 * @returns An object containing headers array and rows array (array of arrays)
 * 
 * @example
 * ```typescript
 * const csv = 'Name,Age,City\nJohn,25,"New York"\nJane,30,Boston';
 * const result = parseCSV(csv);
 * // result.headers: ['Name', 'Age', 'City']
 * // result.rows: [['John', '25', 'New York'], ['Jane', '30', 'Boston']]
 * ```
 * 
 * **Supported Features:**
 * - Basic comma-separated values
 * - Quoted fields (fields wrapped in double quotes)
 * - Escaped quotes within quoted fields (double quotes: `""`)
 * - Fields containing commas when properly quoted
 * - Automatic whitespace trimming on field values
 * 
 * **Limitations:**
 * - No support for embedded newlines inside quoted fields
 * - Only supports comma (`,`) as field separator
 * - Only supports double quote (`"`) as quote character
 * - No support for different line endings (only `\n`)
 * - No support for custom escape characters
 * - No support for CSV headers detection or validation
 * - Assumes first row is always headers
 * - All field values are returned as strings (no type conversion)
 * 
 * **Behavior Notes:**
 * - Whitespace around field values is automatically trimmed
 * - Empty CSV returns empty headers and rows arrays
 * - Malformed quotes may result in unexpected parsing
 * - Extra commas result in empty string fields
 * - Missing fields in rows are not automatically padded
 */
export const parseCSV = (csvText: string): CSVData => {
  const lines = csvText.trim().split("\n");
  if (lines.length === 0) {
    return { headers: [], rows: [] };
  }
  const parseCSVLine = (line: string): string[] => {
    const result: string[] = [];
    let current = "";
    let inQuotes = false;
    let i = 0;

    while (i < line.length) {
      const char = line[i];
      const nextChar = line[i + 1];

      if (char === '"') {
        if (inQuotes && nextChar === '"') {
          // Escaped quote (double quote within quoted field)
          current += '"';
          i += 2;
        } else {
          // Toggle quote state
          inQuotes = !inQuotes;
          i++;
        }
      } else if (char === "," && !inQuotes) {
        // Field separator (only when not inside quotes)
        result.push(current.trim());
        current = "";
        i++;
      } else {
        current += char;
        i++;
      }
    }

    // Add the last field
    result.push(current.trim());
    return result;
  };

  const headers = parseCSVLine(lines[0]);
  const rows = lines.slice(1).map(parseCSVLine);

  return { headers, rows };
};

export type { CSVData };