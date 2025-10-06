/* eslint-disable @typescript-eslint/no-explicit-any */
import React from "react";
import { PROVIDER_TYPE } from "@/types/providers";
import { PrettyViewData } from "@/lib/provider-schemas";
import { getProviderDisplayName } from "@/lib/provider-detection";
import {
  getProviderBadgeVariant,
  getProviderIcon,
  getProviderClassName,
} from "@/lib/provider-styling";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Tag } from "@/components/ui/tag";
import MarkdownPreview from "@/components/shared/MarkdownPreview/MarkdownPreview";
import { cn } from "@/lib/utils";

interface ProviderPrettyViewProps {
  provider: PROVIDER_TYPE;
  data: PrettyViewData;
  type: "input" | "output";
  className?: string;
}

const ProviderPrettyView: React.FC<ProviderPrettyViewProps> = ({
  provider,
  data,
  type,
  className,
}) => {
  const providerName = getProviderDisplayName(provider);

  return (
    <Card className={cn("w-full", className)}>
      <CardHeader className="pb-3">
        <div className="flex items-center justify-between">
          <CardTitle className="comet-title-s">
            {type === "input" ? "Input" : "Output"}
          </CardTitle>
          <div className="flex items-center gap-2">
            <Tag
              variant={getProviderBadgeVariant(provider)}
              className="comet-body-xs"
            >
              <span className="mr-1">{getProviderIcon(provider)}</span>
              {providerName}
            </Tag>
            {data.metadata?.model && (
              <Tag variant="default" className="comet-body-xs">
                {data.metadata.model}
              </Tag>
            )}
          </div>
        </div>
      </CardHeader>
      <CardContent className="pt-0">
        <div className="space-y-4">
          {/* Content */}
          <div>
            <MarkdownPreview className="comet-body">
              {data.content}
            </MarkdownPreview>
          </div>

          {/* Metadata */}
          {data.metadata && (
            <div className="border-t pt-4">
              <h4 className="comet-body-s-accented mb-3">Metadata</h4>
              <div className="grid grid-cols-1 gap-3 md:grid-cols-2 lg:grid-cols-3">
                {data.metadata.model && (
                  <div>
                    <span className="comet-body-xs text-muted-foreground">
                      Model:
                    </span>
                    <p
                      className={getProviderClassName(provider, "comet-body-s")}
                    >
                      {data.metadata.model}
                    </p>
                  </div>
                )}
                {data.metadata.temperature !== undefined && (
                  <div>
                    <span className="comet-body-xs text-muted-foreground">
                      Temperature:
                    </span>
                    <p className="comet-body-s">{data.metadata.temperature}</p>
                  </div>
                )}
                {data.metadata.max_tokens && (
                  <div>
                    <span className="comet-body-xs text-muted-foreground">
                      Max Tokens:
                    </span>
                    <p className="comet-body-s">{data.metadata.max_tokens}</p>
                  </div>
                )}
                {data.metadata.finish_reason && (
                  <div>
                    <span className="comet-body-xs text-muted-foreground">
                      Finish Reason:
                    </span>
                    <p className="comet-body-s">
                      {data.metadata.finish_reason}
                    </p>
                  </div>
                )}
                {data.metadata.stop_reason && (
                  <div>
                    <span className="comet-body-xs text-muted-foreground">
                      Stop Reason:
                    </span>
                    <p className="comet-body-s">{data.metadata.stop_reason}</p>
                  </div>
                )}
              </div>

              {/* Usage Information */}
              {data.metadata.usage && (
                <div className="mt-4">
                  <h5 className="comet-body-s-accented mb-2">Usage</h5>
                  <div className="grid grid-cols-1 gap-2 md:grid-cols-3">
                    {data.metadata.usage.prompt_tokens !== undefined && (
                      <div className="rounded-md bg-muted p-2">
                        <span className="comet-body-xs text-muted-foreground">
                          Prompt Tokens:
                        </span>
                        <p className="comet-body-s font-medium">
                          {data.metadata.usage.prompt_tokens.toLocaleString()}
                        </p>
                      </div>
                    )}
                    {data.metadata.usage.completion_tokens !== undefined && (
                      <div className="rounded-md bg-muted p-2">
                        <span className="comet-body-xs text-muted-foreground">
                          Completion Tokens:
                        </span>
                        <p className="comet-body-s font-medium">
                          {data.metadata.usage.completion_tokens.toLocaleString()}
                        </p>
                      </div>
                    )}
                    {data.metadata.usage.total_tokens !== undefined && (
                      <div className="rounded-md bg-muted p-2">
                        <span className="comet-body-xs text-muted-foreground">
                          Total Tokens:
                        </span>
                        <p className="comet-body-s font-medium">
                          {data.metadata.usage.total_tokens.toLocaleString()}
                        </p>
                      </div>
                    )}
                  </div>
                </div>
              )}
            </div>
          )}
        </div>
      </CardContent>
    </Card>
  );
};

export default ProviderPrettyView;
