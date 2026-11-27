import { Pencil, Copy, Trash, ScrollText, ListTree } from "lucide-react";
import React, { useCallback, useRef, useState } from "react";
import { EvaluatorsRule } from "@/types/automations";
import { CellContext } from "@tanstack/react-table";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import useRulesBatchDeleteMutation from "@/api/automations/useRulesBatchDeleteMutation";
import RowActions, { RowAction } from "@/shared/DataTable/RowActions";
import useAppStore from "@/store/AppStore";
import { COLUMN_TYPE } from "@/types/shared";
import { useTraceLogsSidebarControls } from "@/v2/pages-shared/traces/TraceLogsSidebar/useTraceLogsSidebarControls";

interface RuleRowActionsCellProps {
  canEdit: boolean;
  openEditDialog: (ruleId: string) => void;
  openCloneDialog: (ruleId: string) => void;
}

const RuleRowActionsCell: React.FC<
  RuleRowActionsCellProps & CellContext<EvaluatorsRule, unknown>
> = ({ canEdit, openEditDialog, openCloneDialog, row }) => {
  const resetKeyRef = useRef(0);
  const rule = row.original;
  const [open, setOpen] = useState<boolean | number>(false);
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const { mutate } = useRulesBatchDeleteMutation();

  const deleteRuleHandler = useCallback(() => {
    mutate({
      ids: [rule.id],
    });
  }, [rule.id, mutate]);

  const { openSidebar } = useTraceLogsSidebarControls();
  const projectId =
    rule.projects?.[0]?.project_id ?? rule.project_id ?? rule.project_ids?.[0];

  // Opens the page-level EvaluationTracesSidebar locked to this rule's traces
  // (same behaviour as the former Traces column).
  const openTraces = () =>
    openSidebar(
      [
        {
          id: `rule-traces-${rule.id}`,
          field: "metadata",
          key: "rule_id",
          type: COLUMN_TYPE.string,
          operator: "=",
          value: rule.id,
        },
      ],
      { locked: true, label: rule.name },
    );

  const actions: RowAction[] = [
    ...(projectId
      ? [
          {
            id: "traces",
            label: "Traces",
            icon: ListTree,
            showLabel: true,
            onClick: openTraces,
          },
        ]
      : []),
    {
      id: "logs",
      label: "Execution logs",
      icon: ScrollText,
      showLabel: true,
      link: {
        to: "/$workspaceName/automation-logs",
        params: { workspaceName },
        search: { rule_id: rule.id },
        target: "_blank",
      },
    },
    ...(canEdit
      ? [
          {
            id: "edit",
            label: "Edit",
            icon: Pencil,
            onClick: () => openEditDialog(rule.id),
          },
          {
            id: "clone",
            label: "Clone",
            icon: Copy,
            onClick: () => openCloneDialog(rule.id),
          },
          {
            id: "delete",
            label: "Delete",
            icon: Trash,
            destructive: true,
            onClick: () => {
              setOpen(1);
              resetKeyRef.current = resetKeyRef.current + 1;
            },
          },
        ]
      : []),
  ];

  return (
    <>
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={open === 1}
        setOpen={setOpen}
        onConfirm={deleteRuleHandler}
        title="Delete evaluation rule"
        description={`Deleting an online evaluation rule will stop scoring for all new traces. Existing traces that have already been scored won’t be affected. This action can’t be undone. Are you sure you want to continue?

Tip: To pause scoring without deleting, disable the rule.`}
        confirmText="Delete evaluation rule"
        confirmButtonVariant="destructive"
      />
      <RowActions actions={actions} />
    </>
  );
};

export default RuleRowActionsCell;
