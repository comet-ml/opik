import React, { useMemo, useRef } from "react";
import { useQuery } from "@tanstack/react-query";
import { useVirtualizer } from "@tanstack/react-virtual";
import { csv2json } from "json-2-csv";
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
import { cn } from "@/lib/utils";

interface CSVPreviewProps {
  url: string;
  maxRows?: number;
}

interface CSVData {
  headers: string[];
  rows: string[][];
  totalRows: number;
}

// Virtual scrolling constants matching DataTable patterns
const ROW_HEIGHT = 36; // Height for each row in pixels
const HEADER_HEIGHT = 40; // Height for header row
const MAX_CONTAINER_HEIGHT = 500; // Maximum height for the scrollable container
const VIRTUAL_THRESHOLD = 100; // Use virtual scrolling for datasets larger than this

const CSVPreview: React.FC<CSVPreviewProps> = ({
  url,
  maxRows = 100000, // Support up to 100k rows
}) => {
  const parentRef = useRef<HTMLDivElement>(null);

  const {
    data: csvData,
    isPending,
    isError,
    error,
  } = useQuery({
    queryKey: ["csv", url],
    queryFn: async (): Promise<CSVData> => {
      try {
        const response = await fetch(url);
        
        if (!response.ok) {
          throw new Error(
            `Failed to fetch CSV file: ${response.status} ${response.statusText}`,
          );
        }
        
        const csvText = await response.text();
        
        // Normalize line endings
        const normalizedText = csvText.replace(/\r\n|\r/g, "\n");
        
        // Parse CSV using existing json-2-csv library
        const parsed = await csv2json(normalizedText, {
          excelBOM: true,
          trimHeaderFields: true,
          trimFieldValues: true,
        });

        if (!Array.isArray(parsed) || parsed.length === 0) {
          return { headers: [], rows: [], totalRows: 0 };
        }

        // Type assertion for the parsed data as json-2-csv returns Record<string, unknown>[]
        const parsedData = parsed as Record<string, unknown>[];
        
        // Extract headers from the first object's keys
        const headers = Object.keys(parsedData[0]);
        
        // Convert objects array to rows array (array of arrays), limited by maxRows
        const limitedData = parsedData.slice(0, maxRows);
        const rows = limitedData.map((row) => 
          headers.map((header) => {
            const value = row[header];
            // Convert all values to strings for consistency
            return value == null ? "" : String(value);
          })
        );

        return { 
          headers, 
          rows, 
          totalRows: parsedData.length 
        };
      } catch (error) {
        let message: string | undefined;
        if (isString(error)) {
          message = error;
        } else if (error instanceof Error) {
          message = error.message;
        }
        throw new Error(
          message ?? "Failed to fetch or parse CSV file. CORS issue or invalid URL.",
        );
      }
    },
  });

  // Determine if we should use virtual scrolling
  const shouldUseVirtualScrolling = useMemo(() => {
    return csvData && csvData.rows.length > VIRTUAL_THRESHOLD;
  }, [csvData]);

  // Virtual scrolling setup for large datasets
  const virtualizer = useVirtualizer({
    count: csvData?.rows?.length || 0,
    getScrollElement: () => parentRef.current,
    estimateSize: () => ROW_HEIGHT,
    overscan: 5,
    enabled: Boolean(shouldUseVirtualScrolling),
  });

  const renderContent = () => {
    if (isPending) return <Loader />;

    if (isError) return <NoData icon={null} message={error?.message} />;

    if (!csvData || csvData.headers.length === 0) {
      return <NoData message="No CSV data found" icon={null} />;
    }

    return (
      <div className="space-y-4">
        {/* Row count info */}
        {csvData.totalRows > maxRows ? (
          <div className="text-sm text-muted-foreground">
            Showing first {maxRows.toLocaleString()} rows of {csvData.totalRows.toLocaleString()} total rows, {csvData.headers.length} columns
            {shouldUseVirtualScrolling ? " (virtualized for performance)" : ""}
          </div>
        ) : (
          <div className="text-sm text-muted-foreground">
            {csvData.rows.length.toLocaleString()} rows, {csvData.headers.length} columns
            {shouldUseVirtualScrolling ? " (virtualized for performance)" : ""}
          </div>
        )}

        {shouldUseVirtualScrolling ? (
          /* Virtual Scrolling Table for Large Datasets */
          <div className="rounded-md border">
            <div
              ref={parentRef}
              className="overflow-auto"
              style={{
                height: Math.min(
                  MAX_CONTAINER_HEIGHT,
                  (csvData.rows.length * ROW_HEIGHT) + HEADER_HEIGHT
                ),
              }}
            >
              <Table>
                {/* Static Header */}
                <TableHeader className="sticky top-0 z-10 bg-background">
                  <TableRow>
                    {csvData.headers.map((header, index) => (
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
                    <td colSpan={csvData.headers.length} className="p-0">
                      <div className="relative">
                        {virtualizer.getVirtualItems().map((virtualRow) => {
                          const row = csvData.rows[virtualRow.index];
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
                                    minWidth: `${100 / csvData.headers.length}%`,
                                    width: `${100 / csvData.headers.length}%`,
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
          /* Regular Table for Small Datasets */
          <div className="rounded-md border">
            <Table>
              <TableHeader>
                <TableRow>
                  {csvData.headers.map((header, index) => (
                    <TableHead key={index} className="font-semibold">
                      {header}
                    </TableHead>
                  ))}
                </TableRow>
              </TableHeader>
              <TableBody>
                {csvData.rows.map((row, rowIndex) => (
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
