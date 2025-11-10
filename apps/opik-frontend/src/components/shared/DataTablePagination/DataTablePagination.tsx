import React, { useEffect } from "react";
import {
  ChevronFirst,
  ChevronLast,
  ChevronLeft,
  ChevronRight,
} from "lucide-react";
import isFunction from "lodash/isFunction";

import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import TruncationDisabledWarning from "./TruncationDisabledWarning";
import { TRUNCATION_DISABLED_MAX_PAGE_SIZE } from "@/constants/shared";

type DataTableProps = {
  page: number;
  pageChange: (page: number) => void;
  size: number;
  total: number;
  sizeChange?: (number: number) => void;
  supportsTruncation?: boolean;
  truncationEnabled?: boolean;
};

const ITEMS_PER_PAGE = [5, 10, 25, 50, 100];

const DataTablePagination = ({
  page = 1,
  pageChange,
  size = 10,
  total,
  sizeChange,
  supportsTruncation = false,
  truncationEnabled = true,
}: DataTableProps) => {
  const maxSize =
    supportsTruncation && !truncationEnabled
      ? TRUNCATION_DISABLED_MAX_PAGE_SIZE
      : undefined;

  const showWarning = supportsTruncation && !truncationEnabled;

  const from = Math.max(size * (page - 1) + 1, 0);
  const to = Math.min(size * page, total);
  const totalPages = Math.ceil(total / size);
  const disabledPrevious = page === 1;
  const disabledNext = page === totalPages || !totalPages;
  const disabledSizeChange = !isFunction(sizeChange);

  useEffect(() => {
    if (maxSize && size > maxSize && sizeChange) {
      sizeChange(maxSize);
    }
  }, [maxSize, size, sizeChange]);

  useEffect(() => {
    if (page !== 1 && (page - 1) * size > total) {
      pageChange(1);
    }
  }, [total, page, size, pageChange]);

  return (
    <div className="flex flex-row justify-end gap-4">
      {showWarning && <TruncationDisabledWarning />}
      <div className="flex flex-row gap-2">
        <Button
          variant="outline"
          size="icon-sm"
          disabled={disabledPrevious}
          onClick={() => pageChange(1)}
        >
          <ChevronFirst />
        </Button>
        <Button
          variant="outline"
          size="icon-sm"
          disabled={disabledPrevious}
          onClick={() => pageChange(page - 1)}
        >
          <ChevronLeft />
        </Button>
        <div className="flex flex-row items-center gap-1">
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button
                variant="ghost"
                size="sm"
                className="min-w-4 px-4"
                disabled={disabledSizeChange}
              >
                {`Showing ${from}-${to} of ${total}`}
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end">
              {ITEMS_PER_PAGE.map((count) => {
                const isDisabled =
                  disabledSizeChange ||
                  (maxSize !== undefined && count > maxSize);
                return (
                  <DropdownMenuCheckboxItem
                    key={count}
                    onSelect={() => !isDisabled && sizeChange(count)}
                    checked={count === size}
                    disabled={isDisabled}
                  >
                    {count}
                  </DropdownMenuCheckboxItem>
                );
              })}
            </DropdownMenuContent>
          </DropdownMenu>
        </div>
        <Button
          variant="outline"
          size="icon-sm"
          disabled={disabledNext}
          onClick={() => pageChange(page + 1)}
        >
          <ChevronRight />
        </Button>
        <Button
          variant="outline"
          size="icon-sm"
          disabled={disabledNext}
          onClick={() => pageChange(totalPages)}
        >
          <ChevronLast />
        </Button>
      </div>
    </div>
  );
};

export default DataTablePagination;
