import React, { useState } from "react";
import { ChevronDown, ChevronUp, CircleAlert } from "lucide-react";
import { Button } from "@/ui/button";
import { ModelUsed } from "@/lib/model-prices";

type NoPriceBannerProps = {
  models: ModelUsed[];
  scope: "workspace" | "organization";
  canEdit: boolean;
  onAddPrices: (model: ModelUsed) => void;
};

const NoPriceBanner: React.FC<NoPriceBannerProps> = ({
  models,
  scope,
  canEdit,
  onAddPrices,
}) => {
  const [open, setOpen] = useState(false);
  if (!models.length) {
    return null;
  }
  const count = models.length;
  const title = `${count} ${
    count === 1 ? "model" : "models"
  } in your ${scope} ${count === 1 ? "has" : "have"} no price`;
  return (
    <div
      className="mb-6 rounded-md border border-warning-box-text/30 bg-warning-box-bg"
      data-testid="no-price-banner"
    >
      <button
        type="button"
        className="flex w-full items-center gap-2 p-4 text-left"
        onClick={() => setOpen((value) => !value)}
        aria-expanded={open}
      >
        <CircleAlert className="size-4 shrink-0 text-warning-box-text" />
        <span className="comet-body-s flex-1 font-semibold text-warning-box-text">
          {title}
        </span>
        {open ? (
          <ChevronUp className="size-4 text-warning-box-text" />
        ) : (
          <ChevronDown className="size-4 text-warning-box-text" />
        )}
      </button>
      {open && (
        <div className="px-4 pb-4">
          <p className="comet-body-xs mb-3 text-warning-box-text/80">
            These appear in your spans but aren&apos;t in the price table, so
            their cost can&apos;t be estimated.
          </p>
          <ul className="divide-y rounded-md border bg-background">
            {models.map((model) => (
              <li
                key={`${model.model}|${model.provider}`}
                className="flex items-center gap-3 px-3 py-2"
              >
                <span className="comet-code break-all">{model.model}</span>
                <span className="comet-body-xs flex-1 text-muted-slate">
                  {model.provider ? `${model.provider} · ` : ""}used on{" "}
                  {model.days_used} {model.days_used === 1 ? "day" : "days"}
                </span>
                {canEdit && (
                  <Button
                    size="xs"
                    variant="outline"
                    onClick={() => onAddPrices(model)}
                  >
                    Add prices
                  </Button>
                )}
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
};

export default NoPriceBanner;
