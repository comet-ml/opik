/**
 * Reader for the CSV the app's "Export as CSV" produces.
 *
 * The FE writes these with `json2csv`, which quotes any field containing a
 * comma, a double quote or a newline and escapes an embedded quote by doubling
 * it. Splitting on `,` and `\n` would therefore miscount rows the moment an
 * exported payload contained either — and "how many rows did the export
 * actually hold" is the whole assertion these specs make, so a reader that
 * could be off by one under ordinary data would be worse than none.
 *
 * Deliberately minimal: no type coercion, no header de-duplication, no
 * streaming. Every value comes back as the string the file held.
 */
export interface ParsedCsv {
  header: string[];
  /** One entry per data row, keyed by header name. */
  rows: Array<Record<string, string>>;
}

/** Split CSV text into records of fields, honouring RFC 4180 quoting. */
function parseRecords(text: string): string[][] {
  const records: string[][] = [];
  let record: string[] = [];
  let field = '';
  let inQuotes = false;

  for (let i = 0; i < text.length; i++) {
    const char = text[i];

    if (inQuotes) {
      if (char === '"') {
        // A doubled quote inside a quoted field is a literal quote.
        if (text[i + 1] === '"') {
          field += '"';
          i++;
        } else {
          inQuotes = false;
        }
      } else {
        field += char;
      }
      continue;
    }

    if (char === '"') {
      inQuotes = true;
    } else if (char === ',') {
      record.push(field);
      field = '';
    } else if (char === '\n' || char === '\r') {
      // Treat CRLF as one break rather than an empty record between them.
      if (char === '\r' && text[i + 1] === '\n') i++;
      record.push(field);
      records.push(record);
      record = [];
      field = '';
    } else {
      field += char;
    }
  }

  // A final record with no trailing newline still counts; a trailing newline
  // must not manufacture an empty one.
  if (field !== '' || record.length > 0) {
    record.push(field);
    records.push(record);
  }

  return records;
}

/**
 * Parse an exported CSV into its header and data rows.
 *
 * Throws on an empty file rather than returning zero rows: an export that
 * produced nothing at all is a different failure from one that produced a
 * header and no data, and a caller asserting `rows.length` should not see the
 * two collapse into the same answer.
 */
export function parseCsv(text: string): ParsedCsv {
  const records = parseRecords(text);
  if (records.length === 0) {
    throw new Error('parseCsv: the exported file was empty (no header row)');
  }

  const [header, ...dataRecords] = records;
  const rows = dataRecords.map((record) =>
    Object.fromEntries(header.map((name, i) => [name, record[i] ?? ''])),
  );

  return { header, rows };
}

/**
 * Read one column out of every data row, asserting the column exists first.
 *
 * The exported column set is the table's *currently selected* columns, not a
 * fixed list, so a spec that read a missing column would silently compare a
 * list of empty strings and could pass having checked nothing. This turns that
 * into a loud failure naming the columns the file did carry.
 */
export function csvColumn(parsed: ParsedCsv, column: string): string[] {
  if (!parsed.header.includes(column)) {
    throw new Error(
      `csvColumn: the export carried no "${column}" column; header was [${parsed.header.join(', ')}]`,
    );
  }
  return parsed.rows.map((row) => row[column]);
}
