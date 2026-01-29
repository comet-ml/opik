import React from "react";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import useRulesBatchDeleteMutation from "@/api/automations/useRulesBatchDeleteMutation";
import { EvaluatorsRule } from "@/types/automations";
import { useRowActionsState } from "@/components/shared/DataTable/hooks/useRowActionsState";
import { RowActionsButtons } from "@/components/shared/DataTable/RowActionsButtons";
import useAppStore from "@/store/AppStore";

interface RuleRowActionsProps {
  rule: EvaluatorsRule;
  openEditDialog: (ruleId: string) => void;
  openCloneDialog: (ruleId: string) => void;
}

const RuleRowActions: React.FC<RuleRowActionsProps> = ({
  rule,
  openEditDialog,
  openCloneDialog,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { dialogOpen, open, close } = useRowActionsState();
  const { mutate } = useRulesBatchDeleteMutation();

  const handleShowLogs = () => {
    window.open(
      `/${workspaceName}/automation-logs?rule_id=${rule.id}`,
      "_blank",
    );
  };

  const handleDelete = () => {
    mutate({ ids: [rule.id] });
  };

  const handleEdit = () => {
    openEditDialog(rule.id);
  };

  const handleClone = () => {
    openCloneDialog(rule.id);
  };

  return (
    <>
      <ConfirmDialog
        open={dialogOpen === "delete"}
        setOpen={close}
        onConfirm={handleDelete}
        title="Delete evaluation rule"
        description={`Deleting an online evaluation rule will stop scoring for all new traces. Existing traces that have already been scored won't be affected. This action can't be undone. Are you sure you want to continue?

Tip: To pause scoring without deleting, disable the rule.`}
        confirmText="Delete evaluation rule"
        confirmButtonVariant="destructive"
      />
      <RowActionsButtons
        actions={[
          {
            type: "external",
            label: "Show logs",
            showLabel: true,
            onClick: handleShowLogs,
          },
          { type: "edit", onClick: handleEdit },
          { type: "duplicate", label: "Clone", onClick: handleClone },
          { type: "delete", onClick: open("delete") },
        ]}
      />
    </>
  );
};

export default RuleRowActions;
