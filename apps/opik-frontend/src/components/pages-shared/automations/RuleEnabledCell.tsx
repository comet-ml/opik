import React from "react";
import { Switch } from "@/components/ui/switch";
import { EvaluatorsRule } from "@/types/automations";
import useRuleUpdateMutation from "@/api/automations/useRuleUpdateMutation";
import { useToast } from "@/components/ui/use-toast";

interface RuleEnabledCellProps {
  rule: EvaluatorsRule;
}

const RuleEnabledCell: React.FC<RuleEnabledCellProps> = ({ rule }) => {
  const { toast } = useToast();
  const updateRuleMutation = useRuleUpdateMutation();

  const handleToggle = async (enabled: boolean) => {
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
        description: `Rule "${rule.name}" has been ${enabled ? "enabled" : "disabled"}.`,
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
    <Switch
      checked={rule.enabled}
      onCheckedChange={handleToggle}
      disabled={updateRuleMutation.isPending}
    />
  );
};

export default RuleEnabledCell;