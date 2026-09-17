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

const rowsPage = (from: number, count: number) =>
  Array.from({ length: count }, (_, index) => ({ id: `row-${from + index}` }));

describe("getAllCompareExperimentsItems", () => {
  beforeEach(() => {
    mockGetCompareExperimentsList.mockReset();
  });

  it("pages until the whole result set is read", async () => {
    mockGetCompareExperimentsList
      .mockResolvedValueOnce({ content: rowsPage(0, 100), total: 250 })
      .mockResolvedValueOnce({ content: rowsPage(100, 100), total: 250 })
      .mockResolvedValueOnce({ content: rowsPage(200, 50), total: 250 });

    const rows = await getAllCompareExperimentsItems(PARAMS);

    expect(rows).toHaveLength(250);
    expect(mockGetCompareExperimentsList).toHaveBeenCalledTimes(3);
    expect(rows.at(-1)).toEqual({ id: "row-249" });
  });

  it("requests untruncated rows, so cells are not cut short in the file", async () => {
    mockGetCompareExperimentsList.mockResolvedValue({
      content: rowsPage(0, 1),
      total: 1,
    });

    await getAllCompareExperimentsItems(PARAMS);

    expect(mockGetCompareExperimentsList).toHaveBeenCalledWith(
      expect.anything(),
      expect.objectContaining({ truncate: false }),
    );
  });

  it("refuses a result set over the cap after a single request", async () => {
    mockGetCompareExperimentsList.mockResolvedValue({
      content: rowsPage(0, 100),
      total: EXPORT_ROW_LIMIT + 1,
    });

    await expect(getAllCompareExperimentsItems(PARAMS)).rejects.toBeInstanceOf(
      ExportTooLargeError,
    );
    expect(mockGetCompareExperimentsList).toHaveBeenCalledTimes(1);
  });

  it("allows a result set exactly at the cap", async () => {
    mockGetCompareExperimentsList.mockResolvedValue({
      content: rowsPage(0, EXPORT_ROW_LIMIT),
      total: EXPORT_ROW_LIMIT,
    });

    const rows = await getAllCompareExperimentsItems(PARAMS);

    expect(rows).toHaveLength(EXPORT_ROW_LIMIT);
  });

  it("stops on an empty page rather than looping", async () => {
    mockGetCompareExperimentsList.mockResolvedValue({ content: [], total: 10 });

    const rows = await getAllCompareExperimentsItems(PARAMS);

    expect(rows).toHaveLength(0);
    expect(mockGetCompareExperimentsList).toHaveBeenCalledTimes(1);
  });
});
