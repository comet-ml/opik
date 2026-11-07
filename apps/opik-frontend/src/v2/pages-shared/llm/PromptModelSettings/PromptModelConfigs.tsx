import React, { useSyncExternalStore } from "react";
import { Settings2 } from "lucide-react";

import {
  LLMAnthropicConfigsType,
  LLMGeminiConfigsType,
  LLMOpenAIConfigsType,
  LLMOpenRouterConfigsType,
  LLMPromptConfigsType,
  LLMVertexAIConfigsType,
  LLMCustomConfigsType,
  OpenAiPipelineMode,
  PROVIDER_TYPE,
  PROVIDER_MODEL_TYPE,
  COMPOSED_PROVIDER_TYPE,
} from "@/types/providers";

import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import { Button, ButtonProps } from "@/ui/button";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";

import OpenAIModelConfigs from "@/v2/pages-shared/llm/PromptModelSettings/providerConfigs/OpenAIModelConfigs";
import AnthropicModelConfigs from "@/v2/pages-shared/llm/PromptModelSettings/providerConfigs/AnthropicModelConfigs";
import OpenRouterModelConfigs from "@/v2/pages-shared/llm/PromptModelSettings/providerConfigs/OpenRouterModelConfigs";
import GeminiModelConfigs from "@/v2/pages-shared/llm/PromptModelSettings/providerConfigs/GeminiModelConfigs";
import VertexAIModelConfigs from "@/v2/pages-shared/llm/PromptModelSettings/providerConfigs/VertexAIModelConfigs";
import CustomModelConfigs from "@/v2/pages-shared/llm/PromptModelSettings/providerConfigs/CustomModelConfig";
import GenericModelConfigs from "@/v2/pages-shared/llm/PromptModelSettings/providerConfigs/GenericModelConfigs";
import {
  ANTHROPIC_PANEL,
  GEMINI_PANEL,
  GenericPanelPresentation,
  OPENAI_PANEL,
  VERTEX_AI_PANEL,
} from "@/v2/pages-shared/llm/PromptModelSettings/providerConfigs/genericPanels";
import { getModelParams, toParamsRoute } from "@/lib/modelParams";
import { getGeminiOrVertexListedParams } from "@/lib/modelUtils";
import {
  getModelParamsVersion,
  subscribeToModelParams,
} from "@/lib/modelRegistryStore";
import { ModelParams } from "@/types/modelParams";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/v2/constants/explainers";
import {
  isOpenAICompatibleProvider,
  parseComposedProviderType,
} from "@/lib/provider";
import { ModelConfigParam } from "@/v2/pages-shared/llm/PromptModelSettings/modelConfigParams";
import { hasVisibleControls } from "@/v2/pages-shared/llm/PromptModelSettings/providerConfigs/visibleControls";

interface PromptModelConfigsProps {
  provider: COMPOSED_PROVIDER_TYPE;
  model?: PROVIDER_MODEL_TYPE | "";
  size?: ButtonProps["size"];
  variant?: ButtonProps["variant"];
  configs: Partial<LLMPromptConfigsType>;
  onChange: (configs: Partial<LLMPromptConfigsType>) => void;
  disabled?: boolean;
  /** Defaults to every parameter supported, which is the playground. */
  unsupportedParams?: ReadonlySet<ModelConfigParam>;
  /**
   * Pass only where the request goes through Opik's OpenAI pipeline, which honours the key's mode.
   * The optimizer calls OpenAI through LiteLLM on Chat Completions whatever the key says, so it must
   * leave this out and keep the Chat Completions effort values.
   */
  openAiPipelineMode?: OpenAiPipelineMode;
}

