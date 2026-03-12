#!/bin/bash
# Run production with FRESH_RUN mode
# Does clean compile and run in one go

echo "=== Starting FRESH_RUN production ==="
echo "Step 1: Clean and compile..."
mvn clean compile

if [ $? -ne 0 ]; then
    echo "ERROR: Compilation failed!"
    exit 1
fi

echo ""
echo "Step 2: Running with FRESH_RUN=true..."
mvn spring-boot:run -Dspring-boot.run.jvmArguments="-DfreshRun=true"
