package edu.mcw.rgd.ontomate;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Configuration manager for OntomateAIPipeline
 * Reads from properties file or provides default values
 */
public class ConfigManager {

    private static Properties properties = new Properties();
    private static boolean loaded = false;

    static {
        loadProperties();
    }

    private static void loadProperties() {
        if (loaded) return;

        try {
            // Load from classpath first (defaults)
            try (InputStream input = ConfigManager.class.getClassLoader()
                    .getResourceAsStream("ontomate.properties")) {
                if (input != null) {
                    properties.load(input);
                    System.out.println("Loaded configuration from classpath");
                }
            }

            // Then override with system property location (custom config takes precedence)
            String configPath = System.getProperty("ontomate.config");
            if (configPath != null) {
                try (InputStream input = new FileInputStream(configPath)) {
                    properties.load(input);
                    System.out.println("Loaded configuration from: " + configPath);
                }
            }
        } catch (IOException e) {
            System.out.println("Could not load properties file, using defaults: " + e.getMessage());
        }
        loaded = true;
    }

    private static String getProperty(String key, String defaultValue) {
        return properties.getProperty(key, defaultValue);
    }

    private static int getIntProperty(String key, int defaultValue) {
        String value = properties.getProperty(key);
        if (value != null) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                System.out.println("Invalid integer value for " + key + ": " + value);
            }
        }
        return defaultValue;
    }

    // Last update date configuration
    public static String getLastUpdateDate() {
        return getProperty("last.update.date", "2025-04-15");
    }

    // Ollama configuration
    public static String getOllamaBaseUrl() {
        return getProperty("ollama.base.url", "http://grudge.rgd.mcw.edu:11434");
    }

    // Elasticsearch configuration
    public static String getElasticsearchHost() {
        return getProperty("elasticsearch.host", "travis.rgd.mcw.edu");
    }

    public static int getElasticsearchPort() {
        return getIntProperty("elasticsearch.port", 9200);
    }

    public static String getElasticsearchProtocol() {
        return getProperty("elasticsearch.protocol", "http");
    }

    public static String getElasticsearchIndex() {
        return getProperty("elasticsearch.index", "aimappings_index_dev");
    }

    // Default runtime configuration
    public static String getDefaultAiModel() {
        return getProperty("default.ai.model", "rgdLLama70");
    }

    public static int getDefaultThreads() {
        return getIntProperty("default.threads", 3);
    }

    public static String getDefaultYear() {
        return getProperty("default.year", "2025");
    }
}
