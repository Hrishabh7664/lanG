package com.langame.games.inkarena;

import com.langame.core.GameModule;
import com.langame.core.DeltaSnapshotModule;
import com.langame.core.MatchResult;
import com.langame.core.PlayerHandle;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Deterministic, Android-independent Ink Arena rules and snapshot codec. */
public final class InkArenaModule implements GameModule, DeltaSnapshotModule {
    public static final int WIDTH = 64;
    public static final int HEIGHT = 64;
    public static final int TICK_RATE = 20;
    private static final int CELL_COUNT = WIDTH * HEIGHT;
    private static final int MAGIC = 0x494E4B31; // INK1
    private static final int MATCH_LIMIT_TICKS = TICK_RATE * 300;
    private static final int[] SPAWN_X = {3, 60, 3, 60, 32, 32};
    private static final int[] SPAWN_Y = {3, 60, 60, 3, 3, 60};
    private static final int[] SPAWN_DIR = {1, 3, 0, 2, 2, 0};
    private static final int[] DX = {0, 1, 0, -1};
    private static final int[] DY = {-1, 0, 1, 0};

    private final Map<String, PlayerState> players = new LinkedHashMap<>();
    private final byte[] owners = new byte[CELL_COUNT];
    private final byte[] trails = new byte[CELL_COUNT];
    private final Deque<SnapshotFrame> history = new ArrayDeque<>();
    private int tick;
    private int winnerSlot = -1;

    @Override public String id() { return "ink_arena"; }
    @Override public String displayName() { return "Ink Arena"; }
    @Override public int minPlayers() { return 2; }
    @Override public int maxPlayers() { return 6; }

    @Override
    public synchronized void createInitialState(List<PlayerHandle> handles) {
        if (handles == null || handles.size() < minPlayers() || handles.size() > maxPlayers()) {
            throw new IllegalArgumentException("Ink Arena requires 2–6 players");
        }
        players.clear();
        java.util.Arrays.fill(owners, (byte) 0);
        java.util.Arrays.fill(trails, (byte) 0);
        tick = 0;
        winnerSlot = -1;
        history.clear();
        long seed = new SecureRandom().nextLong();
        Random spawnRandom = new Random(seed);
        List<Integer> spawnOrder = new ArrayList<>();
        for (int i = 0; i < SPAWN_X.length; i++) spawnOrder.add(i);
        Collections.shuffle(spawnOrder, spawnRandom);

        for (int slot = 0; slot < handles.size(); slot++) {
            PlayerHandle handle = handles.get(slot);
            if (handle == null || handle.id() == null || handle.id().isEmpty() || players.containsKey(handle.id())) {
                throw new IllegalArgumentException("Player IDs must be unique and nonempty");
            }
            int spawn = spawnOrder.get(slot);
            PlayerState player = new PlayerState(handle.id(), slot + 1, SPAWN_X[spawn], SPAWN_Y[spawn], SPAWN_DIR[spawn]);
            players.put(handle.id(), player);
            claimBase(player);
        }
        rememberFrame();
    }

    @Override
    public synchronized void applyInput(String playerId, byte[] input) {
        PlayerState player = players.get(playerId);
        if (player == null || !player.alive || input == null || input.length != 1) return;
        int direction = input[0] & 0xff;
        if (direction > 3 || direction == ((player.direction + 2) & 3)) return;
        player.pendingDirection = direction;
    }

    @Override
    public synchronized void step(float deltaSeconds) {
        if (Float.isNaN(deltaSeconds) || Float.isInfinite(deltaSeconds) || deltaSeconds <= 0f || deltaSeconds > 0.1f) {
            throw new IllegalArgumentException("Invalid fixed timestep");
        }
        tick++;
        // Stable insertion order makes simultaneous cell conflicts deterministic.
        for (PlayerState player : players.values()) {
            if (player.alive) move(player);
        }
        int alive = 0;
        for (PlayerState player : players.values()) if (player.alive) alive++;
        if (alive <= 1 || tick >= MATCH_LIMIT_TICKS) {
            winnerSlot = findWinnerSlot();
        }
        rememberFrame();
    }

