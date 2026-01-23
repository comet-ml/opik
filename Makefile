.PHONY: cursor claude codex sync-agents clean-agents help hooks hooks-remove

AI_DIR := .agents
CURSOR_DIR := .cursor
CLAUDE_DIR := .claude
AGENTS_FILE := AGENTS.md
HOOKS_SRC := .hooks
HOOKS_DEST := .git/hooks

help:
	@echo "AI Editor Configuration Sync"
	@echo ""
	@echo "  make cursor        - Ensure .cursor symlink points to .agents/"
	@echo "  make claude        - Sync .agents/ to .claude/ (flattened rules + MCP)"
	@echo "  make codex         - Generate AGENTS.md from .agents/rules/"
	@echo "  make sync-agents   - Sync changes FROM current editor BACK to .agents/"
	@echo "  make clean-agents  - Remove generated files (keeps .agents/)"
	@echo ""
	@echo "Git Hooks"
	@echo ""
	@echo "  make hooks         - Install pre-commit hooks"
	@echo "  make hooks-remove  - Remove pre-commit hooks"
	@echo ""

# Sync to Cursor (symlink .cursor -> .agents)
cursor:
	@if [ ! -d "$(AI_DIR)" ]; then \
		echo "Error: $(AI_DIR)/ does not exist. Run the migration first."; \
		exit 1; \
	fi
	@if [ -d "$(CURSOR_DIR)" ] && [ ! -L "$(CURSOR_DIR)" ]; then \
		echo "Error: $(CURSOR_DIR)/ exists and is not a symlink."; \
		echo "Remove it manually if you want to use .agents/ as source."; \
		exit 1; \
	fi
	@if [ -L "$(CURSOR_DIR)" ]; then \
		echo "$(CURSOR_DIR) symlink already exists"; \
	else \
		echo "Creating symlink $(CURSOR_DIR) -> $(AI_DIR)..."; \
		ln -s $(AI_DIR) $(CURSOR_DIR); \
	fi
	@echo "Cursor ready!"

