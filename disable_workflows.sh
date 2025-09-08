#!/bin/bash

echo "🔍 Listing all workflows in your repository..."
echo "Repository: $(gh repo view --json nameWithOwner -q .nameWithOwner)"
echo ""

# Essential workflows to KEEP active for Azure fork development
WORKFLOWS_TO_KEEP=(
    # Code Quality & Formatting
    "Backend Formatting Check"
    "Frontend Linter"
    "SDK Linter"
    "TypeScript SDK Linter"
    
    # Core Testing
    "Backend Tests"
    "Python Backend Tests"
    "SDK Unit Tests"
    "TypeScript SDK Unit Tests"
    
    # Build & Deployment
    "Build Opik Docker Images"
    "Lint Opik Helm Chart"
    
    # Database Management
    "Database Migration Prefix Check"
    
    # Legacy workflow names (in case some use different naming)
    "PR Linter"
)

# Get all workflow names and filter out the ones to keep
echo "📋 Getting list of all workflows to disable..."
# Use IFS and read to properly handle workflow names with spaces
ALL_WORKFLOWS=()
while IFS= read -r workflow; do
    ALL_WORKFLOWS+=("$workflow")
done < <(gh workflow list --json name | jq -r '.[].name')

WORKFLOWS_TO_DISABLE=()
for workflow in "${ALL_WORKFLOWS[@]}"; do
    # Check if this workflow is in the keep list
    keep_workflow=false
    for keep in "${WORKFLOWS_TO_KEEP[@]}"; do
        if [[ "$workflow" == "$keep" ]]; then
            keep_workflow=true
            break
        fi
    done
    
    # If not in keep list, add to disable list
    if [[ "$keep_workflow" == false ]]; then
        WORKFLOWS_TO_DISABLE+=("$workflow")
    fi
done

echo "✅ Workflows to KEEP active (${#WORKFLOWS_TO_KEEP[@]} total):"
printf '  • %s\n' "${WORKFLOWS_TO_KEEP[@]}"
echo ""

echo "🚫 Workflows to DISABLE (${#WORKFLOWS_TO_DISABLE[@]} total):"
printf '  • %s\n' "${WORKFLOWS_TO_DISABLE[@]}"
echo ""

read -p "🤔 Do you want to proceed with disabling these workflows? (y/N): " -n 1 -r
echo ""

if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    echo "❌ Operation cancelled by user."
    exit 0
fi

echo "🔒 Disabling GitHub workflows for private Azure fork..."
echo ""

# Counter for tracking progress
total=${#WORKFLOWS_TO_DISABLE[@]}
current=0
success_count=0
already_disabled_count=0
failed_count=0

for workflow in "${WORKFLOWS_TO_DISABLE[@]}"; do
    current=$((current + 1))
    echo "[$current/$total] Processing workflow: $workflow"
    
    # Try to disable the workflow by name
    disable_output=$(gh workflow disable "$workflow" 2>&1)
    disable_exit_code=$?
    
    if [ $disable_exit_code -eq 0 ]; then
        echo "  ✅ Successfully disabled: $workflow"
        ((success_count++))
    else
        # Check if it's already disabled
        if echo "$disable_output" | grep -q "already disabled\|disabled_manually"; then
            echo "  🔕 Already disabled: $workflow"
            ((already_disabled_count++))
        else
            echo "  ❌ Failed to disable: $workflow"
            echo "     Error: $disable_output"
            ((failed_count++))
        fi
    fi
    echo ""
done

echo "🎉 Workflow processing completed!"
echo ""
echo "📊 Summary:"
echo "  • Total workflows processed: $total"
echo "  • Successfully disabled: $success_count"
echo "  • Already disabled: $already_disabled_count"
echo "  • Failed to disable: $failed_count"
echo ""
echo "📈 Total effectively disabled: $((success_count + already_disabled_count))"
echo "📈 Active workflows remaining: ${#WORKFLOWS_TO_KEEP[@]}"
echo ""
echo "✅ Essential workflows that remain ACTIVE:"
printf '  • %s\n' "${WORKFLOWS_TO_KEEP[@]}"
echo ""
echo "💡 To re-enable a workflow later, use:"
echo "  gh workflow enable \"WORKFLOW_NAME\""
echo ""

if [ $failed_count -gt 0 ]; then
    echo "🔧 Troubleshooting for failed workflows:"
    echo "  1. Check GitHub CLI authentication: gh auth status"
    echo "  2. Re-authenticate if needed: gh auth login"
    echo "  3. Verify repository permissions: gh repo view"
    echo ""
fi

echo "🔍 Check your Actions tab to verify disabled workflows:"
echo "  https://github.com/$(gh repo view --json nameWithOwner -q .nameWithOwner)/actions"