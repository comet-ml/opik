import { useRef } from "react";
import { Plus } from "lucide-react";

import { Button } from "@/ui/button";
import { generateDefaultPrompt } from "@/lib/playground";
import { PLAYGROUND_LAST_PICKED_MODEL } from "@/constants/llm";
import { useAddPrompt } from "@/store/PlaygroundStore";
import useLastPickedModel from "@/hooks/useLastPickedModel";
import useLLMProviderModelsData from "@/hooks/useLLMProviderModelsData";
import { COMPOSED_PROVIDER_TYPE } from "@/types/providers";

interface PlaygroundAddVariantProps {
  providerKeys: COMPOSED_PROVIDER_TYPE[];
}

const PlaygroundAddVariant = ({ providerKeys }: PlaygroundAddVariantProps) => {
  const addPrompt = useAddPrompt();
  const [lastPickedModel] = useLastPickedModel({
    key: PLAYGROUND_LAST_PICKED_MODEL,
  });
  const { calculateModelProvider, calculateDefaultModel } =
    useLLMProviderModelsData();

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
    scrollToEnd();
  };

  return (
    <div
      ref={stripRef}
      className="group/variant flex w-[var(--add-variant-width)] shrink-0 cursor-pointer items-start justify-center self-stretch bg-background hover:bg-primary-100"
      style={{ boxShadow: "1px 0 0 hsl(var(--border))" }}
      onClick={handleAddBlankPrompt}
    >
      <div className="flex h-[50vh] items-center">
        <div className="flex flex-col items-center gap-3">
          <Button
            data-testid="playground-add-variant-button"
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
      </div>
    </div>
  );
};

export default PlaygroundAddVariant;
