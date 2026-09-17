import {
  getCompareExperimentsList,
  UseCompareExperimentsListResponse,
} from "@/api/datasets/useCompareExperimentsList";
import { ExperimentsCompare } from "@/types/datasets";
import { Filters } from "@/types/filters";
import { Sorting } from "@/types/sorting";

/**
 * A browser export holds the whole result set in memory and serialises it in the tab, so it is capped and
 * larger result sets belong in the SDK. The cap doubles as the page size: one request covers everything under
 * it, which is how the rest of the app reads this endpoint, and avoids paging entirely. That matters beyond
 * round trips - separate pages are separate queries, and rows can shift between them when the sort has ties or
 * when someone writes to the experiment mid-export.
 */
export const EXPORT_ROW_LIMIT = 2000;

export class ExportTooLargeError extends Error {
  constructor(total: number) {
    super(
      `This view has ${total.toLocaleString()} rows. The browser can export ${EXPORT_ROW_LIMIT.toLocaleString()} at a time - filter the table down, select the rows you want, or export it with the SDK.`,
    );
    this.name = "ExportTooLargeError";
  }
}

type GetAllCompareExperimentsItemsParams = {
  workspaceName: string;
  datasetId: string;
  experimentsIds: string[];
  search?: string;
  filters?: Filters;
  sorting?: Sorting;
};

/**
 * Reads every row behind the current view in one request, so an export covers the whole result set rather than
 * the page on screen. Filters, search and sorting are passed through, so the file holds what the table would
 * show if it were one long page.
 */
const getAllCompareExperimentsItems = async (
  params: GetAllCompareExperimentsItemsParams,
  { signal }: { signal?: AbortSignal } = {},
): Promise<ExperimentsCompare[]> => {
  const data: UseCompareExperimentsListResponse =
    await getCompareExperimentsList(
      { signal },
      {
        ...params,
        // Cells are truncated for display; an export has to carry the stored value.
        truncate: false,
        page: 1,
        size: EXPORT_ROW_LIMIT,
      },
    );

  const total = data?.total;
  const rows = data?.content;

  if (!Array.isArray(rows) || typeof total !== "number") {
    throw new Error(
      "Export failed: the server returned an unexpected response.",
    );
  }

  // The panel keeps the control disabled past the cap, so this only catches rows added between rendering the
  // button and clicking it.
  if (total > EXPORT_ROW_LIMIT) {
    throw new ExportTooLargeError(total);
  }

  // A short page here means rows went missing rather than that we reached the end - fail instead of writing a
  // file that silently holds less than the table showed.
  if (rows.length !== total) {
    throw new Error(
      `Export failed: the server returned ${rows.length.toLocaleString()} of ${total.toLocaleString()} rows.`,
    );
  }

  return rows;
};

export default getAllCompareExperimentsItems;
