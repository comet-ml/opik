.PHONY: help sync-skills

# Default target
help:
	@echo "Opik Project Makefile"
	@echo ""
	@echo "Available targets:"
	@echo "  sync-skills - Sync skills from .agents/skills/ to .cursor/skills/"
	@echo "  help        - Show this help message"

# Sync skills from .agents/skills/ to .cursor/skills/
sync-skills:
	@echo "Syncing skills from .agents/skills/ to .cursor/skills/..."
	@mkdir -p .cursor/skills
	@rsync -av --delete .agents/skills/ .cursor/skills/
	@echo "✓ Skills synced successfully"
