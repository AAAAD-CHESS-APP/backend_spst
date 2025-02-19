package com.aaaadchess.backend.services;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import java.io.*;

@Service
public class StockfishService {
    private static final String STOCKFISH_PATH = System.getProperty("user.home") + "/.local/bin/stockfish";
    private Process stockfishProcess;
    private BufferedWriter writer;
    private BufferedReader reader;

    public StockfishService() {
        initializeStockfish();
    }

    private void initializeStockfish() {
        try {
            ProcessBuilder pb = new ProcessBuilder(STOCKFISH_PATH);
            stockfishProcess = pb.start();
            writer = new BufferedWriter(new OutputStreamWriter(stockfishProcess.getOutputStream()));
            reader = new BufferedReader(new InputStreamReader(stockfishProcess.getInputStream()));

            // Simple initialization
            sendCommand("uci");
            sendCommand("isready");

            // Clear initial output
            while (reader.ready()) {
                reader.readLine();
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