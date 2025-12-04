package com.detectionengine.app.model;

import java.util.*;

public class DetectionResult {

    private final Map<String, Map<String, String>> detections = new LinkedHashMap<>();

    public DetectionResult() {
        detections.put("languages", new LinkedHashMap<>());
        detections.put("frameworks", new LinkedHashMap<>());
        detections.put("runtimes", new LinkedHashMap<>());
        detections.put("cloud_sdks", new LinkedHashMap<>());
        detections.put("databases", new LinkedHashMap<>());
        detections.put("containers", new LinkedHashMap<>());
        detections.put("infrastructure_as_code", new LinkedHashMap<>());
    }

    public void add(String category, String technology, String version) {
        detections.computeIfAbsent(category, k -> new LinkedHashMap<>())
                  .put(technology, version != null ? version : "NA");
    }

    public Map<String, Map<String, String>> getDetections() {
        return detections;
    }
}
