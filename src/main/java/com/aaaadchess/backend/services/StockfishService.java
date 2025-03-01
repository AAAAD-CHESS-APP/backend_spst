package com.aaaadchess.backend.services;

import jakarta.annotation.PreDestroy;
import lombok.Getter;
import org.springframework.stereotype.Service;
import java.io.*;
//import java.util.concurrent.TimeUnit;

@Service
public class StockfishService {
    private static final String STOCKFISH_PATH = System.getProperty("user.home") + "/.local/bin/stockfish";
    private Process stockfishProcess;
    private BufferedWriter writer;
    private BufferedReader reader;
    @Getter
    private String engineVersion = "Unknown";

    public StockfishService() {
        initializeStockfish();
    }

    private void initializeStockfish() {
        try {
            ProcessBuilder pb = new ProcessBuilder(STOCKFISH_PATH);
            stockfishProcess = pb.start();
            writer = new BufferedWriter(new OutputStreamWriter(stockfishProcess.getOutputStream()));
            reader = new BufferedReader(new InputStreamReader(stockfishProcess.getInputStream()));

            // Initialize and get engine info
            sendCommand("uci");

            // Read output until we get "uciok"
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("Stockfish")) {
                    engineVersion = line;
                }
                if (line.equals("uciok")) {
                    break;
                }
            }

            sendCommand("isready");

            // Wait for "readyok"
            while ((line = reader.readLine()) != null) {
                if (line.equals("readyok")) {
                    break;
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to start Stockfish: " + e.getMessage());
        }
    }

    private void sendCommand(String command) throws IOException {
        writer.write(command + "\n");
        writer.flush();
    }

    public String evaluatePosition(String fen, int depth) {
        try {
            // Set up the position
            sendCommand("position fen " + fen);
            sendCommand("go depth " + depth);

            // Read until we get the best move
            String line;
            String bestMove = null;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("bestmove")) {
                    bestMove = line.split(" ")[1];
                    break;
                }
            }

            return bestMove != null ? bestMove : "no move";
        } catch (IOException e) {
            // If there's an error, try to reinitialize
            try {
                cleanupResources();
                initializeStockfish();
            } catch (Exception reinitError) {
                System.err.println("Failed to reinitialize Stockfish: " + reinitError.getMessage());
            }
            throw new RuntimeException("Error communicating with Stockfish: " + e.getMessage());
        }
    }

    // New methods for status checking
    public boolean checkEngineStatus() {
        try {
            if (stockfishProcess == null || !stockfishProcess.isAlive()) {
                return false;
            }

            sendCommand("isready");
            String line;
            long startTime = System.currentTimeMillis();
            while ((line = reader.readLine()) != null) {
                if (line.equals("readyok")) {
                    return true;
                }
                // Timeout after 2 seconds
                if (System.currentTimeMillis() - startTime > 2000) {
                    return false;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    public String getEnginePath() {
        return STOCKFISH_PATH;
    }

    @PreDestroy
    public void cleanupResources() {
        try {
            if (writer != null) {
                writer.close();
            }
            if (reader != null) {
                reader.close();
            }
            if (stockfishProcess != null) {
                stockfishProcess.destroy();
            }
        } catch (IOException e) {
            System.err.println("Error cleaning up Stockfish resources: " + e.getMessage());
        }
    }
}