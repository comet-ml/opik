import { describe, it, expect, vi, beforeEach } from "vitest";
import { act, renderHook } from "@testing-library/react";

import { Dataset, DATASET_TYPE } from "@/types/datasets";
import useDatasetForm from "./useDatasetForm";

type MutateOptions = {
  onSuccess?: (data?: unknown) => void;
  onError?: (error: unknown) => void;
  onSettled?: () => void;
};

const NEW_DATASET = { id: "dataset-id", name: "my-dataset" } as Dataset;

const toast = vi.fn();
let uploadSucceeds = true;

vi.mock("@/store/AppStore", () => ({
  useActiveProjectId: () => "project-id",
}));

vi.mock("@/ui/use-toast", () => ({
  useToast: () => ({ toast }),
}));

vi.mock("@/lib/analytics/tracking", () => ({
  OpikEvent: {},
  trackEvent: vi.fn(),
}));

vi.mock("@/api/datasets/useDatasetCreateMutation", () => ({
  default: () => ({
    mutate: (_: unknown, options: MutateOptions) =>
      options.onSuccess?.(NEW_DATASET),
  }),
}));

vi.mock("@/api/datasets/useDatasetItemsFromCsvMutation", () => ({
  default: () => ({
    mutate: (_: unknown, options: MutateOptions) => {
      if (uploadSucceeds) {
        options.onSuccess?.();
      } else {
        options.onError?.(new Error("CSV contains duplicate column header"));
      }
      options.onSettled?.();
    },
  }),
}));

vi.mock("@/api/datasets/useDatasetItemsFromJsonMutation", () => ({
  default: () => ({ mutate: vi.fn() }),
}));

vi.mock("@/api/datasets/useDatasetUpdateMutation", () => ({
  default: () => ({ mutate: vi.fn() }),
}));

vi.mock("@/api/datasets/useDatasetItemChangesMutation", () => ({
  default: () => ({ mutate: vi.fn() }),
}));

const createWithCsv = () => {
  const setOpen = vi.fn();
  const onDatasetCreated = vi.fn();
  const onCreateSuccess = vi.fn();

  const { result } = renderHook(() =>
    useDatasetForm({
      open: true,
      setOpen,
      onDatasetCreated,
      onCreateSuccess,
      skipEvaluationCriteria: true,
      datasetType: DATASET_TYPE.DATASET,
    }),
  );

  act(() => {
    result.current.handleFileSelect(
      new File(["input,input\na,b\n"], "items.csv", { type: "text/csv" }),
    );
  });
  act(() => {
    result.current.submitHandler();
  });

  return { setOpen, onDatasetCreated, onCreateSuccess };
};

beforeEach(() => {
  toast.mockClear();
  uploadSucceeds = true;
});

describe("useDatasetForm create with file upload", () => {
  it("calls onCreateSuccess when the upload is accepted", () => {
    const { onCreateSuccess, onDatasetCreated } = createWithCsv();

    expect(onCreateSuccess).toHaveBeenCalledWith(
      NEW_DATASET,
      expect.any(Function),
    );
    expect(onDatasetCreated).not.toHaveBeenCalled();
  });

  it("skips onCreateSuccess and opens the dataset when the upload is rejected", () => {
    uploadSucceeds = false;
    const { onCreateSuccess, onDatasetCreated, setOpen } = createWithCsv();

    expect(onCreateSuccess).not.toHaveBeenCalled();
    expect(onDatasetCreated).toHaveBeenCalledWith(NEW_DATASET);
    expect(setOpen).toHaveBeenCalledWith(false);
    expect(toast).toHaveBeenCalledTimes(1);
    expect(toast).toHaveBeenCalledWith(
      expect.objectContaining({
        title: "Error uploading CSV file",
        variant: "destructive",
      }),
    );
  });
});
