import React from "react";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { ExperimentItem } from "@/types/datasets";

type MultiRunTabsProps = {
  experimentItems: ExperimentItem[];
  renderRunContent: (item: ExperimentItem, idx: number) => React.ReactNode;
};

const MultiRunTabs: React.FunctionComponent<MultiRunTabsProps> = ({
  experimentItems,
  renderRunContent,
}) => {
  if (experimentItems.length <= 1) {
    return experimentItems[0] ? renderRunContent(experimentItems[0], 0) : null;
  }

  return (
    <Tabs defaultValue="0" className="w-full">
      <TabsList>
        {experimentItems.map((_, idx) => (
          <TabsTrigger key={idx} value={String(idx)}>
            Run {idx + 1}
          </TabsTrigger>
        ))}
      </TabsList>
      {experimentItems.map((item, idx) => (
        <TabsContent key={idx} value={String(idx)}>
          {renderRunContent(item, idx)}
        </TabsContent>
      ))}
    </Tabs>
  );
};

export default MultiRunTabs;
