package com.aaaadchess.backend.dtos;

import java.util.List;

public class GameAnalysisRequest {
    private List<String> fens;
    private List<String> moves;
    private int depth;

    public List<String> getFens() {
        return fens;
    }

    public void setFens(List<String> fens) {
        this.fens = fens;
    }

    public List<String> getMoves() {
        return moves;
    }

    public void setMoves(List<String> moves) {
        this.moves = moves;
    }

    public int getDepth() {
        return depth;
    }

    public void setDepth(int depth) {
        this.depth = depth;
    }
}