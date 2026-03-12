import React from "react";
import { Rocket } from "lucide-react";

type ProdTagSize = "xs" | "sm";

type ProdTagProps = {
  size?: ProdTagSize;
  value: string;
};

const SIZE_CLASSES: Record<ProdTagSize, string> = {
  xs: "comet-body-xs-accented gap-1 rounded px-1 py-px",
  sm: "comet-body-s-accented gap-1.5 rounded-md px-1.5 py-0.5",
};

const ICON_SIZE: Record<ProdTagSize, string> = {
  xs: "size-3",
  sm: "size-3.5",
};

const ProdTag: React.FC<ProdTagProps> = ({ size = "sm", value = "Prod" }) => {
  return (
    <div
      className={`inline-flex items-center bg-[var(--tag-lime-bg)] text-[var(--tag-lime-text)] ${SIZE_CLASSES[size]}`}
    >
      <Rocket className={`shrink-0 ${ICON_SIZE[size]}`} />
      {value}
    </div>
  );
};

export default ProdTag;
