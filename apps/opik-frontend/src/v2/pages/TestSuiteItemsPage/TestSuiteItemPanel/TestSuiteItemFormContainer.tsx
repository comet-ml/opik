import React, { useEffect } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";

import { Form } from "@/ui/form";
import { ExecutionPolicy } from "@/types/test-suites";
import TestSuiteItemForm from "./TestSuiteItemForm";
import {
  testSuiteItemFormSchema,
  TestSuiteItemFormValues,
} from "./testSuiteItemFormSchema";

interface TestSuiteItemFormContainerProps {
  initialValues: TestSuiteItemFormValues;
  suiteAssertions: string[];
  suitePolicy: ExecutionPolicy;
  onOpenSettings: () => void;
  onFormChange?: (
    values: TestSuiteItemFormValues,
    changedField?: string,
  ) => void;
  setHasUnsavedChanges?: (v: boolean) => void;
  formId?: string;
  onSubmit?: (values: TestSuiteItemFormValues) => void;
  showEvaluationCriteria?: boolean;
}

const TestSuiteItemFormContainer: React.FC<TestSuiteItemFormContainerProps> = ({
  initialValues,
  suiteAssertions,
  suitePolicy,
  onOpenSettings,
  onFormChange,
  setHasUnsavedChanges,
  formId,
  onSubmit,
  showEvaluationCriteria,
}) => {
  const form = useForm<TestSuiteItemFormValues>({
    resolver: zodResolver(testSuiteItemFormSchema),
    defaultValues: initialValues,
    mode: "onChange",
  });

  useEffect(() => {
    if (!onFormChange) return;

    const subscription = form.watch((value, { name }) => {
      onFormChange(value as TestSuiteItemFormValues, name);
    });

    return () => subscription.unsubscribe();
  }, [form, onFormChange]);

  useEffect(() => {
    if (setHasUnsavedChanges) {
      setHasUnsavedChanges(form.formState.isDirty);
    }
  }, [form.formState.isDirty, setHasUnsavedChanges]);

  return (
    <Form {...form}>
      <form id={formId} onSubmit={onSubmit && form.handleSubmit(onSubmit)}>
        <TestSuiteItemForm
          suiteAssertions={suiteAssertions}
          suitePolicy={suitePolicy}
          onOpenSettings={onOpenSettings}
          showEvaluationCriteria={showEvaluationCriteria}
        />
      </form>
    </Form>
  );
};

export default TestSuiteItemFormContainer;
