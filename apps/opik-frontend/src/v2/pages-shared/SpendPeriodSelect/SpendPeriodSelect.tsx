import React from "react";
import { Tabs, TabsList, TabsTrigger } from "@/ui/tabs";
import { SPEND_WINDOWS, SpendWindow } from "@/lib/aiSpend";

interface SpendPeriodSelectProps {
  value: SpendWindow;
  onChange: (value: SpendWindow) => void;
}

const SpendPeriodSelect: React.FC<SpendPeriodSelectProps> = ({
  value,
  onChange,
}) => (
  <Tabs
    value={String(value)}
    onValueChange={(next) => onChange(Number(next) as SpendWindow)}
  >
    <TabsList variant="segmented">
      {SPEND_WINDOWS.map((w) => (
        <TabsTrigger key={w} variant="segmented" value={String(w)}>
          {w}d
        </TabsTrigger>
      ))}
    </TabsList>
  </Tabs>
);

export default SpendPeriodSelect;
