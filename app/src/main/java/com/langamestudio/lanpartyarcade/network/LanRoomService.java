package com.langamestudio.lanpartyarcade.network;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import com.langame.core.DeltaSnapshotModule;
import com.langame.core.GameModule;
import com.langame.core.MatchResult;
import com.langame.core.PlayerHandle;
import com.langamestudio.lanpartyarcade.GameModuleRegistry;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

/** Room discovery, encrypted admission, lobby relay, and host-authoritative game loop. */
public final class LanRoomService implements AutoCloseable {
    private static final int DISCOVERY_PORT = 45882;
    private static final int MAX_DISCOVERY_BYTES = 512;
    private static final long ADVERTISE_INTERVAL_MS = 2_000;
    private static final long ROOM_TTL_MS = 6_000;
    private static final String ROOM_CODE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int MAX_PENDING = 3;
    private static final long INPUT_RATE_WINDOW_MS = 1_000;

    private final Context appContext;
    private final GameModuleRegistry registry;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final SecureRandom secureRandom = new SecureRandom();
    private final Object stateLock = new Object();
    private final Object gameLock = new Object();
    private final Map<String, Member> members = new LinkedHashMap<>();
    private final ConcurrentHashMap<String, PendingConnection> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RoomDescriptor> discovered = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RateWindow> joinRates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RateWindow> queryRates = new ConcurrentHashMap<>();
    private final Semaphore joinSlots = new Semaphore(MAX_PENDING);
    private final Listener listener;

    private volatile boolean closed;
    private volatile boolean hosting;
    private volatile boolean inMatch;
    private volatile boolean resultsShown;
    private String roomCode;
    private String roomId;
    private String gameId;
    private String localPlayerId;
    private String localDisplayName;
    private String tlsAlias;
    private String hostFingerprint;
    private int minPlayers;
    private int maxPlayers;
    private int serverPort;
    private String roomStage = "CLOSED";
    private GameModule game;
    private SSLServerSocket serverSocket;
    private Peer clientPeer;
    private Thread acceptThread;
    private DiscoveryAgent discoveryAgent;
    private ScheduledExecutorService tickExecutor;
    private int clientInputSequence;

    public LanRoomService(Context context, GameModuleRegistry registry, Listener listener) {
        this.appContext = context.getApplicationContext();
        this.registry = registry;
        this.listener = listener;
    }

    public String roomCode() { synchronized (stateLock) { return roomCode; } }
    public String localPlayerId() { return localPlayerId; }
    public boolean isHosting() { return hosting; }

    public String hostRoom(GameModule selectedGame, String displayName) throws Exception {
        closeRoom();
        if (selectedGame == null || !registry.isAvailable(selectedGame.id())) throw new IllegalArgumentException("Game is not available");
        this.game = selectedGame;
        this.gameId = selectedGame.id();
        this.minPlayers = selectedGame.minPlayers();
        this.maxPlayers = selectedGame.maxPlayers();
        this.localDisplayName = cleanName(displayName);
        this.roomCode = generateRoomCode();
        this.roomId = randomHex(6);
        this.localPlayerId = "host";
        this.tlsAlias = "lanroom_" + roomId.toLowerCase(Locale.ROOT);
        this.roomStage = "WAITING";
        this.hosting = true;
        this.closed = false;

        TlsSessions.HostIdentity identity = TlsSessions.createHostIdentity(tlsAlias);
        this.hostFingerprint = identity.fingerprint;
        SSLServerSocket listenerSocket = (SSLServerSocket) identity.context.getServerSocketFactory().createServerSocket();
        TlsSessions.enableSupportedSecureProtocols(listenerSocket);
        listenerSocket.setReuseAddress(true);
        listenerSocket.bind(new InetSocketAddress(0), MAX_PENDING + maxPlayers);
        listenerSocket.setSoTimeout(1_000);
        this.serverPort = listenerSocket.getLocalPort();
        this.serverSocket = listenerSocket;

        synchronized (stateLock) {
            members.clear();
            members.put(localPlayerId, new Member(localPlayerId, localDisplayName, true, true, null));
        }
        startDiscoveryAgent();
        acceptThread = new Thread(this::acceptLoop, "lan-room-accept");
        acceptThread.start();
        postLobbyChanged();
        return roomCode;
    }

    public void startDiscovery() throws IOException {
        if (hosting) throw new IllegalStateException("Host does not scan for rooms");
        closeDiscoveryAgent();
        closed = false;
        discoveryAgent = new DiscoveryAgent(false);
        discoveryAgent.start();
    }

    public void queryRoomCode(String value) {
        String code = normalizeRoomCode(value);
        if (code == null || discoveryAgent == null) return;
        discoveryAgent.sendQuery(code);
    }

    public List<RoomDescriptor> discoveredRooms() {
        return Collections.unmodifiableList(new ArrayList<>(discovered.values()));
    }

