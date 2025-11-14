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
import DataTableWrapper from "@/components/shared/DataTable/DataTableWrapper";
import { ROW_HEIGHT_MAP } from "@/constants/shared";
import { ROW_HEIGHT } from "@/types/shared";

interface CSVPreviewProps {
  url: string;
  maxRows?: number;
}

type CSVRow = Record<string, string>;

interface CSVData {
  headers: string[];
  rows: CSVRow[];
  totalRows: number;
}

const VIRTUAL_THRESHOLD = 100;
const CSV_ROW_HEIGHT = ROW_HEIGHT.small; // Use standard small row height (44px)
const VIRTUAL_ROW_HEIGHT = parseInt(
  ROW_HEIGHT_MAP[CSV_ROW_HEIGHT].height as string,
  10,
);
const MAX_CONTAINER_HEIGHT = 500; // Maximum height for the scrollable container
const OVERSCAN_COUNT = 5; // Number of rows to render outside visible area

const CSVPreview: React.FC<CSVPreviewProps> = ({ url, maxRows = 100000 }) => {
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
          headers.reduce(
            (acc, header) => {
              const value = row[header];
              // Convert all values to strings for consistency
              acc[header] = value == null ? "" : String(value);
              return acc;
            },
            {} as Record<string, string>,
          ),
        );

        return {
          headers,
          rows,
          totalRows: parsedData.length,
        };
      } catch (error) {
        let message: string | undefined;
        if (isString(error)) {
          message = error;
        } else if (error instanceof Error) {
          message = error.message;
        }
        throw new Error(
          message ??
            "Failed to fetch or parse CSV file. CORS issue or invalid URL.",
        );
      }
    },
    staleTime: 5 * 60 * 1000,
    retry: false,
    refetchOnWindowFocus: false,
  });

  const shouldUseVirtualScrolling = useMemo(() => {
    return csvData && csvData.rows.length > VIRTUAL_THRESHOLD;
  }, [csvData]);

  const rowVirtualizer = useVirtualizer({
    count: csvData?.rows?.length || 0,
    getScrollElement: () => parentRef.current,
    estimateSize: () => VIRTUAL_ROW_HEIGHT,
    overscan: OVERSCAN_COUNT,
    enabled: Boolean(shouldUseVirtualScrolling),
  });

  const renderContent = () => {
    if (isPending) return <Loader />;

    if (isError) {
      return <NoData icon={null} message={error?.message} />;
    }

    if (!csvData || csvData.headers.length === 0) {
      return <NoData message="No CSV data found" icon={null} />;
    }

    return (
      <div className="space-y-4">
        {/* Row count info */}
        {csvData.totalRows > maxRows ? (
          <div className="text-sm text-muted-foreground">
            Showing first {maxRows.toLocaleString()} rows of{" "}
            {csvData.totalRows.toLocaleString()} total rows,{" "}
            {csvData.headers.length} columns
            {shouldUseVirtualScrolling ? " (virtualized for performance)" : ""}
          </div>
        ) : (
          <div className="text-sm text-muted-foreground">
            {csvData.rows.length.toLocaleString()} rows,{" "}
            {csvData.headers.length} columns
            {shouldUseVirtualScrolling ? " (virtualized for performance)" : ""}
          </div>
        )}

        {shouldUseVirtualScrolling ? (
          /* Virtual Scrolling Table for Large Datasets */
          <DataTableWrapper>
            <div
              ref={parentRef}
              className="overflow-auto"
              style={{
                height: Math.min(
                  MAX_CONTAINER_HEIGHT,
                  csvData.rows.length * VIRTUAL_ROW_HEIGHT + VIRTUAL_ROW_HEIGHT,
                ),
              }}
            >
              <Table>
                {/* Static Header */}
                <TableHeader className="sticky top-0 z-10 bg-background">
                  <TableRow style={ROW_HEIGHT_MAP[CSV_ROW_HEIGHT]}>
                    {csvData.headers.map((header, index) => (
                      <TableHead
                        key={index}
                        className="font-semibold border-b border-border"
                        style={ROW_HEIGHT_MAP[CSV_ROW_HEIGHT]}
                      >
                        {header}
                      </TableHead>
                    ))}
                  </TableRow>
                </TableHeader>

                {/* Virtual Table Body */}
                <TableBody>
                  <tr style={{ height: rowVirtualizer.getTotalSize() }}>
                    <td colSpan={csvData.headers.length} className="p-0">
                      <div className="relative">
                        {rowVirtualizer.getVirtualItems().map((virtualRow) => {
                          const row = csvData.rows[virtualRow.index];
                          if (!row) return null;

                          return (
                            <TableRow
                              key={virtualRow.index}
                              className="absolute top-0 left-0 w-full border-b transition-colors group/row comet-table-row-active"
                              style={{
                                height: VIRTUAL_ROW_HEIGHT,
                                transform: `translateY(${virtualRow.start}px)`,
                              }}
                            >
                              {csvData.headers.map((header, cellIndex) => (
                                <TableCell
                                  key={cellIndex}
                                  className="max-w-[200px] truncate text-sm"
                                  title={row[header]}
                                >
                                  <div className="truncate">
                                    {row[header] || ""}
                                  </div>
                                </TableCell>
                              ))}
                            </TableRow>
                          );
                        })}
                      </div>
                    </td>
                  </tr>
                </TableBody>
              </Table>
            </div>
          </DataTableWrapper>
        ) : (
          /* Regular Table for Small Datasets */
          <DataTableWrapper>
            <Table>
              <TableHeader>
                <TableRow style={ROW_HEIGHT_MAP[CSV_ROW_HEIGHT]}>
                  {csvData.headers.map((header, index) => (
                    <TableHead key={index} className="font-semibold">
                      {header}
                    </TableHead>
                  ))}
                </TableRow>
              </TableHeader>
              <TableBody>
                {csvData.rows.map((row, rowIndex) => (
                  <TableRow
                    key={rowIndex}
                    style={ROW_HEIGHT_MAP[CSV_ROW_HEIGHT]}
                  >
                    {csvData.headers.map((header, cellIndex) => (
                      <TableCell
                        key={cellIndex}
                        className="max-w-[200px] truncate text-sm"
                      >
                        <div className="truncate" title={row[header]}>
                          {row[header] || ""}
                        </div>
                      </TableCell>
                    ))}
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </DataTableWrapper>
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
