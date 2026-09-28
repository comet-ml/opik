import { describe, it, expect, vi, beforeEach } from "vitest";
import getAllCompareExperimentsItems, {
  EXPORT_ROW_LIMIT,
  ExportTooLargeError,
} from "./getAllCompareExperimentsItems";

const mockGetCompareExperimentsList = vi.fn();

vi.mock("@/api/datasets/useCompareExperimentsList", () => ({
  getCompareExperimentsList: (
    ...args: Parameters<typeof mockGetCompareExperimentsList>
  ) => mockGetCompareExperimentsList(...args),
}));

const PARAMS = {
  workspaceName: "default",
  datasetId: "dataset-id",
  experimentsIds: ["experiment-id"],
};

const rows = (count: number) =>
  Array.from({ length: count }, (_, index) => ({ id: `row-${index}` }));

describe("getAllCompareExperimentsItems", () => {
  beforeEach(() => {
    mockGetCompareExperimentsList.mockReset();
  });

  it("reads the whole result set in a single request", async () => {
    mockGetCompareExperimentsList.mockResolvedValue({
      content: rows(250),
      total: 250,
    });

    const result = await getAllCompareExperimentsItems(PARAMS);

    expect(result).toHaveLength(250);
    expect(mockGetCompareExperimentsList).toHaveBeenCalledTimes(1);
    expect(mockGetCompareExperimentsList).toHaveBeenCalledWith(
      expect.anything(),
      expect.objectContaining({
        page: 1,
        size: EXPORT_ROW_LIMIT,
        // Cells are truncated for display, so an export must opt out.
        truncate: false,
      }),
    );
  });

  it("refuses a result set over the cap", async () => {
    mockGetCompareExperimentsList.mockResolvedValue({
      content: rows(EXPORT_ROW_LIMIT),
      total: EXPORT_ROW_LIMIT + 1,
    });

    await expect(getAllCompareExperimentsItems(PARAMS)).rejects.toBeInstanceOf(
      ExportTooLargeError,
    );
  });

  it("allows a result set exactly at the cap", async () => {
    mockGetCompareExperimentsList.mockResolvedValue({
      content: rows(EXPORT_ROW_LIMIT),
      total: EXPORT_ROW_LIMIT,
    });

    await expect(getAllCompareExperimentsItems(PARAMS)).resolves.toHaveLength(
      EXPORT_ROW_LIMIT,
    );
  });

  it("fails rather than writing a file with fewer rows than the table showed", async () => {
    mockGetCompareExperimentsList.mockResolvedValue({
      content: rows(40),
      total: 50,
    });

    await expect(getAllCompareExperimentsItems(PARAMS)).rejects.toThrow(
      "returned 40 of 50 rows",
    );
  });

  it("rejects a response missing content or total", async () => {
    mockGetCompareExperimentsList.mockResolvedValue({});

    await expect(getAllCompareExperimentsItems(PARAMS)).rejects.toThrow(
      "unexpected response",
    );
  });

  it("returns nothing for an empty result set", async () => {
    mockGetCompareExperimentsList.mockResolvedValue({ content: [], total: 0 });

    await expect(getAllCompareExperimentsItems(PARAMS)).resolves.toEqual([]);
  });
});
