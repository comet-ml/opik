import React, { useState } from "react";
import { Bell, ChevronDown, LucideIcon, UserPen, Zap } from "lucide-react";

import { Button } from "@/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import { Link } from "@tanstack/react-router";
import useAppStore from "@/store/AppStore";
import { LOGS_TYPE } from "@/constants/traces";
import { ANNOTATION_QUEUE_SCOPE } from "@/types/annotation-queues";
import { EVALUATORS_RULE_SCOPE } from "@/types/automations";
import { usePermissions } from "@/contexts/PermissionsContext";
import AddEditAnnotationQueueDialog from "@/v2/pages-shared/annotation-queues/AddEditAnnotationQueueDialog";
import AddEditRuleDialog from "@/v2/pages-shared/automations/AddEditRuleDialog/AddEditRuleDialog";

/**
 * The three ways to automate something about what a project logs, gathered behind one button.
 *
 * <p>Each option opens the create form of its own feature, prefilled from the tab the user is on: an
 * annotation queue collecting threads when opened from Threads, traces when opened from Traces. The
 * annotation queue form also opens with its automation section switched on — arriving here is a
 * statement of intent, and having to hunt for the toggle afterwards would waste it.
 *
 * <p>Spans are deliberately absent from the annotation queue option: a span is never a queue item.
 */
type AutomationTarget = "alert" | "online_evaluation" | "annotation_queue";

type AutomationOption = {
  target: AutomationTarget;
  label: string;
  icon: LucideIcon;
  /** Icon plate colour, from the design tokens for these three features. */
  iconClassName: string;
  description: (subject: string) => string;
};

const AUTOMATION_OPTIONS: AutomationOption[] = [
  {
    target: "alert",
    label: "Alerts",
    icon: Bell,
    iconClassName: "bg-pink-500",
    description: (subject) =>
      `Get notified about ${subject} that need attention`,
  },
  {
    target: "online_evaluation",
    label: "Online evaluation",
    icon: Zap,
    iconClassName: "bg-teal-500",
    description: (subject) =>
      `Automatically score incoming ${subject} with evaluators`,
  },
  {
    target: "annotation_queue",
    label: "Annotation queue",
    icon: UserPen,
    iconClassName: "bg-lime-400",
    description: (subject) =>
      `Automatically collect matching ${subject} for human review`,
  },
];

type AddAutomationDropdownProps = {
  projectId: string;
  logsType: LOGS_TYPE;
};

const AddAutomationDropdown: React.FunctionComponent<
  AddAutomationDropdownProps
> = ({ projectId, logsType }) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const {
    permissions: { canCreateAnnotationQueues, canUpdateOnlineEvaluationRules },
  } = usePermissions();

  const [openTarget, setOpenTarget] = useState<AutomationTarget | null>(null);

  const isThreads = logsType === LOGS_TYPE.threads;
  const isSpans = logsType === LOGS_TYPE.spans;
  const subject = isThreads ? "threads" : isSpans ? "spans" : "traces";

  const options = AUTOMATION_OPTIONS.filter(({ target }) => {
    if (target === "annotation_queue") {
      return !isSpans && canCreateAnnotationQueues;
    }
    if (target === "online_evaluation") {
      return canUpdateOnlineEvaluationRules;
    }
    return true;
  });

  if (!options.length) {
    return null;
  }

  return (
    <>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="outline" size="sm">
            <Zap className="mr-1.5 size-3.5" />
            Add automation
            <ChevronDown className="ml-1.5 size-4" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-72">
          {options.map((option) => {
            const item = (
              <div className="flex items-start gap-2">
                <span
                  className={`mt-0.5 flex size-4 shrink-0 items-center justify-center rounded-[4px] p-0.5 ${option.iconClassName}`}
                >
                  <option.icon className="size-3 text-background" />
                </span>
                <span className="min-w-0">
                  <span className="block truncate">{option.label}</span>
                  <span className="comet-body-xs block text-light-slate">
                    {option.description(subject)}
                  </span>
                </span>
              </div>
            );

            // Alerts are created on their own page; the other two have shareable dialogs, so they open
            // in place and leave the user where they were.
            if (option.target === "alert") {
              return (
                <DropdownMenuItem key={option.target} asChild>
                  <Link
                    to="/$workspaceName/projects/$projectId/alerts/new"
                    params={{ workspaceName, projectId }}
                  >
                    {item}
                  </Link>
                </DropdownMenuItem>
              );
            }

            return (
              <DropdownMenuItem
                key={option.target}
                onClick={() => setOpenTarget(option.target)}
              >
                {item}
              </DropdownMenuItem>
            );
          })}
        </DropdownMenuContent>
      </DropdownMenu>
      {openTarget === "annotation_queue" && (
        <AddEditAnnotationQueueDialog
          open
          setOpen={(open) => !open && setOpenTarget(null)}
          projectId={projectId}
          scope={
            isThreads
              ? ANNOTATION_QUEUE_SCOPE.THREAD
              : ANNOTATION_QUEUE_SCOPE.TRACE
          }
          expandAutomation
        />
      )}
      {openTarget === "online_evaluation" && (
        <AddEditRuleDialog
          open
          setOpen={(open) => !open && setOpenTarget(null)}
          projectId={projectId}
          defaultScope={
            isThreads
              ? EVALUATORS_RULE_SCOPE.thread
              : isSpans
                ? EVALUATORS_RULE_SCOPE.span
                : EVALUATORS_RULE_SCOPE.trace
          }
          mode="create"
        />
      )}
    </>
  );
};

export default AddAutomationDropdown;
