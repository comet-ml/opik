import React, { useEffect } from "react";
import {
  ChevronFirst,
  ChevronLast,
  ChevronLeft,
  ChevronRight,
  Loader2,
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
  variant?: "default" | "minimal";
  itemsPerPage?: number[];
  disabled?: boolean;
  isLoadingTotal?: boolean;
};

const DEFAULT_ITEMS_PER_PAGE = [5, 10, 25, 50, 100];

const DataTablePagination = ({
  page = 1,
  pageChange,
  size = 10,
  total,
  sizeChange,
  supportsTruncation = false,
  truncationEnabled = true,
  variant = "default",
  itemsPerPage = DEFAULT_ITEMS_PER_PAGE,
  disabled = false,
  isLoadingTotal = false,
}: DataTableProps) => {
  const maxSize =
    supportsTruncation && !truncationEnabled
      ? TRUNCATION_DISABLED_MAX_PAGE_SIZE
      : undefined;

  const showWarning = supportsTruncation && !truncationEnabled;
  const isMinimal = variant === "minimal";

  const from = Math.max(size * (page - 1) + 1, 0);
  const to = Math.min(size * page, total);
  const totalPages = Math.ceil(total / size);
  const disabledPrevious = page === 1 || disabled;
  const disabledNext = page === totalPages || !totalPages || disabled;
  const disabledSizeChange = !isFunction(sizeChange) || disabled;

  const totalDisplay = isLoadingTotal ? (
    <span className="inline-flex items-center gap-1 pl-1">
      <Loader2 className="size-3 animate-spin" />
      {total.toLocaleString()}
    </span>
  ) : (
    total.toLocaleString()
  );

  const textPrefix = isMinimal
    ? `${from}-${to} of `
    : `Showing ${from}-${to} of `;
  const buttonSize = isMinimal ? "icon-xs" : "icon-sm";
  const navButtonVariant = isMinimal ? "ghost" : "outline";
  const buttonClass = isMinimal ? "w-5" : "";

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
    <div
      className={`flex flex-row justify-end ${isMinimal ? "gap-1" : "gap-4"} ${
        disabled ? "pointer-events-none opacity-50" : ""
      }`}
    >
      {showWarning && <TruncationDisabledWarning />}
      <div className={`flex flex-row ${isMinimal ? "gap-0.5" : "gap-2"}`}>
        <Button
          variant={navButtonVariant}
          size={buttonSize}
          disabled={disabledPrevious}
          onClick={() => pageChange(1)}
          className={buttonClass}
        >
          <ChevronFirst />
        </Button>
        <Button
          variant={navButtonVariant}
          size={buttonSize}
          disabled={disabledPrevious}
          onClick={() => pageChange(page - 1)}
          className={buttonClass}
        >
          <ChevronLeft />
        </Button>
        <div className="flex flex-row items-center gap-1">
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button
                variant="ghost"
                size="sm"
                className={`min-w-4 px-4 ${
                  isMinimal ? "h-6 px-0 leading-none focus-visible:ring-0" : ""
                }`}
                disabled={disabledSizeChange}
              >
                {textPrefix}
                {totalDisplay}
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end">
              {itemsPerPage.map((count) => {
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
          variant={navButtonVariant}
          size={buttonSize}
          disabled={disabledNext}
          className={buttonClass}
          onClick={() => pageChange(page + 1)}
        >
          <ChevronRight />
        </Button>
        <Button
          variant={navButtonVariant}
          size={buttonSize}
          disabled={disabledNext}
          className={buttonClass}
          onClick={() => pageChange(totalPages)}
        >
          <ChevronLast />
        </Button>
      </div>
    </div>
  );
};

export default DataTablePagination;
