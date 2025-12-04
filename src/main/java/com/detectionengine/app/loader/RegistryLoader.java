package com.detectionengine.app.loader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;

@Component
public class RegistryLoader {

    private JsonNode registry;

    @PostConstruct
    public void load() {
        try {
            InputStream in = getClass().getClassLoader().getResourceAsStream("registry.json");
            ObjectMapper mapper = new ObjectMapper();
            registry = mapper.readTree(in);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load registry.json", e);
        }
    }

    public JsonNode get() {
        return registry;
    }
}
