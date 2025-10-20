interface CSVData {
  headers: string[];
  rows: string[][];
}

export const parseCSV = (csvText: string): CSVData => {
  const lines = csvText.trim().split("\n");
  if (lines.length === 0) {
    return { headers: [], rows: [] };
  }

  // Simple CSV parser - handles basic cases including quoted fields and escaped quotes
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