package com.langame.core;

import java.util.List;

public interface GameModule {
    String id();
    String displayName();
    int minPlayers();
    int maxPlayers();
    void createInitialState(List<PlayerHandle> players);
    void applyInput(String playerId, byte[] input);
    void step(float deltaSeconds);
    byte[] serializeSnapshot();
    void applySnapshot(byte[] snapshot);
    boolean isMatchOver();
    MatchResult getResults();
}