    public void joinRoom(String selectedRoomId, String value, String displayName) {
        final RoomDescriptor descriptor = discovered.get(selectedRoomId);
        final String code = normalizeRoomCode(value);
        if (descriptor == null || code == null) {
            notifyError("Enter a valid six-character code and select a room found on this Wi-Fi network.");
            return;
        }
        if (!registry.isAvailable(descriptor.gameId)) {
            notifyError("This room is for a game that is not available in this build.");
            return;
        }
        if (descriptor.address == null || !descriptor.address.isSiteLocalAddress() || descriptor.port < 1 || descriptor.port > 65535) {
            notifyError("The discovered room address is invalid.");
            return;
        }
        this.gameId = descriptor.gameId;
        this.game = registry.get(descriptor.gameId);
        this.localDisplayName = cleanName(displayName);
        this.roomCode = code;
        this.roomId = descriptor.roomId;
        this.minPlayers = game.minPlayers();
        this.maxPlayers = game.maxPlayers();
        this.hosting = false;
        this.closed = false;
        closeDiscoveryAgent();
        Thread thread = new Thread(() -> connectToRoom(descriptor, code), "lan-room-client");
        thread.start();
    }

    public void approveJoin(String pendingId, boolean approved) {
        PendingConnection request = pending.get(pendingId);
        if (request == null) return;
        request.approved = approved;
        request.decision.countDown();
    }

    public void setReady(boolean ready) {
        if (hosting || clientPeer == null) return;
        Peer peer = clientPeer;
        try {
            byte[] payload = WireProtocol.payload(out -> out.writeBoolean(ready));
            peer.send(WireProtocol.READY, payload);
        } catch (IOException failure) {
            notifyError("Unable to update ready state.");
        }
    }

