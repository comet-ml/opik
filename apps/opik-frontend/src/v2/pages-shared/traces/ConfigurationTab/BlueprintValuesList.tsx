import React, { useMemo } from "react";

import { BlueprintValue, BlueprintValueType } from "@/types/agent-configs";
import { formatBlueprintValue } from "@/utils/agent-configurations";
import BlueprintTypeIcon from "./BlueprintTypeIcon";
import BlueprintValuePromptCompact from "@/v2/pages-shared/agent-configuration/fields/BlueprintValuePromptCompact";
import FieldSection from "@/v2/pages-shared/agent-configuration/fields/FieldSection";
import CollapsibleBlock from "@/v2/pages-shared/agent-configuration/fields/CollapsibleBlock";
import {
  collectMultiLineKeys,
  isMultiLineField,
} from "@/v2/pages-shared/agent-configuration/fields/blueprintFieldLayout";
import {
  FieldsCollapseController,
  useFieldsCollapse,
} from "@/v2/pages-shared/agent-configuration/fields/useFieldsCollapse";

const renderScalarValue = (v: BlueprintValue) => (
  <div className="comet-body-s whitespace-pre-wrap break-words text-foreground">
    {formatBlueprintValue(v)}
  </div>
);

type BlueprintValuesListProps = {
  values: BlueprintValue[];
  controller?: FieldsCollapseController;
};

const BlueprintValuesList: React.FC<BlueprintValuesListProps> = ({
  values,
  controller: externalController,
}) => {
  const collapsibleKeys = useMemo(() => collectMultiLineKeys(values), [values]);
  const internalController = useFieldsCollapse({ collapsibleKeys });
  const controller = externalController ?? internalController;

  return (
    <div className="flex flex-col gap-4">
      {values.map((v) => {
        const isPrompt = v.type === BlueprintValueType.PROMPT;
        const collapsible = !isPrompt && isMultiLineField(v);
        return (
          <FieldSection
            key={v.key}
            label={v.key}
            description={v.description}
            icon={<BlueprintTypeIcon type={v.type} />}
            testId={`field-section-${v.key}`}
          >
            {isPrompt ? (
              <BlueprintValuePromptCompact
                key={v.value}
                value={v}
                controller={controller}
              />
            ) : (
              <CollapsibleBlock
                collapsible={collapsible}
                expanded={controller.isExpanded(v.key)}
                onToggle={() => controller.toggle(v.key)}
                testId={`field-block-${v.key}`}
              >
                {renderScalarValue(v)}
              </CollapsibleBlock>
            )}
          </FieldSection>
        );
      })}
    </div>
  );
};

export default BlueprintValuesList;
