import React from "react";
import { ExternalLink } from "lucide-react";

import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogAutoScrollBody,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import cursorLogo from "/images/integrations/cursor.png";
import copilotLogo from "/images/integrations/copilot.png";
import windsurfLogo from "/images/integrations/windsurf.png";
import CopyButton from "@/components/shared/CopyButton/CopyButton";
import HelpLinks from "./HelpLinks";
import { Separator } from "@/components/ui/separator";
import { IntegrationStep } from "./IntegrationStep";

const AI_ASSISTANT_PROMPT = `# OPIK Agentic Onboarding

## Goals

You must help me:

1. Integrate the Opik client with my existing LLM application
2. Set up tracing for my LLM calls and chains

## Rules

Before you begin, you must understand and strictly adhere to these core principles:

1. Code Preservation & Integration Guidelines:

   - Existing business logic must remain untouched and unmodified
   - Only add Opik-specific code (decorators, imports, handlers, env vars)
   - Integration must be non-invasive and backwards compatible

2. Process Requirements:

   - Follow the workflow steps sequentially without deviation
   - Validate completion of each step before proceeding
   - Request explicit approval for any workflow modifications

3. Documentation & Resources:

   - Reference official Opik documentation at https://www.comet.com/docs/opik/quickstart.md
   - Follow Opik best practices and recommended patterns
   - Maintain detailed integration notes and configuration details

4. Testing & Validation:
   - Verify Opik integration without impacting existing functionality
   - Validate tracing works correctly for all LLM interactions
   - Ensure proper error handling and logging

## Integration Workflow

### Step 1: Language and Compatibility Check

First, analyze the codebase to identify:

1. Primary programming language and frameworks
2. Existing LLM integrations and patterns

Compatibility Requirements:

- Supported Languages: Python, JavaScript/TypeScript

If the codebase uses unsupported languages:

- Stop immediately
- Inform me that the codebase is unsupported for AI integration

Only proceed to Step 2 if:

- Language is Python or JavaScript/TypeScript

### Step 2: Codebase Discovery & Entrypoint Confirmation

After verifying language compatibility, perform a full codebase scan with the following objectives:

- LLM Touchpoints: Locate all files and functions that invoke or interface with LLMs or can be a candidates for tracing.
- Entrypoint Detection: Identify the primary application entry point(s) (e.g., main script, API route, CLI handler). If ambiguous, pause and request clarification on which component(s) are most important to trace before proceeding.
  ⚠️ Do not proceed to Step 3 without explicit confirmation if the entrypoint is unclear.
- Return the LLM Touchpoints to me

### Step 3: Discover Available Integrations

After I confirm the LLM Touchpoints and entry point, find the list of supported integrations at https://www.comet.com/docs/opik/integrations/overview.md

### Step 4: Deep Analysis Confirmed files for LLM Frameworks & SDKs

Using the files confirmed in Step 2, perform targeted inspection to detect specific LLM-related technologies in use, such as:
SDKs: openai, anthropic, huggingface, etc.
Frameworks: LangChain, LlamaIndex, Haystack, etc.

### Step 5: Pre-Implementation Development Plan (Approval Required)

Do not write or modify code yet. You must propose me a step-by-step plan including:

- Opik packages to install
- Files to be modified
- Code snippets for insertion, clearly scoped and annotated
- Where to place Opik API keys, with placeholder comments (Visit https://comet.com/opik/your-workspace-name/get-started to copy your API key)
  Wait for approval before proceeding!

### Step 6: Execute the Integration Plan

After approval:

- Run the package installation command via terminal (pip install opik, npm install opik, etc.).
- Apply code modifications exactly as described in Step 5.
- Keep all additions minimal and non-invasive.
  Upon completion, review the changes made and confirm installation success.

### Step 7: Request User Review and Wait

Notify me that all integration steps are complete.
"Please run the application and verify if Opik is capturing traces as expected. Let me know if you need adjustments."

### Step 8: Debugging Loop (If Needed)

If issues are reported:

1. Parse the error or unexpected behavior from feedback.
2. Re-query the Opik docs using https://www.comet.com/docs/opik/quickstart.md if needed.
3. Propose a minimal fix and await approval.
4. Apply and revalidate.
`;

