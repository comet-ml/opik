import useTracesBatchFeedbackScoresMutation from "@/api/traces/useTracesBatchFeedbackScoresMutation";
import { Button } from "@/components/ui/button";
import {
    Dialog,
    DialogContent,
    DialogFooter,
    DialogHeader,
    DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { useToast } from "@/components/ui/use-toast";
import { Trace } from "@/types/traces";
import React, { useState } from "react";

type BatchAnnotateDialogProps = {
    rows: Trace[];
    open: boolean | number;
    setOpen: (o: boolean | number) => void;
    projectId: string;
    onSuccess?: () => void;
};

const MAX_ENTITIES = 10;

const BatchAnnotateDialog: React.FunctionComponent<BatchAnnotateDialogProps> = ({
    rows,
    open,
    setOpen,
    projectId,
    onSuccess,
}) => {
    const { toast } = useToast();
    const [scoreName, setScoreName] = useState<string>("");
    const [value, setValue] = useState<number | undefined>(undefined);
    const [categoryName, setCategoryName] = useState<string>("");
    const [reason, setReason] = useState<string>("");

    const batchMutation = useTracesBatchFeedbackScoresMutation();

    const handleClose = () => {
        setOpen(false);
        setScoreName("");
        setValue(undefined);
        setCategoryName("");
        setReason("");
    };

    const disabled =
        !scoreName || value === undefined || rows.length > MAX_ENTITIES || batchMutation.isPending;

    const handleAnnotate = async () => {
        try {
            await batchMutation.mutateAsync({
                projectId,
                traceIds: rows.map((r) => r.id),
                name: scoreName,
                value: value as number,
                categoryName: categoryName || undefined,
                reason: reason || undefined,
            });
            if (onSuccess) onSuccess();
            handleClose();
        } catch (e) {
            toast({
                title: "Error",
                description: "Failed to annotate traces",
                variant: "destructive",
            });
        }
    };

    return (
        <Dialog open={Boolean(open)} onOpenChange={handleClose}>
            <DialogContent className="sm:max-w-[500px]">
                <DialogHeader>
                    <DialogTitle>Annotate {rows.length} traces</DialogTitle>
                </DialogHeader>
                {rows.length > MAX_ENTITIES && (
                    <div className="mb-2 text-sm text-red-500">
                        You can annotate up to {MAX_ENTITIES} traces at a time. Please reduce your selection.
                    </div>
                )}
                <div className="grid gap-4 py-4">
                    <Input
                        placeholder="Score name"
                        value={scoreName}
                        onChange={(e) => setScoreName(e.target.value)}
                        disabled={rows.length > MAX_ENTITIES}
                    />
                    <Input
                        placeholder="Value"
                        type="number"
                        value={value ?? ""}
                        onChange={(e) => {
                            const v = e.target.value;
                            if (v === "") {
                                setValue(undefined);
                                return;
                            }
                            const parsed = Number(v);
                            setValue(Number.isNaN(parsed) ? undefined : parsed);
                        }}
                        disabled={rows.length > MAX_ENTITIES}
                    />
                    <Input
                        placeholder="Category name (optional)"
                        value={categoryName}
                        onChange={(e) => setCategoryName(e.target.value)}
                        disabled={rows.length > MAX_ENTITIES}
                    />
                    <Textarea
                        placeholder="Reason (optional)"
                        value={reason}
                        onChange={(e) => setReason(e.target.value)}
                        disabled={rows.length > MAX_ENTITIES}
                    />
                </div>
                <DialogFooter>
                    <Button variant="outline" onClick={handleClose} disabled={batchMutation.isPending}>
                        Cancel
                    </Button>
                    <Button onClick={handleAnnotate} disabled={disabled}>
                        Annotate
                    </Button>
                </DialogFooter>
            </DialogContent>
        </Dialog>
    );
};

export default BatchAnnotateDialog; 