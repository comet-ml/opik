#!/bin/sh
#
# This is a script to execute smoke tests with naked OPIK installation
#
echo "░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░"
echo ""
echo "Running smoke tests..."
echo ""

# Find all Python files in current directory
python_files=$(find . -maxdepth 1 -name "*.py" ! -name "__init__.py")

# Check if any Python files were found
if [ -z "$python_files" ]; then
    echo "No Python files found in current directory: $(pwd)"
    exit 1
fi

# Track failures to report aggregate result at the end
failed=0

# Execute each Python file
for file in $python_files; do
    echo "═════════════════════════════════════════════════════════════════"
    echo ""
    echo ">>> Executing: $file"
    python3 "$file"

    # Check execution status
    if [ $? -eq 0 ]; then
        echo ">>> ✔ Test passed: $file"
    else
        echo ">>> ❌ Test failed: $file"
        failed=1
    fi
    echo ""
done

echo "═════════════════════════════════════════════════════════════════"
echo "▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓"
echo ""

# Final summary
if [ $failed -eq 0 ]; then
    echo ">>> ✅ All smoke tests completed successfully!"
else
    echo ">>> ❌ Some tests failed!"
    exit 1
fi

echo ""
echo "░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░"
