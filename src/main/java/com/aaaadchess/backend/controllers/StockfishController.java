package com.aaaadchess.backend.controllers;

import com.aaaadchess.backend.dtos.GameAnalysisRequest;
import com.aaaadchess.backend.services.StockfishService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/stockfish")
@CrossOrigin(origins = "*")
public class StockfishController {

    private final StockfishService stockfishService;

    public StockfishController(StockfishService stockfishService) {
        this.stockfishService = stockfishService;
    }

    @GetMapping("/evaluate")
    public ResponseEntity<String> evaluateFen(@RequestParam String fen, @RequestParam int depth) {
        try {
            String move = stockfishService.evaluatePosition(fen, depth);
            if ("no move".equals(move)) {
                return ResponseEntity.badRequest().body("No valid move found for position");
            }
            return ResponseEntity.ok(move);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Error: " + e.getMessage());
        }
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStockfishStatus() {
        Map<String, Object> status = new HashMap<>();
        try {
            boolean isRunning = stockfishService.checkEngineStatus();
            String enginePath = stockfishService.getEnginePath();
            String engineVersion = stockfishService.getEngineVersion();

            status.put("isRunning", isRunning);
            status.put("enginePath", enginePath);
            status.put("engineVersion", engineVersion);
            status.put("timestamp", java.time.LocalDateTime.now().toString());

            return ResponseEntity.ok(status);
        } catch (Exception e) {
            status.put("isRunning", false);
            status.put("error", e.getMessage());
            status.put("timestamp", java.time.LocalDateTime.now().toString());
            return ResponseEntity.ok(status);
        }
    }

    @GetMapping("/analyze")
    public ResponseEntity<?> analyzePosition(@RequestParam String fen, @RequestParam(defaultValue = "15") int depth) {
        try {
            Map<String, Object> analysis = stockfishService.analyzePosition(fen, depth);
            return ResponseEntity.ok(analysis);
        } catch (Exception e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Analysis failed: " + e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    @PostMapping("/analyze-game")
    public ResponseEntity<?> analyzeGame(@RequestBody GameAnalysisRequest request) {
        try {
            // Validate request
            if (request.getFens() == null || request.getFens().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "No positions provided"));
            }

            // Use default depth if not specified
            int depth = request.getDepth() > 0 ? request.getDepth() : 15;

            // Asynchronously analyze the game (to avoid blocking for long analyses)
            CompletableFuture.runAsync(() -> {
                stockfishService.analyzeGame(request.getFens(), depth);
            });

            // Return immediately to avoid timeout
            return ResponseEntity.ok(Map.of(
                    "message", "Game analysis started",
                    "positions", request.getFens().size(),
                    "depth", depth
            ));
        } catch (Exception e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Game analysis failed: " + e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    @PostMapping("/analyze-position")
    public ResponseEntity<?> analyzePositionDetailed(@RequestBody Map<String, Object> request) {
        try {
            String fen = (String) request.get("fen");
            Integer depth = request.containsKey("depth") ? (Integer) request.get("depth") : 15;

            if (fen == null || fen.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "No position provided"));
            }

            Map<String, Object> analysis = stockfishService.analyzePosition(fen, depth);
            return ResponseEntity.ok(analysis);
        } catch (Exception e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Analysis failed: " + e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    @PostMapping("/find-tactics")
    public ResponseEntity<?> findTacticalOpportunities(@RequestBody GameAnalysisRequest request) {
        try {
            // Validate request
            if (request.getFens() == null || request.getFens().isEmpty() ||
                    request.getMoves() == null || request.getMoves().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Positions or moves not provided"));
            }

            if (request.getFens().size() != request.getMoves().size() + 1) {
                return ResponseEntity.badRequest().body(Map.of("error",
                        "Invalid data: should have one more position than moves"));
            }

            // Use default depth if not specified
            int depth = request.getDepth() > 0 ? request.getDepth() : 15;

            Map<String, Object> tactics = stockfishService.findTacticalOpportunities(
                    request.getFens(), request.getMoves(), depth);

            return ResponseEntity.ok(tactics);
        } catch (Exception e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Tactical analysis failed: " + e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }
}