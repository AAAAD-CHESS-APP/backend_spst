package com.aaaadchess.backend.controllers;

import com.aaaadchess.backend.services.StockfishService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

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


}

