import {
  getCompareExperimentsList,
  UseCompareExperimentsListResponse,
} from "@/api/datasets/useCompareExperimentsList";
import { ExperimentsCompare } from "@/types/datasets";
import { Filters } from "@/types/filters";
import { Sorting } from "@/types/sorting";

const PAGE_SIZE = 100;

/**
 * Browser exports are capped: everything is held in memory and serialised in the tab, so a large result set
 * belongs in the SDK, which streams it. The panel checks this before offering the export; the check inside the
 * fetch covers the case where rows are added between rendering the button and clicking it.
 */
export const EXPORT_ROW_LIMIT = 1000;

export class ExportTooLargeError extends Error {
  constructor(total: number) {
    super(
      `This view has ${total.toLocaleString()} rows, more than the ${EXPORT_ROW_LIMIT.toLocaleString()} that can be exported from the browser. Filter it down, select the rows you need, or export it with the SDK.`,
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
 * Reads every row behind the current view, page by page, so an export covers the whole result set rather than
 * the page on screen. Filters, search and sorting are passed through, so the file holds what the table would
 * show if it were one long page.
 */
const getAllCompareExperimentsItems = async (
  params: GetAllCompareExperimentsItemsParams,
  { signal }: { signal?: AbortSignal } = {},
): Promise<ExperimentsCompare[]> => {
  const rows: ExperimentsCompare[] = [];
  let page = 1;

  for (;;) {
    const data: UseCompareExperimentsListResponse =
      await getCompareExperimentsList(
        { signal },
        {
          ...params,
          // Cells are truncated for display; an export has to carry the stored value.
          truncate: false,
          page,
          size: PAGE_SIZE,
        },
      );

    const total = data?.total ?? 0;

    // Read from the first page, so an oversized result set costs one request rather than all of them.
    if (total > EXPORT_ROW_LIMIT) {
      throw new ExportTooLargeError(total);
    }

    const content = data?.content ?? [];
    if (!content.length) break;

    rows.push(...content);

    if (rows.length >= total) break;

    page += 1;
  }

  return rows;
};

export default getAllCompareExperimentsItems;
