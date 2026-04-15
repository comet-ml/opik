import React, { useMemo } from "react";

import { DATASET_ITEM_SOURCE, DatasetItemColumn } from "@/types/datasets";
import useTestSuiteDraftStore, {
  useAddItem,
} from "@/store/TestSuiteDraftStore";
import { useEffectiveSuiteAssertions } from "@/hooks/useEffectiveSuiteAssertions";
import { useEffectiveExecutionPolicy } from "@/hooks/useEffectiveExecutionPolicy";
import { useDatasetEntityIdFromURL } from "@/v2/hooks/useDatasetEntityIdFromURL";
import { TEST_SUITE_ITEM_PREFILLED_DATA } from "@/constants/test-suites";
import AddItemPanelWrapper from "@/v2/pages-shared/datasets/DatasetItemPanel/AddItemPanelWrapper";
import TestSuiteItemFormContainer from "./TestSuiteItemFormContainer";
import {
  TestSuiteItemFormValues,
  fromFormValues,
} from "./testSuiteItemFormSchema";

const ADD_SUITE_ITEM_FORM_ID = "add-test-suite-item-form";

interface TestSuiteItemFormContentProps {
  columns: DatasetItemColumn[];
  tags: string[];
  setHasUnsavedChanges: (v: boolean) => void;
  onClose: () => void;
  onOpenSettings: () => void;
}

const TestSuiteItemFormContent: React.FC<TestSuiteItemFormContentProps> = ({
  columns,
  tags,
  setHasUnsavedChanges,
  onClose,
  onOpenSettings,
}) => {
  const addItem = useAddItem();
  const setItemAssertionsInStore = useTestSuiteDraftStore(
    (s) => s.setItemAssertions,
  );

  const suiteId = useDatasetEntityIdFromURL();
  const suiteAssertions = useEffectiveSuiteAssertions(suiteId);
  const suitePolicy = useEffectiveExecutionPolicy(suiteId);
  const isEmptyDataset = columns.length === 0;

  const initialData = useMemo(
    () => Object.fromEntries(columns.map((col) => [col.name, ""])),
    [columns],
  );

  const initialValues: TestSuiteItemFormValues = useMemo(
    () => ({
      description: "",
      data: isEmptyDataset
        ? TEST_SUITE_ITEM_PREFILLED_DATA
        : JSON.stringify(initialData, null, 2),
      assertions: [],
      runsPerItem: suitePolicy.runs_per_item,
      passThreshold: suitePolicy.pass_threshold,
      useGlobalPolicy: true,
    }),
    [isEmptyDataset, initialData, suitePolicy],
  );

  const onValidSubmit = (values: TestSuiteItemFormValues) => {
    const { description, data, assertions, policy } = fromFormValues(values);

    const now = new Date().toISOString();
    const saveData = data ?? initialData;
    const policyChanged = policy != null;

    const tempId = addItem({
      data: saveData,
      description: description || undefined,
      source: DATASET_ITEM_SOURCE.manual,
      tags,
      created_at: now,
      last_updated_at: now,
      ...(policyChanged ? { execution_policy: policy } : {}),
    });
    if (assertions.length > 0) {
      setItemAssertionsInStore(tempId, assertions);
    }
    onClose();
  };

  return (
    <TestSuiteItemFormContainer
      formId={ADD_SUITE_ITEM_FORM_ID}
      initialValues={initialValues}
      suiteAssertions={suiteAssertions}
      suitePolicy={suitePolicy}
      onOpenSettings={onOpenSettings}
      onSubmit={onValidSubmit}
      setHasUnsavedChanges={setHasUnsavedChanges}
    />
  );
};

interface AddTestSuiteItemPanelProps {
  open: boolean;
  onClose: () => void;
  columns: DatasetItemColumn[];
  onOpenSettings: () => void;
}

const AddTestSuiteItemPanel: React.FC<AddTestSuiteItemPanelProps> = ({
  open,
  onClose,
  columns,
  onOpenSettings,
}) => (
  <AddItemPanelWrapper
    panelId="test-suite-item-panel"
    formId={ADD_SUITE_ITEM_FORM_ID}
    open={open}
    onClose={onClose}
    initialWidth={0.4}
  >
    {({ tags, setHasUnsavedChanges }) => (
      <TestSuiteItemFormContent
        columns={columns}
        tags={tags}
        setHasUnsavedChanges={setHasUnsavedChanges}
        onClose={onClose}
        onOpenSettings={onOpenSettings}
      />
    )}
  </AddItemPanelWrapper>
);

export default AddTestSuiteItemPanel;
