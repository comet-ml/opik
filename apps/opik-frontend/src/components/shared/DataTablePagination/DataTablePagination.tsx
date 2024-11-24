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

type DataTableProps = {
  page: number;
  pageChange: (page: number) => void;
  size: number;
  total: number;
  sizeChange?: (number: number) => void;
};

const ITEMS_PER_PAGE = [5, 10, 25, 50, 100];

const DataTablePagination = ({
  page = 1,
  pageChange,
  size = 10,
  total,
  sizeChange,
}: DataTableProps) => {
  const from = Math.max(size * (page - 1) + 1, 0);
  const to = Math.min(size * page, total);
  const totalPages = Math.ceil(total / size);
  const disabledPrevious = page === 1;
  const disabledNext = page === totalPages || !totalPages;
  const disabledSizeChange = !isFunction(sizeChange);

  useEffect(() => {
    if (page !== 1 && (page - 1) * size > total) {
      pageChange(1);
    }
  }, [total, page, size, pageChange]);

  return (
    <div className="flex flex-row justify-end">
      <div className="flex flex-row gap-2">
        <Button
          variant="outline"
          size="icon-sm"
          disabled={disabledPrevious}
          onClick={() => pageChange(1)}
        >
          <ChevronFirst className="size-4" />
        </Button>
        <Button
          variant="outline"
          size="icon-sm"
          disabled={disabledPrevious}
          onClick={() => pageChange(page - 1)}
        >
          <ChevronLeft className="size-4" />
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
                return (
                  <DropdownMenuCheckboxItem
                    key={count}
                    onSelect={() => !disabledSizeChange && sizeChange(count)}
                    checked={count === size}
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
          <ChevronRight className="size-4" />
        </Button>
        <Button
          variant="outline"
          size="icon-sm"
          disabled={disabledNext}
          onClick={() => pageChange(totalPages)}
        >
          <ChevronLast className="size-4" />
        </Button>
      </div>
    </div>
  );
};

export default DataTablePagination;
