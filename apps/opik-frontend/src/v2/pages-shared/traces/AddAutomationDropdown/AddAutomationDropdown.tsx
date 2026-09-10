import React, { useState } from "react";
import { Bell, ChevronDown, LucideIcon, UserPen, Zap } from "lucide-react";

import { cn } from "@/lib/utils";
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

type AutomationTarget = "alert" | "online_evaluation" | "annotation_queue";

/** What each logs tab calls the thing it lists, for the option descriptions. */
const SUBJECT_BY_LOGS_TYPE: Record<LOGS_TYPE, string> = {
  [LOGS_TYPE.traces]: "traces",
  [LOGS_TYPE.spans]: "spans",
  [LOGS_TYPE.threads]: "threads",
};

const EVALUATOR_SCOPE_BY_LOGS_TYPE: Record<LOGS_TYPE, EVALUATORS_RULE_SCOPE> = {
  [LOGS_TYPE.traces]: EVALUATORS_RULE_SCOPE.trace,
  [LOGS_TYPE.spans]: EVALUATORS_RULE_SCOPE.span,
  [LOGS_TYPE.threads]: EVALUATORS_RULE_SCOPE.thread,
};

/** A span is never a queue item, so the Spans tab has no scope to map and no option to offer. */
const QUEUE_SCOPE_BY_LOGS_TYPE: Record<
  LOGS_TYPE,
  ANNOTATION_QUEUE_SCOPE | null
> = {
  [LOGS_TYPE.traces]: ANNOTATION_QUEUE_SCOPE.TRACE,
  [LOGS_TYPE.spans]: null,
  [LOGS_TYPE.threads]: ANNOTATION_QUEUE_SCOPE.THREAD,
};

type OptionContext = {
  logsType: LOGS_TYPE;
  permissions: ReturnType<typeof usePermissions>["permissions"];
};

type AutomationOption = {
  target: AutomationTarget;
  label: string;
  icon: LucideIcon;
  /** Icon plate colour, from the design tokens for these three features. */
  iconClassName: string;
  description: (subject: string) => string;
  /** Whether to offer this option at all. Lives on the option so adding one is a single edit. */
  isAvailable: (context: OptionContext) => boolean;
};

const AUTOMATION_OPTIONS: AutomationOption[] = [
  {
    target: "alert",
    label: "Alerts",
    icon: Bell,
    iconClassName: "bg-pink-500",
    description: (subject) =>
      `Get notified about ${subject} that need attention`,
    isAvailable: () => true,
  },
  {
    target: "online_evaluation",
    label: "Online evaluation",
    icon: Zap,
    iconClassName: "bg-teal-500",
    description: (subject) =>
      `Automatically score incoming ${subject} with evaluators`,
    isAvailable: ({ permissions }) =>
      permissions.canUpdateOnlineEvaluationRules,
  },
  {
    target: "annotation_queue",
    label: "Annotation queue",
    icon: UserPen,
    iconClassName: "bg-lime-400",
    description: (subject) =>
      `Automatically collect matching ${subject} for human review`,
    isAvailable: ({ logsType, permissions }) =>
      permissions.canCreateAnnotationQueues &&
      QUEUE_SCOPE_BY_LOGS_TYPE[logsType] !== null,
  },
];

type AddAutomationDropdownProps = {
  projectId: string;
  logsType: LOGS_TYPE;
};

/**
 * The three ways to automate something about what a project logs, gathered behind one button.
 *
 * Each option opens the create form of its own feature, prefilled from the tab the user is on: an
 * annotation queue collecting threads when opened from Threads, traces when opened from Traces. The
 * annotation queue form also opens with its automation section switched on - arriving here is a
 * statement of intent, and having to hunt for the toggle afterwards would waste it.
 */
const AddAutomationDropdown: React.FunctionComponent<
  AddAutomationDropdownProps
> = ({ projectId, logsType }) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { permissions } = usePermissions();

  const [openTarget, setOpenTarget] = useState<AutomationTarget | null>(null);

  const subject = SUBJECT_BY_LOGS_TYPE[logsType];
  const queueScope = QUEUE_SCOPE_BY_LOGS_TYPE[logsType];
  const options = AUTOMATION_OPTIONS.filter((option) =>
    option.isAvailable({ logsType, permissions }),
  );

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
                  className={cn(
                    "mt-0.5 flex size-4 shrink-0 items-center justify-center rounded-[4px] p-0.5",
                    option.iconClassName,
                  )}
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
      {openTarget === "annotation_queue" && queueScope && (
        <AddEditAnnotationQueueDialog
          open
          setOpen={(open) => !open && setOpenTarget(null)}
          projectId={projectId}
          scope={queueScope}
          expandAutomation
        />
      )}
      {openTarget === "online_evaluation" && (
        <AddEditRuleDialog
          open
          setOpen={(open) => !open && setOpenTarget(null)}
          projectId={projectId}
          defaultScope={EVALUATOR_SCOPE_BY_LOGS_TYPE[logsType]}
          mode="create"
        />
      )}
    </>
  );
};

export default AddAutomationDropdown;
