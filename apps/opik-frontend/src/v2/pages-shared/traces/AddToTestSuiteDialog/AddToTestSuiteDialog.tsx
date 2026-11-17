import React, { useCallback, useMemo, useState } from "react";
import isEqual from "lodash/isEqual";

import { Span, Trace } from "@/types/traces";
import { DATASET_TYPE } from "@/types/datasets";
import { DEFAULT_EXECUTION_POLICY, ExecutionPolicy } from "@/types/test-suites";
import useDatasetVersionsList from "@/api/datasets/useDatasetVersionsList";
import { extractAssertions, packAssertions } from "@/lib/assertion-converters";
import EvaluationCriteriaSection from "@/shared/EvaluationCriteriaSection/EvaluationCriteriaSection";
import Loader from "@/shared/Loader/Loader";
import AddEditTestSuiteDialog from "@/v2/pages-shared/datasets/AddEditTestSuiteDialog/AddEditTestSuiteDialog";
import {
  Dialog,
  DialogAutoScrollBody,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/ui/dialog";
import { Button } from "@/ui/button";
import DatasetPickerSection from "@/v2/pages-shared/traces/AddToDatasetDialog/DatasetPickerSection";
import useAddToDatasetForm from "@/v2/pages-shared/traces/AddToDatasetDialog/useAddToDatasetForm";

type AddToTestSuiteDialogProps = {
  selectedRows: Array<Trace | Span>;
  open: boolean;
  setOpen: (open: boolean) => void;
};

const AddToTestSuiteDialog: React.FunctionComponent<
  AddToTestSuiteDialogProps
> = ({ selectedRows, open, setOpen }) => {
  const [assertions, setAssertions] = useState<string[]>([]);
  // null means the item inherits the suite policy
  const [customPolicy, setCustomPolicy] = useState<ExecutionPolicy | null>(
    null,
  );

  const handleDatasetChange = useCallback(() => {
    setAssertions([]);
    setCustomPolicy(null);
  }, []);

  const form = useAddToDatasetForm({
    selectedRows,
    open,
    setOpen,
    datasetType: DATASET_TYPE.TEST_SUITE,
    getSubmitExtras: () => buildEvaluatorParams(),
    onDatasetChange: handleDatasetChange,
  });

  const { selectedDataset } = form;

  const { data: versionsData, isPending: isSuiteVersionPending } =
    useDatasetVersionsList(
      {
        datasetId: selectedDataset?.id ?? "",
        page: 1,
        size: 1,
      },
      {
        enabled: Boolean(selectedDataset?.id),
      },
    );

  const suiteAssertions = useMemo(() => {
    const evaluators = versionsData?.content?.[0]?.evaluators ?? [];
    return extractAssertions(evaluators);
  }, [versionsData]);

  const suiteExecutionPolicy = useMemo(() => {
    return (
      versionsData?.content?.[0]?.execution_policy ?? DEFAULT_EXECUTION_POLICY
    );
  }, [versionsData]);

  const policy = customPolicy ?? suiteExecutionPolicy;

  const handleAssertionChange = useCallback((index: number, value: string) => {
    setAssertions((prev) => prev.map((a, i) => (i === index ? value : a)));
  }, []);

  const handleAssertionRemove = useCallback((index: number) => {
    setAssertions((prev) => prev.filter((_, i) => i !== index));
  }, []);

  const handleAssertionAdd = useCallback(() => {
    setAssertions((prev) => ["", ...prev]);
  }, []);

  // Functional update: the section can change both fields in the same tick
  const updateCustomPolicy = useCallback(
    (change: Partial<ExecutionPolicy>) => {
      setCustomPolicy((prev) => ({
        ...(prev ?? suiteExecutionPolicy),
        ...change,
      }));
    },
    [suiteExecutionPolicy],
  );

  const handleRunsPerItemChange = useCallback(
    (runs_per_item: number) => updateCustomPolicy({ runs_per_item }),
    [updateCustomPolicy],
  );

  const handlePassThresholdChange = useCallback(
    (pass_threshold: number) => updateCustomPolicy({ pass_threshold }),
    [updateCustomPolicy],
  );

  const handleRevertToDefaults = useCallback(() => {
    setCustomPolicy(null);
  }, []);

  const buildEvaluatorParams = useCallback(() => {
    const nonEmptyAssertions = assertions.map((a) => a.trim()).filter(Boolean);
    const policyOverride =
      customPolicy && !isEqual(customPolicy, suiteExecutionPolicy)
        ? customPolicy
        : null;

    if (nonEmptyAssertions.length === 0 && !policyOverride) return {};

    return {
      ...(nonEmptyAssertions.length > 0 && {
        evaluators: [packAssertions(nonEmptyAssertions)],
      }),
      ...(policyOverride && { executionPolicy: policyOverride }),
    };
  }, [assertions, customPolicy, suiteExecutionPolicy]);

  const { noValidRows, fetching, configSectionRef, submit } = form;

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-screen-sm">
        <DialogHeader>
          <DialogTitle>Add to test suite</DialogTitle>
        </DialogHeader>
        <DialogAutoScrollBody>
          <DatasetPickerSection
            datasetType={DATASET_TYPE.TEST_SUITE}
            form={form}
            renderCreateDialog={({ open, setOpen, onDatasetCreated }) => (
              <AddEditTestSuiteDialog
                open={open}
                setOpen={setOpen}
                onDatasetCreated={onDatasetCreated}
                hideUpload={true}
              />
            )}
          />
          {selectedDataset && (
            <div ref={configSectionRef} className="mt-6">
              {isSuiteVersionPending ? (
                <Loader className="min-h-32" />
              ) : (
                <EvaluationCriteriaSection
                  suiteAssertions={suiteAssertions}
                  editableAssertions={assertions}
                  onChangeAssertion={handleAssertionChange}
                  onRemoveAssertion={handleAssertionRemove}
                  onAddAssertion={handleAssertionAdd}
                  runsPerItem={policy.runs_per_item}
                  passThreshold={policy.pass_threshold}
                  onRunsPerItemChange={handleRunsPerItemChange}
                  onPassThresholdChange={handlePassThresholdChange}
                  useGlobalPolicy={customPolicy === null}
                  onRevertToDefaults={handleRevertToDefaults}
                  defaultRunsPerItem={suiteExecutionPolicy.runs_per_item}
                  defaultPassThreshold={suiteExecutionPolicy.pass_threshold}
                />
              )}
            </div>
          )}
        </DialogAutoScrollBody>
        <DialogFooter>
          <Button
            variant="outline"
            onClick={() => setOpen(false)}
            disabled={fetching}
          >
            Cancel
          </Button>
          <Button
            onClick={() => {
              if (selectedDataset) submit(selectedDataset);
            }}
            disabled={!selectedDataset || noValidRows || fetching}
          >
            Add to test suite
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default AddToTestSuiteDialog;
