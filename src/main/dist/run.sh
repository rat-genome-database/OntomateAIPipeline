#!/bin/bash

. /etc/profile

APPNAME=PosgressAILoader-1.0
APPDIR=/home/rgddata/pipelines/$APPNAME

cd $APPDIR

java -Dspring.config=$APPDIR/../properties/default_db2.xml \
    -Dlog4j.configurationFile=file://$APPDIR/properties/log4j2.xml \
    -jar lib/$APPNAME.jar "$@" rgdllama3.18b 3 2>&1