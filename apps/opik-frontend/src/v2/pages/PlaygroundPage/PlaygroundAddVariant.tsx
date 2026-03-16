import { RefObject, useState } from "react";
import { Copy, File, Plus } from "lucide-react";

import { Button } from "@/ui/button";
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/ui/popover";
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
  containerRef: RefObject<HTMLDivElement | null>;
}

const PlaygroundAddVariant = ({
  providerKeys,
  containerRef,
}: PlaygroundAddVariantProps) => {
  const promptMap = usePromptMap();
  const addPrompt = useAddPrompt();
  const promptIds = usePromptIds();
  const [lastPickedModel] = useLastPickedModel({
    key: PLAYGROUND_LAST_PICKED_MODEL,
  });
  const { calculateModelProvider, calculateDefaultModel } =
    useLLMProviderModelsData();

  const [addVariantOpen, setAddVariantOpen] = useState(false);

  const scrollToEnd = () => {
    requestAnimationFrame(() => {
      containerRef.current?.parentElement?.scrollTo({
        left: containerRef.current.scrollWidth,
        behavior: "smooth",
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
      initPrompt: lastPrompt,
      setupProviders: providerKeys,
      providerResolver: calculateModelProvider,
      modelResolver: calculateDefaultModel,
    });
    addPrompt(newPrompt);
    setAddVariantOpen(false);
    scrollToEnd();
  };

  return (
    <div className="mt-16 flex shrink-0 items-start self-stretch border-r border-t bg-background px-3">
      <div className="flex h-[calc(50vh-4rem)] items-center">
        <Popover open={addVariantOpen} onOpenChange={setAddVariantOpen}>
          <PopoverTrigger asChild>
            <div className="group/variant flex cursor-pointer flex-col items-center gap-3">
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
          <PopoverContent align="start" className="w-48 p-1" side="left">
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
