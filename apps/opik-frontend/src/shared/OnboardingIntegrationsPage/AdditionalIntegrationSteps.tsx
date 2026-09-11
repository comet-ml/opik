import React from "react";
import CodeHighlighter from "@/shared/CodeHighlighter/CodeHighlighter";
import { putConfigInCode } from "@/lib/formatCodeSnippets";
import { PROJECT_NAME_PLACEHOLDER } from "@/constants/shared";
import { IntegrationStepConfig } from "@/constants/integrations";

type IntegrationStepComponent = React.ComponentType<{
  title: string;
  description?: string;
  className?: string;
  children?: React.ReactNode;
}>;

type AdditionalIntegrationStepsProps = {
  steps: IntegrationStepConfig[];
  workspaceName: string;
  apiKey?: string;
  projectName: string;
  IntegrationStep: IntegrationStepComponent;
  stepClassName?: string;
  codeWrapperClassName?: string;
};

const AdditionalIntegrationSteps: React.FC<AdditionalIntegrationStepsProps> = ({
  steps,
  workspaceName,
  apiKey,
  projectName,
  IntegrationStep,
  stepClassName,
  codeWrapperClassName = "min-h-7",
}) => {
  return (
    <>
      {steps.map((step, idx) => {
        const { code: stepCode } = putConfigInCode({
          code: step.code,
          workspaceName,
          apiKey,
          shouldMaskApiKey: true,
          projectName,
        });
        return (
          <IntegrationStep
            key={idx}
            title={step.title}
            description={step.description?.replaceAll(
              PROJECT_NAME_PLACEHOLDER,
              projectName,
            )}
            className={stepClassName}
          >
            <div className={codeWrapperClassName}>
              <CodeHighlighter data={stepCode} language={step.language} />
            </div>
          </IntegrationStep>
        );
      })}
    </>
  );
};

export default AdditionalIntegrationSteps;
