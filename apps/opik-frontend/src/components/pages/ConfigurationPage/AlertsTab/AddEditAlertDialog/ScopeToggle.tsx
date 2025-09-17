import React from "react";
import { UseFormReturn } from "react-hook-form";

import { Label } from "@/components/ui/label";
import { FormField } from "@/components/ui/form";
import { Switch } from "@/components/ui/switch";
import { AlertFormType } from "./schema";

type ScopeToggleProps = {
  form: UseFormReturn<AlertFormType>;
  toggleFieldName: keyof AlertFormType["eventTriggers"];
  scopeFieldName: keyof AlertFormType["eventTriggers"];
};

const ScopeToggle: React.FunctionComponent<ScopeToggleProps> = ({
  form,
  toggleFieldName,
  scopeFieldName,
}) => {
  return (
    <div className="flex items-center justify-between">
      <Label className="text-sm font-medium text-slate-900">All projects</Label>
      <FormField
        control={form.control}
        name={`eventTriggers.${toggleFieldName}`}
        render={({ field }) => (
          <Switch
            checked={field.value as boolean}
            onCheckedChange={(checked) => {
              field.onChange(checked);

              // Clear project array when "all projects" is selected (true)
              if (checked) {
                form.setValue(
                  `eventTriggers.${scopeFieldName}` as keyof AlertFormType,
                  [] as never,
                );
              }
            }}
          />
        )}
      />
    </div>
  );
};

export default ScopeToggle;
