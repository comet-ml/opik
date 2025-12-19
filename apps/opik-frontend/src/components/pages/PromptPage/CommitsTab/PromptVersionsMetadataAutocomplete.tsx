import React, { useMemo } from "react";
import uniq from "lodash/uniq";
import isObject from "lodash/isObject";
import isArray from "lodash/isArray";

import Autocomplete from "@/components/shared/Autocomplete/Autocomplete";
import usePromptVersionsById from "@/api/prompts/usePromptVersionsById";
import { Prompt } from "@/types/prompts";
import { getJSONPaths } from "@/lib/utils";

type PromptVersionsMetadataAutocompleteProps = {
  prompt: Prompt;
  hasError?: boolean;
  value: string;
  onValueChange: (value: string) => void;
  placeholder?: string;
};

const PromptVersionsMetadataAutocomplete: React.FC<
  PromptVersionsMetadataAutocompleteProps
> = ({ prompt, hasError, value, onValueChange, placeholder = "key" }) => {
  // Fetch recent versions to extract metadata keys
  const { data, isPending } = usePromptVersionsById({
    promptId: prompt.id,
    page: 1,
    size: 100,
  });

  const items = useMemo(() => {
    const key = "metadata";

    return uniq(
      (data?.content || []).reduce<string[]>((acc, version) => {
        return acc.concat(
          isObject(version[key]) || isArray(version[key])
            ? getJSONPaths(version[key], key).map((path) =>
                path.substring(path.indexOf(".") + 1),
              )
            : [],
        );
      }, []),
    )
      .filter((p) =>
        value ? p.toLowerCase().includes(value.toLowerCase()) : true,
      )
      .sort();
  }, [data, value]);

  return (
    <Autocomplete
      value={value}
      onValueChange={onValueChange}
      items={items}
      hasError={hasError}
      isLoading={isPending}
      placeholder={placeholder}
    />
  );
};

export default PromptVersionsMetadataAutocomplete;
