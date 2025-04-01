package edu.mcw.rgd.ontomate;

import dev.langchain4j.model.ollama.OllamaChatModel;
import edu.mcw.rgd.dao.DataSourceFactory;
import edu.mcw.rgd.dao.impl.GeneDAO;
import edu.mcw.rgd.process.Stamp;
import org.apache.hadoop.fs.Stat;

import java.io.FileWriter;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;

public class AICompare {

    public static void main(String[] args) throws Exception {
        GeneDAO gdao = new GeneDAO();

        FileWriter fw = new FileWriter("/Users/jdepons/out.txt");

        fw.write("PMID\tref_rgd_id\tRGD Curated\tOntomate\tLlama3.3:70b\tLlama3.1:8b\trgdwizard7b\trgdqwen7b\trgdgemma9b\tphi4:17b\n");

        Connection conn = gdao.getConnection();
        Connection conn3 = DataSourceFactory.getInstance().getPostgressDataSource().getConnection();


        String query = "select distinct *  from (" +
                "SELECT fa.ref_rgd_id, xdb.acc_id " +
                "FROM full_annot fa , references r, rgd_acc_xdb xdb " +
                "WHERE fa.ref_rgd_id = r.rgd_id and fa.ref_rgd_id=xdb.rgd_id and xdb.xdb_key=2 " +
                "and fa.rgd_object_key = 1 " +
                "ORDER BY fa.created_date DESC " +
                "FETCH FIRST 600 ROWS ONLY " +
                ")";

        Statement stmt = conn.createStatement();

        ResultSet rs = stmt.executeQuery(query);

        while (rs.next()) {
            String refRgdId = rs.getString("ref_rgd_id");
            String pmid = rs.getString("acc_id");

            System.out.println("processing pmid " + pmid);

            fw.write(pmid + "\t" + refRgdId + "\t");

            //get the annotated rgd_ids
            String query2 = "select object_symbol from full_annot where ref_rgd_id='" + refRgdId + "' and rgd_object_key=1";

           // Connection conn2 = gdao.getConnection();
            Statement stmt2 = conn.createStatement();

            ResultSet rs2 = stmt2.executeQuery(query2);
            String annotated = "";

            HashMap<String,String> seen = new HashMap<String,String>();

            while (rs2.next()) {
                String val = rs2.getString("object_symbol");

                if (seen.isEmpty()) {
                    annotated+= val;
                }else {
                    if (!seen.containsKey(val)) {
                        annotated += "|" + val;
                    }
                }
                seen.put(val,val);
            }

            fw.write(annotated + "\t");

            rs2.close();


            String query3 = "select * from solr_docs where pmid='" + pmid + "'";

            Statement stmt3 = conn3.createStatement();

            ResultSet rs3 = stmt3.executeQuery(query3);

            String abstractText = "";
            String ontomateGenes = "";
            while (rs3.next()) {
                ontomateGenes = rs3.getString("gene");
                if (ontomateGenes == null) ontomateGenes="";
                if (abstractText == null) abstractText="";
                abstractText = rs3.getString("title") + " - " + rs3.getString("abstract");
            }
            fw.write(ontomateGenes.trim() + "\t");

            Stamp.it("running llama3.3 70");
            OllamaChatModel model = null;
            String prompt = "Extract the <symbol> for any gene discussed in the following abstract. <abstract>" + abstractText + "</abstract> respond with a pipe delimited list of <symbol> and no other output";
            String genes = null;

            model = OllamaChatModel.builder()
                    //.baseUrl("http://localhost:11434") // Ollama's default port
                    .baseUrl("http://grudge.rgd.mcw.edu:11434") // Ollama's default port
                    //.modelName("curatorModel") // Replace with your downloaded model
                    .modelName("rgdLLama70") // Replace with your downloaded model
                    //.modelName("rgddeepseek70") // Replace with your downloaded model
                    //.modelName("rgddeepseek32") // Replace with your downloaded model
                    //.modelName("rgdllama3") // Replace with your downloaded model
                    .build();

            genes = model.generate(prompt);
            fw.write(genes.trim() + "\t");

            Stamp.it("running llama3.1 8b");
            model = OllamaChatModel.builder()
                    //.baseUrl("http://localhost:11434") // Ollama's default port
                    .baseUrl("http://grudge.rgd.mcw.edu:11434") // Ollama's default port
                    //.modelName("curatorModel") // Replace with your downloaded model
                    .modelName("rgdllama3.18b") // Replace with your downloaded model
                    //.modelName("rgddeepseek70") // Replace with your downloaded model
                    //.modelName("rgddeepseek32") // Replace with your downloaded model
                    //.modelName("rgdllama3") // Replace with your downloaded model
                    .build();

            genes = model.generate(prompt);
            fw.write(genes.trim() + "\t");

            Stamp.it("rgdwizard7b");
            model = OllamaChatModel.builder()
                    //.baseUrl("http://localhost:11434") // Ollama's default port
                    .baseUrl("http://grudge.rgd.mcw.edu:11434") // Ollama's default port
                    //.modelName("curatorModel") // Replace with your downloaded model
                    .modelName("rgdwizard7b") // Replace with your downloaded model
                    //.modelName("rgddeepseek70") // Replace with your downloaded model
                    //.modelName("rgddeepseek32") // Replace with your downloaded model
                    //.modelName("rgdllama3") // Replace with your downloaded model
                    .build();

            genes = model.generate(prompt);

            genes = genes.replaceAll("(?s)<think>.*?</think>", "");
            fw.write(genes.trim() + "\t");


            Stamp.it("running rgdqwen7b");
            model = OllamaChatModel.builder()
                    //.baseUrl("http://localhost:11434") // Ollama's default port
                    .baseUrl("http://grudge.rgd.mcw.edu:11434") // Ollama's default port
                    //.modelName("curatorModel") // Replace with your downloaded model
                    .modelName("rgdqwen7b") // Replace with your downloaded model
                    //.modelName("rgddeepseek70") // Replace with your downloaded model
                    //.modelName("rgddeepseek32") // Replace with your downloaded model
                    //.modelName("rgdllama3") // Replace with your downloaded model
                    .build();

            genes = model.generate(prompt);

            genes = genes.replaceAll("(?s)<think>.*?</think>", "");
            fw.write(genes.trim() + "\t");

            Stamp.it("running rgdgemma9b");
            model = OllamaChatModel.builder()
                    //.baseUrl("http://localhost:11434") // Ollama's default port
                    .baseUrl("http://grudge.rgd.mcw.edu:11434") // Ollama's default port
                    //.modelName("curatorModel") // Replace with your downloaded model
                    .modelName("rgdgemma9b") // Replace with your downloaded model
                    //.modelName("rgddeepseek70") // Replace with your downloaded model
                    //.modelName("rgddeepseek32") // Replace with your downloaded model
                    //.modelName("rgdllama3") // Replace with your downloaded model
                    .build();

            genes = model.generate(prompt);

            genes = genes.replaceAll("(?s)<think>.*?</think>", "");
            fw.write(genes.trim() + "\t");



            Stamp.it("done");


            //fw.write("\"" + abstractText.replaceAll("\\n","") + "\"");


            fw.write("\n");
            fw.flush();

        }

        rs.close();
        conn.close();

        conn3.close();






        System.out.println("hello");
    }
}
