import * as fs from 'node:fs/promises';
import * as path from 'node:path';
import { expect } from '@playwright/test';
import { test as baseTest } from './thread-search-population.fixture';

/**
 * What the server must do with one uploaded file, and how it must say so.
 *
 * `rejected` files are the subject: opik#8605 (OPIK-8188) made them 400
 * SYNCHRONOUSLY, where before they were answered 202 and then failed in async
 * processing — leaving an empty dataset while the UI said the import had worked.
 * The `accepted` file is the control beside them: without it, a build that
 * refused EVERY upload would satisfy every rejection assertion.
 */
export interface UploadFileCase {
  /** Stable key a spec names the case by. */
  key: 'dupHeaderCase' | 'badJsonArray' | 'badJsonlLine' | 'validCsv';
  /** Absolute path of the written file. */
  filePath: string;
  /** The file's base name without its extension — what the sidebar derives the name from. */
  baseName: string;
  /** Which upload endpoint the format routes to, as the FE picks it. */
  endpoint: 'from-csv' | 'from-json';
  /** `CSV` / `JSON` / `JSONL` — the label the toast titles use (`formatToHumanLabel`). */
  formatLabel: 'CSV' | 'JSON' | 'JSONL';
  /** The status the upload must answer. */
  expectedStatus: 400 | 202;
  /**
   * What the server's rejection message must say, as a regex. Absent for the
   * accepted case, which has no error message to carry.
   *
   * A regex rather than a literal for the two JSON cases, whose messages embed a
   * Jackson parser string this spec has no business pinning; the CSV one is
   * exact, because the backend composes the whole sentence itself. Each is
   * anchored at the start so a message that merely CONTAINS the expected words
   * somewhere in a different sentence does not pass.
   */
  expectedMessage?: RegExp;
  /** How many items the dataset must hold once the dust settles. */
  expectedItemCount: number;
}

export interface DatasetUploadFilesRef {
  cases: UploadFileCase[];
  /** One case by key, so a spec reads `files.case('badJsonlLine')` rather than indexing. */
  case(key: UploadFileCase['key']): UploadFileCase;
}

export interface DatasetUploadFilesFixtures {
  datasetUploadFiles: DatasetUploadFilesRef;
}

