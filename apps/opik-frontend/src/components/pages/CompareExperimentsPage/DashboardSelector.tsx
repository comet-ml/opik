import React, { useState } from "react";
import { Plus, Settings, Copy, Trash2, Download, Upload, Edit } from "lucide-react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { useDashboardStore } from "./dashboardStore";
import useDashboards from "@/api/dashboards/useDashboards";
import { useExperimentDashboard } from "@/api/dashboards/useExperimentDashboard";
import useDashboardCreateMutation from "@/api/dashboards/useDashboardCreateMutation";
import useDashboardUpdateMutation from "@/api/dashboards/useDashboardUpdateMutation";
import useDashboardDeleteMutation from "@/api/dashboards/useDashboardDeleteMutation";
import useExperimentDashboardAssociateMutation from "@/api/dashboards/useExperimentDashboardAssociateMutation";
import LoadableSelectBox from "@/components/shared/LoadableSelectBox/LoadableSelectBox";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import { DropdownOption } from "@/types/shared";
import { Button } from "@/components/ui/button";
import { 
  Dialog, 
  DialogContent, 
  DialogHeader, 
  DialogTitle, 
  DialogFooter,
  DialogClose 
} from "@/components/ui/dialog";
import {
  Form,
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/components/ui/form";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { 
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";

// Form validation schemas
const dashboardFormSchema = z.object({
  name: z
    .string()
    .min(1, "Dashboard name is required")
    .max(100, "Dashboard name must be less than 100 characters"),
  description: z
    .string()
    .max(500, "Description must be less than 500 characters")
    .optional(),
});

type DashboardFormData = z.infer<typeof dashboardFormSchema>;

interface DashboardSelectorProps {
  experimentId: string;
}

const DashboardSelector: React.FC<DashboardSelectorProps> = ({ experimentId }) => {
  const { currentDashboardId, setCurrentDashboard } = useDashboardStore();
  
  // API hooks
  const { data: dashboardsData, isLoading: dashboardsLoading } = useDashboards(
    {},
    {
      retry: 1,
      staleTime: 5 * 60 * 1000,
      enabled: !!experimentId,
    }
  );
  const { data: experimentDashboard } = useExperimentDashboard(
    { experimentId },
    { 
      enabled: !!experimentId,
      retry: 1,
      staleTime: 5 * 60 * 1000,
    }
  );
  const createDashboardMutation = useDashboardCreateMutation();
  const updateDashboardMutation = useDashboardUpdateMutation();
  const deleteDashboardMutation = useDashboardDeleteMutation();
  const associateDashboardMutation = useExperimentDashboardAssociateMutation();

  // Modal states
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [showEditModal, setShowEditModal] = useState(false);
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false);
  const [editingDashboard, setEditingDashboard] = useState<any>(null);
  const [deletingDashboard, setDeletingDashboard] = useState<any>(null);

  // Forms
  const createForm = useForm<DashboardFormData>({
    resolver: zodResolver(dashboardFormSchema),
    defaultValues: {
      name: "",
      description: "",
    },
  });

  const editForm = useForm<DashboardFormData>({
    resolver: zodResolver(dashboardFormSchema),
    defaultValues: {
      name: "",
      description: "",
    },
  });

  // Convert dashboards to dropdown options
  const dashboardOptions: DropdownOption<string>[] = dashboardsData?.map((dashboard) => ({
    value: dashboard.id,
    label: dashboard.name,
  })) || [];

  const handleCreateDashboard = async (data: DashboardFormData) => {
    try {
      const newDashboard = await createDashboardMutation.mutateAsync({
        dashboard: {
          name: data.name.trim(),
          description: data.description?.trim() || "",
        }
      });
      
      if (newDashboard?.id) {
        await associateDashboardMutation.mutateAsync({
          experimentId,
          dashboardId: newDashboard.id
        });
        
        setCurrentDashboard(newDashboard.id);
        createForm.reset();
        setShowCreateModal(false);
      }
    } catch (error) {
      console.error("Failed to create dashboard:", error);
    }
  };

  const handleSelectDashboard = async (dashboardId: string) => {
    try {
      await associateDashboardMutation.mutateAsync({
        experimentId,
        dashboardId
      });
      setCurrentDashboard(dashboardId);
    } catch (error) {
      console.error("Failed to associate dashboard:", error);
    }
  };

  const handleEditDashboard = (dashboard: any) => {
    setEditingDashboard(dashboard);
    editForm.reset({
      name: dashboard.name,
      description: dashboard.description || "",
    });
    setShowEditModal(true);
  };

  const handleUpdateDashboard = async (data: DashboardFormData) => {
    if (!editingDashboard) return;
    
    try {
      await updateDashboardMutation.mutateAsync({
        dashboardId: editingDashboard.id,
        dashboard: {
          name: data.name.trim(),
          description: data.description?.trim() || "",
        }
      });
      
      setShowEditModal(false);
      setEditingDashboard(null);
      editForm.reset();
    } catch (error) {
      console.error("Failed to update dashboard:", error);
    }
  };

  const handleDuplicateDashboard = async (dashboard: any) => {
    try {
      const duplicatedDashboard = await createDashboardMutation.mutateAsync({
        dashboard: {
          name: `${dashboard.name} (Copy)`,
          description: dashboard.description || "",
        }
      });
      
      if (duplicatedDashboard?.id) {
        setCurrentDashboard(duplicatedDashboard.id);
      }
    } catch (error) {
      console.error("Failed to duplicate dashboard:", error);
    }
  };

  const handleDeleteDashboard = (dashboard: any) => {
    setDeletingDashboard(dashboard);
    setShowDeleteConfirm(true);
  };

  const confirmDeleteDashboard = async () => {
    if (!deletingDashboard) return;
    
    try {
      await deleteDashboardMutation.mutateAsync({
        dashboardId: deletingDashboard.id
      });
      
      if (currentDashboardId === deletingDashboard.id) {
        setCurrentDashboard(null);
      }
      
      setShowDeleteConfirm(false);
      setDeletingDashboard(null);
    } catch (error) {
      console.error("Failed to delete dashboard:", error);
    }
  };

  const handleExportDashboard = (dashboard: any) => {
    const exportData = {
      name: dashboard.name,
      description: dashboard.description,
      sections: dashboard.sections || [],
      exportedAt: new Date().toISOString(),
      version: "1.0"
    };
    
    const blob = new Blob([JSON.stringify(exportData, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `${dashboard.name.replace(/[^a-zA-Z0-9]/g, '_')}_dashboard.json`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  };

  const handleImportDashboard = () => {
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = '.json';
    input.onchange = async (e) => {
      const file = (e.target as HTMLInputElement).files?.[0];
      if (!file) return;
      
      try {
        const text = await file.text();
        const data = JSON.parse(text);
        
        if (data.name && data.sections) {
          await createDashboardMutation.mutateAsync({
            dashboard: {
              name: `${data.name} (Imported)`,
              description: data.description || "Imported dashboard",
            }
          });
        }
      } catch (error) {
        console.error("Failed to import dashboard:", error);
      }
    };
    input.click();
  };

  const currentDashboardData = dashboardsData?.find(d => d.id === currentDashboardId);

  return (
    <div className="space-y-4">
      {/* Dashboard Selection */}
      <div className="flex items-center gap-2">
        <div className="flex-1">
                     <LoadableSelectBox
             value={currentDashboardId || ""}
             onChange={handleSelectDashboard}
             options={dashboardOptions}
             placeholder="Select a dashboard..."
             isLoading={dashboardsLoading}
             disabled={dashboardsLoading}
           />
        </div>
        
        <Button
          onClick={() => setShowCreateModal(true)}
          size="sm"
          variant="outline"
        >
          <Plus className="mr-2 size-4" />
          Create
        </Button>

        {currentDashboardData && (
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button size="sm" variant="outline">
                <Settings className="size-4" />
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end">
              <DropdownMenuItem onClick={() => handleEditDashboard(currentDashboardData)}>
                <Edit className="mr-2 size-4" />
                Edit
              </DropdownMenuItem>
              <DropdownMenuItem onClick={() => handleDuplicateDashboard(currentDashboardData)}>
                <Copy className="mr-2 size-4" />
                Duplicate
              </DropdownMenuItem>
              <DropdownMenuItem onClick={() => handleExportDashboard(currentDashboardData)}>
                <Download className="mr-2 size-4" />
                Export
              </DropdownMenuItem>
              <DropdownMenuItem onClick={handleImportDashboard}>
                <Upload className="mr-2 size-4" />
                Import
              </DropdownMenuItem>
              <DropdownMenuItem 
                onClick={() => handleDeleteDashboard(currentDashboardData)}
                className="text-destructive"
              >
                <Trash2 className="mr-2 size-4" />
                Delete
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        )}
      </div>

      {/* Create Dashboard Modal */}
      <Dialog open={showCreateModal} onOpenChange={setShowCreateModal}>
        <DialogContent className="sm:max-w-[500px]">
          <DialogHeader>
            <DialogTitle>Create New Dashboard</DialogTitle>
          </DialogHeader>
          <Form {...createForm}>
            <form onSubmit={createForm.handleSubmit(handleCreateDashboard)} className="space-y-4">
              <FormField
                control={createForm.control}
                name="name"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Name</FormLabel>
                    <FormControl>
                      <Input placeholder="Enter dashboard name..." {...field} />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
              <FormField
                control={createForm.control}
                name="description"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Description (Optional)</FormLabel>
                    <FormControl>
                      <Textarea 
                        placeholder="Enter dashboard description..." 
                        className="min-h-[80px]"
                        {...field} 
                      />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
              <DialogFooter>
                <DialogClose asChild>
                  <Button variant="outline">Cancel</Button>
                </DialogClose>
                <Button 
                  type="submit" 
                  disabled={createDashboardMutation.isPending}
                >
                  {createDashboardMutation.isPending ? "Creating..." : "Create Dashboard"}
                </Button>
              </DialogFooter>
            </form>
          </Form>
        </DialogContent>
      </Dialog>

      {/* Edit Dashboard Modal */}
      <Dialog open={showEditModal} onOpenChange={setShowEditModal}>
        <DialogContent className="sm:max-w-[500px]">
          <DialogHeader>
            <DialogTitle>Edit Dashboard</DialogTitle>
          </DialogHeader>
          <Form {...editForm}>
            <form onSubmit={editForm.handleSubmit(handleUpdateDashboard)} className="space-y-4">
              <FormField
                control={editForm.control}
                name="name"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Name</FormLabel>
                    <FormControl>
                      <Input placeholder="Enter dashboard name..." {...field} />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
              <FormField
                control={editForm.control}
                name="description"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Description (Optional)</FormLabel>
                    <FormControl>
                      <Textarea 
                        placeholder="Enter dashboard description..." 
                        className="min-h-[80px]"
                        {...field} 
                      />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
              <DialogFooter>
                <DialogClose asChild>
                  <Button variant="outline">Cancel</Button>
                </DialogClose>
                <Button 
                  type="submit" 
                  disabled={updateDashboardMutation.isPending}
                >
                  {updateDashboardMutation.isPending ? "Updating..." : "Update Dashboard"}
                </Button>
              </DialogFooter>
            </form>
          </Form>
        </DialogContent>
      </Dialog>

      {/* Delete Confirmation Dialog */}
      <ConfirmDialog
        open={showDeleteConfirm}
        setOpen={setShowDeleteConfirm}
        onConfirm={confirmDeleteDashboard}
        title="Delete Dashboard"
        description={`Are you sure you want to delete "${deletingDashboard?.name}"? This action cannot be undone and will remove all sections and panels in this dashboard.`}
        confirmText="Delete"
        cancelText="Cancel"
        confirmButtonVariant="destructive"
      />
    </div>
  );
};

export default DashboardSelector; 
