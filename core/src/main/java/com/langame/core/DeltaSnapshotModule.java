package com.langame.core;

/** Optional per-peer delta codec; baseline is the last snapshot tick acknowledged by that peer. */
public interface DeltaSnapshotModule {
    int currentTick();
    byte[] serializeDeltaSnapshot(int baselineTick);
}
