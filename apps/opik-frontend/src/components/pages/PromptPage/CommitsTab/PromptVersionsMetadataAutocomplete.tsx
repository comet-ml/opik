import React, { useMemo } from "react";
import uniq from "lodash/uniq";
import isObject from "lodash/isObject";

import Autocomplete from "@/components/shared/Autocomplete/Autocomplete";
import usePromptVersionsById from "@/api/prompts/usePromptVersionsById";
import { Prompt } from "@/types/prompts";

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

  const metadataKeys = useMemo(() => {
    const versions = data?.content || [];

    // Extract all unique metadata keys from versions
    const keys = versions.reduce<string[]>((acc, version) => {
      if (version.metadata && isObject(version.metadata)) {
        return acc.concat(Object.keys(version.metadata));
      }
      return acc;
    }, []);

    // Deduplicate and sort
    const uniqueKeys = uniq(keys).sort();

    // Filter based on current input value
    return uniqueKeys.filter((key) =>
      value ? key.toLowerCase().includes(value.toLowerCase()) : true,
    );
  }, [data, value]);

  return (
    <Autocomplete
      value={value}
      onValueChange={onValueChange}
      items={metadataKeys}
      hasError={hasError}
      isLoading={isPending}
      placeholder={placeholder}
    />
  );
};

export default PromptVersionsMetadataAutocomplete;
