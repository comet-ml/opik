import React from "react";
import { Label } from "@/ui/label";
import { Input } from "@/ui/input";
import DatasetVariablesHint from "./DatasetVariablesHint";

type ReferenceKeyFieldProps = {
  value: string;
  onChange: (value: string) => void;
  datasetVariables?: string[];
  placeholder?: string;
};

/**
 * Shared "Reference key" input + dataset-variable hint used by the metrics that
 * compare against a dataset column (Equals, JSON Schema, Levenshtein, Numerical
 * Similarity). Keeps the label, input, and hint in one place so styling and the
 * hint behavior stay consistent across all of them.
 */
const ReferenceKeyField = ({
  value,
  onChange,
  datasetVariables = [],
  placeholder = "e.g., answer or $.scores[?(@.name=='Useful')].value",
}: ReferenceKeyFieldProps) => (
  <div className="space-y-2">
    <Label htmlFor="reference_key" className="text-sm">
      Reference key
    </Label>
    <Input
      id="reference_key"
      dimension="sm"
      placeholder={placeholder}
      value={value}
      onChange={(e) => onChange(e.target.value)}
    />
    <DatasetVariablesHint
      datasetVariables={datasetVariables}
      onSelect={onChange}
    />
  </div>
);

export default ReferenceKeyField;