    private void move(PlayerState player) {
        if (player.pendingDirection >= 0) {
            player.direction = player.pendingDirection;
            player.pendingDirection = -1;
        }
        int nextX = player.x + DX[player.direction];
        int nextY = player.y + DY[player.direction];
        if (!inside(nextX, nextY)) {
            eliminate(player);
            return;
        }

        int nextCell = index(nextX, nextY);
        for (PlayerState other : players.values()) {
            if (other != player && other.alive && other.x == nextX && other.y == nextY) {
                eliminate(player);
                eliminate(other);
                return;
            }
        }

        byte trailOwner = trails[nextCell];
        if (trailOwner != 0) {
            if (trailOwner == (byte) player.slot) eliminate(player);
            else eliminate(findBySlot(trailOwner));
            if (!player.alive) return;
        }

        player.x = nextX;
        player.y = nextY;
        if (owners[nextCell] == (byte) player.slot) {
            if (!player.trail.isEmpty()) capture(player);
        } else {
            trails[nextCell] = (byte) player.slot;
            player.trail.add(nextCell);
        }
    }

    private void capture(PlayerState player) {
        boolean[] outside = new boolean[CELL_COUNT];
        int[] queue = new int[CELL_COUNT];
        int head = 0;
        int tail = 0;
        for (int x = 0; x < WIDTH; x++) {
            tail = enqueueIfOpen(index(x, 0), player.slot, outside, queue, tail);
            tail = enqueueIfOpen(index(x, HEIGHT - 1), player.slot, outside, queue, tail);
        }
        for (int y = 0; y < HEIGHT; y++) {
            tail = enqueueIfOpen(index(0, y), player.slot, outside, queue, tail);
            tail = enqueueIfOpen(index(WIDTH - 1, y), player.slot, outside, queue, tail);
        }
        while (head < tail) {
            int cell = queue[head++];
            int x = cell % WIDTH;
            int y = cell / WIDTH;
            if (x > 0) tail = enqueueIfOpen(cell - 1, player.slot, outside, queue, tail);
            if (x + 1 < WIDTH) tail = enqueueIfOpen(cell + 1, player.slot, outside, queue, tail);
            if (y > 0) tail = enqueueIfOpen(cell - WIDTH, player.slot, outside, queue, tail);
            if (y + 1 < HEIGHT) tail = enqueueIfOpen(cell + WIDTH, player.slot, outside, queue, tail);
        }
        for (int cell = 0; cell < CELL_COUNT; cell++) {
            if (owners[cell] == 0 && !outside[cell]) owners[cell] = (byte) player.slot;
            if (trails[cell] == (byte) player.slot) trails[cell] = 0;
        }
        player.trail.clear();
    }

    private int enqueueIfOpen(int cell, int slot, boolean[] visited, int[] queue, int tail) {
        if (!visited[cell] && trails[cell] != (byte) slot) {
            visited[cell] = true;
            queue[tail++] = cell;
        }
        return tail;
    }

    private void claimBase(PlayerState player) {
        for (int y = Math.max(0, player.y - 1); y <= Math.min(HEIGHT - 1, player.y + 1); y++) {
            for (int x = Math.max(0, player.x - 1); x <= Math.min(WIDTH - 1, player.x + 1); x++) {
                owners[index(x, y)] = (byte) player.slot;
            }
        }
    }

    private void eliminate(PlayerState player) {
        if (player == null || !player.alive) return;
        player.alive = false;
        for (int cell : player.trail) trails[cell] = 0;
        player.trail.clear();
    }

    private PlayerState findBySlot(byte slot) {
        for (PlayerState player : players.values()) if (player.slot == (slot & 0xff)) return player;
        return null;
    }

    private int findWinnerSlot() {
        PlayerState best = null;
        for (PlayerState player : players.values()) {
            int territory = territoryCount(player.slot);
            int bestTerritory = best == null ? -1 : territoryCount(best.slot);
            if (player.alive && (best == null || !best.alive || territory > bestTerritory)) best = player;
            else if (best == null || (!best.alive && territory > bestTerritory)) best = player;
        }
        return best == null ? -1 : best.slot;
    }

    private int territoryCount(int slot) {
        int count = 0;
        for (byte owner : owners) if ((owner & 0xff) == slot) count++;
        return count;
    }

    private boolean inside(int x, int y) { return x >= 0 && x < WIDTH && y >= 0 && y < HEIGHT; }
    private int index(int x, int y) { return y * WIDTH + x; }

