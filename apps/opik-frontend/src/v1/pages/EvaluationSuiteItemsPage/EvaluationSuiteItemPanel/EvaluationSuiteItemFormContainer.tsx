import React, { useEffect } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";

import { Form } from "@/ui/form";
import { ExecutionPolicy } from "@/types/evaluation-suites";
import EvaluationSuiteItemForm from "./EvaluationSuiteItemForm";
import {
  evaluationSuiteItemFormSchema,
  EvaluationSuiteItemFormValues,
} from "./evaluationSuiteItemFormSchema";

interface EvaluationSuiteItemFormContainerProps {
  initialValues: EvaluationSuiteItemFormValues;
  suiteAssertions: string[];
  suitePolicy: ExecutionPolicy;
  onOpenSettings: () => void;
  onFormChange?: (
    values: EvaluationSuiteItemFormValues,
    changedField?: string,
  ) => void;
  setHasUnsavedChanges?: (v: boolean) => void;
  formId?: string;
  onSubmit?: (values: EvaluationSuiteItemFormValues) => void;
  showDataDescription?: boolean;
}

const EvaluationSuiteItemFormContainer: React.FC<
  EvaluationSuiteItemFormContainerProps
> = ({
  initialValues,
  suiteAssertions,
  suitePolicy,
  onOpenSettings,
  onFormChange,
  setHasUnsavedChanges,
  formId,
  onSubmit,
  showDataDescription,
}) => {
  const form = useForm<EvaluationSuiteItemFormValues>({
    resolver: zodResolver(evaluationSuiteItemFormSchema),
    defaultValues: initialValues,
    mode: "onChange",
  });

  useEffect(() => {
    if (!onFormChange) return;

    const subscription = form.watch((value, { name }) => {
      onFormChange(value as EvaluationSuiteItemFormValues, name);
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
        <EvaluationSuiteItemForm
          suiteAssertions={suiteAssertions}
          suitePolicy={suitePolicy}
          onOpenSettings={onOpenSettings}
          showDataDescription={showDataDescription}
        />
      </form>
    </Form>
  );
};

export default EvaluationSuiteItemFormContainer;