const PromptModelConfigs = ({
  provider: composedProviderType,
  model,
  size = "icon-sm",
  variant = "outline",
  configs,
  onChange,
  disabled: disabledProp = false,
  unsupportedParams,
  openAiPipelineMode,
}: PromptModelConfigsProps) => {
  const provider: PROVIDER_TYPE =
    parseComposedProviderType(composedProviderType);
  useSyncExternalStore(subscribeToModelParams, getModelParamsVersion);

  const renderCatalogPanel = (
    params: ModelParams,
    presentation: GenericPanelPresentation,
  ) => (
    <GenericModelConfigs
      model={model}
      params={params}
      presentation={presentation}
      configs={configs as Record<string, unknown>}
      unsupportedParams={unsupportedParams}
      onChange={onChange}
    />
  );

  const getProviderForm = () => {
    if (provider === PROVIDER_TYPE.OPEN_AI) {
      const params = getModelParams(
        PROVIDER_TYPE.OPEN_AI,
        model,
        toParamsRoute(openAiPipelineMode),
      );
      if (params) {
        return renderCatalogPanel(params, OPENAI_PANEL);
      }
      return (
        <OpenAIModelConfigs
          configs={configs as LLMOpenAIConfigsType}
          unsupportedParams={unsupportedParams}
          model={model}
          onChange={onChange}
          openAiPipelineMode={openAiPipelineMode}
        />
      );
    }

    if (provider === PROVIDER_TYPE.ANTHROPIC) {
      const params = getModelParams(PROVIDER_TYPE.ANTHROPIC, model);
      if (params) {
        return renderCatalogPanel(params, ANTHROPIC_PANEL);
      }
      return (
        <AnthropicModelConfigs
          configs={configs as LLMAnthropicConfigsType}
          unsupportedParams={unsupportedParams}
          onChange={onChange}
          model={model}
        />
      );
    }

    if (provider === PROVIDER_TYPE.OPEN_ROUTER) {
      return (
        <OpenRouterModelConfigs
          configs={configs as LLMOpenRouterConfigsType}
          model={model}
          unsupportedParams={unsupportedParams}
          onChange={onChange}
        />
      );
    }

    if (provider === PROVIDER_TYPE.GEMINI) {
      const params = getGeminiOrVertexListedParams(model);
      if (params) {
        return renderCatalogPanel(params, GEMINI_PANEL);
      }
      return (
        <GeminiModelConfigs
          configs={configs as LLMGeminiConfigsType}
          unsupportedParams={unsupportedParams}
          model={model}
          onChange={onChange}
        />
      );
    }

    if (provider === PROVIDER_TYPE.VERTEX_AI) {
      const params = getGeminiOrVertexListedParams(model);
      if (params) {
        return renderCatalogPanel(params, VERTEX_AI_PANEL);
      }
      return (
        <VertexAIModelConfigs
          configs={configs as LLMVertexAIConfigsType}
          unsupportedParams={unsupportedParams}
          model={model}
          onChange={onChange}
        />
      );
    }

    if (isOpenAICompatibleProvider(provider)) {
      return (
        <CustomModelConfigs
          configs={configs as LLMCustomConfigsType}
          model={model}
          provider={provider}
          unsupportedParams={unsupportedParams}
          onChange={onChange}
        />
      );
    }

    return;
  };

  const providerForm = getProviderForm();

  // Hide, not disable (as for decisions models): an empty panel misleads, and a disabled button can't
  // say why. Covers providers without a form and models with nothing to set on this surface. No
  // provider yet keeps the disabled button, so it still hints at settings once a model is picked.
  if (
    composedProviderType &&
    !hasVisibleControls(
      provider,
      model ?? "",
      configs,
      unsupportedParams,
      openAiPipelineMode,
    )
  ) {
    return null;
  }

  const disabled = disabledProp || !composedProviderType;

  return (
    // Not modal: a modal menu sets pointer-events: none on the body, so the first click outside only
    // closes the panel and is lost, e.g. Run right after changing a setting. Focus outside is still
    // ignored, as the modal menu does, because the thinking effort list renders in a portal outside
    // this panel and moving focus into it would otherwise close the panel.
    <DropdownMenu modal={false}>
      <TooltipWrapper content="Model parameters">
        <DropdownMenuTrigger asChild>
          <Button variant={variant} size={size} disabled={disabled}>
            <Settings2 />
          </Button>
        </DropdownMenuTrigger>
      </TooltipWrapper>

      <DropdownMenuContent
        className="max-h-[70vh] overflow-y-auto p-6"
        side="bottom"
        align="end"
        onCloseAutoFocus={(e) => e.preventDefault()}
        onFocusOutside={(e) => e.preventDefault()}
      >
        <div className="mb-5 w-72">
          <h5 className="comet-body-s-accented text-foreground">
            Model parameters
          </h5>
          <p className="comet-body-xs mt-1 text-light-slate">
            {
              EXPLAINERS_MAP[EXPLAINER_ID.whats_these_configuration_things]
                .description
            }
          </p>
        </div>
        {providerForm}
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

export default PromptModelConfigs;
