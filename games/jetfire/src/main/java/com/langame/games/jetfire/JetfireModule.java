package com.langame.games.jetfire;

import com.langame.core.GameModule;
import com.langame.core.MatchResult;
import com.langame.core.PlayerHandle;

import java.util.List;

/** Visible in game selection; gameplay is deliberately unavailable in this build. */
public final class JetfireModule implements GameModule {
    @Override public String id() { return "jetfire"; }
    @Override public String displayName() { return "Jetfire"; }
    @Override public int minPlayers() { return 2; }
    @Override public int maxPlayers() { return 8; }
    @Override public void createInitialState(List<PlayerHandle> players) { throw unavailable(); }
    @Override public void applyInput(String playerId, byte[] input) { throw unavailable(); }
    @Override public void step(float deltaSeconds) { throw unavailable(); }
    @Override public byte[] serializeSnapshot() { throw unavailable(); }
    @Override public void applySnapshot(byte[] snapshot) { throw unavailable(); }
    @Override public boolean isMatchOver() { throw unavailable(); }
    @Override public MatchResult getResults() { throw unavailable(); }

    private UnsupportedOperationException unavailable() {
        return new UnsupportedOperationException("Jetfire is not available in this build");
    }
}
