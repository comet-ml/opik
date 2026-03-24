import React, { useMemo, useState } from "react";
import { Check, Plus, Tags, Trash2, X } from "lucide-react";

import { ConfigHistoryItem } from "@/types/agent-configs";
import useAgentConfigEnvsMutation from "@/api/agent-configs/useAgentConfigEnvsMutation";
import useDeleteAgentConfigEnvMutation from "@/api/agent-configs/useDeleteAgentConfigEnvMutation";
import ColoredTag from "@/shared/ColoredTag/ColoredTag";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import BasicStageTag from "./BasicStageTag";
import { Button } from "@/ui/button";
import { Input } from "@/ui/input";
import { Separator } from "@/ui/separator";
import { Popover, PopoverContent, PopoverTrigger } from "@/ui/popover";
import {
  AgentConfigurationBasicStage,
  isBasicStage,
  isProdTag,
  isStageTag,
} from "@/utils/agent-configurations";

type DeployToPopoverProps = {
  item: ConfigHistoryItem;
  projectId: string;
  versions: ConfigHistoryItem[];
};

const DeployToPopover: React.FC<DeployToPopoverProps> = ({
  item,
  projectId,
  versions,
}) => {
  const [open, setOpen] = useState(false);
  const [selectedStages, setSelectedStages] = useState<string[]>([]);
  const [customValue, setCustomValue] = useState("");
  const [showCustomInput, setShowCustomInput] = useState(false);

  const { mutate: setEnvs, isPending: isSetPending } =
    useAgentConfigEnvsMutation();
  const { mutate: deleteEnv, isPending: isDeletePending } =
    useDeleteAgentConfigEnvMutation();

  const isPending = isSetPending || isDeletePending;

  const resetAndOpen = () => {
    setSelectedStages([...item.tags]);
    setCustomValue("");
    setShowCustomInput(false);
    setOpen(true);
  };

  const isSelected = (stage: string) =>
    selectedStages.some((s) => isStageTag(s, stage));

  const isSavedProd = (stage: string) =>
    isProdTag(stage) && item.tags.some((t) => isStageTag(t, stage));

  const toggleStage = (stage: string) => {
    if (isSavedProd(stage) && isSelected(stage)) return;

    setSelectedStages((prev) =>
      prev.some((s) => isStageTag(s, stage))
        ? prev.filter((s) => !isStageTag(s, stage))
        : [...prev, stage],
    );
  };

  const addCustomStage = () => {
    const trimmed = customValue.trim();
    if (!trimmed || isSelected(trimmed)) return;
    setSelectedStages((prev) => [...prev, trimmed]);
    setCustomValue("");
    setShowCustomInput(false);
  };

  const handleApply = () => {
    const removedTags = item.tags.filter(
      (tag) => !selectedStages.some((s) => isStageTag(s, tag)),
    );

    removedTags.forEach((tag) => {
      deleteEnv({ envName: tag, projectId });
    });

    if (selectedStages.length > 0) {
      setEnvs({
        envsRequest: {
          project_id: projectId,
          envs: selectedStages.map((stage) => ({
            env_name: stage,
            blueprint_id: item.id,
          })),
        },
      });
    }

    setOpen(false);
  };

  const allCustomEnvs = useMemo(() => {
    const envSet = new Set<string>();
    versions.forEach((v) =>
      v.tags.forEach((t) => {
        if (!isBasicStage(t)) envSet.add(t);
      }),
    );
    return Array.from(envSet);
  }, [versions]);

  const initialCustomTags = item.tags.filter((t) => !isBasicStage(t));
  const restCustomEnvs = allCustomEnvs.filter(
    (env) => !initialCustomTags.includes(env),
  );
  const newCustomStages = selectedStages.filter(
    (s) => !isBasicStage(s) && !allCustomEnvs.includes(s),
  );
  const allVisibleCustomEnvs = [
    ...initialCustomTags,
    ...restCustomEnvs,
    ...newCustomStages,
  ];

  const removeCustomStage = (stage: string) => {
    setSelectedStages((prev) => prev.filter((s) => s !== stage));
  };

  const prodStage = AgentConfigurationBasicStage.PROD;
  const otherBasicStages = [
    AgentConfigurationBasicStage.STAGING,
    AgentConfigurationBasicStage.DEV,
  ];

  const prodSelected = isSelected(prodStage);
  const prodLocked = isSavedProd(prodStage) && prodSelected;
  const isNewProd =
    prodSelected && !item.tags.some((t) => isStageTag(t, prodStage));

  const hasChanges = useMemo(() => {
    if (selectedStages.length !== item.tags.length) return true;
    const sortedSelected = [...selectedStages].sort();
    const sortedTags = [...item.tags].sort();
    return sortedSelected.some((s, i) => s !== sortedTags[i]);
  }, [selectedStages, item.tags]);

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button size="xs" variant="outline" onClick={resetAndOpen}>
          <Tags className="mr-1.5 size-3.5 text-primary" />
          Deploy to
        </Button>
      </PopoverTrigger>
      <PopoverContent align="end" className="w-64 p-3">
        <p className="comet-body-s-accented mb-1">Select environments</p>
        <p className="comet-body-xs mb-1 text-light-slate">
          This version will be deployed to the selected environment and used by
          your agent
        </p>
        <Separator className="my-2" />

        <button
          type="button"
          className="flex w-full cursor-pointer items-center justify-between rounded-md px-2 py-1.5 hover:bg-muted"
          onClick={() => toggleStage(prodStage)}
        >
          <BasicStageTag value={prodStage} size="sm" />
          {prodSelected &&
            (prodLocked ? (
              <TooltipWrapper content="You can't remove the prod stage from a version. Please assign the prod stage to another version.">
                <Check className="size-4 text-light-slate" />
              </TooltipWrapper>
            ) : (
              <Check className="size-4 text-primary" />
            ))}
        </button>

        <Separator className="my-2" />

        <div className="mb-1 flex items-center justify-between">
          <p className="comet-body-xs-accented">Other environments</p>
          <TooltipWrapper content="Create a custom environment">
            <Button
              size="icon-xs"
              variant="minimal"
              onClick={() => setShowCustomInput((prev) => !prev)}
            >
              <Plus className="size-3.5" />
            </Button>
          </TooltipWrapper>
        </div>
        <div className="flex max-h-[176px] flex-col gap-1 overflow-y-auto">
          {otherBasicStages.map((stage) => {
            const selected = isSelected(stage);
            return (
              <button
                key={stage}
                type="button"
                className="flex cursor-pointer items-center justify-between rounded-md px-2 py-1.5 hover:bg-muted"
                onClick={() => toggleStage(stage)}
              >
                <BasicStageTag value={stage} size="sm" />
                {selected && <Check className="size-4 text-primary" />}
              </button>
            );
          })}
          {allVisibleCustomEnvs.map((stage) => {
            const selected = isSelected(stage);
            const isNew = !allCustomEnvs.includes(stage);
            return (
              <div
                key={stage}
                className="group flex cursor-pointer items-center justify-between rounded-md px-2 py-1.5 hover:bg-muted"
                onClick={() => toggleStage(stage)}
              >
                <ColoredTag
                  label={stage}
                  size="md"
                  variant="gray"
                  className="max-w-[170px]"
                />
                <div className="flex items-center">
                  {selected &&
                    (isNew ? (
                      <>
                        <Check className="size-4 text-primary group-hover:hidden" />
                        <button
                          type="button"
                          className="hidden group-hover:block"
                          onClick={(e) => {
                            e.stopPropagation();
                            removeCustomStage(stage);
                          }}
                        >
                          <Trash2 className="size-4 text-destructive" />
                        </button>
                      </>
                    ) : (
                      <Check className="size-4 text-primary" />
                    ))}
                </div>
              </div>
            );
          })}
          {showCustomInput && (
            <div className="relative mt-1">
              <Input
                dimension="sm"
                placeholder="Add environment"
                value={customValue}
                onChange={(e) => setCustomValue(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === "Enter") addCustomStage();
                  if (e.key === "Escape") {
                    setCustomValue("");
                    setShowCustomInput(false);
                  }
                }}
                autoFocus
                className="pr-14"
              />
              <div className="absolute right-1.5 top-1/2 flex -translate-y-1/2 items-center gap-1">
                {customValue.trim() && isSelected(customValue.trim()) ? (
                  <TooltipWrapper content="This environment already picked">
                    <Check className="size-3.5 text-light-slate" />
                  </TooltipWrapper>
                ) : (
                  <button
                    type="button"
                    onClick={addCustomStage}
                    disabled={!customValue.trim()}
                    className="text-[var(--color-green)] disabled:opacity-30"
                  >
                    <Check className="size-3.5" />
                  </button>
                )}
                <button
                  type="button"
                  onClick={() => {
                    setCustomValue("");
                    setShowCustomInput(false);
                  }}
                  className="text-destructive"
                >
                  <X className="size-3.5" />
                </button>
              </div>
            </div>
          )}
        </div>
        <Separator className="my-2" />
        <Button
          size="sm"
          variant={isNewProd ? "destructive" : "default"}
          className={
            isNewProd
              ? "w-full bg-destructive text-white hover:bg-destructive/90"
              : "w-full bg-foreground-secondary text-primary-foreground hover:bg-foreground-secondary/90"
          }
          onClick={handleApply}
          disabled={isPending || !hasChanges}
        >
          {isNewProd ? "Save and deploy to Prod" : "Save changes"}
        </Button>
      </PopoverContent>
    </Popover>
  );
};

export default DeployToPopover;
