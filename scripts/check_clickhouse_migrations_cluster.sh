#!/bin/bash

# Script to check ClickHouse migrations for proper ON CLUSTER clause usage
# This script validates that all DDL operations in ClickHouse migrations include the ON CLUSTER '{cluster}' clause
# Reference: https://clickhouse.com/docs/sql-reference/distributed-ddl
#
# Usage:
#   ./check_clickhouse_migrations_cluster.sh                    # Check all migration files
#   ./check_clickhouse_migrations_cluster.sh file1.sql file2.sql # Check specific files

set -euo pipefail

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Configuration
MIGRATION_DIR="apps/opik-backend/src/main/resources/liquibase/db-app-analytics/migrations"
EXIT_CODE=0

echo "🔍 Checking ClickHouse migrations for ON CLUSTER clause usage..."
echo "📁 Migration directory: ${MIGRATION_DIR}"
echo

# Function to check if a DDL statement has ON CLUSTER clause
check_ddl_statement() {
    local file="$1"
    local line_num="$2"
    local line="$3"
    local ddl_type="$4"
    
    # Skip if line is a comment or rollback statement
    if echo "$line" | grep -qE "^\s*--"; then
        return 0
    fi
    
    # Skip if this is part of a rollback section
    if echo "$line" | grep -qiE "rollback.*${ddl_type}"; then
        return 0
    fi
    
    # Check if the DDL statement has ON CLUSTER clause 
    # Both patterns are valid in ClickHouse:
    # - ON CLUSTER '{cluster}' (with quotes)  
    # - ON CLUSTER {cluster} (without quotes)
    if ! echo "$line" | grep -qiE "ON\s+CLUSTER\s+(['\"][^'\"]*['\"]|[{][^}]*[}])"; then
        echo -e "${RED}❌ ERROR: Missing ON CLUSTER clause${NC}"
        echo -e "   📄 File: ${file}"
        echo -e "   📍 Line ${line_num}: ${line}"
        echo -e "   🔧 Expected: ${ddl_type} ... ON CLUSTER '{cluster}' or ON CLUSTER {cluster}"
        echo
        return 1
    fi
    
    return 0
}

# Function to validate a single migration file
validate_migration_file() {
    local file="$1"
    local file_errors=0
    
    echo "📋 Checking: $(basename "$file")"
    
    # Read file line by line
    local line_num=0
    while IFS= read -r line; do
        ((line_num++))
        
        # Remove leading/trailing whitespace (but preserve quotes)
        line="${line#"${line%%[![:space:]]*}"}"  # Remove leading whitespace
        line="${line%"${line##*[![:space:]]}"}"  # Remove trailing whitespace
        
        # Skip empty lines and comments
        if [[ -z "$line" || "$line" =~ ^[[:space:]]*-- ]]; then
            continue
        fi
        
        # Check for DDL statements (case insensitive)
        if echo "$line" | grep -qiE "^\s*(CREATE|DROP|ALTER|RENAME)\s+(TABLE|INDEX)"; then
            # Extract DDL type
            local ddl_type=$(echo "$line" | sed -nE 's/^\s*(CREATE|DROP|ALTER|RENAME)\s+.*/\1/Ip')
            
            if ! check_ddl_statement "$file" "$line_num" "$line" "$ddl_type"; then
                ((file_errors++))
            fi
        fi
    done < "$file"
    
    if [[ $file_errors -eq 0 ]]; then
        echo -e "   ✅ ${GREEN}All DDL statements have proper ON CLUSTER clause${NC}"
    else
        echo -e "   ❌ ${RED}Found $file_errors DDL statement(s) missing ON CLUSTER clause${NC}"
    fi
    
    echo
    return $file_errors
}

# Main validation logic
main() {
    local total_errors=0
    local total_files=0
    local migration_files=()
    
    # Check if specific files were provided as arguments
    if [[ $# -gt 0 ]]; then
        # Use provided file arguments
        migration_files=("$@")
        echo "🎯 Validating specific migration files provided as arguments..."
        
        # Validate that all provided files exist and are SQL files
        for file in "${migration_files[@]}"; do
            if [[ ! -f "$file" ]]; then
                echo -e "${RED}❌ ERROR: File not found: $file${NC}"
                exit 1
            fi
            
            if [[ ! "$file" =~ \.sql$ ]]; then
                echo -e "${YELLOW}⚠️  WARNING: Skipping non-SQL file: $file${NC}"
                continue
            fi
        done
    else
        # Check if migration directory exists
        if [[ ! -d "$MIGRATION_DIR" ]]; then
            echo -e "${RED}❌ ERROR: Migration directory not found: $MIGRATION_DIR${NC}"
            exit 1
        fi
        
        # Find all SQL migration files in the directory
        migration_files=($(find "$MIGRATION_DIR" -name "*.sql" | sort))
        echo "📊 Validating all migration files in directory..."
    fi
    
    # Filter out non-SQL files and files that don't exist
    local valid_files=()
    for file in "${migration_files[@]}"; do
        if [[ -f "$file" && "$file" =~ \.sql$ ]]; then
            valid_files+=("$file")
        fi
    done
    
    if [[ ${#valid_files[@]} -eq 0 ]]; then
        echo -e "${YELLOW}⚠️  WARNING: No valid SQL migration files to check${NC}"
        exit 0
    fi
    
    echo "📊 Found ${#valid_files[@]} migration file(s) to check"
    echo "=" $(printf '=%.0s' {1..50})
    echo
    
    # Validate each migration file
    for file in "${valid_files[@]}"; do
        ((total_files++))
        
        if ! validate_migration_file "$file"; then
            ((total_errors++))
        fi
    done
    
    # Summary
    echo "=" $(printf '=%.0s' {1..50})
    echo "📈 SUMMARY:"
    echo "   📁 Total files checked: $total_files"
    
    if [[ $total_errors -eq 0 ]]; then
        echo -e "   ✅ ${GREEN}All migration files are valid!${NC}"
        echo -e "   🎉 ${GREEN}All DDL operations include proper ON CLUSTER clause${NC}"
    else
        echo -e "   ❌ ${RED}Found $total_errors file(s) with missing ON CLUSTER clauses${NC}"
        echo -e "   🛠️  ${YELLOW}Please add 'ON CLUSTER '\"'\"'{cluster}'\"'\"'' to all CREATE, DROP, ALTER, and RENAME statements${NC}"
        echo
        echo -e "${YELLOW}📖 Reference: https://clickhouse.com/docs/sql-reference/distributed-ddl${NC}"
        echo -e "${YELLOW}🔧 Example: CREATE TABLE my_table ON CLUSTER '{cluster}' (...);${NC}"
        EXIT_CODE=1
    fi
    
    echo
}

# Run main function
main "$@"
exit $EXIT_CODE
