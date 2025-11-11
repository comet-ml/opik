import React from "react";
import { useTranslation } from "react-i18next";
import DebounceInput from "@/components/shared/DebounceInput/DebounceInput";
import { Group, GroupRowConfig } from "@/types/groups";
import SortDirectionSelector from "@/components/shared/GroupsButton/SortDirectionSelector";

type DictionaryRowProps = {
  config?: GroupRowConfig;
  group: Group;
  onChange: (group: Group) => void;
};

export const DictionaryRow: React.FC<DictionaryRowProps> = ({
  config,
  group,
  onChange,
}) => {
  const { t } = useTranslation();
  
  const keyValueChangeHandler = (value: unknown) =>
    onChange({ ...group, key: value as string });

  const KeyComponent = config?.keyComponent ?? DebounceInput;

  return (
    <>
      <td className="p-1">
        <KeyComponent
          className="w-full min-w-32 max-w-[30vw]"
          placeholder={t("filters.key")}
          value={group.key}
          onValueChange={keyValueChangeHandler}
          {...(config?.keyComponentProps ?? {})}
        />
      </td>
      <td className="p-1">
        <SortDirectionSelector
          direction={group.direction}
          onSelect={(d) => onChange({ ...group, direction: d })}
        />
      </td>
    </>
  );
};

export default DictionaryRow;
