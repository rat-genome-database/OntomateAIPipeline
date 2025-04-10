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

public class PostgressAILoader extends Thread{

    public static AtomicInteger totalProcessed=new AtomicInteger(0);
    String abstractText;
    String pmid;
    public static String lud = "2025-02-19";

    public PostgressAILoader(String abstractText,String pmid) {
        this.abstractText=abstractText;
        this.pmid=pmid;
    }

    public void run() {

        try {
            System.out.println("top of run");
            if (abstractText == null || abstractText.equals("")) {
                this.updateLastUpdate(pmid);
            }

            ArrayList<String> it = new ArrayList<String>();

                totalProcessed.getAndIncrement();

                //if ((totalProcessed.get() % 100) == 0) {
                    System.out.println(totalProcessed.get());
                //}

            System.out.println("getting model");
            OllamaChatModel model = OllamaChatModel.builder()
                        //.baseUrl("http://localhost:11434") // Ollama's default port
                        .baseUrl("http://grudge.rgd.mcw.edu:11434") // Ollama's default port
                        //.modelName("curatorModel") // Replace with your downloaded model
                        //.modelName("rgdLLama70") // Replace with your downloaded model
                        //.modelName("rgdwizard7b") // Replace with your downloaded model
                        //.modelName("rgddeepseek70") // Replace with your downloaded model
                        //.modelName("rgddeepseek32") // Replace with your downloaded model
                        .modelName("rgdllama3.18b") // Replace with your downloaded model
                        .build();

                String prompt = "Extract the <symbol> for any gene discussed in the following abstract. The maximum number of symbols returned should be 100.  If you fine more than 100, please return the first 100 found.  <abstract>" + abstractText + "</abstract> respond with a pipe delimited list of <symbol> and no other output";
               // System.out.println("prompt " + prompt);

                //System.out.println(prompt);



                System.out.println("about to run");
            String genes = model.generate(prompt);
            System.out.println("after model run");

                HashMap<String,String> hm = this.getPositionInfo(genes,abstractText);
            System.out.println("updating");
                this.update(pmid,hm);
            System.out.println("updated");

        }catch(Exception e) {
            e.printStackTrace();
        }
    }

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



            if(isEmpty(currentValue)) {
                continue;
            }

            count=0;
            int index = abstractText.indexOf(currentValue);


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
                    System.out.println("count was 50 " + posString);
                    System.out.println(abstractText);
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
        HashMap<String, String> hm = new HashMap<String,String>();
            hm.put("positions","");
            hm.put("counts","");
            hm.put("terms","");

        this.update(pmid,hm);
    }
    public void update(String pmid,HashMap<String,String> hm) throws Exception {

        Connection conn = DataSourceFactory.getInstance().getPostgressDataSource().getConnection();

        String query = "update SOLR_DOCS set gene_count=?, gene=?, gene_pos=?, last_update_date=? where pmid=?";

        PreparedStatement s = conn.prepareStatement(query);

        s.setString(1,hm.get("counts"));
        s.setString(2,hm.get("terms"));
        s.setString(3,hm.get("positions"));
        s.setDate(4,new java.sql.Date(new Date().getTime()));
        s.setString(5,pmid);

        s.executeUpdate();

        conn.close();
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
                new HttpHost("travis.rgd.mcw.edu", 9200, "http")
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
            Request request = new Request("POST", "/aimappings_index_dev/_search");
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


        SimpleDateFormat sdf = new SimpleDateFormat("yyyy.MM.dd 'at' HH:mm:ss");

        String pubDate = "";

//        while (true) {

            // Create a fixed thread pool with 3 threads
            ExecutorService executor = Executors.newFixedThreadPool(1);

            pubDate = getDate();
            System.out.println("========== Running for " + pubDate);

            // Use try-with-resources to ensure the Connection, Statement, and ResultSet are closed automatically
            try {

                Connection conn = DataSourceFactory.getInstance().getPostgressDataSource().getConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT * FROM solr_docs WHERE last_update_date < DATE '" + PostgressAILoader.lud + "' and (p_date = DATE '" + pubDate + "') FETCH FIRST 15000 ROWS ONLY");

                  //ResultSet rs = stmt.executeQuery(
                  //        "SELECT * FROM solr_docs WHERE pmid='38309493'")) {

                while (rs.next()) {
                    String pmid = rs.getString("pmid");
                    String abstractText = rs.getString("abstract");

                    // Create your PostgressAILoader object
                    PostgressAILoader pmb = new PostgressAILoader(abstractText, pmid);

                    // Submit the task to the ExecutorService instead of calling pmb.run() directly
                    executor.submit(() -> {
                        System.out.println("submitting job");
                        pmb.run();
                    });
                }

            } catch (SQLException e) {
                e.printStackTrace();
            } finally {
                // Tell the executor to stop accepting new tasks
                executor.shutdown();
                // Optionally, wait for all tasks to finish (or time out)
                try {
                    if (!executor.awaitTermination(60, TimeUnit.MINUTES)) {
                        executor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    executor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }

            System.out.println("All tasks submitted. Main thread exiting.");
        }
  //  }


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
