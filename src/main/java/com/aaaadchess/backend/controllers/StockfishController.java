package com.aaaadchess.backend.controllers;

import com.aaaadchess.backend.services.StockfishService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
}

