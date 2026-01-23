.PHONY: cursor claude clean-agents help hooks hooks-remove

AI_DIR := .agents
CURSOR_DIR := .cursor
CLAUDE_DIR := .claude
HOOKS_SRC := .hooks
HOOKS_DEST := .git/hooks

help:
	@echo "AI Editor Configuration Sync"
	@echo ""
	@echo "  make cursor        - Ensure .cursor symlink points to .agents/"
	@echo "  make claude        - Sync .agents/ to .claude/ + generate .mcp.json for Claude CLI"
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

# Sync to Claude (preserve nested structure, convert frontmatter)
# Converts: .mdc -> .md, Cursor frontmatter -> Claude frontmatter
# Cursor: globs/alwaysApply -> Claude: paths
claude:
	@if [ ! -d "$(AI_DIR)/rules" ]; then \
		echo "Error: $(AI_DIR)/rules/ does not exist."; \
		exit 1; \
	fi
	@echo "Syncing $(AI_DIR)/rules/ to $(CLAUDE_DIR)/rules/ (nested, with frontmatter conversion)..."
	@rm -rf $(CLAUDE_DIR)/rules
	@find $(AI_DIR)/rules -name "*.mdc" | while read src; do \
		rel=$${src#$(AI_DIR)/rules/}; \
		dest="$(CLAUDE_DIR)/rules/$${rel%.mdc}.md"; \
		mkdir -p "$$(dirname "$$dest")"; \
		./scripts/convert-frontmatter.sh cursor-to-claude "$$src" "$$dest"; \
		echo "  $$rel -> $${rel%.mdc}.md"; \
	done
	@# Sync commands (preserve nested structure)
	@if [ -d "$(AI_DIR)/commands" ]; then \
		echo "Syncing $(AI_DIR)/commands/ to $(CLAUDE_DIR)/commands/..."; \
		rm -rf $(CLAUDE_DIR)/commands; \
		find $(AI_DIR)/commands -name "*.md" | while read src; do \
			rel=$${src#$(AI_DIR)/commands/}; \
			dest="$(CLAUDE_DIR)/commands/$$rel"; \
			mkdir -p "$$(dirname "$$dest")"; \
			cp "$$src" "$$dest"; \
			echo "  $$rel"; \
		done; \
	fi
	@# Convert MCP config to Claude CLI format (.mcp.json at repo root)
	@if [ -f "$(AI_DIR)/mcp.json" ]; then \
		echo "Converting MCP config to Claude CLI format..."; \
		./scripts/convert-mcp.sh "$(AI_DIR)/mcp.json" ".mcp.json"; \
	fi
	@echo "Claude ready! Files in $(CLAUDE_DIR)/ and .mcp.json"

# Clean generated files (preserves .agents/)
clean-agents:
	@echo "Removing generated files..."
	@[ -L "$(CURSOR_DIR)" ] && rm -f $(CURSOR_DIR) && echo "Removed $(CURSOR_DIR) symlink" || true
	@rm -rf $(CLAUDE_DIR)/rules $(CLAUDE_DIR)/commands && echo "Removed $(CLAUDE_DIR)/rules/ and commands/" || true
	@[ -f ".mcp.json" ] && rm -f .mcp.json && echo "Removed .mcp.json" || true
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
