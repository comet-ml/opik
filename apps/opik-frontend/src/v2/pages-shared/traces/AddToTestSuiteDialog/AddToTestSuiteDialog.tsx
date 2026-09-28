import React, { useCallback, useEffect, useMemo, useState } from "react";

import { Span, Trace } from "@/types/traces";
import { DATASET_TYPE } from "@/types/datasets";
import { DEFAULT_EXECUTION_POLICY } from "@/types/test-suites";
import useDatasetVersionsList from "@/api/datasets/useDatasetVersionsList";
import { extractAssertions, packAssertions } from "@/lib/assertion-converters";
import EvaluationCriteriaSection from "@/shared/EvaluationCriteriaSection/EvaluationCriteriaSection";
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
  const [runsPerItem, setRunsPerItem] = useState(
    DEFAULT_EXECUTION_POLICY.runs_per_item,
  );
  const [passThreshold, setPassThreshold] = useState(
    DEFAULT_EXECUTION_POLICY.pass_threshold,
  );
  const [useGlobalPolicy, setUseGlobalPolicy] = useState(true);

  const handleDatasetChange = useCallback(() => {
    setAssertions([]);
    setRunsPerItem(DEFAULT_EXECUTION_POLICY.runs_per_item);
    setPassThreshold(DEFAULT_EXECUTION_POLICY.pass_threshold);
    setUseGlobalPolicy(true);
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

  const { data: versionsData } = useDatasetVersionsList(
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

  useEffect(() => {
    if (!selectedDataset?.id) return;
    setRunsPerItem(suiteExecutionPolicy.runs_per_item);
    setPassThreshold(suiteExecutionPolicy.pass_threshold);
    setUseGlobalPolicy(true);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedDataset?.id]);

  const handleAssertionChange = useCallback((index: number, value: string) => {
    setAssertions((prev) => prev.map((a, i) => (i === index ? value : a)));
  }, []);

  const handleAssertionRemove = useCallback((index: number) => {
    setAssertions((prev) => prev.filter((_, i) => i !== index));
  }, []);

  const handleAssertionAdd = useCallback(() => {
    setAssertions((prev) => ["", ...prev]);
  }, []);

  const handleRunsPerItemChange = useCallback(
    (v: number) => {
      setUseGlobalPolicy(false);
      setRunsPerItem(v);
      if (passThreshold > v) setPassThreshold(v);
    },
    [passThreshold],
  );

  const handlePassThresholdChange = useCallback((v: number) => {
    setUseGlobalPolicy(false);
    setPassThreshold(v);
  }, []);

  const handleRevertToDefaults = useCallback(() => {
    setUseGlobalPolicy(true);
    setRunsPerItem(suiteExecutionPolicy.runs_per_item);
    setPassThreshold(suiteExecutionPolicy.pass_threshold);
  }, [suiteExecutionPolicy]);

  const buildEvaluatorParams = useCallback(() => {
    const nonEmptyAssertions = assertions.map((a) => a.trim()).filter(Boolean);
    const hasCustomPolicy =
      runsPerItem !== suiteExecutionPolicy.runs_per_item ||
      passThreshold !== suiteExecutionPolicy.pass_threshold;

    if (nonEmptyAssertions.length === 0 && !hasCustomPolicy) return {};

    return {
      ...(nonEmptyAssertions.length > 0 && {
        evaluators: [packAssertions(nonEmptyAssertions)],
      }),
      ...(hasCustomPolicy && {
        executionPolicy: {
          runs_per_item: runsPerItem,
          pass_threshold: passThreshold,
        },
      }),
    };
  }, [assertions, runsPerItem, passThreshold, suiteExecutionPolicy]);

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
              <EvaluationCriteriaSection
                suiteAssertions={suiteAssertions}
                editableAssertions={assertions}
                onChangeAssertion={handleAssertionChange}
                onRemoveAssertion={handleAssertionRemove}
                onAddAssertion={handleAssertionAdd}
                runsPerItem={runsPerItem}
                passThreshold={passThreshold}
                onRunsPerItemChange={handleRunsPerItemChange}
                onPassThresholdChange={handlePassThresholdChange}
                useGlobalPolicy={useGlobalPolicy}
                onRevertToDefaults={handleRevertToDefaults}
                defaultRunsPerItem={suiteExecutionPolicy.runs_per_item}
                defaultPassThreshold={suiteExecutionPolicy.pass_threshold}
              />
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
