import React from "react";
import { UseFormReturn } from "react-hook-form";
import { EvaluationRuleFormType } from "@/components/pages/TracesPage/RulesTab/AddEditRuleDialog/schema";

type PythonCodeRuleDetailsProps = {
  form?: UseFormReturn<EvaluationRuleFormType>;
};

const PythonCodeRuleDetails: React.FC<PythonCodeRuleDetailsProps> = () => {
  return <div>TO BE IMPLEMENTED</div>;
};

export default PythonCodeRuleDetails;
