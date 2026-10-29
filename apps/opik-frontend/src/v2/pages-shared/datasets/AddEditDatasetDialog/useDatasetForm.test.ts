import React from "react";
import { describe, it, expect, vi, beforeEach } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";

import { Dataset, DATASET_TYPE } from "@/types/datasets";
import useDatasetForm from "./useDatasetForm";

type MutateOptions = {
  onSuccess?: (data?: unknown) => void;
};

const NEW_DATASET = { id: "dataset-id", name: "my-dataset" } as Dataset;

const toast = vi.fn();
const post = vi.fn();

vi.mock("@/api/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/api/api")>()),
  default: { post: (...args: unknown[]) => post(...args) },
}));

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

vi.mock("@/api/datasets/useDatasetUpdateMutation", () => ({
  default: () => ({ mutate: vi.fn() }),
}));

vi.mock("@/api/datasets/useDatasetItemChangesMutation", () => ({
  default: () => ({ mutate: vi.fn() }),
}));

const createWithFile = (file: File) => {
  const setOpen = vi.fn();
  const onDatasetCreated = vi.fn();
  const onCreateSuccess = vi.fn();
  const queryClient = new QueryClient({
    defaultOptions: { mutations: { retry: false } },
  });

  const { result } = renderHook(
    () =>
      useDatasetForm({
        open: true,
        setOpen,
        onDatasetCreated,
        onCreateSuccess,
        skipEvaluationCriteria: true,
        datasetType: DATASET_TYPE.DATASET,
      }),
    {
      wrapper: ({ children }) =>
        React.createElement(
          QueryClientProvider,
          { client: queryClient },
          children,
        ),
    },
  );

  act(() => {
    result.current.handleFileSelect(file);
  });
  act(() => {
    result.current.submitHandler();
  });

  return { setOpen, onDatasetCreated, onCreateSuccess };
};

beforeEach(() => {
  toast.mockClear();
  post.mockReset();
});

const FILES = [
  { label: "CSV", file: new File(["input\na\n"], "items.csv") },
  { label: "JSON", file: new File(['[{"input":"a"}]'], "items.json") },
];

describe.each(FILES)(
  "useDatasetForm create with $label upload",
  ({ label, file }) => {
    it("calls onCreateSuccess when the upload is accepted", async () => {
      post.mockResolvedValue({ data: undefined });
      const { onCreateSuccess, onDatasetCreated } = createWithFile(file);

      await waitFor(() =>
        expect(onCreateSuccess).toHaveBeenCalledWith(
          NEW_DATASET,
          expect.any(Function),
        ),
      );
      expect(onDatasetCreated).not.toHaveBeenCalled();
    });

    it("shows one error toast, skips onCreateSuccess and opens the dataset when the upload is rejected", async () => {
      post.mockRejectedValue(new Error("File failed validation"));
      const { onCreateSuccess, onDatasetCreated, setOpen } =
        createWithFile(file);

      await waitFor(() =>
        expect(onDatasetCreated).toHaveBeenCalledWith(NEW_DATASET),
      );
      expect(onCreateSuccess).not.toHaveBeenCalled();
      expect(setOpen).toHaveBeenCalledWith(false);
      expect(toast).toHaveBeenCalledTimes(1);
      expect(toast).toHaveBeenCalledWith(
        expect.objectContaining({
          title: `Error uploading ${label} file`,
          variant: "destructive",
        }),
      );
    });
  },
);
