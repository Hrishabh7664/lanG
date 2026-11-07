package com.langame.games.racer3d;

import com.langame.core.GameModule;
import com.langame.core.MatchResult;
import com.langame.core.PlayerHandle;

import java.util.List;

/** Visible in game selection; gameplay is deliberately unavailable in this build. */
public final class Racer3dModule implements GameModule {
    @Override public String id() { return "racer3d"; }
    @Override public String displayName() { return "3D Racer"; }
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
        return new UnsupportedOperationException("3D Racer is not available in this build");
    }
}
