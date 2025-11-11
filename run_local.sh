#!/bin/bash

# Local run script for OntomateAIPipeline
# Extracts genes and diseases from PubMed abstracts using AI

APPNAME=PosgressAILoader-1.0
APPDIR=/Users/jdepons/git/dev/OntomateAIPipeline

cd $APPDIR

# Build the project first
echo "Building project..."
./gradlew jar copyDependencies

# Check if build was successful
if [ ! -f "build/libs/$APPNAME.jar" ]; then
    echo "Error: JAR file not found at build/libs/$APPNAME.jar"
    echo "Build may have failed. Please check the output above."
    exit 1
fi

# Run the application
# Extracts: Gene symbols + Disease terms (with RDO IDs)
# Arguments: aiModel threads pubYear [pmid]
# Default: rgdLLama70 3 2025
# Examples:
#   ./run_local.sh                           # Use defaults
#   ./run_local.sh rgdLLama70 5 2024         # Process year 2024 with 5 threads
#   ./run_local.sh rgdLLama70 1 2025 12345678 # Process single PMID 12345678
echo "Starting gene and disease extraction..."

# Use provided arguments or defaults
ARGS="${@:-rgdLLama70 3 2025}"

java -Dspring.config=/Users/jdepons/properties/default_db2.xml \
    -cp "build/libs/$APPNAME.jar:build/libs/lib/*:lib/*" \
    edu.mcw.rgd.ontomate.PostgressAILoader $ARGS
