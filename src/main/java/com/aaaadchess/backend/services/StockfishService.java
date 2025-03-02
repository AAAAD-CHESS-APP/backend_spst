package com.aaaadchess.backend.services;

import jakarta.annotation.PreDestroy;
import lombok.Getter;
import org.springframework.stereotype.Service;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

@Service
public class StockfishService {
    private static final String STOCKFISH_PATH = System.getProperty("user.home") + "/.local/bin/stockfish";
    private Process stockfishProcess;
    private BufferedWriter writer;
    private BufferedReader reader;
    @Getter
    private String engineVersion = "Unknown";

    // Cache to store analysis results to reduce redundant calls
    private final Map<String, CachedAnalysis> analysisCache = new ConcurrentHashMap<>();

    // Maximum size of cache
    private static final int MAX_CACHE_SIZE = 100;

    // Cache TTL in milliseconds (10 minutes)
    private static final long CACHE_TTL = 10 * 60 * 1000;

    public StockfishService() {
        initializeStockfish();
        // Start a periodic task to clean expired cache entries
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(this::cleanCache, 5, 5, TimeUnit.MINUTES);
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

    // New method to analyze position and get evaluation score
    public Map<String, Object> analyzePosition(String fen, int depth) {
        // Check if we have a cached result
        String cacheKey = fen + "-" + depth;
        CachedAnalysis cachedResult = analysisCache.get(cacheKey);
        if (cachedResult != null && !cachedResult.isExpired()) {
            return cachedResult.getData();
        }

        Map<String, Object> result = new HashMap<>();
        try {
            // Set up the position
            sendCommand("position fen " + fen);
            sendCommand("go depth " + depth);

            // Read until we get the best move
            String line;
            String bestMove = null;
            double score = 0.0;
            List<Map<String, Object>> lines = new ArrayList<>();

            while ((line = reader.readLine()) != null) {
                if (line.startsWith("bestmove")) {
                    bestMove = line.split(" ")[1];
                    break;
                } else if (line.startsWith("info") && line.contains("score")) {
                    // Extract evaluation score and principal variation
                    Map<String, Object> info = parseInfoLine(line);
                    if (info != null) {
                        lines.add(info);
                        // Keep the latest score
                        if (info.containsKey("score")) {
                            score = (double) info.get("score");
                        }
                    }
                }
            }

            result.put("bestMove", bestMove != null ? bestMove : "no move");
            result.put("score", score);
            result.put("lines", lines);
            result.put("fen", fen);
            result.put("depth", depth);

            // Cache the result
            if (analysisCache.size() < MAX_CACHE_SIZE) {
                analysisCache.put(cacheKey, new CachedAnalysis(result));
            }

            return result;
        } catch (IOException e) {
            // If there's an error, try to reinitialize
            try {
                cleanupResources();
                initializeStockfish();
            } catch (Exception reinitError) {
                System.err.println("Failed to reinitialize Stockfish: " + reinitError.getMessage());
            }
            throw new RuntimeException("Error analyzing position with Stockfish: " + e.getMessage());
        }
    }

    // Parse the info line from Stockfish
    private Map<String, Object> parseInfoLine(String line) {
        Map<String, Object> info = new HashMap<>();
        try {
            if (line.contains("score cp ")) {
                // Extract centipawn score
                String[] parts = line.split("score cp ");
                String scoreStr = parts[1].split(" ")[0];
                double score = Double.parseDouble(scoreStr) / 100.0;  // Convert centipawns to pawns
                info.put("score", score);
                info.put("unit", "pawns");
            } else if (line.contains("score mate ")) {
                // Extract mate score
                String[] parts = line.split("score mate ");
                String scoreStr = parts[1].split(" ")[0];
                int moves = Integer.parseInt(scoreStr);
                info.put("score", moves > 0 ? 999.0 : -999.0);  // Large value for checkmate
                info.put("mate", moves);
                info.put("unit", "mate");
            }

            // Extract depth
            if (line.contains(" depth ")) {
                String[] parts = line.split(" depth ");
                String depthStr = parts[1].split(" ")[0];
                info.put("depth", Integer.parseInt(depthStr));
            }

            // Extract principal variation (sequence of best moves)
            if (line.contains(" pv ")) {
                String[] parts = line.split(" pv ");
                String[] moves = parts[1].split(" ");
                info.put("pv", Arrays.asList(moves));
            }

            return info;
        } catch (Exception e) {
            System.err.println("Error parsing Stockfish info: " + e.getMessage());
            return null;
        }
    }

    // Analyze a game move by move
    public List<Map<String, Object>> analyzeGame(List<String> fens, int depth) {
        List<Map<String, Object>> analysis = new ArrayList<>();

        // Limit the number of positions to analyze to avoid overload
        int maxPositions = Math.min(fens.size(), 30);
        for (int i = 0; i < maxPositions; i++) {
            Map<String, Object> positionAnalysis = analyzePosition(fens.get(i), depth);
            analysis.add(positionAnalysis);
        }

        return analysis;
    }

    // Find tactical opportunities (blunders, missed wins, etc.)
    public Map<String, Object> findTacticalOpportunities(List<String> fens, List<String> moves, int depth) {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> opportunities = new ArrayList<>();

        // Limit analysis to avoid overload
        int maxMoves = Math.min(fens.size() - 1, 20);

        for (int i = 0; i < maxMoves; i++) {
            String fen = fens.get(i);
            String actualMove = moves.get(i);

            // Get best move
            Map<String, Object> analysis = analyzePosition(fen, depth);
            String bestMove = analysis.get("bestMove").toString();
            double bestScore = (double) analysis.get("score");

            // Setup the position and evaluate the actual move
            try {
                sendCommand("position fen " + fen);
                sendCommand("go depth " + depth + " searchmoves " + actualMove);

                double actualMoveScore = 0.0;
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("bestmove")) {
                        break;
                    } else if (line.contains("score cp ")) {
                        String[] parts = line.split("score cp ");
                        String scoreStr = parts[1].split(" ")[0];
                        actualMoveScore = Double.parseDouble(scoreStr) / 100.0;
                    } else if (line.contains("score mate ")) {
                        String[] parts = line.split("score mate ");
                        String scoreStr = parts[1].split(" ")[0];
                        int mateInMoves = Integer.parseInt(scoreStr);
                        actualMoveScore = mateInMoves > 0 ? 999.0 : -999.0;
                    }
                }

                // Calculate the difference between best move and actual move
                double scoreDiff = bestScore - actualMoveScore;

                // Classify the move
                String moveQuality;
                if (Math.abs(scoreDiff) < 0.1) {
                    moveQuality = "Best Move";
                } else if (Math.abs(scoreDiff) < 0.3) {
                    moveQuality = "Excellent Move";
                } else if (Math.abs(scoreDiff) < 0.7) {
                    moveQuality = "Good Move";
                } else if (Math.abs(scoreDiff) < 1.5) {
                    moveQuality = "Inaccuracy";
                } else if (Math.abs(scoreDiff) < 3.0) {
                    moveQuality = "Mistake";
                } else {
                    moveQuality = "Blunder";
                }

                // If significant difference, add to opportunities
                if (Math.abs(scoreDiff) >= 0.7) {
                    Map<String, Object> opportunity = new HashMap<>();
                    opportunity.put("moveNumber", i + 1);
                    opportunity.put("fen", fen);
                    opportunity.put("actualMove", actualMove);
                    opportunity.put("bestMove", bestMove);
                    opportunity.put("scoreDifference", scoreDiff);
                    opportunity.put("classification", moveQuality);
                    opportunities.add(opportunity);
                }
            } catch (IOException e) {
                System.err.println("Error analyzing move: " + e.getMessage());
            }
        }

