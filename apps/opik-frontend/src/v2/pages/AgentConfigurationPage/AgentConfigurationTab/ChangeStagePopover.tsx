import React, { useState } from "react";
import { Check, Plus, Tags } from "lucide-react";

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
  BASIC_STAGE_ORDER,
  isBasicStage,
  isProdTag,
  isStageTag,
} from "@/utils/agent-configurations";

type ChangeStagePopoverProps = {
  item: ConfigHistoryItem;
  projectId: string;
};

const ChangeStagePopover: React.FC<ChangeStagePopoverProps> = ({
  item,
  projectId,
}) => {
  const [open, setOpen] = useState(false);
  const [selectedStages, setSelectedStages] = useState<string[]>([]);
  const [customValue, setCustomValue] = useState("");

  const { mutate: setEnvs, isPending: isSetPending } =
    useAgentConfigEnvsMutation();
  const { mutate: deleteEnv, isPending: isDeletePending } =
    useDeleteAgentConfigEnvMutation();

  const isPending = isSetPending || isDeletePending;

  const resetAndOpen = () => {
    setSelectedStages([...item.tags]);
    setCustomValue("");
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

  const customStages = selectedStages.filter((s) => !isBasicStage(s));

  const renderStageLabel = (stage: string) =>
    isBasicStage(stage) ? (
      <BasicStageTag value={stage} size="sm" />
    ) : (
      <ColoredTag label={stage} size="md" />
    );

  return (
    <>
      <Popover open={open} onOpenChange={setOpen}>
        <PopoverTrigger asChild>
          <Button size="xs" variant="outline" onClick={resetAndOpen}>
            <Tags className="mr-1.5 size-3.5 text-primary" />
            Change environment
          </Button>
        </PopoverTrigger>
        <PopoverContent align="end" className="w-56 p-3">
          <p className="comet-body-xs-accented mb-1 text-light-slate">
            Default environments
          </p>
          <div className="flex flex-col gap-1">
            {BASIC_STAGE_ORDER.map((stage) => {
              const selected = isSelected(stage);
              const locked = isSavedProd(stage) && selected;

              return (
                <button
                  key={stage}
                  type="button"
                  className="flex cursor-pointer items-center justify-between rounded-md px-2 py-1.5 hover:bg-muted"
                  onClick={() => toggleStage(stage)}
                >
                  {renderStageLabel(stage)}
                  {selected &&
                    (locked ? (
                      <TooltipWrapper content="You can't remove the prod stage from a version. Please assign the prod stage to another version.">
                        <Check className="size-4 text-light-slate" />
                      </TooltipWrapper>
                    ) : (
                      <Check className="size-4 text-primary" />
                    ))}
                </button>
              );
            })}
          </div>
          <Separator className="my-2" />
          {customStages.length > 0 && (
            <>
              <p className="comet-body-xs-accented mb-1 text-light-slate">
                Custom environments
              </p>
              <div className="flex max-h-[140px] flex-col gap-1 overflow-y-auto">
                {customStages.map((stage) => (
                  <button
                    key={stage}
                    type="button"
                    className="flex cursor-pointer items-center justify-between rounded-md px-2 py-1.5 hover:bg-muted"
                    onClick={() => toggleStage(stage)}
                  >
                    <ColoredTag label={stage} size="md" />
                    <Check className="size-4 text-primary" />
                  </button>
                ))}
              </div>
            </>
          )}
          <div className="mt-2 flex items-center gap-1.5">
            <Input
              dimension="sm"
              placeholder="Add custom environment"
              value={customValue}
              onChange={(e) => setCustomValue(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Enter") addCustomStage();
              }}
            />
            <Button
              size="icon-xs"
              variant="outline"
              onClick={addCustomStage}
              disabled={!customValue.trim()}
            >
              <Plus className="size-3.5" />
            </Button>
          </div>
          <Separator className="my-2" />
          <div className="flex justify-end gap-2">
            <Button size="2xs" variant="outline" onClick={() => setOpen(false)}>
              Cancel
            </Button>
            <Button size="2xs" onClick={handleApply} disabled={isPending}>
              Apply
            </Button>
          </div>
        </PopoverContent>
      </Popover>
    </>
  );
};

export default ChangeStagePopover;
