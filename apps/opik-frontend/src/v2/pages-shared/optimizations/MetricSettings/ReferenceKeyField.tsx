import React from "react";
import { cn } from "@/lib/utils";
import { Label } from "@/ui/label";
import { Input } from "@/ui/input";
import { FormErrorSkeleton } from "@/ui/form";
import DatasetVariablesHint from "./DatasetVariablesHint";

type ReferenceKeyFieldProps = {
  value: string;
  onChange: (value: string) => void;
  datasetVariables?: string[];
  placeholder?: string;
  error?: string;
};

const ReferenceKeyField = ({
  value,
  onChange,
  datasetVariables = [],
  placeholder = "e.g., answer or $.scores[?(@.name=='Useful')].value",
  error,
}: ReferenceKeyFieldProps) => (
  <div className="space-y-1">
    <Label htmlFor="reference_key" className="text-sm">
      Reference key
    </Label>
    <Input
      id="reference_key"
      dimension="sm"
      placeholder={placeholder}
      value={value}
      onChange={(e) => onChange(e.target.value)}
      className={cn(error && "border-destructive")}
    />
    {error && <FormErrorSkeleton>{error}</FormErrorSkeleton>}
    <DatasetVariablesHint
      datasetVariables={datasetVariables}
      onSelect={onChange}
    />
  </div>
);

export default ReferenceKeyField;
