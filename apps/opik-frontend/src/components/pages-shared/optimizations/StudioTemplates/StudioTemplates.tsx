import React from "react";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import ColoredTag from "@/components/shared/ColoredTag/ColoredTag";
import { Zap } from "lucide-react";
import { Link } from "@tanstack/react-router";
import useAppStore from "@/store/AppStore";
import { OPTIMIZATION_DEMO_TEMPLATES } from "@/constants/optimizations";

const StudioTemplates: React.FC = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  return (
    <div className="pt-6">
      <h2 className="comet-title-s sticky top-0 z-10 truncate break-words bg-soft-background pb-3 pt-2">
        Run an optimization
      </h2>
      <div className="grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-3">
        <Card className="cursor-pointer transition-shadow hover:shadow-md">
          <Link
            to="/$workspaceName/optimizations/new"
            params={{ workspaceName }}
            className="block"
          >
            <CardHeader className="pb-3">
              <CardTitle className="comet-body-m-accented flex items-center gap-2">
                <Zap className="size-4" />
                Optimize your prompt
              </CardTitle>
              <CardDescription className="comet-body-s">
                Start from scratch and configure your own optimization
              </CardDescription>
            </CardHeader>
            <CardContent className="pt-0">
              <div className="flex flex-wrap gap-2">
                <ColoredTag label="Custom setup" size="default" />
              </div>
            </CardContent>
          </Link>
        </Card>

        {OPTIMIZATION_DEMO_TEMPLATES.map((template) => (
          <Card
            key={template.id}
            className="cursor-pointer transition-shadow hover:shadow-md"
          >
            <Link
              to="/$workspaceName/optimizations/new"
              params={{ workspaceName }}
              search={{ template: template.id }}
              className="block"
            >
              <CardHeader className="pb-3">
                <CardTitle className="comet-body-m-accented">
                  {template.title}
                </CardTitle>
                <CardDescription className="comet-body-s">
                  {template.description}
                </CardDescription>
              </CardHeader>
              <CardContent className="pt-0">
                <div className="flex flex-wrap gap-2">
                  <ColoredTag
                    label={template.studio_config?.optimizer.type || ""}
                    size="default"
                  />
                  <ColoredTag
                    label={
                      template.studio_config?.evaluation.metrics[0]?.type || ""
                    }
                    size="default"
                  />
                </div>
              </CardContent>
            </Link>
          </Card>
        ))}
      </div>
    </div>
  );
};

export default StudioTemplates;
