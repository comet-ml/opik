/**
 * A minimal RFC-4180 reader for files the app *downloads*.
 *
 * Splitting on `\n` and `,` would be enough for the values these tests seed,
 * but the app writes the CSV with `json-2-csv`, which quotes any value holding
 * a comma, a quote or a newline — a free-text comment or an LLM output would
 * silently shift every column to the right of it and the assertion would then
 * be reading the wrong field rather than failing. Parsing properly keeps a
 * malformed export a failure instead of a wrong-but-green comparison.
 */

/** Rows of raw cells, header row included, exactly as written. */
export function parseCsvRows(text: string): string[][] {
  // Strip an Excel BOM and normalise line endings before scanning.
  const input = text.replace(/^﻿/, '').replace(/\r\n/g, '\n');
  const rows: string[][] = [];
  let row: string[] = [];
  let cell = '';
  let quoted = false;

  for (let i = 0; i < input.length; i += 1) {
    const char = input[i];

    if (quoted) {
      if (char === '"') {
        if (input[i + 1] === '"') {
          cell += '"';
          i += 1;
        } else {
          quoted = false;
        }
      } else {
        cell += char;
      }
      continue;
    }

    if (char === '"') {
      quoted = true;
    } else if (char === ',') {
      row.push(cell);
      cell = '';
    } else if (char === '\n') {
      row.push(cell);
      rows.push(row);
      row = [];
      cell = '';
    } else {
      cell += char;
    }
  }

  // A file that does not end in a newline still has one last cell in hand.
  if (cell !== '' || row.length > 0) {
    row.push(cell);
    rows.push(row);
  }

  return rows;
}

export interface ParsedCsv {
  headers: string[];
  /** One record per data row, keyed by header. */
  rows: Array<Record<string, string>>;
}

/**
 * The same file as a header list plus header-keyed records. Throws on a row
 * whose width disagrees with the header, because a ragged export is a defect in
 * its own right and reading it as though it were fine would hide that.
 */
export function parseCsv(text: string): ParsedCsv {
  const raw = parseCsvRows(text).filter(
    (row) => !(row.length === 1 && row[0].trim() === ''),
  );
  if (raw.length === 0) return { headers: [], rows: [] };

  const [headers, ...dataRows] = raw;
  const rows = dataRows.map((row, index) => {
    if (row.length !== headers.length) {
      throw new Error(
        `parseCsv: data row ${index + 1} has ${row.length} cells, header has ${headers.length}`,
      );
    }
    return Object.fromEntries(headers.map((header, i) => [header, row[i]]));
  });

  return { headers, rows };
}
