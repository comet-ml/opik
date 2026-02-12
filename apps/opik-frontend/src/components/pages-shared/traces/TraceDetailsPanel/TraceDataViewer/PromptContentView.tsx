import React from "react";
import { ExternalLink } from "lucide-react";
import { Link } from "@tanstack/react-router";
import { Button } from "@/components/ui/button";
import PromptTemplateView from "@/components/pages-shared/llm/PromptTemplateView/PromptTemplateView";
import { PROMPT_TEMPLATE_STRUCTURE } from "@/types/prompts";

export const CustomUseInPlaygroundButton: React.FC<{
  variant?: string;
  size?: string;
  disabled?: boolean;
  onClick?: () => void;
  children: React.ReactNode;
  className?: string;
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
}> = ({ onClick, disabled, size, variant, ...props }) => {
  return (
    <Button
      variant="ghost"
      onClick={onClick}
      size="sm"
      disabled={disabled}
      className="inline-flex items-center gap-2"
      {...props}
    >
      Use in Playground
      <ExternalLink className="size-3.5 shrink-0" />
    </Button>
  );
};

interface PromptContentViewProps {
  template: unknown;
  promptId?: string;
  activeVersionId?: string;
  workspaceName: string;
  search?: string;
  templateStructure?: PROMPT_TEMPLATE_STRUCTURE;
  playgroundButton: React.ReactNode;
}

const PromptContentView: React.FC<PromptContentViewProps> = ({
  template,
  promptId,
  activeVersionId,
  workspaceName,
  search,
  templateStructure,
  playgroundButton,
}) => {
  return (
    <PromptTemplateView
      template={template}
      templateStructure={templateStructure}
      search={search}
    >
      <div className="mt-2 flex items-center justify-between">
        {promptId && (
          <Button variant="ghost" size="sm" asChild>
            <Link
              to="/$workspaceName/prompts/$promptId"
              params={{ workspaceName, promptId }}
              search={{ activeVersionId }}
              className="inline-flex items-center"
            >
              View in Prompt library
            </Link>
          </Button>
        )}
        {playgroundButton}
      </div>
    </PromptTemplateView>
  );
};

export default PromptContentView;
