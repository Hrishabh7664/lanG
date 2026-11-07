package com.langamestudio.lanpartyarcade;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.langame.core.GameModule;
import com.langame.core.MatchResult;
import com.langame.games.inkarena.InkArenaModule;
import com.langamestudio.lanpartyarcade.network.LanRoomService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int BG = Color.rgb(12, 20, 33);
    private static final int PANEL = Color.rgb(25, 38, 56);
    private static final int ACCENT = Color.rgb(37, 189, 156);
    private static final int TEXT = Color.rgb(238, 245, 250);
    private static final int SUBTLE = Color.rgb(162, 180, 196);

    private enum Page { LANDING, GAMES, JOIN, CONNECTING, LOBBY, MATCH, RESULTS }

    private final ExecutorService background = Executors.newSingleThreadExecutor();
    private GameModuleRegistry gameRegistry;
    private LanRoomService roomService;
    private LinearLayout root;
    private LinearLayout roomRows;
    private EditText roomCodeInput;
    private TextView pairingCodeView;
    private Page page = Page.LANDING;
    private GameModule selectedGame;
    private GameModule activeGame;
    private InkArenaView inkArenaView;
    private boolean hosting;
    private boolean localReady;
    private String displayName;
    private String roomCode = "";
    private String currentRoomStage = "CLOSED";
    private List<LanRoomService.PlayerEntry> players = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().requestFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        displayName = getPreferences(MODE_PRIVATE).getString("displayName", "Player");
        gameRegistry = new GameModuleRegistry();
        roomService = new LanRoomService(this, gameRegistry, new RoomListener());
        showLanding();
    }

    private void showLanding() {
        page = Page.LANDING;
        root = pageRoot();
        root.setGravity(Gravity.CENTER);
        addSpace(root, 24);
        TextView title = text("LAN ARCADE", 38, TEXT, true);
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap());
        TextView subtitle = text("Play together on the same Wi-Fi", 17, SUBTLE, false);
        subtitle.setGravity(Gravity.CENTER);
        addSpace(root, 14);
        root.addView(subtitle, matchWrap());

        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setTextColor(TEXT);
        name.setHintTextColor(SUBTLE);
        name.setHint("Player name");
        name.setText(displayName);
        name.setGravity(Gravity.CENTER);
        name.setTextSize(18);
        name.setBackgroundColor(PANEL);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(dp(300), dp(54));
        nameParams.topMargin = dp(28);
        nameParams.bottomMargin = dp(16);
        root.addView(name, nameParams);

        Button host = button("HOST", true);
        Button join = button("JOIN", false);
        root.addView(host, buttonParams());
        root.addView(join, buttonParams());
        host.setOnClickListener(view -> {
            saveName(name.getText().toString());
            showGameSelect();
        });
        join.setOnClickListener(view -> {
            saveName(name.getText().toString());
            showJoin();
        });
        setContentView(root);
    }

    private void showGameSelect() {
        page = Page.GAMES;
        root = pageRoot();
        addBackHeader(root, "Choose a game", this::showLanding);
        TextView hint = text("Pick a game to host", 16, SUBTLE, false);
        root.addView(hint, matchWrap());
        ScrollView scroll = new ScrollView(this);
        LinearLayout cards = new LinearLayout(this);
        cards.setOrientation(LinearLayout.VERTICAL);
        cards.setPadding(dp(20), dp(10), dp(20), dp(20));
        scroll.addView(cards);
        for (GameModule module : gameRegistry.all()) {
            boolean available = gameRegistry.isAvailable(module.id());
            LinearLayout card = card();
            TextView name = text(module.displayName(), 23, TEXT, true);
            TextView info = text(module.minPlayers() + "–" + module.maxPlayers() + " players  ·  " +
                    (available ? "Ready to play" : "Coming soon"), 15, available ? ACCENT : SUBTLE, false);
            TextView description = text(gameDescription(module.id()), 14, SUBTLE, false);
            card.addView(name, matchWrap());
            addSpace(card, 4);
            card.addView(info, matchWrap());
            addSpace(card, 8);
            card.addView(description, matchWrap());
            cards.addView(card, cardParams());
            card.setOnClickListener(view -> {
                if (!available) {
                    new AlertDialog.Builder(this).setTitle(module.displayName())
                            .setMessage("Not available in this build. Choose Ink Arena to start a match.")
                            .setPositiveButton("OK", null).show();
                } else {
                    selectedGame = module;
                    createRoom();
                }
            });
        }
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    private String gameDescription(String id) {
        if ("ink_arena".equals(id)) return "Capture territory, close your trail, and avoid crossing an opponent's ink.";
        if ("jetfire".equals(id)) return "Side-scrolling arena shooter with jetpacks and platforms.";
        return "A LAN multiplayer racing game.";
    }

    private void createRoom() {
        page = Page.CONNECTING;
        hosting = true;
        root = pageRoot();
        TextView status = text("Creating secure room…", 22, TEXT, true);
        status.setGravity(Gravity.CENTER);
        root.setGravity(Gravity.CENTER);
        root.addView(status, matchWrap());
        setContentView(root);
        background.execute(() -> {
            try {
                String code = roomService.hostRoom(selectedGame, displayName);
                runOnUiThread(() -> {
                    roomCode = code;
                    localReady = true;
                    showLobby();
                });
            } catch (Exception failure) {
                runOnUiThread(() -> showError("Could not create room: " + safeMessage(failure)));
            }
        });
    }

    private void showJoin() {
        page = Page.JOIN;
        hosting = false;
        root = pageRoot();
        addBackHeader(root, "Join a room", this::showLanding);
        TextView instruction = text("Enter the room code shared by the host. Both phones must be on the same Wi-Fi.", 16, SUBTLE, false);
        root.addView(instruction, matchWrap());

        roomCodeInput = new EditText(this);
        roomCodeInput.setSingleLine(true);
        roomCodeInput.setTextColor(TEXT);
        roomCodeInput.setHintTextColor(SUBTLE);
        roomCodeInput.setHint("6-character room code");
        roomCodeInput.setGravity(Gravity.CENTER);
        roomCodeInput.setTextSize(24);
        roomCodeInput.setLetterSpacing(0.12f);
        roomCodeInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        roomCodeInput.setBackgroundColor(PANEL);
        LinearLayout.LayoutParams codeParams = new LinearLayout.LayoutParams(dp(320), dp(58));
        codeParams.topMargin = dp(18);
        root.addView(roomCodeInput, codeParams);

        Button find = button("FIND ROOM", true);
        LinearLayout.LayoutParams findParams = buttonParams();
        findParams.topMargin = dp(10);
        root.addView(find, findParams);
        find.setOnClickListener(view -> {
            String code = roomCodeInput.getText().toString().trim().toUpperCase(java.util.Locale.ROOT);
            if (!code.matches("[A-Z0-9]{6}")) {
                showToast("Enter all six letters or numbers.");
                return;
            }
            roomService.queryRoomCode(code);
            showToast("Searching this Wi-Fi network…");
        });

        TextView roomsTitle = text("ROOMS FOUND", 14, ACCENT, true);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38));
        titleParams.topMargin = dp(10);
        root.addView(roomsTitle, titleParams);
        ScrollView scroll = new ScrollView(this);
        roomRows = new LinearLayout(this);
        roomRows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(roomRows);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        pairingCodeView = text("", 18, ACCENT, true);
        pairingCodeView.setGravity(Gravity.CENTER);
        root.addView(pairingCodeView, matchWrap());
        setContentView(root);

        try {
            roomService.startDiscovery();
            renderRoomRows(roomService.discoveredRooms());
        } catch (IOException failure) {
            showError("Room discovery could not start. Check that Wi-Fi is on.");
        }
    }

    private void showConnecting(LanRoomService.RoomDescriptor room) {
        page = Page.CONNECTING;
        root = pageRoot();
        root.setGravity(Gravity.CENTER);
        TextView status = text("Connecting to " + room.hostName + "…", 22, TEXT, true);
        status.setGravity(Gravity.CENTER);
        root.addView(status, matchWrap());
        pairingCodeView = text("After connection, compare the six-character safety code with the host before they admit you.", 18, ACCENT, true);
        pairingCodeView.setGravity(Gravity.CENTER);
        root.addView(pairingCodeView, matchWrap());
        setContentView(root);
        String code = roomCodeInput == null ? "" : roomCodeInput.getText().toString();
        roomService.joinRoom(room.roomId, code, displayName);
    }

    private void renderRoomRows(List<LanRoomService.RoomDescriptor> rooms) {
        if (roomRows == null || page != Page.JOIN) return;
        roomRows.removeAllViews();
        if (rooms.isEmpty()) {
            TextView empty = text("No matching rooms yet. Check the code and try FIND ROOM again.", 15, SUBTLE, false);
            roomRows.addView(empty, matchWrap());
            return;
        }
        for (LanRoomService.RoomDescriptor room : rooms) {
            LinearLayout row = card();
            TextView host = text(room.hostName + "  ·  " + room.currentPlayers + "/" + room.maxPlayers, 18, TEXT, true);
            TextView game = text(gameTitle(room.gameId) + "  ·  " + room.addressText(), 14, SUBTLE, false);
            row.addView(host, matchWrap());
            addSpace(row, 4);
            row.addView(game, matchWrap());
            row.setOnClickListener(view -> {
                if (roomCodeInput == null || !roomCodeInput.getText().toString().trim().matches("(?i)[A-Z0-9]{6}")) {
                    showToast("Enter the six-character room code first.");
                    return;
                }
                roomService.joinRoom(room.roomId, roomCodeInput.getText().toString(), displayName);
                showConnecting(room);
            });
            roomRows.addView(row, cardParams());
        }
    }

    private String gameTitle(String id) {
        GameModule game = gameRegistry.get(id);
        return game == null ? id : game.displayName();
    }

    private void showLobby() {
        page = Page.LOBBY;
        root = pageRoot();
        addBackHeader(root, "Room lobby", () -> confirmLeave("Leave this room?"));
        TextView codeLabel = text(hosting ? "SHARE THIS ROOM CODE" : "ROOM CODE", 14, ACCENT, true);
        root.addView(codeLabel, matchWrap());
        TextView code = text(roomCode, 32, TEXT, true);
        code.setGravity(Gravity.CENTER);
        code.setLetterSpacing(0.16f);
        LinearLayout.LayoutParams codeLayout = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        root.addView(code, codeLayout);
        if (hosting) {
            Button copy = button("COPY CODE", false);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(190), dp(44));
            cp.gravity = Gravity.CENTER_HORIZONTAL;
            root.addView(copy, cp);
            copy.setOnClickListener(view -> {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(ClipData.newPlainText("LAN room code", roomCode));
                showToast("Room code copied.");
            });
        }
        TextView gameTitle = text(selectedGame != null ? selectedGame.displayName() : gameTitle(activeGame != null ? activeGame.id() : "ink_arena"), 20, TEXT, true);
        LinearLayout.LayoutParams gameParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        gameParams.topMargin = dp(8);
        root.addView(gameTitle, gameParams);
        TextView playersTitle = text("PLAYERS", 14, ACCENT, true);
        root.addView(playersTitle, matchWrap());
        ScrollView scroll = new ScrollView(this);
        LinearLayout roster = new LinearLayout(this);
        roster.setOrientation(LinearLayout.VERTICAL);
        for (LanRoomService.PlayerEntry player : players) {
            TextView line = text((player.host ? "★  " : "•  ") + player.displayName + "     " + (player.ready ? "READY" : "NOT READY"), 17, player.ready ? TEXT : SUBTLE, false);
            line.setPadding(dp(12), dp(9), dp(12), dp(9));
            roster.addView(line, matchWrap());
        }
        TextView wait = text(hosting ? "Waiting for players on this Wi-Fi…" : "Wait for the host to start the match.", 14, SUBTLE, false);
        wait.setPadding(0, dp(6), 0, dp(6));
        roster.addView(wait, matchWrap());
        scroll.addView(roster);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER);
        if (!hosting) {
            Button ready = button(localReady ? "NOT READY" : "READY", false);
            actions.addView(ready, buttonParams());
            ready.setOnClickListener(view -> {
                localReady = !localReady;
                roomService.setReady(localReady);
                showLobby();
            });
        }
        if (hosting) {
            Button start = button("START MATCH", true);
            boolean canStart = players.size() >= (selectedGame == null ? 2 : selectedGame.minPlayers()) &&
                    players.stream().allMatch(player -> player.ready) && "WAITING".equals(currentRoomStage);
            start.setEnabled(canStart);
            start.setAlpha(canStart ? 1f : 0.45f);
            actions.addView(start, buttonParams());
            start.setOnClickListener(view -> roomService.startMatch());
            Button close = button("CLOSE ROOM", false);
            actions.addView(close, buttonParams());
            close.setOnClickListener(view -> confirmLeave("Close this room and return to the start screen?"));
        } else {
            Button leave = button("LEAVE", false);
            actions.addView(leave, buttonParams());
            leave.setOnClickListener(view -> confirmLeave("Leave this room?"));
        }
        root.addView(actions, matchWrap());
        setContentView(root);
    }

    private void showMatch(GameModule module) {
        page = Page.MATCH;
        activeGame = module;
        if (!(module instanceof InkArenaModule)) {
            showError("This game is not available in this build.");
            return;
        }
        root = pageRoot();
        inkArenaView = new InkArenaView(this, (InkArenaModule) module, roomService,
                roomService.localPlayerId(), hosting, () -> runOnUiThread(() -> confirmLeave("End this match and leave the room?")));
        root.addView(inkArenaView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    private void showResults(MatchResult result) {
        page = Page.RESULTS;
        root = pageRoot();
        root.setGravity(Gravity.CENTER);
        TextView title = text("MATCH COMPLETE", 30, TEXT, true);
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap());
        TextView reason = text(result.reason(), 15, SUBTLE, false);
        reason.setGravity(Gravity.CENTER);
        root.addView(reason, matchWrap());
        List<Map.Entry<String, Integer>> scores = new ArrayList<>(result.scores().entrySet());
        scores.sort(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()));
        for (Map.Entry<String, Integer> score : scores) {
            String name = players.stream().filter(player -> player.id.equals(score.getKey())).map(player -> player.displayName).findFirst().orElse(score.getKey());
            TextView line = text(name + "  —  " + score.getValue() + " cells", 20, score.getKey().equals(roomService.localPlayerId()) ? ACCENT : TEXT, true);
            line.setGravity(Gravity.CENTER);
            root.addView(line, matchWrap());
        }
        Button leave = button(hosting ? "RETURN TO LOBBY" : "LEAVE ROOM", true);
        LinearLayout.LayoutParams params = buttonParams();
        params.topMargin = dp(20);
        root.addView(leave, params);
        leave.setOnClickListener(view -> {
            if (hosting) {
                roomService.returnToLobby();
                showLobby();
            } else {
                confirmLeave("Leave this room?");
            }
        });
        setContentView(root);
    }

    private void confirmLeave(String message) {
        new AlertDialog.Builder(this).setMessage(message)
                .setNegativeButton("Stay", null)
                .setPositiveButton("Leave", (dialog, which) -> {
                    roomService.leaveRoom();
                    hosting = false;
                    selectedGame = null;
                    activeGame = null;
                    players.clear();
                    showLanding();
                }).show();
    }

    private void showError(String message) {
        showToast(message);
        if (hosting) roomService.leaveRoom();
        hosting = false;
        showLanding();
    }

    private void saveName(String input) {
        String clean = input == null ? "" : input.trim().replaceAll("[\\p{Cntrl}|]", "");
        if (clean.isEmpty()) clean = "Player";
        if (clean.length() > 20) clean = clean.substring(0, 20);
        displayName = clean;
        getPreferences(MODE_PRIVATE).edit().putString("displayName", displayName).apply();
    }

    private LinearLayout pageRoot() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(BG);
        layout.setPadding(dp(24), dp(14), dp(24), dp(18));
        return layout;
    }

    private void addBackHeader(LinearLayout target, String title, Runnable back) {
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button backButton = button("‹", false);
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(dp(52), dp(44));
        header.addView(backButton, backParams);
        backButton.setOnClickListener(view -> back.run());
        TextView heading = text(title, 24, TEXT, true);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(0, dp(48), 1);
        headingParams.leftMargin = dp(12);
        header.addView(heading, headingParams);
        target.addView(header, matchWrap());
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        card.setBackgroundColor(PANEL);
        return card;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(12);
        return params;
    }

    private Button button(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(15);
        button.setTextColor(primary ? BG : TEXT);
        button.setAllCaps(false);
        button.setBackgroundColor(primary ? ACCENT : PANEL);
        return button;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(220), dp(54));
        params.gravity = Gravity.CENTER_HORIZONTAL;
        params.setMargins(dp(6), dp(7), dp(6), dp(7));
        return params;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        if (bold) text.setTypeface(null, android.graphics.Typeface.BOLD);
        return text;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void addSpace(LinearLayout target, int heightDp) {
        View space = new View(this);
        target.addView(space, new LinearLayout.LayoutParams(1, dp(heightDp)));
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void showToast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }

    private static String safeMessage(Exception failure) {
        String message = failure.getMessage();
        if (message == null || message.trim().isEmpty()) return failure.getClass().getSimpleName();
        return message.length() > 100 ? message.substring(0, 100) : message;
    }

    @Override
    public void onBackPressed() {
        if (page == Page.LANDING) super.onBackPressed();
        else if (page == Page.GAMES || page == Page.JOIN) showLanding();
        else confirmLeave("Leave this room?");
    }

    @Override
    protected void onDestroy() {
        if (roomService != null) roomService.close();
        background.shutdownNow();
        super.onDestroy();
    }

    private final class RoomListener implements LanRoomService.Listener {
        @Override public void onRoomsUpdated(List<LanRoomService.RoomDescriptor> rooms) {
            renderRoomRows(rooms);
        }

        @Override public void onLobbyUpdated(List<LanRoomService.PlayerEntry> updated, String code, String stage) {
            players = new ArrayList<>(updated);
            roomCode = code;
            currentRoomStage = stage;
            if (page == Page.LOBBY) showLobby();
        }

        @Override public void onJoinRequest(LanRoomService.PendingJoin request) {
            if (!hosting || isFinishing()) return;
            runOnUiThread(() -> new AlertDialog.Builder(MainActivity.this)
                    .setTitle("Player wants to join")
                    .setMessage(request.displayName + " at " + request.address + "\n\nSafety code: " + request.safetyCode +
                            "\n\nCompare this code with the joining phone. Accept only if they match.")
                    .setNegativeButton("Decline", (dialog, which) -> roomService.approveJoin(request.id, false))
                    .setPositiveButton("Accept", (dialog, which) -> roomService.approveJoin(request.id, true))
                    .setOnCancelListener(dialog -> roomService.approveJoin(request.id, false))
                    .show());
        }

        @Override public void onPairingCode(String code) {
            runOnUiThread(() -> {
                if (pairingCodeView != null) pairingCodeView.setText("Compare this six-character safety code with the host:  " + code);
            });
        }

        @Override public void onConnected() {
            runOnUiThread(() -> {
                showLobby();
                showToast("Secure connection established. Wait for the host to admit you.");
            });
        }

        @Override public void onMatchStarted(GameModule module) {
            runOnUiThread(() -> showMatch(module));
        }

        @Override public void onGameFrame(GameModule module) {
            runOnUiThread(() -> { if (inkArenaView != null && page == Page.MATCH) inkArenaView.invalidate(); });
        }

        @Override public void onMatchOver(MatchResult result) {
            runOnUiThread(() -> showResults(result));
        }

        @Override public void onRoomClosed(String reason) {
            runOnUiThread(() -> {
                roomService.close();
                hosting = false;
                showLanding();
                showToast(reason);
            });
        }

        @Override public void onError(String message) {
            runOnUiThread(() -> showToast(message));
        }
    }
}
