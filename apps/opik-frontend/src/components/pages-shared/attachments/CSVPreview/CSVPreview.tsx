import React, { useMemo, useState, useRef } from "react";
import { useQuery } from "@tanstack/react-query";
import { useVirtualizer } from "@tanstack/react-virtual";
import isString from "lodash/isString";

import Loader from "@/components/shared/Loader/Loader";
import NoData from "@/components/shared/NoData/NoData";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { Button } from "@/components/ui/button";
import { ChevronLeft, ChevronRight } from "lucide-react";
import { parseCSV, CSVData } from "@/lib/csv";
import { cn } from "@/lib/utils";

interface CSVPreviewProps {
  url: string;
  maxRows?: number;
  showPagination?: boolean;
  enableVirtualScrolling?: boolean;
}

// Virtual scrolling constants matching DataTable patterns
const ROW_HEIGHT = 36; // Height for each row in pixels
const HEADER_HEIGHT = 40; // Height for header row
const MIN_OVERSCAN_ROWS = 2; // Minimum rows to render outside visible area
const MAX_CONTAINER_HEIGHT = 500; // Maximum height for the scrollable container
const VIRTUAL_THRESHOLD = 100; // Use virtual scrolling for datasets larger than this

const CSVPreview: React.FC<CSVPreviewProps> = ({
  url,
  maxRows = 100,
  showPagination = true,
  enableVirtualScrolling = true,
}) => {
  const parentRef = useRef<HTMLDivElement>(null);
  const [currentPage, setCurrentPage] = useState(0);
  const rowsPerPage = 50;

  const {
    data: csvText,
    isPending,
    isError,
    error,
  } = useQuery({
    queryKey: ["csv", url],
    queryFn: async () => {
      try {
        const response = await fetch(url);
        
        if (!response.ok) {
          throw new Error(
            `Failed to fetch CSV file: ${response.status} ${response.statusText}`,
          );
        }
        
        return await response.text();
      } catch (error) {
        let message: string | undefined;
        if (isString(error)) {
          message = error;
        } else if (error instanceof Error) {
          message = error.message;
        }
        throw new Error(
          message ?? "Failed to fetch CSV file. CORS issue or invalid URL.",
        );
      }
    },
  });

  const {
    data: csvData,
    isPending: isParsing,
    isError: isParseError,
    error: parseError,
  } = useQuery({
    queryKey: ["csv-parse", csvText],
    queryFn: async () => {
      if (!csvText || !isString(csvText)) return null;
      return await parseCSV(csvText);
    },
    enabled: Boolean(csvText && isString(csvText)),
  });

  const paginatedData = useMemo(() => {
    if (!csvData) return null;

    const startRow = currentPage * rowsPerPage;
    const endRow = Math.min(
      startRow + rowsPerPage,
      Math.min(csvData.rows.length, maxRows),
    );

    return {
      ...csvData,
      rows: csvData.rows.slice(startRow, endRow),
      totalRows: csvData.rows.length,
      startRow,
      endRow,
    };
  }, [csvData, currentPage, rowsPerPage, maxRows]);

  // Determine if we should use virtual scrolling
  const shouldUseVirtualScrolling = useMemo(() => {
    return enableVirtualScrolling && 
           csvData && 
           csvData.rows.length > VIRTUAL_THRESHOLD &&
           !showPagination; // Don't use virtual scrolling if pagination is enabled
  }, [enableVirtualScrolling, csvData, showPagination]);

  // Virtual scrolling setup for large datasets
  const virtualizer = useVirtualizer({
    count: csvData?.rows?.length || 0,
    getScrollElement: () => parentRef.current,
    estimateSize: () => ROW_HEIGHT,
    overscan: Math.max(MIN_OVERSCAN_ROWS, Math.ceil(MAX_CONTAINER_HEIGHT / ROW_HEIGHT)),
    enabled: Boolean(shouldUseVirtualScrolling),
  });

  const totalPages = useMemo(() => {
    if (!csvData) return 0;
    return Math.ceil(Math.min(csvData.rows.length, maxRows) / rowsPerPage);
  }, [csvData, maxRows, rowsPerPage]);

  const renderContent = () => {
    if (isPending || isParsing) return <Loader />;

    if (isError) return <NoData icon={null} message={error?.message} />;
    
    if (isParseError) {
      return <NoData icon={null} message={`CSV parsing failed: ${parseError?.message || 'Unknown error'}`} />;
    }

    if (!paginatedData || paginatedData.headers.length === 0) {
      return <NoData message="No CSV data found" icon={null} />;
    }

    return (
      <div className="space-y-4">
        {shouldUseVirtualScrolling ? (
          <div className="text-sm text-muted-foreground">
            Showing {Math.min(csvData?.rows?.length || 0, maxRows).toLocaleString()} rows, {csvData?.headers?.length || 0} columns (virtualized for performance)
          </div>
        ) : paginatedData.totalRows > maxRows ? (
          <div className="text-sm text-muted-foreground">
            Showing first {maxRows} rows of {paginatedData.totalRows} total rows
          </div>
        ) : null}

        {shouldUseVirtualScrolling ? (
          /* Virtual Scrolling Table for Large Datasets */
          <div className="rounded-md border">
            <div
              ref={parentRef}
              className="overflow-auto"
              style={{
                height: Math.min(
                  MAX_CONTAINER_HEIGHT,
                  (Math.min(csvData?.rows?.length || 0, maxRows) * ROW_HEIGHT) + HEADER_HEIGHT
                ),
              }}
            >
              <Table>
                {/* Static Header */}
                <TableHeader className="sticky top-0 z-10 bg-background">
                  <TableRow>
                    {csvData?.headers.map((header, index) => (
                      <TableHead 
                        key={index} 
                        className="font-semibold border-b border-border"
                        style={{ height: HEADER_HEIGHT }}
                      >
                        {header}
                      </TableHead>
                    ))}
                  </TableRow>
                </TableHeader>
                
                {/* Virtual Table Body */}
                <TableBody>
                  <tr style={{ height: virtualizer.getTotalSize() }}>
                    <td colSpan={csvData?.headers.length || 0} className="p-0">
                      <div className="relative">
                        {virtualizer.getVirtualItems().map((virtualRow) => {
                          const row = csvData?.rows[virtualRow.index];
                          if (!row) return null;
                          
                          return (
                            <div
                              key={virtualRow.index}
                              className={cn(
                                "absolute top-0 left-0 w-full flex",
                                "border-b border-border hover:bg-muted/50"
                              )}
                              style={{
                                height: ROW_HEIGHT,
                                transform: `translateY(${virtualRow.start}px)`,
                              }}
                            >
                              {row.map((cell, cellIndex) => (
                                <div
                                  key={cellIndex}
                                  className={cn(
                                    "flex items-center px-4 py-2",
                                    "max-w-[200px] truncate text-sm",
                                    cellIndex > 0 && "border-l border-border"
                                  )}
                                  style={{
                                    minWidth: `${100 / (csvData?.headers.length || 1)}%`,
                                    width: `${100 / (csvData?.headers.length || 1)}%`,
                                  }}
                                  title={cell}
                                >
                                  {cell}
                                </div>
                              ))}
                            </div>
                          );
                        })}
                      </div>
                    </td>
                  </tr>
                </TableBody>
              </Table>
            </div>
          </div>
        ) : (
          /* Regular Table for Small Datasets or When Pagination is Enabled */
          <div className="rounded-md border">
            <Table>
              <TableHeader>
                <TableRow>
                  {paginatedData.headers.map((header, index) => (
                    <TableHead key={index} className="font-semibold">
                      {header}
                    </TableHead>
                  ))}
                </TableRow>
              </TableHeader>
              <TableBody>
                {paginatedData.rows.map((row, rowIndex) => (
                  <TableRow key={rowIndex}>
                    {row.map((cell, cellIndex) => (
                      <TableCell
                        key={cellIndex}
                        className="max-w-[200px] truncate"
                      >
                        <div title={cell}>{cell}</div>
                      </TableCell>
                    ))}
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        )}

        {showPagination && totalPages > 1 && (
          <div className="flex items-center justify-center gap-2">
            <Button
              variant="outline"
              size="sm"
              disabled={currentPage === 0}
              onClick={() => setCurrentPage((prev) => Math.max(0, prev - 1))}
            >
              <ChevronLeft className="size-4" />
              Previous
            </Button>

            <span className="text-sm text-muted-foreground">
              Page {currentPage + 1} of {totalPages}
            </span>

            <Button
              variant="outline"
              size="sm"
              disabled={currentPage >= totalPages - 1}
              onClick={() =>
                setCurrentPage((prev) => Math.min(totalPages - 1, prev + 1))
              }
            >
              Next
              <ChevronRight className="size-4" />
            </Button>
          </div>
        )}

        {paginatedData.totalRows > 0 && !shouldUseVirtualScrolling && (
          <div className="text-sm text-muted-foreground">
            Showing rows {paginatedData.startRow + 1}-{paginatedData.endRow}
            {paginatedData.totalRows > maxRows
              ? ` of first ${maxRows} rows (${paginatedData.totalRows} total)`
              : ` of ${paginatedData.totalRows} total`}
          </div>
        )}
      </div>
    );
  };

  return (
    <div className="relative flex size-full justify-center overflow-y-auto p-4">
      <div className="min-w-[600px] max-w-full">{renderContent()}</div>
    </div>
  );
};

export default CSVPreview;
