import React, { useMemo } from "react";
import { ChartNoAxesColumn } from "lucide-react";
import CodeHighlighter, {
  SUPPORTED_LANGUAGE,
} from "@/shared/CodeHighlighter/CodeHighlighter";
import { BASE_API_URL } from "@/api/api";
import { useAiSpend } from "@/contexts/AiSpendContext";

const AiSpendEmptyState: React.FC = () => {
  const { projectName, spendWorkspaceName } = useAiSpend();

  const settingsSnippet = useMemo(
    () =>
      JSON.stringify(
        {
          extraKnownMarketplaces: {
            opik: {
              source: {
                source: "github",
                repo: "comet-ml/opik-claude-code-plugin",
              },
              autoUpdate: true,
            },
          },
          enabledPlugins: { "opik@opik": true },
          env: {
            OPIK_CC_TRACING_ENABLED: "true",
            OPIK_BASE_URL: new URL(
              BASE_API_URL,
              window.location.origin,
            ).toString(),
            OPIK_CC_WORKSPACE: spendWorkspaceName || "your-org-cc-workspace",
            OPIK_API_KEY: "<workspace-scoped API key>",
            OPIK_CC_PROJECT: projectName,
          },
          forceRemoteSettingsRefresh: true,
        },
        null,
        2,
      ),
    [projectName, spendWorkspaceName],
  );

  return (
    <div className="flex flex-col items-center gap-6 py-16">
      <div className="flex size-12 items-center justify-center rounded-full bg-primary-foreground">
        <ChartNoAxesColumn className="size-6 text-muted-slate" />
      </div>
      <div className="flex flex-col items-center gap-2">
        <h2 className="comet-title-s text-foreground">No AI usage data yet</h2>
        <p className="comet-body-s max-w-[570px] text-center text-muted-slate">
          Cost Intelligence builds these dashboards from Claude Code sessions
          traced by the Opik plugin. Roll the plugin out to your team to start
          collecting data.
        </p>
      </div>
      <div className="flex w-full max-w-[640px] flex-col gap-2">
        <span className="comet-body-s text-foreground">
          Add this to your organization&apos;s managed settings or each
          developer&apos;s .claude/settings.json:
        </span>
        <CodeHighlighter
          data={settingsSnippet}
          language={SUPPORTED_LANGUAGE.json}
        />
        <span className="comet-body-xs text-muted-slate">
          Replace OPIK_API_KEY with a workspace-scoped API key. Data appears
          here shortly after the first traced session.
        </span>
      </div>
    </div>
  );
};

export default AiSpendEmptyState;