    @Override
    public synchronized byte[] serializeSnapshot() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(10_000);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC);
            out.writeByte(1); // full snapshot
            out.writeShort(WIDTH);
            out.writeShort(HEIGHT);
            out.writeInt(tick);
            writePlayers(out);
            out.write(owners);
            out.write(trails);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @Override
    public synchronized int currentTick() { return tick; }

    @Override
    public synchronized byte[] serializeDeltaSnapshot(int baselineTick) {
        SnapshotFrame baseline = null;
        for (SnapshotFrame frame : history) if (frame.tick == baselineTick) baseline = frame;
        if (baseline == null || tick % 60 == 0) return serializeSnapshot();
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(8_192);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC);
            out.writeByte(2); // delta snapshot
            out.writeShort(WIDTH);
            out.writeShort(HEIGHT);
            out.writeInt(tick);
            out.writeInt(baselineTick);
            writePlayers(out);
            int changes = 0;
            for (int i = 0; i < CELL_COUNT; i++) {
                if (baseline.owners[i] != owners[i] || baseline.trails[i] != trails[i]) changes++;
            }
            out.writeShort(changes);
            for (int i = 0; i < CELL_COUNT; i++) {
                if (baseline.owners[i] != owners[i] || baseline.trails[i] != trails[i]) {
                    out.writeShort(i);
                    out.writeByte(owners[i]);
                    out.writeByte(trails[i]);
                }
            }
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @Override
    public synchronized void applySnapshot(byte[] snapshot) {
        if (snapshot == null || snapshot.length > 16_384) throw new IllegalArgumentException("Invalid snapshot size");
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(snapshot));
            if (in.readInt() != MAGIC) throw new IOException("Invalid snapshot header");
            int snapshotKind = in.readUnsignedByte();
            if (snapshotKind != 1 && snapshotKind != 2) throw new IOException("Invalid snapshot kind");
            if (in.readUnsignedShort() != WIDTH || in.readUnsignedShort() != HEIGHT) throw new IOException("Invalid board dimensions");
            int newTick = in.readInt();
            int baselineTick = snapshotKind == 2 ? in.readInt() : -1;
            int playerCount = in.readUnsignedByte();
            if (newTick < 0 || playerCount < 1 || playerCount > maxPlayers() ||
                    (snapshotKind == 2 && (baselineTick < 0 || baselineTick > tick || newTick <= tick))) {
                throw new IOException("Invalid snapshot metadata or baseline");
            }
            Map<String, PlayerState> decoded = new LinkedHashMap<>();
            for (int i = 0; i < playerCount; i++) {
                String id = readString(in, 64);
                int slot = in.readUnsignedByte();
                int x = in.readUnsignedByte();
                int y = in.readUnsignedByte();
                int direction = in.readUnsignedByte();
                boolean alive = in.readBoolean();
                if (id.isEmpty() || slot < 1 || slot > maxPlayers() || !inside(x, y) || direction > 3 || decoded.containsKey(id)) {
                    throw new IOException("Invalid player in snapshot");
                }
                PlayerState player = new PlayerState(id, slot, x, y, direction);
                player.alive = alive;
                decoded.put(id, player);
            }
            byte[] newOwners = snapshotKind == 1 ? new byte[CELL_COUNT] : owners.clone();
            byte[] newTrails = snapshotKind == 1 ? new byte[CELL_COUNT] : trails.clone();
            if (snapshotKind == 1) {
                in.readFully(newOwners);
                in.readFully(newTrails);
            } else {
                int changeCount = in.readUnsignedShort();
                if (changeCount > CELL_COUNT) throw new IOException("Too many cell changes");
                boolean[] seen = new boolean[CELL_COUNT];
                for (int i = 0; i < changeCount; i++) {
                    int cell = in.readUnsignedShort();
                    int owner = in.readUnsignedByte();
                    int trail = in.readUnsignedByte();
                    if (cell >= CELL_COUNT || seen[cell] || owner > playerCount || trail > playerCount) {
                        throw new IOException("Invalid cell delta");
                    }
                    seen[cell] = true;
                    newOwners[cell] = (byte) owner;
                    newTrails[cell] = (byte) trail;
                }
            }
            if (in.available() != 0) throw new IOException("Trailing snapshot bytes");
            for (byte cell : newOwners) if ((cell & 0xff) > playerCount) throw new IOException("Invalid territory owner");
            for (byte cell : newTrails) if ((cell & 0xff) > playerCount) throw new IOException("Invalid trail owner");
            players.clear();
            players.putAll(decoded);
            System.arraycopy(newOwners, 0, owners, 0, CELL_COUNT);
            System.arraycopy(newTrails, 0, trails, 0, CELL_COUNT);
            tick = newTick;
            winnerSlot = findWinnerSlot();
            history.clear();
            rememberFrame();
        } catch (IOException | RuntimeException invalid) {
            throw new IllegalArgumentException("Malformed Ink Arena snapshot", invalid);
        }
    }

    @Override
    public synchronized boolean isMatchOver() {
        int alive = 0;
        for (PlayerState player : players.values()) if (player.alive) alive++;
        return !players.isEmpty() && (alive <= 1 || tick >= MATCH_LIMIT_TICKS);
    }

    @Override
    public synchronized MatchResult getResults() {
        Map<String, Integer> scores = new LinkedHashMap<>();
        for (PlayerState player : players.values()) scores.put(player.id, territoryCount(player.slot));
        return new MatchResult("last_player_or_time_limit", scores);
    }

    public synchronized RenderState renderState() {
        List<PlayerView> playerViews = new ArrayList<>();
        for (PlayerState player : players.values()) {
            playerViews.add(new PlayerView(player.id, player.slot, player.x, player.y, player.direction, player.alive));
        }
        return new RenderState(tick, owners.clone(), trails.clone(), playerViews);
    }

    private static void writeString(DataOutputStream out, String value, int maxBytes) throws IOException {
        byte[] data = value.getBytes(StandardCharsets.UTF_8);
        if (data.length == 0 || data.length > maxBytes) throw new IOException("String size out of range");
        out.writeByte(data.length);
        out.write(data);
    }

    private static String readString(DataInputStream in, int maxBytes) throws IOException {
        int length = in.readUnsignedByte();
        if (length == 0 || length > maxBytes) throw new IOException("String size out of range");
        byte[] data = new byte[length];
        in.readFully(data);
        return new String(data, StandardCharsets.UTF_8);
    }

    private void writePlayers(DataOutputStream out) throws IOException {
        out.writeByte(players.size());
        for (PlayerState player : players.values()) {
            writeString(out, player.id, 64);
            out.writeByte(player.slot);
            out.writeByte(player.x);
            out.writeByte(player.y);
            out.writeByte(player.direction);
            out.writeBoolean(player.alive);
        }
    }

    private void rememberFrame() {
        history.addLast(new SnapshotFrame(tick, owners.clone(), trails.clone()));
        while (history.size() > 61) history.removeFirst();
    }

    public static final class PlayerView {
        public final String id;
        public final int slot;
        public final int x;
        public final int y;
        public final int direction;
        public final boolean alive;

        private PlayerView(String id, int slot, int x, int y, int direction, boolean alive) {
            this.id = id; this.slot = slot; this.x = x; this.y = y; this.direction = direction; this.alive = alive;
        }
    }

    public static final class RenderState {
        public final int tick;
        public final byte[] owners;
        public final byte[] trails;
        public final List<PlayerView> players;

        private RenderState(int tick, byte[] owners, byte[] trails, List<PlayerView> players) {
            this.tick = tick; this.owners = owners; this.trails = trails;
            this.players = Collections.unmodifiableList(players);
        }
    }

    private static final class PlayerState {
        final String id;
        final int slot;
        final List<Integer> trail = new ArrayList<>();
        int x;
        int y;
        int direction;
        int pendingDirection = -1;
        boolean alive = true;

        PlayerState(String id, int slot, int x, int y, int direction) {
            this.id = id; this.slot = slot; this.x = x; this.y = y; this.direction = direction;
        }
    }

    private static final class SnapshotFrame {
        final int tick;
        final byte[] owners;
        final byte[] trails;
        SnapshotFrame(int tick, byte[] owners, byte[] trails) {
            this.tick = tick;
            this.owners = owners;
            this.trails = trails;
        }
    }
}