    public void startMatch() {
        if (!hosting || game == null || inMatch || closed) return;
        List<PlayerHandle> players = new ArrayList<>();
        synchronized (stateLock) {
            if (members.size() < minPlayers || members.size() > maxPlayers) return;
            for (Member member : members.values()) {
                if (!member.ready || !member.connected) return;
                players.add(new PlayerHandle(member.id, member.name));
            }
            roomStage = "STARTING";
        }
        try {
            synchronized (gameLock) {
                game.createInitialState(players);
            }
            byte[] snapshot;
            synchronized (gameLock) { snapshot = game.serializeSnapshot(); }
            byte[] payload = WireProtocol.payload(out -> {
                WireProtocol.writeString(out, gameId, 32);
                out.writeByte(20);
                WireProtocol.writeBytes(out, snapshot, WireProtocol.MAX_SNAPSHOT_FRAME - 16);
            });
            synchronized (stateLock) {
                roomStage = "IN_PROGRESS";
                inMatch = true;
                resultsShown = false;
                for (Member member : members.values()) if (member.peer != null) member.peer.lastAckTick = gameTick();
            }
            broadcast(WireProtocol.START_MATCH, payload);
            postMatchStarted(game);
            tickExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "ink-arena-authoritative-tick");
                thread.setDaemon(true);
                return thread;
            });
            tickExecutor.scheduleAtFixedRate(this::advanceGameTick, 50, 50, TimeUnit.MILLISECONDS);
            postLobbyChanged();
        } catch (Exception failure) {
            synchronized (stateLock) { roomStage = "WAITING"; inMatch = false; }
            notifyError("Could not start match: " + safeMessage(failure));
        }
    }

    public void returnToLobby() {
        if (!hosting || !"RESULTS".equals(roomStage) || closed) return;
        if (tickExecutor != null) { tickExecutor.shutdownNow(); tickExecutor = null; }
        synchronized (stateLock) {
            roomStage = "WAITING";
            inMatch = false;
            resultsShown = false;
            for (Member member : members.values()) member.ready = member.host;
        }
        broadcastLobby();
    }

    public void sendInput(byte[] input) {
        if (input == null || input.length != 1 || (input[0] & 0xff) > 3 || !inMatch || game == null) return;
        if (hosting) {
            synchronized (gameLock) { game.applyInput(localPlayerId, input.clone()); }
            return;
        }
        Peer peer = clientPeer;
        if (peer == null) return;
        try {
            int sequence = ++clientInputSequence;
            byte[] payload = WireProtocol.payload(out -> {
                out.writeInt(sequence);
                out.writeByte(input[0]);
            });
            peer.send(WireProtocol.INPUT, payload);
        } catch (IOException failure) {
            notifyError("Unable to send game input.");
        }
    }

    public void leaveRoom() {
        if (clientPeer != null) {
            try { clientPeer.send(WireProtocol.LEAVE, new byte[0]); } catch (IOException ignored) { }
        }
        closeRoom();
    }

    private void startDiscoveryAgent() throws IOException {
        closeDiscoveryAgent();
        discoveryAgent = new DiscoveryAgent(true);
        discoveryAgent.start();
    }

    private void acceptLoop() {
        while (!closed && serverSocket != null && !serverSocket.isClosed()) {
            try {
                SSLSocket socket = (SSLSocket) serverSocket.accept();
                TlsSessions.enableSupportedSecureProtocols(socket);
                socket.setSoTimeout(10_000);
                if (!joinSlots.tryAcquire()) {
                    reject(socket, "RATE_LIMITED");
                    continue;
                }
                Thread pendingThread = new Thread(() -> processJoinRequest(socket), "lan-room-pending-join");
                try {
                    pendingThread.start();
                } catch (RuntimeException failure) {
                    joinSlots.release();
                    closeQuietly(socket);
                }
            } catch (SocketTimeoutException ignored) {
                // Periodic timeout allows closeRoom to stop the accept loop promptly.
            } catch (IOException failure) {
                if (!closed) notifyError("Room listener stopped: " + safeMessage(failure));
            }
        }
    }

    private void processJoinRequest(SSLSocket socket) {
        PendingConnection request = null;
        try {
            if (pending.size() >= MAX_PENDING || !allow(joinRates, socket.getInetAddress().getHostAddress(), 5, 60_000)) {
                reject(socket, "RATE_LIMITED");
                return;
            }
            socket.startHandshake();
            DataInputStream input = new DataInputStream(socket.getInputStream());
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            WireProtocol.Frame frame = WireProtocol.read(input);
            if (frame.type != WireProtocol.ROOM_JOIN) throw new IOException("Expected join request");
            String code = WireProtocol.readString(frame.payload, 6);
            String playerName = cleanName(WireProtocol.readString(frame.payload, 64));
            ensureConsumed(frame.payload);
            if (!code.equals(roomCode)) {
                reject(socket, "INVALID_CODE");
                return;
            }
            synchronized (stateLock) {
                if (!hosting || !"WAITING".equals(roomStage) || members.size() >= maxPlayers) {
                    reject(socket, "ROOM_UNAVAILABLE");
                    return;
                }
            }
            String id = UUID.randomUUID().toString();
            request = new PendingConnection(id, playerName, socket, input, output,
                    socket.getInetAddress().getHostAddress(), TlsSessions.safetyCode(socket.getSession()));
            pending.put(id, request);
            socket.setSoTimeout(30_000);
            postJoinRequest(request.publicView());
            request.decision.await(30, TimeUnit.SECONDS);
            pending.remove(id);
            if (!Boolean.TRUE.equals(request.approved) || closed) {
                reject(socket, "HOST_DECLINED");
                return;
            }
            String playerId = UUID.randomUUID().toString();
            Peer peer = new Peer(socket, input, output);
            peer.lastAckTick = gameTick();
            Member member = new Member(playerId, playerName, false, true, peer);
            synchronized (stateLock) {
                if (!hosting || !"WAITING".equals(roomStage) || members.size() >= maxPlayers) {
                    reject(socket, "ROOM_UNAVAILABLE");
                    return;
                }
                members.put(playerId, member);
            }
            byte[] accepted = WireProtocol.payload(out -> {
                WireProtocol.writeString(out, playerId, 64);
                WireProtocol.writeString(out, gameId, 32);
            });
            peer.send(WireProtocol.ROOM_ACCEPT, accepted);
            socket.setSoTimeout(15_000);
            sendLobbyTo(peer);
            broadcastLobby();
            Thread reader = new Thread(() -> readHostPeer(playerId, peer), "lan-room-peer-" + playerId);
            reader.start();
            request = null;
        } catch (Exception failure) {
            if (!closed) notifyError("Join request failed: " + safeMessage(failure));
        } finally {
            if (request != null) pending.remove(request.id);
            if (request == null && !isSocketOwned(socket)) closeQuietly(socket);
            joinSlots.release();
        }
    }

    private boolean isSocketOwned(SSLSocket socket) {
        synchronized (stateLock) {
            for (Member member : members.values()) if (member.peer != null && member.peer.socket == socket) return true;
        }
        return false;
    }

    private void connectToRoom(RoomDescriptor descriptor, String code) {
        SSLSocket socket = null;
        try {
            socket = (SSLSocket) TlsSessions.createPinnedClientContext(descriptor.fingerprint)
                    .getSocketFactory().createSocket();
            TlsSessions.enableSupportedSecureProtocols(socket);
            socket.connect(new InetSocketAddress(descriptor.address, descriptor.port), 8_000);
            socket.setSoTimeout(35_000);
            socket.startHandshake();
            String safety = TlsSessions.safetyCode(socket.getSession());
            postPairingCode(safety);
            DataInputStream input = new DataInputStream(socket.getInputStream());
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            byte[] joinPayload = WireProtocol.payload(out -> {
                WireProtocol.writeString(out, code, 6);
                WireProtocol.writeString(out, localDisplayName, 64);
            });
            WireProtocol.write(output, WireProtocol.ROOM_JOIN, joinPayload);
            WireProtocol.Frame frame = WireProtocol.read(input);
            if (frame.type == WireProtocol.ROOM_REJECT) {
                String reason = WireProtocol.readString(frame.payload, 64);
                ensureConsumed(frame.payload);
                postError("Host did not admit the connection: " + reason);
                closeQuietly(socket);
                return;
            }
            if (frame.type != WireProtocol.ROOM_ACCEPT) throw new IOException("Unexpected response to join request");
            localPlayerId = WireProtocol.readString(frame.payload, 64);
            String acceptedGameId = WireProtocol.readString(frame.payload, 32);
            ensureConsumed(frame.payload);
            if (!gameId.equals(acceptedGameId)) throw new IOException("Room game changed during join");
            clientPeer = new Peer(socket, input, output);
            socket.setSoTimeout(15_000);
            postConnected();
            readClientPeer(clientPeer);
        } catch (Exception failure) {
            if (!closed) postError("Could not join room: " + safeMessage(failure));
            closeQuietly(socket);
        }
    }

    private void readHostPeer(String playerId, Peer peer) {
        try {
            while (!closed && !peer.socket.isClosed()) {
                WireProtocol.Frame frame;
                try {
                    frame = WireProtocol.read(peer.input);
                } catch (SocketTimeoutException timeout) {
                    if (System.currentTimeMillis() - peer.lastPongAt > 30_000) throw new IOException("Peer timed out");
                    peer.send(WireProtocol.PING, new byte[0]);
                    continue;
                }
                peer.countInboundMessage();
                switch (frame.type) {
                    case WireProtocol.READY: {
                        boolean ready = frame.payload.readBoolean();
                        ensureConsumed(frame.payload);
                        synchronized (stateLock) {
                            Member member = members.get(playerId);
                            if (member == null || inMatch) break;
                            member.ready = ready;
                        }
                        broadcastLobby();
                        break;
                    }
                    case WireProtocol.INPUT:
                        acceptInput(playerId, peer, frame.payload);
                        break;
                    case WireProtocol.SNAPSHOT_ACK: {
                        int ackTick = frame.payload.readInt();
                        ensureConsumed(frame.payload);
                        int current = gameTick();
                        if (ackTick >= peer.lastAckTick && ackTick <= current) peer.lastAckTick = ackTick;
                        break;
                    }
                    case WireProtocol.REQUEST_FULL:
                        ensureConsumed(frame.payload);
                        sendSnapshotTo(peer, true);
                        break;
                    case WireProtocol.PING:
                        ensureConsumed(frame.payload);
                        peer.send(WireProtocol.PONG, new byte[0]);
                        break;
                    case WireProtocol.PONG:
                        ensureConsumed(frame.payload);
                        peer.lastPongAt = System.currentTimeMillis();
                        break;
                    case WireProtocol.LEAVE:
                        ensureConsumed(frame.payload);
                        return;
                    default:
                        throw new IOException("Message is not valid from a lobby peer");
                }
            }
        } catch (Exception failure) {
            if (!closed && !(failure instanceof EOFException)) postError("Peer disconnected.");
        } finally {
            removeMember(playerId, peer);
            closeQuietly(peer.socket);
        }
    }

    private void acceptInput(String playerId, Peer peer, DataInputStream payload) throws IOException {
        int sequence = payload.readInt();
        byte direction = payload.readByte();
        ensureConsumed(payload);
        if (!inMatch || sequence < 0 || sequence <= peer.lastInputSequence || sequence > peer.lastInputSequence + 120 || (direction & 0xff) > 3) return;
        long now = System.currentTimeMillis();
        if (now - peer.inputWindowStart >= INPUT_RATE_WINDOW_MS) {
            peer.inputWindowStart = now;
            peer.inputCount = 0;
        }
        if (++peer.inputCount > 60) throw new IOException("Input rate limit exceeded");
        peer.lastInputSequence = sequence;
        synchronized (gameLock) { game.applyInput(playerId, new byte[]{direction}); }
    }

    private void readClientPeer(Peer peer) throws IOException {
        try {
            while (!closed && !peer.socket.isClosed()) {
                WireProtocol.Frame frame;
                try {
                    frame = WireProtocol.read(peer.input);
                } catch (SocketTimeoutException timeout) {
                    peer.send(WireProtocol.PING, new byte[0]);
                    continue;
                }
                peer.countInboundMessage();
                switch (frame.type) {
                    case WireProtocol.LOBBY_STATE:
                        readLobbyState(frame.payload);
                        break;
                    case WireProtocol.START_MATCH:
                        readMatchStart(frame.payload);
                        break;
                    case WireProtocol.SNAPSHOT:
                        readSnapshot(frame.payload);
                        break;
                    case WireProtocol.MATCH_OVER:
                        readMatchOver(frame.payload);
                        break;
                    case WireProtocol.ROOM_CLOSED:
                        ensureConsumed(frame.payload);
                        postRoomClosed("Host closed the room");
                        return;
                    case WireProtocol.PING:
                        ensureConsumed(frame.payload);
                        peer.send(WireProtocol.PONG, new byte[0]);
                        break;
                    case WireProtocol.PONG:
                        ensureConsumed(frame.payload);
                        break;
                    case WireProtocol.ROOM_REJECT:
                        postError(WireProtocol.readString(frame.payload, 64));
                        return;
                    case WireProtocol.LEAVE:
                        ensureConsumed(frame.payload);
                        return;
                    default:
                        throw new IOException("Unexpected host message");
                }
            }
        } catch (EOFException disconnected) {
            // Normal socket close.
        } finally {
            if (clientPeer == peer) clientPeer = null;
            closeQuietly(peer.socket);
            if (!closed) postRoomClosed("Disconnected from host");
        }
    }

    private void readLobbyState(DataInputStream payload) throws IOException {
        String incomingCode = WireProtocol.readString(payload, 6);
        int count = payload.readUnsignedByte();
        if (count < 1 || count > 8) throw new IOException("Invalid lobby size");
        List<PlayerEntry> roster = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String id = WireProtocol.readString(payload, 64);
            String name = WireProtocol.readString(payload, 64);
            boolean ready = payload.readBoolean();
            boolean host = payload.readBoolean();
            roster.add(new PlayerEntry(id, name, ready, host));
        }
        ensureConsumed(payload);
        roomCode = incomingCode;
        postLobby(roster, incomingCode, "WAITING");
    }

    private void readMatchStart(DataInputStream payload) throws IOException {
        String incomingGame = WireProtocol.readString(payload, 32);
        int tickRate = payload.readUnsignedByte();
        byte[] snapshot = WireProtocol.readBytes(payload, WireProtocol.MAX_SNAPSHOT_FRAME - 16);
        ensureConsumed(payload);
        if (!gameId.equals(incomingGame) || tickRate != 20) throw new IOException("Unsupported match settings");
        synchronized (gameLock) { game.applySnapshot(snapshot); }
        inMatch = true;
        roomStage = "IN_PROGRESS";
        postMatchStarted(game);
    }

    private void readSnapshot(DataInputStream payload) throws IOException {
        int tick = payload.readInt();
        int baseline = payload.readInt();
        byte[] snapshot = WireProtocol.readBytes(payload, WireProtocol.MAX_SNAPSHOT_FRAME - 16);
        ensureConsumed(payload);
        try {
            synchronized (gameLock) { game.applySnapshot(snapshot); }
            Peer peer = clientPeer;
            if (peer != null) peer.send(WireProtocol.SNAPSHOT_ACK,
                    WireProtocol.payload(out -> out.writeInt(tick)));
            postGameFrame(game);
        } catch (IllegalArgumentException invalidDelta) {
            Peer peer = clientPeer;
            if (peer != null) peer.send(WireProtocol.REQUEST_FULL, new byte[0]);
        }
    }

    private void readMatchOver(DataInputStream payload) throws IOException {
        String reason = WireProtocol.readString(payload, 64);
        int count = payload.readUnsignedByte();
        if (count > 8) throw new IOException("Invalid result size");
        Map<String, Integer> scores = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) scores.put(WireProtocol.readString(payload, 64), payload.readInt());
        ensureConsumed(payload);
        inMatch = false;
        roomStage = "RESULTS";
        postMatchOver(new MatchResult(reason, scores));
    }

    private void advanceGameTick() {
        if (!hosting || !inMatch || closed || game == null) return;
        try {
            synchronized (gameLock) { game.step(1f / 20f); }
            if (game.isMatchOver()) {
                finishMatch();
                return;
            }
            synchronized (stateLock) {
                for (Member member : members.values()) if (member.peer != null) sendSnapshotTo(member.peer, false);
            }
            postGameFrame(game);
        } catch (Exception failure) {
            notifyError("Match stopped due to a simulation error.");
            finishMatch();
        }
    }

    private void sendSnapshotTo(Peer peer, boolean forceFull) throws IOException {
        byte[] snapshot;
        int tick;
        synchronized (gameLock) {
            tick = game instanceof DeltaSnapshotModule ? ((DeltaSnapshotModule) game).currentTick() : 0;
            snapshot = forceFull || !(game instanceof DeltaSnapshotModule)
                    ? game.serializeSnapshot()
                    : ((DeltaSnapshotModule) game).serializeDeltaSnapshot(peer.lastAckTick);
        }
        int baseline = snapshot.length > 5 && snapshot[4] == 2 ? peer.lastAckTick : -1;
        final int snapshotTick = tick;
        final int snapshotBaseline = baseline;
        byte[] payload = WireProtocol.payload(out -> {
            out.writeInt(snapshotTick);
            out.writeInt(snapshotBaseline);
            WireProtocol.writeBytes(out, snapshot, WireProtocol.MAX_SNAPSHOT_FRAME - 16);
        });
        peer.send(WireProtocol.SNAPSHOT, payload);
    }

    private void finishMatch() {
        if (!hosting || resultsShown || game == null) return;
        resultsShown = true;
        inMatch = false;
        roomStage = "RESULTS";
        if (tickExecutor != null) tickExecutor.shutdownNow();
        MatchResult result;
        synchronized (gameLock) { result = game.getResults(); }
        try {
            byte[] payload = WireProtocol.payload(out -> {
                WireProtocol.writeString(out, result.reason(), 64);
                out.writeByte(Math.min(8, result.scores().size()));
                int count = 0;
                for (Map.Entry<String, Integer> score : result.scores().entrySet()) {
                    if (count++ == 8) break;
                    WireProtocol.writeString(out, score.getKey(), 64);
                    out.writeInt(score.getValue());
                }
            });
            broadcast(WireProtocol.MATCH_OVER, payload);
        } catch (IOException ignored) { }
        postMatchOver(result);
        postLobbyChanged();
    }

    private void removeMember(String playerId, Peer peer) {
        boolean removed;
        synchronized (stateLock) {
            Member member = members.get(playerId);
            removed = member != null && member.peer == peer;
            if (removed) members.remove(playerId);
        }
        if (removed) {
            postLobbyChanged();
            broadcastLobby();
        }
    }

    private void broadcastLobby() {
        List<Member> peers = new ArrayList<>();
        synchronized (stateLock) { for (Member member : members.values()) if (member.peer != null) peers.add(member); }
        for (Member member : peers) {
            try { sendLobbyTo(member.peer); } catch (IOException failure) { closeQuietly(member.peer.socket); }
        }
        postLobbyChanged();
    }

    private void sendLobbyTo(Peer peer) throws IOException {
        byte[] payload;
        synchronized (stateLock) {
            payload = WireProtocol.payload(out -> {
                WireProtocol.writeString(out, roomCode, 6);
                out.writeByte(members.size());
                for (Member member : members.values()) {
                    WireProtocol.writeString(out, member.id, 64);
                    WireProtocol.writeString(out, member.name, 64);
                    out.writeBoolean(member.ready);
                    out.writeBoolean(member.host);
                }
            });
        }
        peer.send(WireProtocol.LOBBY_STATE, payload);
    }

    private void broadcast(int type, byte[] payload) {
        List<Peer> peers = new ArrayList<>();
        synchronized (stateLock) { for (Member member : members.values()) if (member.peer != null) peers.add(member.peer); }
        for (Peer peer : peers) {
            try { peer.send(type, payload); } catch (IOException failure) { closeQuietly(peer.socket); }
        }
    }

    private void postLobbyChanged() {
        List<PlayerEntry> roster = new ArrayList<>();
        synchronized (stateLock) {
            for (Member member : members.values()) roster.add(new PlayerEntry(member.id, member.name, member.ready, member.host));
        }
        postLobby(roster, roomCode == null ? "" : roomCode, roomStage);
    }

    private void postLobby(List<PlayerEntry> roster, String code, String stage) {
        mainHandler.post(() -> listener.onLobbyUpdated(Collections.unmodifiableList(roster), code, stage));
    }

    private void postRoomsChanged() {
        List<RoomDescriptor> rooms = new ArrayList<>(discovered.values());
        mainHandler.post(() -> listener.onRoomsUpdated(Collections.unmodifiableList(rooms)));
    }

    private void postJoinRequest(PendingJoin request) { mainHandler.post(() -> listener.onJoinRequest(request)); }
    private void postPairingCode(String code) { mainHandler.post(() -> listener.onPairingCode(code)); }
    private void postConnected() { mainHandler.post(listener::onConnected); }
    private void postMatchStarted(GameModule module) { mainHandler.post(() -> listener.onMatchStarted(module)); }
    private void postGameFrame(GameModule module) { mainHandler.post(() -> listener.onGameFrame(module)); }
    private void postMatchOver(MatchResult result) { mainHandler.post(() -> listener.onMatchOver(result)); }
    private void postRoomClosed(String reason) { mainHandler.post(() -> listener.onRoomClosed(reason)); }
    private void postError(String message) { mainHandler.post(() -> listener.onError(message)); }
    private void notifyError(String message) { postError(message); }

    private int gameTick() {
        synchronized (gameLock) { return game instanceof DeltaSnapshotModule ? ((DeltaSnapshotModule) game).currentTick() : 0; }
    }

    private boolean allow(ConcurrentHashMap<String, RateWindow> limits, String key, int max, long windowMs) {
        if (key == null) return false;
        RateWindow counter = limits.computeIfAbsent(key, ignored -> new RateWindow());
        synchronized (counter) {
            long now = System.currentTimeMillis();
            if (now - counter.startedAt >= windowMs) { counter.startedAt = now; counter.count = 0; }
            if (++counter.count > max) return false;
        }
        if (limits.size() > 128) limits.entrySet().removeIf(entry -> nowMinus(entry.getValue().startedAt) > 300_000);
        return true;
    }

    private long nowMinus(long timestamp) { return System.currentTimeMillis() - timestamp; }

    private void reject(SSLSocket socket, String reason) {
        try {
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            WireProtocol.write(output, WireProtocol.ROOM_REJECT,
                    WireProtocol.payload(out -> WireProtocol.writeString(out, reason, 64)));
        } catch (Exception ignored) { }
        closeQuietly(socket);
    }

    private static void ensureConsumed(DataInputStream input) throws IOException {
        if (input.available() != 0) throw new IOException("Unexpected trailing bytes");
    }

    private String generateRoomCode() {
        StringBuilder result = new StringBuilder(6);
        for (int i = 0; i < 6; i++) result.append(ROOM_CODE_CHARS.charAt(secureRandom.nextInt(ROOM_CODE_CHARS.length())));
        return result.toString();
    }

    private String randomHex(int bytes) {
        byte[] value = new byte[bytes];
        secureRandom.nextBytes(value);
        StringBuilder result = new StringBuilder(bytes * 2);
        for (byte item : value) result.append(String.format(Locale.ROOT, "%02X", item & 0xff));
        return result.toString();
    }

    private static String normalizeRoomCode(String value) {
        if (value == null) return null;
        String code = value.trim().toUpperCase(Locale.ROOT);
        return code.matches("[A-Z0-9]{6}") ? code : null;
    }

    private static String cleanName(String value) {
        if (value == null) return "Player";
        StringBuilder clean = new StringBuilder();
        for (int i = 0; i < value.length() && clean.length() < 20; i++) {
            char character = value.charAt(i);
            if (!Character.isISOControl(character) && character != '|') clean.append(character);
        }
        String result = clean.toString().trim();
        return result.isEmpty() ? "Player" : result;
    }

    private static String safeMessage(Exception failure) {
        String message = failure.getMessage();
        if (message == null) return failure.getClass().getSimpleName();
        return message.length() > 100 ? message.substring(0, 100) : message;
    }

    private void closeDiscoveryAgent() {
        DiscoveryAgent agent = discoveryAgent;
        discoveryAgent = null;
        if (agent != null) agent.close();
    }

    private void closeRoom() {
        closed = true;
        if (tickExecutor != null) { tickExecutor.shutdownNow(); tickExecutor = null; }
        if (hosting) {
            byte[] closedPayload = new byte[0];
            broadcast(WireProtocol.ROOM_CLOSED, closedPayload);
        }
        closeDiscoveryAgent();
        if (serverSocket != null) { try { serverSocket.close(); } catch (IOException ignored) { } serverSocket = null; }
        if (clientPeer != null) { closeQuietly(clientPeer.socket); clientPeer = null; }
        for (PendingConnection request : pending.values()) {
            request.approved = false;
            request.decision.countDown();
            closeQuietly(request.socket);
        }
        pending.clear();
        synchronized (stateLock) {
            for (Member member : members.values()) if (member.peer != null) closeQuietly(member.peer.socket);
            members.clear();
        }
        if (tlsAlias != null) {
            try {
                KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
                keyStore.load(null);
                if (keyStore.containsAlias(tlsAlias)) keyStore.deleteEntry(tlsAlias);
            } catch (Exception ignored) { }
        }
        hosting = false;
        inMatch = false;
        resultsShown = false;
        roomStage = "CLOSED";
        roomCode = null;
        roomId = null;
        gameId = null;
        localPlayerId = null;
        tlsAlias = null;
        hostFingerprint = null;
        game = null;
        discovered.clear();
    }

    @Override public void close() { closeRoom(); }

    private final class DiscoveryAgent implements AutoCloseable {
        private final boolean host;
        private volatile boolean running = true;
        private DatagramSocket socket;
        private WifiManager.MulticastLock multicastLock;
        private Thread thread;

        DiscoveryAgent(boolean host) { this.host = host; }

        void start() throws IOException {
            WifiManager wifi = (WifiManager) appContext.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                multicastLock = wifi.createMulticastLock("LanArcadeDiscovery");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
            }
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.setBroadcast(true);
            socket.bind(new InetSocketAddress(DISCOVERY_PORT));
            thread = new Thread(this::loop, host ? "lan-room-advertiser" : "lan-room-discovery");
            thread.start();
        }

        void sendQuery(String code) {
            DatagramSocket current = socket;
            if (!running || current == null) return;
            sendBroadcast(current, "LAR1|QUERY|" + code);
        }

        private void loop() {
            byte[] buffer = new byte[MAX_DISCOVERY_BYTES];
            long lastSend = 0;
            while (running) {
                long now = System.currentTimeMillis();
                if (now - lastSend >= ADVERTISE_INTERVAL_MS) {
                    if (host) sendBroadcast(socket, advertisement("AD"));
                    else sendBroadcast(socket, "LAR1|DISCOVER");
                    lastSend = now;
                }
                try {
                    socket.setSoTimeout(500);
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);
                    if (packet.getLength() <= 0 || packet.getLength() > MAX_DISCOVERY_BYTES) continue;
                    String message = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
                    if (host) handleHostDiscoveryMessage(message, packet);
                    else handleClientDiscoveryMessage(message, packet);
                } catch (SocketTimeoutException ignored) {
                    expireRooms();
                } catch (Exception failure) {
                    if (running) postError("LAN room discovery stopped.");
                    break;
                }
            }
        }

        private void handleHostDiscoveryMessage(String message, DatagramPacket source) {
            if ("LAR1|DISCOVER".equals(message)) {
                sendUnicast(socket, advertisement("AD"), source.getAddress(), source.getPort());
                return;
            }
            String[] fields = message.split("\\|", -1);
            if (fields.length != 3 || !"LAR1".equals(fields[0]) || !"QUERY".equals(fields[1])) return;
            if (!allow(queryRates, source.getAddress().getHostAddress(), 10, 60_000)) return;
            String code = normalizeRoomCode(fields[2]);
            if (code != null && code.equals(roomCode)) sendUnicast(socket, advertisement("ROOM"), source.getAddress(), source.getPort());
        }

        private void handleClientDiscoveryMessage(String message, DatagramPacket source) {
            if (message.length() > MAX_DISCOVERY_BYTES) return;
            String[] fields = message.split("\\|", -1);
            if (fields.length != 9 || !"LAR1".equals(fields[0]) || !("AD".equals(fields[1]) || "ROOM".equals(fields[1]))) return;
            try {
                String id = fields[2];
                String game = fields[3];
                String name = cleanName(fields[4]);
                int port = Integer.parseInt(fields[5]);
                String fingerprint = fields[6];
                int count = Integer.parseInt(fields[7]);
                int capacity = Integer.parseInt(fields[8]);
                if (!id.matches("[A-F0-9]{12}") || !game.matches("[a-z0-9_]{1,32}") ||
                        !fingerprint.matches("[A-F0-9]{64}") || port < 1 || port > 65535 ||
                        count < 1 || capacity < 2 || count > capacity || capacity > 8 ||
                        !(source.getAddress() instanceof Inet4Address) || !source.getAddress().isSiteLocalAddress()) return;
                discovered.put(id, new RoomDescriptor(id, game, name, port, fingerprint, source.getAddress(), count, capacity,
                        System.currentTimeMillis()));
                postRoomsChanged();
            } catch (NumberFormatException invalid) {
                // Malformed adverts are discarded before entering UI state.
            }
        }

        private void expireRooms() {
            long now = System.currentTimeMillis();
            boolean changed = discovered.entrySet().removeIf(entry -> now - entry.getValue().lastSeenAt > ROOM_TTL_MS);
            if (changed) postRoomsChanged();
        }

        @Override public void close() {
            running = false;
            if (socket != null) socket.close();
            if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        }
    }

    private String advertisement(String kind) {
        int count;
        synchronized (stateLock) { count = members.size(); }
        return "LAR1|" + kind + "|" + roomId + "|" + gameId + "|" + cleanName(localDisplayName) + "|" + serverPort + "|" + hostFingerprint + "|" + count + "|" + maxPlayers;
    }

    private static void sendBroadcast(DatagramSocket socket, String value) {
        if (socket == null || socket.isClosed()) return;
        byte[] data = value.getBytes(StandardCharsets.UTF_8);
        if (data.length > MAX_DISCOVERY_BYTES) return;
        try {
            DatagramPacket packet = new DatagramPacket(data, data.length, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT);
            socket.send(packet);
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return;
            while (interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback()) continue;
                for (java.net.InterfaceAddress address : network.getInterfaceAddresses()) {
                    InetAddress broadcastAddress = address.getBroadcast();
                    if (broadcastAddress != null) socket.send(new DatagramPacket(data, data.length, broadcastAddress, DISCOVERY_PORT));
                }
            }
        } catch (Exception ignored) { }
    }

    private static void sendUnicast(DatagramSocket socket, String value, InetAddress address, int port) {
        if (socket == null || socket.isClosed() || address == null || port < 1 || port > 65535) return;
        byte[] data = value.getBytes(StandardCharsets.UTF_8);
        if (data.length > MAX_DISCOVERY_BYTES) return;
        try { socket.send(new DatagramPacket(data, data.length, address, port)); } catch (IOException ignored) { }
    }

    private static void closeQuietly(SSLSocket socket) { if (socket != null) try { socket.close(); } catch (IOException ignored) { } }

    public interface Listener {
        default void onRoomsUpdated(List<RoomDescriptor> rooms) { }
        default void onLobbyUpdated(List<PlayerEntry> players, String code, String stage) { }
        default void onJoinRequest(PendingJoin request) { }
        default void onPairingCode(String code) { }
        default void onConnected() { }
        default void onMatchStarted(GameModule module) { }
        default void onGameFrame(GameModule module) { }
        default void onMatchOver(MatchResult result) { }
        default void onRoomClosed(String reason) { }
        default void onError(String message) { }
    }

    public static final class RoomDescriptor {
        public final String roomId, gameId, hostName, fingerprint;
        public final int port, currentPlayers, maxPlayers;
        public final InetAddress address;
        final long lastSeenAt;
        RoomDescriptor(String roomId, String gameId, String hostName, int port, String fingerprint, InetAddress address,
                       int currentPlayers, int maxPlayers, long lastSeenAt) {
            this.roomId = roomId; this.gameId = gameId; this.hostName = hostName; this.port = port;
            this.fingerprint = fingerprint; this.address = address; this.currentPlayers = currentPlayers;
            this.maxPlayers = maxPlayers; this.lastSeenAt = lastSeenAt;
        }
        public String addressText() { return address.getHostAddress(); }
    }

    public static final class PlayerEntry {
        public final String id, displayName;
        public final boolean ready, host;
        PlayerEntry(String id, String displayName, boolean ready, boolean host) {
            this.id = id; this.displayName = displayName; this.ready = ready; this.host = host;
        }
    }

    public static final class PendingJoin {
        public final String id, displayName, address, safetyCode;
        PendingJoin(String id, String displayName, String address, String safetyCode) {
            this.id = id; this.displayName = displayName; this.address = address; this.safetyCode = safetyCode;
        }
    }

    private static final class Member {
        final String id, name;
        final boolean host;
        final Peer peer;
        boolean ready, connected;
        Member(String id, String name, boolean host, boolean ready, Peer peer) {
            this.id = id; this.name = name; this.host = host; this.ready = ready; this.connected = true; this.peer = peer;
        }
    }

    private static final class Peer {
        final SSLSocket socket;
        final DataInputStream input;
        final DataOutputStream output;
        final Object writeLock = new Object();
        volatile int lastAckTick;
        volatile int lastInputSequence = -1;
        volatile long lastPongAt = System.currentTimeMillis();
        long inputWindowStart = System.currentTimeMillis();
        int inputCount;
        long messageWindowStart = System.currentTimeMillis();
        int messageCount;
        Peer(SSLSocket socket, DataInputStream input, DataOutputStream output) {
            this.socket = socket; this.input = input; this.output = output;
        }
        void countInboundMessage() throws IOException {
            long now = System.currentTimeMillis();
            if (now - messageWindowStart >= 1_000) {
                messageWindowStart = now;
                messageCount = 0;
            }
            if (++messageCount > 100) throw new IOException("Peer message rate limit exceeded");
        }
        void send(int type, byte[] payload) throws IOException { synchronized (writeLock) { WireProtocol.write(output, type, payload); } }
    }

    private static final class PendingConnection {
        final String id, name, address, safetyCode;
        final SSLSocket socket;
        final DataInputStream input;
        final DataOutputStream output;
        final CountDownLatch decision = new CountDownLatch(1);
        volatile Boolean approved;
        PendingConnection(String id, String name, SSLSocket socket, DataInputStream input, DataOutputStream output, String address, String safetyCode) {
            this.id = id; this.name = name; this.socket = socket; this.input = input; this.output = output;
            this.address = address; this.safetyCode = safetyCode;
        }
        PendingJoin publicView() { return new PendingJoin(id, name, address, safetyCode); }
    }

    private static final class RateWindow {
        long startedAt = System.currentTimeMillis();
        int count;
    }
}
