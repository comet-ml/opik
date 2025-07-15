import React from "react";
import { CellContext } from "@tanstack/react-table";
import { Switch } from "@/components/ui/switch";
import { EvaluatorsRule } from "@/types/automations";
import useRuleUpdateMutation from "@/api/automations/useRuleUpdateMutation";
import { useToast } from "@/components/ui/use-toast";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";

const RuleEnabledCell = (context: CellContext<EvaluatorsRule, unknown>) => {
  const { toast } = useToast();
  const updateRuleMutation = useRuleUpdateMutation();

  const rule = context.row.original;

  // Default to true if enabled property doesn't exist yet
  const isEnabled = rule?.enabled ?? true;

  const handleToggle = async (enabled: boolean) => {
    if (!rule) {
      console.error("Cannot update rule: rule object is undefined");
      return;
    }

    try {
      await updateRuleMutation.mutateAsync({
        ruleId: rule.id,
        rule: {
          ...rule,
          enabled,
        },
      });

      toast({
        title: "Rule updated",
        description: `Rule "${rule.name}" has been ${
          enabled ? "enabled" : "disabled"
        }.`,
      });
    } catch (error) {
      console.error("Failed to update rule:", error);
      toast({
        title: "Error",
        description: "Failed to update rule. Please try again.",
        variant: "destructive",
      });
    }
  };

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      stopClickPropagation
    >
      <Switch
        checked={isEnabled}
        onCheckedChange={handleToggle}
        disabled={updateRuleMutation.isPending}
      />
    </CellWrapper>
  );
};

export default RuleEnabledCell;
