package edu.mcw.rgd.ontomate;


import edu.mcw.rgd.process.solr.SolrIndexer;


public class SolrUpdater extends Thread{



    public static void main(String[] args) throws Exception {

        SolrIndexer si = new SolrIndexer();
        si.batchIndexer(2025, "OntoMate");
    }


}
