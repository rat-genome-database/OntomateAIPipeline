# OntomateAIPipeline - Server Deployment Guide

## Prerequisites
- Java 17 or higher installed on the server
- Access to the database server (hoshi.rgd.mcw.edu)
- Access to Elasticsearch (travis.rgd.mcw.edu)
- Access to Ollama AI service (grudge.rgd.mcw.edu)

## Server Directory Structure
```
/home/rgddata/pipelines/
├── PosgressAILoader-1.0/
│   ├── lib/
│   │   ├── PosgressAILoader-1.0.jar
│   │   └── lib/              # Dependencies folder
│   │       └── *.jar
│   ├── properties/
│   │   └── ontomate.properties
│   └── run.sh
└── properties/
    └── default_db2.xml        # Database credentials
```

## Build and Deployment Steps

### 1. Build Distribution Package
```bash
cd /Users/jdepons/git/dev/OntomateAIPipeline
./gradlew clean build createDistro
```

This creates the deployment package at:
- `build/distributions/PosgressAILoader-1.0.zip`
- `build/install/PosgressAILoader-1.0/` (extracted version)

### 2. Transfer to Server
```bash
# Option 1: Using scp
scp build/distributions/PosgressAILoader-1.0.zip user@server:/home/rgddata/pipelines/

# Option 2: Using the extracted directory
rsync -avz build/install/PosgressAILoader-1.0/ user@server:/home/rgddata/pipelines/PosgressAILoader-1.0/
```

### 3. Extract on Server (if using zip)
```bash
ssh user@server
cd /home/rgddata/pipelines
unzip PosgressAILoader-1.0.zip
```

### 4. Database Configuration
The database credentials file must be at:
```
/home/rgddata/pipelines/properties/default_db2.xml
```

This file should contain:
```xml
<bean id="solrPostgressDataSource" class="org.apache.commons.dbcp2.BasicDataSource" destroy-method="close">
    <property name="driverClassName"><value>org.postgresql.Driver</value></property>
    <property name="url"><value>jdbc:postgresql://hoshi.rgd.mcw.edu:5432/rgdsolrdev</value></property>
    <property name="username"><value>rgdsolrownerdev</value></property>
    <property name="password"><value>SlrT3stextCh4f#25dv</value></property>
</bean>
```

## Running the Pipeline

### Default Run (all 2025 records with 3 threads)
```bash
cd /home/rgddata/pipelines/PosgressAILoader-1.0
./run.sh
```

### Custom Parameters
```bash
# Process with 5 threads
./run.sh rgdLLama70 5 2025

# Process year 2024 with 10 threads
./run.sh rgdLLama70 10 2024

# Process single PMID for testing
./run.sh rgdLLama70 1 2025 20952494
```

### Arguments
1. **aiModel**: AI model name (default: rgdLLama70)
2. **threads**: Number of parallel threads (default: 3)
3. **pubYear**: Publication year to process (default: 2025)
4. **pmid** (optional): Process single PMID instead of all records

## Configuration Files

### ontomate.properties
Located at: `properties/ontomate.properties`

Key settings:
```properties
# Set to future date to reprocess all records
last.update.date=2025-12-31

# AI service
ollama.base.url=http://grudge.rgd.mcw.edu:11434

# Elasticsearch
elasticsearch.host=travis.rgd.mcw.edu
elasticsearch.port=9200
elasticsearch.index=aimappings_index_dev

# Defaults
default.ai.model=rgdLLama70
default.threads=3
default.year=2025
```

## Processing Statistics

For year 2025:
- Total records: 838,589
- With abstracts: ~95% (estimated)
- Processing speed: ~2-3 records/second per thread
- Estimated time (3 threads): ~30-40 hours
- Estimated time (10 threads): ~10-12 hours

## Monitoring

### Check Progress
```bash
# Check log output
tail -f /home/rgddata/pipelines/PosgressAILoader-1.0/logs/ontomate.log

# Check database for processed records
psql -h hoshi.rgd.mcw.edu -d rgdsolrdev -U rgdsolrownerdev -c \
  "SELECT COUNT(*) FROM solr_docs WHERE last_update_date >= '2025-12-31' AND p_year = 2025;"
```

### Common Issues

1. **OutOfMemoryError**: Increase heap size in run.sh:
   ```bash
   java -Xmx4g -Xms2g ...
   ```

2. **Connection timeouts**: Check network access to:
   - Database: hoshi.rgd.mcw.edu:5432
   - Elasticsearch: travis.rgd.mcw.edu:9200
   - Ollama: grudge.rgd.mcw.edu:11434

3. **No records processed**: Check last.update.date in ontomate.properties

## Reprocessing All Records

To force reprocessing of all records for a year, set last.update.date to a future date:
```properties
last.update.date=2025-12-31
```

This ensures the WHERE clause `last_update_date < DATE '2025-12-31'` matches all records.
