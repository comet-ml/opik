import React, { useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
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

interface CSVPreviewProps {
  url: string;
  maxRows?: number;
  showPagination?: boolean;
}

const CSVPreview: React.FC<CSVPreviewProps> = ({
  url,
  maxRows = 100,
  showPagination = true,
}) => {
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

  const csvData = useMemo(() => {
    if (!csvText || !isString(csvText)) return null;
    return parseCSV(csvText);
  }, [csvText]);

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

  const totalPages = useMemo(() => {
    if (!csvData) return 0;
    return Math.ceil(Math.min(csvData.rows.length, maxRows) / rowsPerPage);
  }, [csvData, maxRows, rowsPerPage]);

  const renderContent = () => {
    if (isPending) return <Loader />;

    if (isError) return <NoData icon={null} message={error?.message} />;

    if (!paginatedData || paginatedData.headers.length === 0) {
      return <NoData message="No CSV data found" icon={null} />;
    }

    return (
      <div className="space-y-4">
        {paginatedData.totalRows > maxRows && (
          <div className="text-sm text-muted-foreground">
            Showing first {maxRows} rows of {paginatedData.totalRows} total rows
          </div>
        )}

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

        {paginatedData.totalRows > 0 && (
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
