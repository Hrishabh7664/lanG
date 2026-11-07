package com.langame.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class MatchResult {
    private final String reason;
    private final Map<String, Integer> scores;

    public MatchResult(String reason, Map<String, Integer> scores) {
        this.reason = reason;
        this.scores = Collections.unmodifiableMap(new LinkedHashMap<>(scores));
    }

    public String reason() { return reason; }
    public Map<String, Integer> scores() { return scores; }
}