export const test = baseTest.extend<DatasetUploadFilesFixtures>({
  /**
   * The four upload files this spec drives, written into the run's scratch dir.
   *
   * Fully self-seeding: nothing here depends on a checked-in sample, on the
   * workspace's contents, or on the wall clock. The three malformed shapes are
   * each the specific thing opik#8605 made synchronous, and each is malformed
   * PAST the point the old head-only validation looked at — which is what makes
   * them regression tests rather than smoke tests:
   *
   *  - **`dupHeaderCase`** — two headers differing only in case (`Input` /
   *    `input`). The parser runs with `setIgnoreHeaderCase(true)`, so it would
   *    have merged them; the fix turns on Commons CSV's
   *    `DuplicateHeaderMode.DISALLOW`, which compares headers exactly the way
   *    the parser does.
   *  - **`badJsonArray`** — a syntax error in the SECOND element. The old
   *    `validateHead` checked `START_ARRAY` followed by one `START_OBJECT` and
   *    stopped, so this file passed validation and 202'd.
   *  - **`badJsonlLine`** — a non-object on the SECOND line. The old check
   *    `return`ed after the first non-blank line, so again only line 1 was ever
   *    seen.
   *
   * The file NAMES are inside the run prefix deliberately. The sidebar derives
   * the dataset name from the uploaded file's base name
   * (`getDatasetUploadFilenameWithoutExtension` plus today's local date), so a
   * file named anything else creates a dataset outside the namespace
   * `global-teardown` sweeps — which is how the exploration ended up deleting
   * three datasets by hand. The spec registers each created dataset for cleanup
   * as well; this is the belt beside that brace.
   *
   * The files live in `scratchDir`, which owns their removal and honours
   * `OPIK_LEAVE_FAILURES`, so a failed run keeps the exact bytes that were sent.
   */
  datasetUploadFiles: async ({ scratchDir, testNamespace }, use) => {
    const write = async (fileName: string, body: string): Promise<string> => {
      const filePath = path.join(scratchDir.path, fileName);
      await fs.writeFile(filePath, body, 'utf8');
      return filePath;
    };

    // Prefixed with the run namespace so the derived dataset name is swept. Kept
    // short of the namespace's full length only by what reads well in a failure
    // message; nothing depends on the length.
    const named = (suffix: string) => `${testNamespace}-${suffix}`;

    const cases: UploadFileCase[] = [
      {
        key: 'dupHeaderCase',
        filePath: await write(
          `${named('dup-header-case')}.csv`,
          // `Input` and `input` differ only in case, which the parser folds.
          'Input,input,expected\nhello,world,hi\n',
        ),
        baseName: named('dup-header-case'),
        endpoint: 'from-csv',
        formatLabel: 'CSV',
        expectedStatus: 400,
        expectedMessage:
          /^CSV contains duplicate column headers\. All column headers must be unique\.$/,
        expectedItemCount: 0,
      },
      {
        key: 'badJsonArray',
        filePath: await write(
          `${named('bad-json-array')}.json`,
          // Element 0 is well-formed, so head-only validation accepted this
          // file; element 1 has a value missing after its colon.
          '[{"input": "one", "expected": "1"},\n {"input": "two", "expected": }]\n',
        ),
        baseName: named('bad-json-array'),
        endpoint: 'from-json',
        formatLabel: 'JSON',
        expectedStatus: 400,
        expectedMessage: /^JSON file is not valid JSON: /,
        expectedItemCount: 0,
      },
      {
        key: 'badJsonlLine',
        filePath: await write(
          `${named('bad-jsonl-line')}.jsonl`,
          // Line 1 is an object, so the old check returned happy; line 2 is a
          // bare string, which the importer would have dropped silently.
          '{"input": "one", "expected": "1"}\n"this line is not an object"\n',
        ),
        baseName: named('bad-jsonl-line'),
        endpoint: 'from-json',
        formatLabel: 'JSONL',
        expectedStatus: 400,
        expectedMessage: /^JSONL line 2 is not a JSON object$/,
        expectedItemCount: 0,
      },
      {
        key: 'validCsv',
        filePath: await write(
          `${named('valid')}.csv`,
          'input,expected\nhello,hi\ngoodbye,bye\n',
        ),
        baseName: named('valid'),
        endpoint: 'from-csv',
        formatLabel: 'CSV',
        expectedStatus: 202,
        // No `expectedMessage`: the accepted path's own "CSV upload accepted"
        // toast is never observable. `use-toast` has `TOAST_LIMIT = 1` and
        // `useDatasetForm` raises it and the "is ready to use" one in the same
        // React commit, so the second replaces the first before either reaches
        // the DOM. The spec asserts the toast a user actually sees instead.
        expectedItemCount: 2,
      },
    ];

    // The premise the whole spec rests on: every file name is inside the swept
    // namespace, because the dataset the sidebar creates is named after it.
    for (const file of cases) {
      expect(
        file.baseName.startsWith(testNamespace),
        `upload file '${file.baseName}' must be named inside the run namespace — the sidebar ` +
          'derives the dataset name from it, so otherwise the dataset escapes the sweep',
      ).toBe(true);
    }
    expect(
      new Set(cases.map((c) => c.baseName)).size,
      'each case writes a distinctly named file, so each creates its own dataset',
    ).toBe(cases.length);

    await use({
      cases,
      case(key) {
        const found = cases.find((candidate) => candidate.key === key);
        if (!found) throw new Error(`datasetUploadFiles: no case named '${key}'`);
        return found;
      },
    });
  },
});

export { expect } from './thread-search-population.fixture';
