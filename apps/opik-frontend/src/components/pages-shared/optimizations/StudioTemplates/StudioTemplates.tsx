import React from "react";
import { useNavigate } from "@tanstack/react-router";
import {
  SquareDashedMousePointer,
  MessageSquare,
  FileJson,
} from "lucide-react";
import useAppStore from "@/store/AppStore";
import { OPTIMIZATION_DEMO_TEMPLATES } from "@/constants/optimizations";
import OptimizationTemplateCard from "./OptimizationTemplateCard";

const TEMPLATE_ICONS: Record<
  string,
  { icon: React.ComponentType<{ className?: string }>; color: string }
> = {
  "opik-chatbot": {
    icon: MessageSquare,
    color: "text-template-icon-performance",
  },
  "json-output": {
    icon: FileJson,
    color: "text-template-icon-metrics",
  },
};

const StudioTemplates: React.FC = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const navigate = useNavigate();

  const handleTemplateClick = (templateId?: string) => {
    navigate({
      to: "/$workspaceName/optimizations/new",
      params: { workspaceName },
      search: templateId ? { template: templateId } : undefined,
    });
  };

  return (
    <div className="pt-6">
      <h2 className="comet-title-s sticky top-0 z-10 truncate break-words bg-soft-background pb-3 pt-2">
        Run an optimization
      </h2>
      <div className="flex flex-col">
        <div className="flex items-stretch gap-6">
          <div className="flex gap-4">
            {OPTIMIZATION_DEMO_TEMPLATES.map((template) => {
              const iconConfig = TEMPLATE_ICONS[template.id];
              return (
                <div key={template.id} className="w-80">
                  <OptimizationTemplateCard
                    name={template.title}
                    description={template.description}
                    icon={iconConfig?.icon}
                    iconColor={iconConfig?.color}
                    tags={[
                      template.studio_config?.optimizer.type || "",
                      template.studio_config?.evaluation.metrics[0]?.type || "",
                    ].filter(Boolean)}
                    interactive
                    onClick={() => handleTemplateClick(template.id)}
                  />
                </div>
              );
            })}
          </div>

          <div className="flex flex-col items-center gap-2">
            <div className="w-px flex-1 bg-slate-200" />
            <p className="comet-body-xs whitespace-nowrap text-light-slate">
              or
            </p>
            <div className="w-px flex-1 bg-slate-200" />
          </div>

          <div className="flex w-80">
            <OptimizationTemplateCard
              name="Optimize your own prompt"
              description="Start from scratch and configure your own optimization settings."
              icon={SquareDashedMousePointer}
              iconColor="text-template-icon-scratch"
              interactive
              onClick={() => handleTemplateClick()}
            />
          </div>
        </div>
      </div>
    </div>
  );
};

export default StudioTemplates;
