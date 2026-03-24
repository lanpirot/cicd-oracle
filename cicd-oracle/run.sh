#!/bin/bash
# Run production in resume mode (default, no FRESH_RUN)
# Does clean compile and run in one go

echo "=== Starting production (resume mode) ==="
echo "Step 1: Clean and compile..."
mvn clean compile

if [ $? -ne 0 ]; then
    echo "ERROR: Compilation failed!"
    exit 1
fi

echo ""
echo "Step 2: Running in resume mode (skips already processed repos)..."
mvn spring-boot:run
