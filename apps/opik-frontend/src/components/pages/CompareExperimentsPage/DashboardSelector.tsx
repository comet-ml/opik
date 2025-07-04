import React, { useState } from "react";
import { Plus, Settings, Copy, Trash2, Download, Upload, Edit } from "lucide-react";
import { useDashboardStore } from "./dashboardStore";
import { useDashboards } from "@/api/dashboards/useDashboards";
import { useExperimentDashboard } from "@/api/dashboards/useExperimentDashboard";
import useDashboardCreateMutation from "@/api/dashboards/useDashboardCreateMutation";
import useDashboardUpdateMutation from "@/api/dashboards/useDashboardUpdateMutation";
import useDashboardDeleteMutation from "@/api/dashboards/useDashboardDeleteMutation";
import useExperimentDashboardAssociateMutation from "@/api/dashboards/useExperimentDashboardAssociateMutation";
import LoadableSelectBox from "@/components/shared/LoadableSelectBox/LoadableSelectBox";
import { DropdownOption } from "@/types/shared";

interface DashboardSelectorProps {
  experimentId: string;
}

const DashboardSelector: React.FC<DashboardSelectorProps> = ({ experimentId }) => {
  const { currentDashboardId, setCurrentDashboard } = useDashboardStore();
  
  // API hooks with error handling and retry limits
  const { data: dashboardsData, isLoading: dashboardsLoading, error: dashboardsError } = useDashboards(
    {},
    {
      retry: 1,
      staleTime: 5 * 60 * 1000, // 5 minutes
      enabled: !!experimentId,
    }
  );
  const { data: experimentDashboard, error: experimentDashboardError } = useExperimentDashboard(
    { experimentId },
    { 
      enabled: !!experimentId,
      retry: 1,
      staleTime: 5 * 60 * 1000, // 5 minutes
    }
  );
  const createDashboardMutation = useDashboardCreateMutation();
  const updateDashboardMutation = useDashboardUpdateMutation();
  const deleteDashboardMutation = useDashboardDeleteMutation();
  const associateDashboardMutation = useExperimentDashboardAssociateMutation();

  // Modal states
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [showManageModal, setShowManageModal] = useState(false);
  const [showEditModal, setShowEditModal] = useState(false);
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false);
  
  // Form states
  const [newDashboardName, setNewDashboardName] = useState("");
  const [newDashboardDescription, setNewDashboardDescription] = useState("");
  const [editingDashboard, setEditingDashboard] = useState<any>(null);
  const [deletingDashboard, setDeletingDashboard] = useState<any>(null);

  // Convert dashboards to dropdown options
  const dashboardOptions: DropdownOption<string>[] = dashboardsData?.content?.map((dashboard) => ({
    value: dashboard.id,
    label: dashboard.name,
  })) || [];

  const handleCreateDashboard = async () => {
    if (!newDashboardName.trim()) return;
    
    try {
      const newDashboard = await createDashboardMutation.mutateAsync({
        dashboard: {
          name: newDashboardName.trim(),
          description: newDashboardDescription.trim(),
        }
      });
      
      // Only proceed if dashboard creation was successful
      if (newDashboard?.id) {
        // Auto-select the new dashboard for this experiment
        await associateDashboardMutation.mutateAsync({
          experimentId,
          dashboardId: newDashboard.id
        });
        
        setCurrentDashboard(newDashboard.id);
        setNewDashboardName("");
        setNewDashboardDescription("");
        setShowCreateModal(false);
      }
    } catch (error) {
      console.error("Failed to create dashboard:", error);
      // Don't close modal on error, let user try again or cancel
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

  const handleDuplicateDashboard = async (dashboard: any) => {
    try {
      const duplicatedDashboard = await createDashboardMutation.mutateAsync({
        dashboard: {
          name: `${dashboard.name} (Copy)`,
          description: dashboard.description,
        }
      });
      
      if (duplicatedDashboard?.id) {
        setCurrentDashboard(duplicatedDashboard.id);
        setShowManageModal(false);
      }
    } catch (error) {
      console.error("Failed to duplicate dashboard:", error);
    }
  };

  const handleEditDashboard = (dashboard: any) => {
    setEditingDashboard(dashboard);
    setNewDashboardName(dashboard.name);
    setNewDashboardDescription(dashboard.description || "");
    setShowEditModal(true);
  };

  const handleUpdateDashboard = async () => {
    if (!editingDashboard || !newDashboardName.trim()) return;
    
    try {
      await updateDashboardMutation.mutateAsync({
        dashboardId: editingDashboard.id,
        dashboard: {
          name: newDashboardName.trim(),
          description: newDashboardDescription.trim(),
        }
      });
      
      setShowEditModal(false);
      setEditingDashboard(null);
      setNewDashboardName("");
      setNewDashboardDescription("");
    } catch (error) {
      console.error("Failed to update dashboard:", error);
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
      
      // If we're deleting the currently selected dashboard, clear the selection
      if (currentDashboardId === deletingDashboard.id) {
        setCurrentDashboard(null);
      }
      
      setShowDeleteConfirm(false);
      setDeletingDashboard(null);
      setShowManageModal(false);
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
        const importData = JSON.parse(text);
        
        // Create dashboard from imported data
        const newDashboard = await createDashboardMutation.mutateAsync({
          dashboard: {
            name: `${importData.name} (Imported)`,
            description: importData.description,
          }
        });
        
        if (newDashboard?.id) {
          setCurrentDashboard(newDashboard.id);
          setShowManageModal(false);
        }
      } catch (error) {
        console.error("Failed to import dashboard:", error);
        alert("Failed to import dashboard. Please check the file format.");
      }
    };
    input.click();
  };

  const selectedDashboardId = experimentDashboard?.dashboard_id || currentDashboardId;

  // Show error state if there are errors
  if ((dashboardsError || experimentDashboardError) && !dashboardsLoading) {
    return (
      <div className="border-b bg-background p-4">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-4">
            <h2 className="comet-title-m">Dashboard</h2>
            <div className="text-red-600 text-sm">
              ⚠️ Error loading dashboards. Please refresh the page.
            </div>
          </div>
          <button 
            onClick={() => window.location.reload()} 
            className="btn btn-secondary"
          >
            Refresh
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="border-b bg-background p-4">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-4">
          <h2 className="comet-title-m">Dashboard</h2>
          
          {/* Dashboard Selector */}
          <div className="w-80">
            <LoadableSelectBox
              value={selectedDashboardId || ""}
              placeholder="Select a dashboard..."
              onChange={handleSelectDashboard}
              options={dashboardOptions}
              buttonSize="sm"
              buttonClassName="w-full"
              isLoading={dashboardsLoading}
            />
          </div>
        </div>

        <div className="flex items-center gap-2">
          {/* Create Dashboard */}
          <button
            onClick={() => setShowCreateModal(true)}
            className="btn btn-primary flex items-center gap-2 hover:bg-blue-700 transition-colors"
            disabled={createDashboardMutation.isPending}
          >
            <Plus size={16} />
            New Dashboard
          </button>

          {/* Manage Dashboards */}
          <button
            onClick={() => setShowManageModal(true)}
            className="btn btn-secondary flex items-center gap-2 hover:bg-gray-700 transition-colors"
          >
            <Settings size={16} />
            Manage
          </button>
        </div>
      </div>

      {/* Create Dashboard Modal */}
      {showCreateModal && (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
          <div className="bg-white rounded-lg p-6 w-full max-w-md">
            <h3 className="comet-title-m mb-4">Create New Dashboard</h3>
            
            <div className="space-y-4">
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">
                  Dashboard Name *
                </label>
                <input
                  type="text"
                  value={newDashboardName}
                  onChange={(e) => setNewDashboardName(e.target.value)}
                  className="w-full border rounded-md px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-500"
                  placeholder="Enter dashboard name"
                  autoFocus
                />
              </div>
              
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">
                  Description
                </label>
                <textarea
                  value={newDashboardDescription}
                  onChange={(e) => setNewDashboardDescription(e.target.value)}
                  className="w-full border rounded-md px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-500"
                  placeholder="Enter description (optional)"
                  rows={3}
                />
              </div>
            </div>

            <div className="flex justify-end gap-2 mt-6">
              <button
                onClick={() => {
                  setShowCreateModal(false);
                  setNewDashboardName("");
                  setNewDashboardDescription("");
                }}
                className="btn btn-secondary"
                disabled={createDashboardMutation.isPending}
              >
                Cancel
              </button>
              <button
                onClick={handleCreateDashboard}
                className="btn btn-primary"
                disabled={!newDashboardName.trim() || createDashboardMutation.isPending}
              >
                {createDashboardMutation.isPending ? "Creating..." : "Create Dashboard"}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Manage Dashboards Modal */}
      {showManageModal && (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
          <div className="bg-white rounded-lg p-6 w-full max-w-4xl max-h-[80vh] overflow-y-auto">
            <div className="flex items-center justify-between mb-6">
              <h3 className="comet-title-m">Manage Dashboards</h3>
              <div className="flex items-center gap-2">
                <button
                  onClick={handleImportDashboard}
                  className="btn btn-secondary flex items-center gap-2"
                  disabled={createDashboardMutation.isPending}
                >
                  <Upload size={16} />
                  Import
                </button>
                <button
                  onClick={() => setShowManageModal(false)}
                  className="btn btn-secondary"
                >
                  Close
                </button>
              </div>
            </div>
            
            <div className="space-y-4">
              {dashboardsData?.content?.map((dashboard) => (
                <div key={dashboard.id} className="border rounded-lg p-4 hover:bg-gray-50">
                  <div className="flex items-center justify-between">
                    <div className="flex-1">
                      <h4 className="font-medium text-gray-900">{dashboard.name}</h4>
                      {dashboard.description && (
                        <p className="text-sm text-gray-600 mt-1">{dashboard.description}</p>
                      )}
                      <div className="flex items-center gap-4 mt-2 text-xs text-gray-500">
                        <span>Created: {new Date(dashboard.created_at).toLocaleDateString()}</span>
                        <span>By: {dashboard.created_by}</span>
                        {dashboard.last_updated_at !== dashboard.created_at && (
                          <span>Updated: {new Date(dashboard.last_updated_at).toLocaleDateString()}</span>
                        )}
                      </div>
                    </div>
                    
                    <div className="flex items-center gap-2 ml-4">
                      <button
                        onClick={() => handleEditDashboard(dashboard)}
                        className="btn btn-sm btn-secondary flex items-center gap-1"
                        disabled={updateDashboardMutation.isPending}
                      >
                        <Edit size={14} />
                        Edit
                      </button>
                      <button
                        onClick={() => handleDuplicateDashboard(dashboard)}
                        className="btn btn-sm btn-secondary flex items-center gap-1"
                        disabled={createDashboardMutation.isPending}
                      >
                        <Copy size={14} />
                        Duplicate
                      </button>
                      <button
                        onClick={() => handleExportDashboard(dashboard)}
                        className="btn btn-sm btn-secondary flex items-center gap-1"
                      >
                        <Download size={14} />
                        Export
                      </button>
                      <button
                        onClick={() => handleDeleteDashboard(dashboard)}
                        className="btn btn-sm btn-danger flex items-center gap-1"
                        disabled={deleteDashboardMutation.isPending}
                      >
                        <Trash2 size={14} />
                        Delete
                      </button>
                    </div>
                  </div>
                </div>
              ))}
              
              {(!dashboardsData?.content || dashboardsData.content.length === 0) && (
                <div className="text-center py-8 text-gray-500">
                  <div className="text-4xl mb-2">📊</div>
                  <p>No dashboards found.</p>
                  <button
                    onClick={() => {
                      setShowManageModal(false);
                      setShowCreateModal(true);
                    }}
                    className="btn btn-primary mt-4"
                  >
                    Create Your First Dashboard
                  </button>
                </div>
              )}
            </div>
          </div>
        </div>
      )}

      {/* Edit Dashboard Modal */}
      {showEditModal && editingDashboard && (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
          <div className="bg-white rounded-lg p-6 w-full max-w-md">
            <h3 className="comet-title-m mb-4">Edit Dashboard</h3>
            
            <div className="space-y-4">
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">
                  Dashboard Name *
                </label>
                <input
                  type="text"
                  value={newDashboardName}
                  onChange={(e) => setNewDashboardName(e.target.value)}
                  className="w-full border rounded-md px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-500"
                  placeholder="Enter dashboard name"
                  autoFocus
                />
              </div>
              
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">
                  Description
                </label>
                <textarea
                  value={newDashboardDescription}
                  onChange={(e) => setNewDashboardDescription(e.target.value)}
                  className="w-full border rounded-md px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-500"
                  placeholder="Enter description (optional)"
                  rows={3}
                />
              </div>
            </div>

            <div className="flex justify-end gap-2 mt-6">
              <button
                onClick={() => {
                  setShowEditModal(false);
                  setEditingDashboard(null);
                  setNewDashboardName("");
                  setNewDashboardDescription("");
                }}
                className="btn btn-secondary"
                disabled={updateDashboardMutation.isPending}
              >
                Cancel
              </button>
              <button
                onClick={handleUpdateDashboard}
                className="btn btn-primary"
                disabled={!newDashboardName.trim() || updateDashboardMutation.isPending}
              >
                {updateDashboardMutation.isPending ? "Updating..." : "Update Dashboard"}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Delete Confirmation Modal */}
      {showDeleteConfirm && deletingDashboard && (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
          <div className="bg-white rounded-lg p-6 w-full max-w-md">
            <h3 className="comet-title-m mb-4 text-red-600">Delete Dashboard</h3>
            
            <div className="mb-6">
              <p className="text-gray-700">
                Are you sure you want to delete the dashboard "{deletingDashboard.name}"? 
              </p>
              <p className="text-red-600 text-sm mt-2">
                This action cannot be undone. All sections and panels will be permanently deleted.
              </p>
            </div>

            <div className="flex justify-end gap-2">
              <button
                onClick={() => {
                  setShowDeleteConfirm(false);
                  setDeletingDashboard(null);
                }}
                className="btn btn-secondary"
                disabled={deleteDashboardMutation.isPending}
              >
                Cancel
              </button>
              <button
                onClick={confirmDeleteDashboard}
                className="btn btn-danger"
                disabled={deleteDashboardMutation.isPending}
              >
                {deleteDashboardMutation.isPending ? "Deleting..." : "Delete Dashboard"}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default DashboardSelector; 