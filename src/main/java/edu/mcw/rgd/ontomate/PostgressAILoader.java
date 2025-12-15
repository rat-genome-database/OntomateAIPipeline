package edu.mcw.rgd.ontomate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.ollama.OllamaChatModel;
import edu.mcw.rgd.dao.DataSourceFactory;
import edu.mcw.rgd.dao.impl.solr.SolrDocsDAO;
import org.apache.http.HttpHost;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;

import javax.sql.DataSource;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.Date;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class PostgressAILoader implements Runnable {

    public static AtomicInteger totalProcessed = new AtomicInteger(0);
    public static AtomicInteger totalSuccess = new AtomicInteger(0);
    public static AtomicInteger totalFailures = new AtomicInteger(0);

    private String abstractText;
    private String pmid;

    public static String lud = ConfigManager.getLastUpdateDate();
    public static String aiModel = "";
    public static int threads = 1;
    public static String pubYear = "";
    public static String pmidFilter = null;  // Optional: process single PMID

    // Reusable AI model instance (thread-safe)
    private static volatile OllamaChatModel sharedModel = null;
    private static final Object modelLock = new Object();

    public PostgressAILoader(String abstractText, String pmid) {
        this.abstractText = abstractText;
        this.pmid = pmid;
    }

    /**
     * Get or create the shared OllamaChatModel instance (thread-safe singleton)
     */
    private static OllamaChatModel getModel() {
        if (sharedModel == null) {
            synchronized (modelLock) {
                if (sharedModel == null) {
                    sharedModel = OllamaChatModel.builder()
                            .baseUrl(ConfigManager.getOllamaBaseUrl())
                            .modelName(aiModel)
                            .build();
                    System.out.println("Created shared OllamaChatModel: " + aiModel);
                }
            }
        }
        return sharedModel;
    }

    public void run() {
        int processed = totalProcessed.incrementAndGet();

        try {
            // Log progress every 10 records
            if (processed % 10 == 0) {
                System.out.println(String.format("Progress: %d processed, %d success, %d failures",
                        processed, totalSuccess.get(), totalFailures.get()));
            }

            // Handle empty abstracts
            if (abstractText == null || abstractText.trim().isEmpty()) {
                this.updateLastUpdate(pmid);
                totalSuccess.incrementAndGet();
                return;
            }

            // Use shared model instance
            OllamaChatModel model = getModel();

            // Generate prompt - LLM extracts both genes and diseases
            String prompt = """
                Extract all genes and diseases discussed in the following abstract.

                GENE EXTRACTION RULES:
                - Convert gene names to their official symbols (e.g., "tumor protein p53" → TP53)
                - Include genes mentioned by symbol (e.g., "TP53" → TP53)
                - Include genes mentioned by name only (e.g., "amyloid precursor protein" → APP)
                - Preserve species-appropriate capitalization (Human: TP53, Mouse/Rat: Tp53, Zebrafish: tp53)
                - Each symbol should appear only once (remove duplicates)
                - Maximum 100 symbols

                DISEASE EXTRACTION RULES:
                - Extract disease names as they appear (e.g., "Alzheimer's disease", "diabetes mellitus")
                - Include diseases mentioned by common names
                - Normalize to standard disease names when possible
                - Each disease should appear only once (remove duplicates)
                - Maximum 100 diseases

                EXCLUDE:
                - Proteins/enzymes that are not genes
                - Common words, species names
                - Symptoms that are not diseases

                OUTPUT FORMAT (two lines):
                GENES: gene1|gene2|gene3
                DISEASES: disease1|disease2|disease3

                EXAMPLES:
                Abstract: "TP53 mutations are associated with cancer and Alzheimer's disease."
                GENES: TP53
                DISEASES: cancer|Alzheimer's disease

                Abstract: "Diabetes mellitus affects insulin production by pancreatic beta cells."
                GENES:
                DISEASES: diabetes mellitus

                Abstract: "Expression of Tp53 and Apoe in models of Parkinson disease and hypertension."
                GENES: Tp53|Apoe
                DISEASES: Parkinson disease|hypertension

                ABSTRACT:
                %s

                OUTPUT:
                """.formatted(abstractText);

            // Call AI model
            String response = model.generate(prompt);

            // Parse response to separate genes and diseases
            ExtractionResult result = parseExtractionResult(response, abstractText);

            // Update database with both genes and diseases
            this.update(pmid, result);

            totalSuccess.incrementAndGet();

        } catch (Exception e) {
            totalFailures.incrementAndGet();
            System.err.println("ERROR processing PMID " + pmid + ": " + e.getMessage());
            e.printStackTrace();

            // Try to update with empty data to mark as processed
            try {
                this.updateLastUpdate(pmid);
            } catch (Exception ex) {
                System.err.println("FATAL: Failed to update last_update_date for PMID " + pmid + ": " + ex.getMessage());
            }
        }
    }

    /**
     * Parse the LLM response to extract genes and diseases separately
     */
    private ExtractionResult parseExtractionResult(String response, String abstractText) {
        String genes = "";
        String diseases = "";

        // Parse the response - expecting format:
        // GENES: gene1|gene2
        // DISEASES: disease1|disease2
        String[] lines = response.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.toUpperCase().startsWith("GENES:")) {
                genes = line.substring(6).trim();
            } else if (line.toUpperCase().startsWith("DISEASES:")) {
                diseases = line.substring(9).trim();
            }
        }

        // Get position info for genes
        HashMap<String, String> geneInfo = getPositionInfo(genes, abstractText);

        // Get position and ID info for diseases
        HashMap<String, String> diseaseInfo = getDiseasePositionInfo(diseases, abstractText);

        return new ExtractionResult(geneInfo, diseaseInfo);
    }

    /**
     * Find positions of disease terms in the abstract text and lookup RDO IDs
     */
    private HashMap<String, String> getDiseasePositionInfo(String textList, String abstractText) {
        HashMap<String, String> hm = new HashMap<>();

        if (isEmpty(textList)) {
            hm.put("positions", "");
            hm.put("counts", "");
            hm.put("terms", "");
            hm.put("ids", "");
            return hm;
        }

        String[] diseases = textList.split("\\|");
        String posString = "";
        String countString = "";
        String diseaseString = "";
        String idString = "";

        for (int i = 0; i < diseases.length; i++) {
            String currentDisease = diseases[i].trim();

            if (isEmpty(currentDisease) || currentDisease.length() > 100) {
                if (currentDisease.length() > 0) {
                    System.out.println("WARNING: Discarding invalid disease term (too long): " + currentDisease);
                }
                continue;
            }

            // Find positions in text
            int count = 0;
            int index = abstractText.indexOf(currentDisease);

            if (index == -1) {
                // Disease term not found literally in text (likely normalized)
                posString += "|0;0-0";
            }

            while (index != -1) {
                posString = posString + "|" + "1;" + index + "-" + (index + currentDisease.length());
                index = abstractText.indexOf(currentDisease, index + 1);
                count++;

                if (count == 100) {
                    System.out.println("WARNING: Found 100 occurrences (max limit) for disease: " + currentDisease);
                    break;
                }
            }

            // Lookup RDO ID using Elasticsearch
            String rdoId = "";
            try {
                rdoId = getAcc(currentDisease, "DO");
                System.out.println("Disease: " + currentDisease + " → RDO ID: " + rdoId);
            } catch (IOException e) {
                System.err.println("WARNING: Could not lookup RDO ID for: " + currentDisease + " - " + e.getMessage());
                rdoId = ""; // Empty if lookup fails
            }

            countString += " | " + count;
            diseaseString += " | " + currentDisease;
            idString += " | " + rdoId;
        }

        // Build result map
        if (!posString.isEmpty()) {
            hm.put("positions", posString.substring(1));
            hm.put("counts", countString.substring(3));
            hm.put("terms", diseaseString.substring(3));
            hm.put("ids", idString.substring(3));
        } else {
            hm.put("positions", "");
            hm.put("counts", "");
            hm.put("terms", "");
            hm.put("ids", "");
        }

        return hm;
    }

    /**
     * Find positions of gene symbols in the abstract text.
     *
     * Note: gene_count=0 is expected when the LLM normalized a gene name to its symbol
     * (e.g., "tumor protein p53" → TP53). In these cases, the gene was discussed but
     * the symbol doesn't appear literally in the text.
     */
    private HashMap<String,String> getPositionInfo(String textList,String abstractText) {

        HashMap<String, String> hm = new HashMap<String,String>();

        if (isEmpty(textList)) {
            hm.put("positions","");
            hm.put("counts","");
            hm.put("terms","");
            return hm;
        }

        String[] val = textList.split("\\|");

        boolean first = true;
        String posString="";
        String countString="";
        String geneString="";
        int count=0;

        for (int i=0; i< val.length;i++) {
            String currentValue=val[i].trim();

            if(isEmpty(currentValue) || currentValue.length() > 40) {
                if (currentValue.length() > 0 ) {
                    System.out.println("WARNING: Discarding invalid gene symbol (too long): " + currentValue);
                }
                continue;
            }

            count=0;
            int index = abstractText.indexOf(currentValue);

            // Symbol not found literally in text (likely normalized from gene name)
            if (index == -1) {
                posString+= "|0;0-0";
            }

            // Keep searching as long as we find a valid index
            while (index != -1) {
                posString=posString + "|" + "1;" + index + "-" + (index + currentValue.length());
                // Move 1 character ahead to find subsequent (possibly overlapping) occurrences
                index = abstractText.indexOf(currentValue, index + 1);
                count++;

                if (count == 100) {
                    System.out.println("WARNING: Found 100 occurrences (max limit) for gene symbol: " + currentValue);
                    break;
                }
            }

            countString += " | " + count;
            geneString += " | " + currentValue;
            count=0;

        }

        hm = new HashMap<String,String>();
        hm.put("positions",posString.substring(1));
        hm.put("counts",countString.substring(3));
        hm.put("terms",geneString.substring(3));


        return hm;
    }

    private boolean isEmpty(String str) {
        str = str.replaceAll("\\|","");
        if (str.trim().toLowerCase().startsWith("none") || str.trim().equals("")) {
            return true;
        }
        return false;
    }

    public void updateLastUpdate(String pmid) throws Exception {
        HashMap<String, String> emptyGeneInfo = new HashMap<>();
        emptyGeneInfo.put("positions", "");
        emptyGeneInfo.put("counts", "");
        emptyGeneInfo.put("terms", "");

        HashMap<String, String> emptyDiseaseInfo = new HashMap<>();
        emptyDiseaseInfo.put("positions", "");
        emptyDiseaseInfo.put("counts", "");
        emptyDiseaseInfo.put("terms", "");
        emptyDiseaseInfo.put("ids", "");

        ExtractionResult emptyResult = new ExtractionResult(emptyGeneInfo, emptyDiseaseInfo);
        this.update(pmid, emptyResult);
    }

    public void update(String pmid, ExtractionResult result) throws Exception {
        String query = "UPDATE SOLR_DOCS SET " +
                "gene_count=?, gene=?, gene_pos=?, " +
                "rdo_count=?, rdo_term=?, rdo_pos=?, rdo_id=?, " +
                "last_update_date=? " +
                "WHERE pmid=?";

        Connection conn = null;
        PreparedStatement s = null;

        try {
            conn = DataSourceFactory.getInstance().getPostgressDataSource().getConnection();

            // Disable auto-commit so we can control when to commit
            conn.setAutoCommit(false);

            s = conn.prepareStatement(query);

            // Set gene fields
            s.setString(1, result.geneInfo.get("counts"));
            s.setString(2, result.geneInfo.get("terms"));
            s.setString(3, result.geneInfo.get("positions"));

            // Set disease (RDO) fields
            s.setString(4, result.diseaseInfo.get("counts"));
            s.setString(5, result.diseaseInfo.get("terms"));
            s.setString(6, result.diseaseInfo.get("positions"));
            s.setString(7, result.diseaseInfo.get("ids"));

            // Set timestamp and identifier
            s.setDate(8, new java.sql.Date(new Date().getTime()));
            s.setString(9, pmid);

            int rowsUpdated = s.executeUpdate();

            // EXPLICITLY COMMIT THE TRANSACTION
            conn.commit();

            if (rowsUpdated == 0) {
                System.err.println("WARNING: No rows updated for PMID " + pmid);
            }

        } catch (Exception e) {
            // Rollback on error
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackEx) {
                    System.err.println("ERROR: Failed to rollback transaction for PMID " + pmid);
                }
            }
            throw e;
        } finally {
            // Close resources
            if (s != null) {
                try { s.close(); } catch (SQLException e) { /* ignore */ }
            }
            if (conn != null) {
                try { conn.close(); } catch (SQLException e) { /* ignore */ }
            }
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Using the Low-Level REST Client to mimic your original Python logic.
     */
    public String getAcc(String term, String ont) throws IOException {

        // Remap ontology strings (like in your Python code)
        if ("MA".equals(ont)) {
            ont = "UBERON";
        }
        if ("DO".equals(ont)) {
            ont = "RDO";
        }

        // Create the low-level REST client
        try (RestClient restClient = RestClient.builder(
                new HttpHost(ConfigManager.getElasticsearchHost(),
                           ConfigManager.getElasticsearchPort(),
                           ConfigManager.getElasticsearchProtocol())
        ).build()) {

            String searchTerm = term.trim().toLowerCase();
            String ontology = ont.toUpperCase();

            // Build the request body (query) as JSON
            String requestBody = """
                {
                  "size": 10000,
                  "query": {
                    "bool": {
                      "must": {
                        "dis_max": {
                          "queries": [
                            { "term": { "term.symbol": "%s" } }
                          ],
                          "tie_breaker": 0.7
                        }
                      },
                      "filter": {
                        "term": { "subcat.keyword": "%s" }
                      }
                    }
                  }
                }
                """.formatted(searchTerm, ontology);

            // Create a Request object
            // Use the POST method on [index]/_search
            Request request = new Request("POST", "/" + ConfigManager.getElasticsearchIndex() + "/_search");
            request.setJsonEntity(requestBody);

            // Perform the request
            Response response = restClient.performRequest(request);

            // Parse the response JSON
            String responseBody = EntityUtils.toString(response.getEntity());
            JsonNode rootNode = MAPPER.readTree(responseBody);

            // For debugging, you can print the entire JSON:
            // System.out.println("Raw Response:\n" + responseBody);

            // The 'hits' section is typically at: hits.hits[]
            // The total number of hits is at: hits.total.value

            JsonNode hitsNode = rootNode.path("hits");
            //long totalHits = hitsNode.path("total").path("value").asLong();
            //System.out.println("Got " + totalHits + " Hits:");

            JsonNode hitsArray = hitsNode.path("hits");
            if (hitsArray.isArray()) {
                for (JsonNode hitNode : hitsArray) {
                    JsonNode sourceNode = hitNode.path("_source");
                    // Adjust these field names to match your actual data structure
                    JsonNode termVal = sourceNode.path("term");
                    JsonNode termAcc = sourceNode.path("term_acc");
                    JsonNode typeVal = sourceNode.path("type");

                    return termAcc.asText();
                    // Print them out
                    //System.out.println("Term Found: " + termVal.asText() + " accID: " + termAcc.asText() + " typeVal: " + typeVal.asText() + "\n\n");
                }
            }
        }
        return "DOID:4";
    }




    public String listToString(List<String> lst) {
        return String.join("||", lst);
    }

    public static String getDate() throws Exception{
       String retVal = "";
        String query = "SELECT p_date " +
                "FROM solr_docs " +
                "where last_update_date < Date '" + PostgressAILoader.lud + "' or last_update_date is null " +
                "ORDER BY p_date DESC " +
                "LIMIT 1;";
         Connection conn;

            conn = DataSourceFactory.getInstance().getPostgressDataSource().getConnection();

            ResultSet rs = conn.createStatement().executeQuery(query);

            while (rs.next()) {
                retVal = rs.getString("p_date");
            }
            conn.close();

        return retVal;
    }


    public static void main(String[] args) throws Exception {

        aiModel = args.length > 0 ? args[0] : ConfigManager.getDefaultAiModel();
        threads = args.length > 1 ? Integer.parseInt(args[1]) : ConfigManager.getDefaultThreads();
        pubYear = args.length > 2 ? args[2] : ConfigManager.getDefaultYear();

        // Optional PMID filter - treat empty string as null
        if (args.length > 3 && args[3] != null && !args[3].trim().isEmpty()) {
            pmidFilter = args[3];
        }

        // Optional: Override last update date filter (args[4])
        if (args.length > 4 && pmidFilter == null && args[4] != null && !args[4].trim().isEmpty()) {
            lud = args[4];
        }

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy.MM.dd 'at' HH:mm:ss");
        System.out.println("\n========================================");
        System.out.println("OntomateAIPipeline Starting");
        System.out.println("========================================");
        System.out.println("AI Model: " + aiModel);
        System.out.println("Threads: " + threads);
        if (pmidFilter != null) {
            System.out.println("Processing Single PMID: " + pmidFilter);
        } else {
            System.out.println("Publication Year: " + pubYear);
            System.out.println("Last Update Date Filter: " + lud);
        }
        System.out.println("Started at: " + sdf.format(new Date()));
        System.out.println("========================================\n");

        // Create a fixed thread pool with bounded queue
        ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(threads);

        try {
            int totalSubmitted = 0;

            // Single PMID mode: process just one record
            if (pmidFilter != null) {
                try (Connection conn = DataSourceFactory.getInstance().getPostgressDataSource().getConnection();
                     Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(
                             "SELECT pmid, abstract FROM solr_docs WHERE pmid = '" + pmidFilter + "'")) {

                    if (rs.next()) {
                        String pmid = rs.getString("pmid");
                        String abstractText = rs.getString("abstract");
                        executor.submit(new PostgressAILoader(abstractText, pmid));
                        totalSubmitted++;
                        System.out.println("Submitted PMID: " + pmid);
                    } else {
                        System.err.println("ERROR: PMID " + pmidFilter + " not found in database");
                    }
                } catch (SQLException e) {
                    System.err.println("ERROR loading PMID " + pmidFilter + ": " + e.getMessage());
                    e.printStackTrace();
                }
            }
            // Batch mode: process by year
            else {
                int batchSize = 500;  // Larger batches for better throughput
                int maxQueueSize = 2000;  // Maximum tasks in queue before waiting
                boolean hasMoreRecords = true;
                int consecutiveEmptyBatches = 0;
                int maxEmptyRetries = 10;

                while (hasMoreRecords) {
                    // BACKPRESSURE: Wait if queue is too large
                    while (executor.getQueue().size() > maxQueueSize) {
                        int queueSize = executor.getQueue().size();
                        int activeThreads = executor.getActiveCount();
                        System.out.println("Queue full (" + queueSize + " tasks). Waiting for workers... (Active: " + activeThreads + ", Success: " + totalSuccess.get() + ", Failures: " + totalFailures.get() + ")");
                        try {
                            Thread.sleep(5000);  // Wait 5 seconds for queue to drain
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            hasMoreRecords = false;
                            break;
                        }
                    }

                    if (!hasMoreRecords) break;

                    List<AbstractRecord> batch = new ArrayList<>();

                    // Load batch of records - trust the database as source of truth
                    try (Connection conn = DataSourceFactory.getInstance().getPostgressDataSource().getConnection();
                         Statement stmt = conn.createStatement();
                         ResultSet rs = stmt.executeQuery(
                                 "SELECT pmid, abstract FROM solr_docs " +
                                 "WHERE (last_update_date < DATE '" + lud + "' OR last_update_date IS NULL) " +
                                 "AND p_year = " + pubYear + " " +
                                 "AND abstract IS NOT NULL " +
                                 "ORDER BY pmid " +
                                 "FETCH FIRST " + batchSize + " ROWS ONLY")) {

                        while (rs.next()) {
                            batch.add(new AbstractRecord(rs.getString("pmid"), rs.getString("abstract")));
                        }
                    } catch (SQLException e) {
                        System.err.println("ERROR loading batch: " + e.getMessage());
                        e.printStackTrace();
                        break;
                    }

                    if (batch.isEmpty()) {
                        consecutiveEmptyBatches++;
                        int queueSize = executor.getQueue().size();
                        System.out.println("No new records found. Waiting 10 seconds for workers to finish... (Queue: " + queueSize + ", attempt " + consecutiveEmptyBatches + "/" + maxEmptyRetries + ")");

                        if (consecutiveEmptyBatches >= maxEmptyRetries) {
                            System.out.println("No new records after " + maxEmptyRetries + " attempts. Processing complete.");
                            hasMoreRecords = false;
                            break;
                        }

                        try {
                            Thread.sleep(10000);  // Wait 10 seconds for workers to update database
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        continue;
                    }

                    // Reset empty batch counter when we find records
                    consecutiveEmptyBatches = 0;

                    // Submit batch to executor
                    for (AbstractRecord record : batch) {
                        executor.submit(new PostgressAILoader(record.abstractText, record.pmid));
                        totalSubmitted++;
                    }

                    int queueSize = executor.getQueue().size();
                    System.out.println("Submitted batch of " + batch.size() + " records (Total: " + totalSubmitted + ", Queue: " + queueSize + ", Success: " + totalSuccess.get() + ", Failures: " + totalFailures.get() + ")");

                    // If batch is smaller than batchSize, we're approaching the end
                    if (batch.size() < batchSize) {
                        System.out.println("Small batch received (" + batch.size() + "), may be near completion.");
                    }
                }
            }

            System.out.println("\nAll " + totalSubmitted + " tasks submitted. Waiting for completion...");

        } finally {
            // Shutdown executor and wait for tasks to complete
            executor.shutdown();
            try {
                if (!executor.awaitTermination(120, TimeUnit.MINUTES)) {
                    System.err.println("WARNING: Tasks did not complete within timeout. Forcing shutdown...");
                    executor.shutdownNow();
                    if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                        System.err.println("ERROR: Executor did not terminate");
                    }
                }
            } catch (InterruptedException e) {
                System.err.println("Main thread interrupted. Forcing shutdown...");
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }

            // Print final statistics
            System.out.println("\n========================================");
            System.out.println("Processing Complete");
            System.out.println("========================================");
            System.out.println("Total Processed: " + totalProcessed.get());
            System.out.println("Successful: " + totalSuccess.get());
            System.out.println("Failures: " + totalFailures.get());
            System.out.println("Finished at: " + sdf.format(new Date()));
            System.out.println("========================================\n");
        }
    }

    /**
     * Simple record class to hold abstract data
     */
    private static class AbstractRecord {
        String pmid;
        String abstractText;

        AbstractRecord(String pmid, String abstractText) {
            this.pmid = pmid;
            this.abstractText = abstractText;
        }
    }

    /**
     * Data structure to hold extraction results for both genes and diseases
     */
    private static class ExtractionResult {
        HashMap<String, String> geneInfo;
        HashMap<String, String> diseaseInfo;

        ExtractionResult(HashMap<String, String> geneInfo, HashMap<String, String> diseaseInfo) {
            this.geneInfo = geneInfo;
            this.diseaseInfo = diseaseInfo;
        }
    }


    public static List<String> listFiles(String dir) {
        return Stream.of(new File(dir).listFiles())
                .filter(file -> !file.isDirectory())
                .map(File::getName)
                .collect(Collectors.toList());
    }





    private static ArrayList<String> toList(String val) {
        ArrayList<String> al = new ArrayList<String>();
        al.add(val);
        return al;
    }

    private static ArrayList<String> toListOfSizeOne(List<String> vals) {
        ArrayList<String> al = new ArrayList<String>();

        String retVal = "";
        boolean first = true;
        for (String val: vals) {
            if (first) {
                retVal=val;
                first=false;
            }else {
                retVal += "," + val;
            }
        }

        al.add(retVal);
        return al;
    }


    private static ArrayList<Integer> toList(int val) {
        ArrayList<Integer> al = new ArrayList<Integer>();
        al.add(val);
        return al;
    }



}
