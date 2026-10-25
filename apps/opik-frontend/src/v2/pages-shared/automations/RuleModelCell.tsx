import { CellContext } from "@tanstack/react-table";

import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import useLLMProviderModelsData from "@/hooks/useLLMProviderModelsData";
import { parseComposedProviderType } from "@/lib/provider";
import { EvaluatorsRule, LLMJudgeObject } from "@/types/automations";
import ModelRetirementTag from "@/v2/pages-shared/llm/ModelRetirementTag/ModelRetirementTag";

const ruleModel = (rule: EvaluatorsRule) =>
  (rule.code as Partial<LLMJudgeObject> | undefined)?.model?.name;

const RuleModelCell = (context: CellContext<EvaluatorsRule, unknown>) => {
  const { calculateModelProvider } = useLLMProviderModelsData();
  const model = ruleModel(context.row.original);
  const provider = model ? calculateModelProvider(model) : undefined;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="gap-2"
    >
      <span className="truncate">{model ?? "-"}</span>
      {model && provider && (
        <ModelRetirementTag
          model={model}
          provider={parseComposedProviderType(provider)}
        />
      )}
    </CellWrapper>
  );
};

export default RuleModelCell;
