import React from "react";
import { describe, it, expect, vi, beforeEach } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";

import PermissionsTestProvider from "@/testing/PermissionsTestProvider";
import { Dataset, DATASET_TYPE } from "@/types/datasets";
import { Permissions } from "@/types/permissions";
import useDatasetForm from "./useDatasetForm";

type MutateOptions = {
  onSuccess?: (data?: unknown) => void;
};

const NEW_DATASET = { id: "dataset-id", name: "my-dataset" } as Dataset;

const toast = vi.fn();
const post = vi.fn();
const changesMutate = vi.fn(
  (_: unknown, options?: { onSettled?: () => void }) => options?.onSettled?.(),
);

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
  default: () => ({
    mutate: (...args: Parameters<typeof changesMutate>) =>
      changesMutate(...args),
  }),
}));

type SetupOptions = {
  permissions?: Partial<Permissions>;
  datasetType?: DATASET_TYPE;
  skipEvaluationCriteria?: boolean;
  csvRequired?: boolean;
};

const setupForm = ({
  permissions = {},
  datasetType = DATASET_TYPE.DATASET,
  skipEvaluationCriteria = true,
  csvRequired,
}: SetupOptions = {}) => {
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
        skipEvaluationCriteria,
        csvRequired,
        datasetType,
      }),
    {
      wrapper: ({ children }) =>
        React.createElement(
          PermissionsTestProvider,
          { permissions },
          React.createElement(QueryClientProvider, { client: queryClient }, children),
        ),
    },
  );

  return { result, setOpen, onDatasetCreated, onCreateSuccess };
};

const createWithFile = (file: File) => {
  const { result, setOpen, onDatasetCreated, onCreateSuccess } = setupForm();

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
  changesMutate.mockClear();
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

describe("useDatasetForm without dataset edit permission", () => {
  const permissions = { canEditDatasets: false };
  const csv = new File(["input\na\n"], "items.csv");

  it("reports upload hidden and items not editable", () => {
    const { result } = setupForm({ permissions });

    expect(result.current.hideUpload).toBe(true);
    expect(result.current.canEditDatasets).toBe(false);
  });

  it("does not block creation on a required CSV when upload is hidden", () => {
    const { result } = setupForm({ permissions, csvRequired: true });

    act(() => result.current.setName("my-dataset"));

    expect(result.current.isValid).toBe(true);
  });

  it("creates without uploading a selected file", async () => {
    post.mockResolvedValue({ data: undefined });
    const { result, onCreateSuccess } = setupForm({ permissions });

    act(() => result.current.handleFileSelect(csv));
    act(() => result.current.submitHandler());

    await waitFor(() => expect(onCreateSuccess).toHaveBeenCalled());
    expect(post).not.toHaveBeenCalled();
  });

  it("does not save assertions or run policy for a test suite", async () => {
    const { result, onCreateSuccess } = setupForm({
      permissions,
      datasetType: DATASET_TYPE.TEST_SUITE,
      skipEvaluationCriteria: false,
    });

    act(() => {
      result.current.setAssertions(["Response is polite"]);
      result.current.setRunsPerItem(3);
      result.current.setPassThreshold(2);
    });
    act(() => result.current.submitHandler());

    await waitFor(() => expect(onCreateSuccess).toHaveBeenCalled());
    expect(changesMutate).not.toHaveBeenCalled();
    expect(post).not.toHaveBeenCalled();
  });
});

describe("useDatasetForm with dataset edit permission", () => {
  it("reports upload visible and items editable", () => {
    const { result } = setupForm();

    expect(result.current.hideUpload).toBe(false);
    expect(result.current.canEditDatasets).toBe(true);
  });

  it("requires a CSV when csvRequired is set", () => {
    const { result } = setupForm({ csvRequired: true });

    act(() => result.current.setName("my-dataset"));

    expect(result.current.isValid).toBeFalsy();
  });

  it("saves assertions and run policy for a test suite", async () => {
    const { result, onCreateSuccess } = setupForm({
      datasetType: DATASET_TYPE.TEST_SUITE,
      skipEvaluationCriteria: false,
    });

    act(() => {
      result.current.setAssertions(["Response is polite"]);
      result.current.setRunsPerItem(3);
      result.current.setPassThreshold(2);
    });
    act(() => result.current.submitHandler());

    await waitFor(() => expect(onCreateSuccess).toHaveBeenCalled());
    expect(changesMutate).toHaveBeenCalledTimes(1);
    expect(changesMutate.mock.calls[0][0]).toMatchObject({
      datasetId: NEW_DATASET.id,
      payload: { execution_policy: { runs_per_item: 3, pass_threshold: 2 } },
    });
  });
});