# Sync to Claude (flatten .agents/rules/ -> .claude/rules/ and merge MCP config)
# Converts: apps/opik-backend/api.mdc -> apps-opik-backend-api.md
claude:
	@if [ ! -d "$(AI_DIR)/rules" ]; then \
		echo "Error: $(AI_DIR)/rules/ does not exist."; \
		exit 1; \
	fi
	@echo "Syncing $(AI_DIR)/rules/ to $(CLAUDE_DIR)/rules/ (flattened)..."
	@mkdir -p $(CLAUDE_DIR)/rules
	@rm -f $(CLAUDE_DIR)/rules/*.md
	@find $(AI_DIR)/rules -name "*.mdc" | while read src; do \
		rel=$${src#$(AI_DIR)/rules/}; \
		flat=$$(echo "$$rel" | sed 's|/|-|g' | sed 's|\.mdc$$|.md|'); \
		cp "$$src" "$(CLAUDE_DIR)/rules/$$flat"; \
		echo "  $$rel -> $$flat"; \
	done
	@# Sync commands (flatten nested structure)
	@if [ -d "$(AI_DIR)/commands" ]; then \
		echo "Syncing $(AI_DIR)/commands/ to $(CLAUDE_DIR)/commands/..."; \
		mkdir -p $(CLAUDE_DIR)/commands; \
		rm -rf $(CLAUDE_DIR)/commands/*; \
		find $(AI_DIR)/commands -name "*.md" | while read src; do \
			rel=$${src#$(AI_DIR)/commands/}; \
			flat=$$(echo "$$rel" | sed 's|/|-|g'); \
			cp "$$src" "$(CLAUDE_DIR)/commands/$$flat"; \
			echo "  $$rel -> $$flat"; \
		done; \
	fi
	@# Merge MCP config into settings.local.json
	@if [ -f "$(AI_DIR)/mcp.json" ]; then \
		echo "Merging MCP config into $(CLAUDE_DIR)/settings.local.json..."; \
		if [ -f "$(CLAUDE_DIR)/settings.local.json" ]; then \
			jq -s '.[0] * .[1]' "$(CLAUDE_DIR)/settings.local.json" "$(AI_DIR)/mcp.json" > "$(CLAUDE_DIR)/settings.local.json.tmp"; \
			mv "$(CLAUDE_DIR)/settings.local.json.tmp" "$(CLAUDE_DIR)/settings.local.json"; \
		else \
			cp "$(AI_DIR)/mcp.json" "$(CLAUDE_DIR)/settings.local.json"; \
		fi; \
		echo "  MCP config merged"; \
	fi
	@echo "Claude ready! Files in $(CLAUDE_DIR)/"

# Generate AGENTS.md (pointer to .agents/ for Codex, etc.)
codex: $(AGENTS_FILE)
	@echo "Codex/Agents ready! Generated $(AGENTS_FILE)"

$(AGENTS_FILE):
	@echo "Generating $(AGENTS_FILE)..."
	@echo "# AI Coding Guidelines" > $(AGENTS_FILE)
	@echo "" >> $(AGENTS_FILE)
	@echo "This project uses structured AI rules in \`.agents/rules/\`." >> $(AGENTS_FILE)
	@echo "" >> $(AGENTS_FILE)
	@echo "## Rules Structure" >> $(AGENTS_FILE)
	@echo "" >> $(AGENTS_FILE)
	@echo "\`\`\`" >> $(AGENTS_FILE)
	@echo ".agents/rules/" >> $(AGENTS_FILE)
	@echo "  core.mdc           # Git workflow, clean code, testing standards" >> $(AGENTS_FILE)
	@echo "  project.mdc        # Project structure and quick commands" >> $(AGENTS_FILE)
	@echo "  development.mdc    # Local dev workflow" >> $(AGENTS_FILE)
	@echo "  apps/              # Backend, frontend, docs rules" >> $(AGENTS_FILE)
	@echo "  sdks/              # Python, TypeScript, Optimizer SDK rules" >> $(AGENTS_FILE)
	@echo "\`\`\`" >> $(AGENTS_FILE)
	@echo "" >> $(AGENTS_FILE)
	@echo "Read the relevant \`.mdc\` files for the component you're working on." >> $(AGENTS_FILE)

# Sync changes from editor-specific locations back to .agents/
# Detects which editor is in use and syncs appropriately
sync-agents:
	@echo "Syncing changes back to $(AI_DIR)/..."
	@# Cursor: if .cursor is a symlink, nothing to do (already pointing to .agents)
	@if [ -L "$(CURSOR_DIR)" ]; then \
		echo "Cursor: symlink detected, no sync needed (already using .agents/)"; \
	elif [ -d "$(CURSOR_DIR)" ]; then \
		echo "Cursor: syncing $(CURSOR_DIR)/ -> $(AI_DIR)/..."; \
		rsync -av --delete "$(CURSOR_DIR)/rules/" "$(AI_DIR)/rules/"; \
		[ -d "$(CURSOR_DIR)/commands" ] && rsync -av "$(CURSOR_DIR)/commands/" "$(AI_DIR)/commands/"; \
		[ -f "$(CURSOR_DIR)/mcp.json" ] && cp "$(CURSOR_DIR)/mcp.json" "$(AI_DIR)/mcp.json"; \
		echo "  Synced from Cursor"; \
	fi
	@# Claude: unflatten rules back to nested structure
	@if [ -d "$(CLAUDE_DIR)/rules" ]; then \
		echo "Claude: syncing $(CLAUDE_DIR)/rules/ -> $(AI_DIR)/rules/ (unflattening)..."; \
		for src in $(CLAUDE_DIR)/rules/*.md; do \
			[ -f "$$src" ] || continue; \
			base=$$(basename "$$src" .md); \
			nested=$$(echo "$$base" | sed 's|-|/|g').mdc; \
			dir=$$(dirname "$(AI_DIR)/rules/$$nested"); \
			mkdir -p "$$dir"; \
			cp "$$src" "$(AI_DIR)/rules/$$nested"; \
			echo "  $$base.md -> $$nested"; \
		done; \
	fi
	@# Claude: unflatten commands back to nested structure  
	@if [ -d "$(CLAUDE_DIR)/commands" ]; then \
		echo "Claude: syncing $(CLAUDE_DIR)/commands/ -> $(AI_DIR)/commands/ (unflattening)..."; \
		for src in $(CLAUDE_DIR)/commands/*.md; do \
			[ -f "$$src" ] || continue; \
			base=$$(basename "$$src" .md); \
			nested=$$(echo "$$base" | sed 's|-|/|g').md; \
			dir=$$(dirname "$(AI_DIR)/commands/$$nested"); \
			mkdir -p "$$dir"; \
			cp "$$src" "$(AI_DIR)/commands/$$nested"; \
			echo "  $$base.md -> $$nested"; \
		done; \
	fi
	@echo "Done! $(AI_DIR)/ is now up to date."

# Clean generated files (preserves .agents/)
clean-agents:
	@echo "Removing generated files..."
	@[ -L "$(CURSOR_DIR)" ] && rm -f $(CURSOR_DIR) && echo "Removed $(CURSOR_DIR) symlink" || true
	@rm -rf $(CLAUDE_DIR)/rules $(CLAUDE_DIR)/commands && echo "Removed $(CLAUDE_DIR)/rules/ and commands/" || true
	@[ -f "$(AGENTS_FILE)" ] && rm -f $(AGENTS_FILE) && echo "Removed $(AGENTS_FILE)" || true
	@echo "Done! $(AI_DIR)/ preserved."

# Install Git hooks from .hooks/ to .git/hooks/
hooks:
	@if [ ! -d "$(HOOKS_SRC)" ]; then \
		echo "Error: $(HOOKS_SRC)/ does not exist."; \
		exit 1; \
	fi
	@if [ ! -d "$(HOOKS_DEST)" ]; then \
		echo "Error: $(HOOKS_DEST)/ does not exist. Is this a git repository?"; \
		exit 1; \
	fi
	@cp $(HOOKS_SRC)/pre-commit $(HOOKS_DEST)/pre-commit
	@chmod +x $(HOOKS_DEST)/pre-commit
	@echo "Pre-commit hook installed."

# Remove Git hooks
hooks-remove:
	@if [ -f "$(HOOKS_DEST)/pre-commit" ]; then \
		rm -f $(HOOKS_DEST)/pre-commit; \
		echo "Pre-commit hook removed."; \
	else \
		echo "No pre-commit hook found."; \
	fi
