import React, { useState, useMemo, useEffect } from "react";
import { Pencil } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { TraceFeedbackScore } from "@/types/traces";
import { cn } from "@/lib/utils";
import useFeedbackDefinitionsList from "@/api/feedback-definitions/useFeedbackDefinitionsList";
import useAppStore, { useLoggedInUserName } from "@/store/AppStore";
import { USER_FEEDBACK_NAME } from "@/constants/shared";
import {
  CategoricalFeedbackDefinition,
  FEEDBACK_DEFINITION_TYPE,
} from "@/types/feedback-definitions";

interface FeedbackScoreEditDropdownProps {
  feedbackScore?: TraceFeedbackScore;
  onValueChange: (value: number) => void;
}

const FeedbackScoreEditDropdown: React.FC<FeedbackScoreEditDropdownProps> = ({
  feedbackScore,
  onValueChange,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const currentUserName = useLoggedInUserName() ?? "admin";
  const [open, setOpen] = useState(false);
  const [keepVisible, setKeepVisible] = useState(false);

  // Keep the button visible briefly after dropdown closes to prevent anchor shift during animation
  useEffect(() => {
    if (open) {
      setKeepVisible(true);
    } else {
      const timeout = setTimeout(() => {
        setKeepVisible(false);
      }, 150); // Match Radix UI default animation duration
      return () => clearTimeout(timeout);
    }
  }, [open]);

  const { data: feedbackDefinitionsData } = useFeedbackDefinitionsList({
    workspaceName,
    page: 1,
    size: 1000,
  });

  const userFeedbackDefinition = useMemo(() => {
    return feedbackDefinitionsData?.content?.find(
      (def) =>
        def.name === USER_FEEDBACK_NAME &&
        def.type === FEEDBACK_DEFINITION_TYPE.categorical,
    );
  }, [feedbackDefinitionsData?.content]);

  const feedbackOptions = useMemo(() => {
    return Object.entries(
      (userFeedbackDefinition as unknown as CategoricalFeedbackDefinition)
        ?.details?.categories ?? {},
    ).map(([name, value]) => ({
      name,
      value,
    }));
  }, [userFeedbackDefinition]);

  const handleValueSelect = (value: number) => {
    onValueChange(value);
    setOpen(false);
  };

  const currentValue = feedbackScore?.value_by_author?.[currentUserName]?.value;

  // Don't render if no feedback options are available
  if (feedbackOptions.length === 0) {
    return null;
  }

  return (
    <DropdownMenu open={open} onOpenChange={setOpen}>
      <DropdownMenuTrigger asChild>
        <Button
          size="icon-xs"
          variant="ghost"
          className={cn(
            "hidden group-hover:inline-flex",
            keepVisible && "inline-flex",
          )}
          onClick={(e) => {
            e.stopPropagation();
          }}
        >
          <Pencil className="size-3" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent
        onClick={(event) => event.stopPropagation()}
        align="end"
        className="w-fit p-1"
        sideOffset={4}
      >
        <DropdownMenuLabel className="px-2 text-xs font-medium text-secondary-foreground">
          Personal user feedback
        </DropdownMenuLabel>
        <div className="flex items-center gap-1 rounded-md border border-gray-200 bg-white">
          {feedbackOptions.map((option) => (
            <DropdownMenuItem
              key={option.name}
              onClick={() => handleValueSelect(option.value)}
              className={cn(
                "flex items-center justify-center px-1 py-2 cursor-pointer rounded-sm text-sm flex-1",
                "hover:bg-accent hover:text-accent-foreground",
                "focus:bg-accent focus:text-accent-foreground",
                currentValue === option.value &&
                  "bg-accent text-accent-foreground",
              )}
            >
              <div className="flex items-center gap-2 px-3">
                <span className="text-lg">{option.name}</span>
                <span className="text-sm font-medium">({option.value})</span>
              </div>
            </DropdownMenuItem>
          ))}
        </div>
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

export default FeedbackScoreEditDropdown;
