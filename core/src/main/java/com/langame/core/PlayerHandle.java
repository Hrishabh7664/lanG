package com.langame.core;

import java.util.Objects;

public final class PlayerHandle {
    private final String id;
    private final String displayName;

    public PlayerHandle(String id, String displayName) {
        this.id = Objects.requireNonNull(id, "id");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
    }

    public String id() { return id; }
    public String displayName() { return displayName; }
}
