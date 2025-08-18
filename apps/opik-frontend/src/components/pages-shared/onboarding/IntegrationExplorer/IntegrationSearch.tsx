import React from "react";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import { useIntegrationExplorer } from "./IntegrationExplorerContext";
import { cn } from "@/lib/utils";

type IntegrationSearchProps = {
  placeholder?: string;
  className?: string;
};

const IntegrationSearch: React.FunctionComponent<IntegrationSearchProps> = ({
  placeholder = "Search integration",
  className,
}) => {
  const { searchText, setSearchText } = useIntegrationExplorer();

  return (
    <SearchInput
      searchText={searchText}
      setSearchText={setSearchText}
      placeholder={placeholder}
      className={cn("max-w-[240px]", className)}
      size="sm"
    />
  );
};

export default IntegrationSearch;
