#!/bin/bash

# Server run script for OntomateAIPipeline
# Extracts genes and diseases from PubMed abstracts using AI

. /etc/profile

APPNAME=PosgressAILoader-1.0
APPDIR=/home/rgddata/pipelines/$APPNAME

cd $APPDIR

# Use provided arguments or defaults
ARGS="${@:-rgdLLama70 3 2025}"

java -Dspring.config=$APPDIR/../properties/default_db2.xml \
    -Dlog4j.configurationFile=file://$APPDIR/properties/log4j2.xml \
    -cp "lib/*" \
    edu.mcw.rgd.ontomate.PostgressAILoader $ARGS 2>&1