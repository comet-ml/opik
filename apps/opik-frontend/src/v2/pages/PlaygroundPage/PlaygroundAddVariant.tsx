import { useRef, useState } from "react";
import { Copy, File, Plus } from "lucide-react";

import { cn } from "@/lib/utils";
import { Button } from "@/ui/button";
import { Popover, PopoverContent, PopoverTrigger } from "@/ui/popover";
import { generateDefaultPrompt } from "@/lib/playground";
import { PLAYGROUND_LAST_PICKED_MODEL } from "@/constants/llm";
import {
  useAddPrompt,
  usePromptIds,
  usePromptMap,
} from "@/store/PlaygroundStore";
import useLastPickedModel from "@/hooks/useLastPickedModel";
import useLLMProviderModelsData from "@/hooks/useLLMProviderModelsData";
import { COMPOSED_PROVIDER_TYPE } from "@/types/providers";

interface PlaygroundAddVariantProps {
  providerKeys: COMPOSED_PROVIDER_TYPE[];
}

const PlaygroundAddVariant = ({ providerKeys }: PlaygroundAddVariantProps) => {
  const promptMap = usePromptMap();
  const addPrompt = useAddPrompt();
  const promptIds = usePromptIds();
  const [lastPickedModel] = useLastPickedModel({
    key: PLAYGROUND_LAST_PICKED_MODEL,
  });
  const { calculateModelProvider, calculateDefaultModel } =
    useLLMProviderModelsData();

  const [addVariantOpen, setAddVariantOpen] = useState(false);
  const stripRef = useRef<HTMLDivElement>(null);

  const scrollToEnd = () => {
    requestAnimationFrame(() => {
      stripRef.current?.scrollIntoView({
        behavior: "smooth",
        inline: "end",
        block: "nearest",
      });
    });
  };

  const handleAddBlankPrompt = () => {
    const newPrompt = generateDefaultPrompt({
      setupProviders: providerKeys,
      lastPickedModel,
      providerResolver: calculateModelProvider,
      modelResolver: calculateDefaultModel,
    });
    addPrompt(newPrompt);
    setAddVariantOpen(false);
    scrollToEnd();
  };

  const handleDuplicateLastPrompt = () => {
    const lastPromptId = promptIds[promptIds.length - 1];
    const lastPrompt = promptMap[lastPromptId];
    if (!lastPrompt) return;

    const newPrompt = generateDefaultPrompt({
      // Clear library link so useLoadChatPrompt doesn't overwrite
      // the duplicated messages with the saved library version
      initPrompt: { ...lastPrompt, loadedChatPromptId: undefined },
      setupProviders: providerKeys,
      providerResolver: calculateModelProvider,
      modelResolver: calculateDefaultModel,
    });
    addPrompt(newPrompt);
    setAddVariantOpen(false);
    scrollToEnd();
  };

  return (
    <div
      ref={stripRef}
      className={cn(
        "group/variant flex w-[var(--add-variant-width)] shrink-0 cursor-pointer items-start justify-center self-stretch border-r bg-background hover:bg-primary-100",
        addVariantOpen && "bg-primary-100",
      )}
      onClick={() => setAddVariantOpen(true)}
    >
      <div className="flex h-[50vh] items-center">
        <Popover open={addVariantOpen} onOpenChange={setAddVariantOpen}>
          <PopoverTrigger asChild>
            <div className="flex cursor-pointer flex-col items-center gap-3">
              <Button
                variant="secondary"
                size="icon-xs"
                className="group-hover/variant:bg-secondary group-hover/variant:text-primary-hover"
              >
                <Plus />
              </Button>
              <span className="comet-body-xs whitespace-nowrap text-primary">
                Add variant
              </span>
            </div>
          </PopoverTrigger>
          <PopoverContent
            align="start"
            className="w-48 p-1"
            side="left"
            onClick={(e) => e.stopPropagation()}
          >
            <Button
              variant="ghost"
              size="sm"
              className="w-full justify-start"
              onClick={handleDuplicateLastPrompt}
            >
              <Copy className="mr-2 size-3.5" />
              Duplicate variant
            </Button>
            <Button
              variant="ghost"
              size="sm"
              className="w-full justify-start"
              onClick={handleAddBlankPrompt}
            >
              <File className="mr-2 size-3.5" />
              Blank variant
            </Button>
          </PopoverContent>
        </Popover>
      </div>
    </div>
  );
};

export default PlaygroundAddVariant;