        result.put("opportunities", opportunities);
        return result;
    }

    // Add these methods to your StockfishService class

    /**
     * Generate a simple hint showing the best move from the current position
     */
    public Map<String, Object> getBestMoveHint(String fen, int depth) {
        Map<String, Object> result = new HashMap<>();

        try {
            // Get best move using existing analysis
            Map<String, Object> analysis = analyzePosition(fen, depth);
            String bestMove = analysis.get("bestMove").toString();
            double score = (double) analysis.get("score");

            result.put("hintType", "bestMove");
            result.put("bestMove", bestMove);
            result.put("score", score);
            result.put("from", bestMove.substring(0, 2));
            result.put("to", bestMove.substring(2, 4));

            // Check if this is a promotion move
            if (bestMove.length() > 4) {
                result.put("promotion", bestMove.substring(4));
            }

            return result;
        } catch (Exception e) {
            result.put("error", "Failed to generate hint: " + e.getMessage());
            return result;
        }
    }

    /**
     * Provide a hint about which piece to move without revealing the destination
     */
    public Map<String, Object> getPieceSelectionHint(String fen, int depth) {
        Map<String, Object> result = new HashMap<>();

        try {
            Map<String, Object> analysis = analyzePosition(fen, depth);
            String bestMove = analysis.get("bestMove").toString();
            String fromSquare = bestMove.substring(0, 2);

            result.put("hintType", "pieceSelection");
            result.put("fromSquare", fromSquare);
            result.put("message", "Consider moving the piece at " + fromSquare);

            return result;
        } catch (Exception e) {
            result.put("error", "Failed to generate piece hint: " + e.getMessage());
            return result;
        }
    }

    /**
     * Provide strategic evaluation of the position with general advice
     */
    public Map<String, Object> getStrategicHint(String fen, int depth) {
        Map<String, Object> result = new HashMap<>();

        try {
            Map<String, Object> analysis = analyzePosition(fen, depth);
            double score = (double) analysis.get("score");
            List<Map<String, Object>> lines = (List<Map<String, Object>>) analysis.get("lines");

            // Determine position evaluation
            String evaluation;
            if (score > 3.0) {
                evaluation = "winning";
            } else if (score > 1.5) {
                evaluation = "clearly better";
            } else if (score > 0.5) {
                evaluation = "slightly better";
            } else if (score > -0.5) {
                evaluation = "approximately equal";
            } else if (score > -1.5) {
                evaluation = "slightly worse";
            } else if (score > -3.0) {
                evaluation = "clearly worse";
            } else {
                evaluation = "losing";
            }

            // Generate strategic advice based on evaluation
            String advice;
            if (evaluation.equals("winning")) {
                advice = "You have a winning position. Look for forcing moves or simplify to convert your advantage.";
            } else if (evaluation.equals("clearly better")) {
                advice = "You have a clear advantage. Consider trading pieces to simplify into a winning endgame.";
            } else if (evaluation.equals("slightly better")) {
                advice = "You have a slight advantage. Improve your piece positioning and look for tactical opportunities.";
            } else if (evaluation.equals("approximately equal")) {
                advice = "The position is balanced. Focus on improving your pieces and watch for tactical opportunities.";
            } else if (evaluation.equals("slightly worse")) {
                advice = "You're at a slight disadvantage. Look for active counterplay and avoid further weaknesses.";
            } else if (evaluation.equals("clearly worse")) {
                advice = "You're at a significant disadvantage. Seek counterplay and complicate the position.";
            } else {
                advice = "You're in a difficult position. Look for tactical resources or defensive opportunities.";
            }

            result.put("hintType", "strategic");
            result.put("evaluation", evaluation);
            result.put("score", score);
            result.put("advice", advice);

            return result;
        } catch (Exception e) {
            result.put("error", "Failed to generate strategic hint: " + e.getMessage());
            return result;
        }
    }

    /**
     * Provide a tactical hint about a specific threat or opportunity
     */
    public Map<String, Object> getTacticalHint(String fen, int depth) {
        Map<String, Object> result = new HashMap<>();

        try {
            Map<String, Object> analysis = analyzePosition(fen, depth);
            String bestMove = analysis.get("bestMove").toString();
            double score = (double) analysis.get("score");

            // Check if there's a mate on the horizon
            if (analysis.containsKey("lines") && !((List<?>)analysis.get("lines")).isEmpty()) {
                List<Map<String, Object>> lines = (List<Map<String, Object>>) analysis.get("lines");
                for (Map<String, Object> line : lines) {
                    if (line.containsKey("mate")) {
                        int mateIn = (int) line.get("mate");
                        if (mateIn > 0) {
                            result.put("hintType", "tactical");
                            result.put("message", "There is a checkmate in " + mateIn + " moves!");
                            result.put("bestMove", bestMove);
                            result.put("from", bestMove.substring(0, 2));
                            result.put("to", bestMove.substring(2, 4));
                            return result;
                        }
                    }
                }
            }

            // If score is high, there's probably a tactic
            if (score > 3.0) {
                result.put("hintType", "tactical");
                result.put("message", "There's a strong tactical opportunity available!");
                result.put("bestMove", bestMove);
                result.put("from", bestMove.substring(0, 2));
                result.put("to", bestMove.substring(2, 4));
            } else {
                result.put("hintType", "tactical");
                result.put("message", "Look for the best move in this position.");
                result.put("bestMove", bestMove);
                result.put("from", bestMove.substring(0, 2));
                result.put("to", bestMove.substring(2, 4));
            }

            return result;
        } catch (Exception e) {
            result.put("error", "Failed to generate tactical hint: " + e.getMessage());
            return result;
        }
    }

    /**
     * Get multiple hints with varying levels of detail
     */
    public Map<String, Object> getAllHints(String fen, int depth) {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> hints = new ArrayList<>();

        try {
            // Add hints of increasing specificity
            hints.add(getStrategicHint(fen, depth));
            hints.add(getPieceSelectionHint(fen, depth));
            hints.add(getTacticalHint(fen, depth));
            hints.add(getBestMoveHint(fen, depth));

            result.put("hintType", "all");
            result.put("hints", hints);
            return result;
        } catch (Exception e) {
            result.put("error", "Failed to generate hints: " + e.getMessage());
            return result;
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

    // Clean expired entries from cache
    private void cleanCache() {
        Iterator<Map.Entry<String, CachedAnalysis>> iterator = analysisCache.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().isExpired()) {
                iterator.remove();
            }
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

    // Inner class for cached analysis
    private static class CachedAnalysis {
        private final Map<String, Object> data;
        private final long timestamp;

        public CachedAnalysis(Map<String, Object> data) {
            this.data = data;
            this.timestamp = System.currentTimeMillis();
        }

        public Map<String, Object> getData() {
            return data;
        }

        public boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL;
        }
    }
}