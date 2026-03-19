import React, { useMemo } from "react";
import isObject from "lodash/isObject";
import uniq from "lodash/uniq";

import { Tag } from "@/ui/tag";

type ToolsDiffProps = {
  baseline: unknown[];
  current: unknown[];
};

const extractNames = (items: unknown[]) =>
  items.map((t) =>
    isObject(t) && "name" in (t as Record<string, unknown>)
      ? String((t as Record<string, unknown>).name)
      : JSON.stringify(t),
  );

const ToolsDiff: React.FunctionComponent<ToolsDiffProps> = ({
  baseline,
  current,
}) => {
  const baseNames = useMemo(() => extractNames(baseline), [baseline]);
  const currNames = useMemo(() => extractNames(current), [current]);

  const allNames = uniq([...baseNames, ...currNames]);

  return (
    <div className="flex flex-wrap gap-1.5">
      {allNames.map((name) => {
        const inBase = baseNames.includes(name);
        const inCurr = currNames.includes(name);

        if (inBase && inCurr) {
          return (
            <Tag key={name} variant="gray" size="sm">
              {name}
            </Tag>
          );
        }
        if (inCurr && !inBase) {
          return (
            <Tag key={name} variant="green" size="sm">
              + {name}
            </Tag>
          );
        }
        return (
          <Tag key={name} variant="red" size="sm">
            - {name}
          </Tag>
        );
      })}
    </div>
  );
};

export default ToolsDiff;