const QUICKSTART_DOCS_LINK = "https://www.comet.com/docs/opik/quickstart";
const CURSOR_PROMPT_URL = `https://cursor.com/link/prompt?text=${encodeURIComponent(
  AI_ASSISTANT_PROMPT,
)}`;

type QuickInstallDialogProps = {
  open: boolean;
  onClose: () => void;
};

const QuickInstallDialog: React.FunctionComponent<QuickInstallDialogProps> = ({
  open,
  onClose,
}) => {
  const handleOpenChange = (newOpen: boolean) => {
    if (!newOpen) {
      onClose();
    }
  };

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="max-w-[720px] gap-2">
        <DialogHeader>
          <DialogTitle className="flex flex-col gap-0.5">
            <div className="-ml-1 flex gap-1 pr-2">
              <img
                alt="Cursor"
                src={cursorLogo}
                className="size-[32px] shrink-0"
              />
              <img
                alt="Copilot"
                src={copilotLogo}
                className="size-[32px] shrink-0"
              />
              <img
                alt="Windsurf"
                src={windsurfLogo}
                className="size-[32px] shrink-0"
              />
            </div>
            Quick install with AI assistants
          </DialogTitle>
        </DialogHeader>

        <DialogAutoScrollBody className="border-0">
          <div className="space-y-6">
            <div className="comet-body-s text-muted-slate">
              Get Opik integrated instantly by copying this prompt to Cursor,
              Claude, or any AI coding assistant{" "}
              <a
                href={QUICKSTART_DOCS_LINK}
                target="_blank"
                rel="noopener noreferrer"
                className="inline-flex items-center gap-1 text-primary hover:underline dark:text-primary-hover"
              >
                Read the full guide
                <ExternalLink className="size-3" />
              </a>{" "}
              in our docs.
            </div>

            <IntegrationStep
              title="Set up Opik"
              description="Use this prompt with your AI coding assistant of choice to integrate Opik with you application."
              className="mb-6"
            >
              <div className="relative overflow-hidden rounded-md bg-primary-foreground">
                <div className="flex items-center justify-between gap-2 border-b border-b-border p-2">
                  <div className="comet-body-s-accented px-2 text-foreground">
                    AI assistant prompt
                  </div>
                  <div className="flex items-center gap-2">
                    <Button
                      size="xs"
                      asChild
                      id="quick-install-try-in-cursor"
                      data-fs-element="QuickInstallTryInCursor"
                    >
                      <a
                        href={CURSOR_PROMPT_URL}
                        target="_blank"
                        rel="noopener noreferrer"
                      >
                        Try in Cursor
                        <ExternalLink className="ml-1.5 size-3" />
                      </a>
                    </Button>
                    <CopyButton
                      message="Successfully copied code"
                      text={AI_ASSISTANT_PROMPT}
                      tooltipText="Copy code"
                      className="text-muted-slate"
                      id="quick-install-copy-prompt"
                      data-fs-element="QuickInstallCopyPrompt"
                    />
                  </div>
                </div>
                <pre className="max-h-[300px] select-all overflow-auto whitespace-pre-wrap p-4 font-code text-sm">
                  {AI_ASSISTANT_PROMPT}
                </pre>
              </div>
            </IntegrationStep>

            {/* <div>
              <h3 className="comet-title-s mb-3">
                2. Wait for prompt response
              </h3>
              <div className="flex items-center gap-3 rounded-lg border bg-background p-4">
                <div className="flex items-center gap-2">
                  <div
                    className={`size-2 rounded-full ${
                      isWaitingForData
                        ? "animate-pulse bg-blue-500"
                        : "bg-green-500"
                    }`}
                  />
                  <span className="comet-body-s text-muted-slate">
                    {isWaitingForData ? "Waiting for data" : "Data received"}
                  </span>
                </div>
              </div>
              <div className="comet-body-s mt-2 text-muted-slate">
                If everything is set up correctly, your data should start
                flowing into the Opik platform.
              </div>
            </div> */}
          </div>

          <Separator className="my-6" />
          <HelpLinks
            onCloseParentDialog={onClose}
            title="Need some help?"
            description="Get help from your team or ours. Choose the option that works best for you."
          >
            <HelpLinks.InviteDev />
            <HelpLinks.Slack />
            <HelpLinks.WatchTutorial />
          </HelpLinks>
        </DialogAutoScrollBody>
      </DialogContent>
    </Dialog>
  );
};

export default QuickInstallDialog;
