package com.detectionengine.app.controller;

import com.detectionengine.app.model.DetectionResult;
import com.detectionengine.app.service.DetectionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/detect")
public class DetectController {

    private final DetectionService service;

    public DetectController(DetectionService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<DetectionResult> detect(@RequestParam(required = false) String path) {
        if (path == null || path.trim().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        DetectionResult result = service.detect(path);
        return ResponseEntity.ok(result);
    }
}
