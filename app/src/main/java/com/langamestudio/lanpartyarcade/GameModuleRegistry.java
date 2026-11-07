package com.langamestudio.lanpartyarcade;

import com.langame.core.GameModule;
import com.langame.games.inkarena.InkArenaModule;
import com.langame.games.jetfire.JetfireModule;
import com.langame.games.racer3d.Racer3dModule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** App-level registry: adding a game does not change room or screen code. */
public final class GameModuleRegistry {
    private final Map<String, GameModule> modules;

    public GameModuleRegistry() {
        Map<String, GameModule> registered = new LinkedHashMap<>();
        register(registered, new InkArenaModule());
        register(registered, new JetfireModule());
        register(registered, new Racer3dModule());
        modules = Collections.unmodifiableMap(registered);
    }

    private static void register(Map<String, GameModule> target, GameModule module) {
        if (target.put(module.id(), module) != null) {
            throw new IllegalStateException("Duplicate game id: " + module.id());
        }
    }

    public GameModule get(String id) {
        return modules.get(id);
    }

    public boolean isAvailable(String id) {
        return "ink_arena".equals(id);
    }

    public List<GameModule> all() {
        return Collections.unmodifiableList(new ArrayList<>(modules.values()));
    }
}
