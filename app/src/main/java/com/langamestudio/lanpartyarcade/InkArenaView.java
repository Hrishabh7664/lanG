package com.langamestudio.lanpartyarcade;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import com.langame.games.inkarena.InkArenaModule;
import com.langamestudio.lanpartyarcade.network.LanRoomService;

import java.util.Locale;

/** Lightweight Canvas renderer and four-button touch controller for Ink Arena. */
public final class InkArenaView extends View {
    private static final int[] COLORS = {
            0xff243348,
            0xfffa5b62,
            0xff3ca9ff,
            0xff51d18d,
            0xffffc247,
            0xffbd7bff,
            0xff42d7d0
    };

    private final InkArenaModule game;
    private final LanRoomService roomService;
    private final String localPlayerId;
    private final boolean hosting;
    private final Runnable leaveAction;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF board = new RectF();
    private final RectF upButton = new RectF();
    private final RectF downButton = new RectF();
    private final RectF leftButton = new RectF();
    private final RectF rightButton = new RectF();
    private final RectF leaveButton = new RectF();
    private float density;

    public InkArenaView(Context context, InkArenaModule game, LanRoomService roomService,
                        String localPlayerId, boolean hosting, Runnable leaveAction) {
        super(context);
        this.game = game;
        this.roomService = roomService;
        this.localPlayerId = localPlayerId;
        this.hosting = hosting;
        this.leaveAction = leaveAction;
        density = getResources().getDisplayMetrics().density;
        setFocusable(true);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(0xff0c1421);
        float width = getWidth();
        float height = getHeight();
        float padding = 18 * density;
        float headerHeight = 54 * density;
        float availableHeight = Math.max(100 * density, height - headerHeight - padding * 2);
        float sideWidth = Math.max(112 * density, width * 0.26f);
        float boardSize = Math.min(availableHeight, Math.max(100 * density, width - sideWidth - padding * 3));
        boardSize = Math.min(boardSize, height * 0.88f);
        float boardLeft = padding + Math.max(0, (width - boardSize - sideWidth - padding * 3) * 0.5f);
        float boardTop = headerHeight + (availableHeight - boardSize) * 0.5f;
        board.set(boardLeft, boardTop, boardLeft + boardSize, boardTop + boardSize);

        paint.setColor(0xfff2f6fa);
        paint.setTextSize(19 * density);
        paint.setFakeBoldText(true);
        canvas.drawText("INK ARENA", padding, 29 * density, paint);
        paint.setFakeBoldText(false);
        paint.setTextSize(13 * density);
        paint.setColor(0xffa2b4c4);
        canvas.drawText(hosting ? "HOST · AUTHORITATIVE" : "CONNECTED TO HOST", padding, 47 * density, paint);

        paint.setColor(0xff18263a);
        canvas.drawRoundRect(board, 8 * density, 8 * density, paint);
        InkArenaModule.RenderState state = game.renderState();
        float cell = board.width() / InkArenaModule.WIDTH;
        for (int y = 0; y < InkArenaModule.HEIGHT; y++) {
            int row = y * InkArenaModule.WIDTH;
            for (int x = 0; x < InkArenaModule.WIDTH; x++) {
                int index = row + x;
                int owner = state.owners[index] & 0xff;
                int trail = state.trails[index] & 0xff;
                if (owner == 0 && trail == 0) continue;
                int slot = trail != 0 ? trail : owner;
                int color = COLORS[Math.min(slot, COLORS.length - 1)];
                paint.setColor(trail != 0 ? color : tint(color, 0.48f));
                float left = board.left + x * cell;
                float top = board.top + y * cell;
                canvas.drawRect(left, top, left + cell + 0.3f, top + cell + 0.3f, paint);
            }
        }

        boolean localAlive = false;
        int localTerritory = 0;
        for (InkArenaModule.PlayerView player : state.players) {
            paint.setColor(COLORS[Math.min(player.slot, COLORS.length - 1)]);
            float cx = board.left + (player.x + 0.5f) * cell;
            float cy = board.top + (player.y + 0.5f) * cell;
            canvas.drawCircle(cx, cy, Math.max(3 * density, cell * 0.42f), paint);
            if (!player.alive) {
                paint.setColor(0xff121c29);
                canvas.drawCircle(cx, cy, Math.max(2 * density, cell * 0.2f), paint);
            }
            if (player.id.equals(localPlayerId)) {
                localAlive = player.alive;
                int slot = player.slot;
                for (byte owner : state.owners) if ((owner & 0xff) == slot) localTerritory++;
            }
        }

        float controlsLeft = board.right + padding;
        float controlsRight = width - padding;
        float controlAreaWidth = Math.max(96 * density, controlsRight - controlsLeft);
        float buttonSize = Math.min(58 * density, controlAreaWidth / 3.2f);
        float gap = 5 * density;
        float centerX = controlsLeft + controlAreaWidth * 0.5f;
        float centerY = board.centerY();
        upButton.set(centerX - buttonSize * 0.5f, centerY - buttonSize * 1.5f - gap,
                centerX + buttonSize * 0.5f, centerY - buttonSize * 0.5f - gap);
        downButton.set(centerX - buttonSize * 0.5f, centerY + buttonSize * 0.5f + gap,
                centerX + buttonSize * 0.5f, centerY + buttonSize * 1.5f + gap);
        leftButton.set(centerX - buttonSize * 1.5f - gap, centerY - buttonSize * 0.5f,
                centerX - buttonSize * 0.5f - gap, centerY + buttonSize * 0.5f);
        rightButton.set(centerX + buttonSize * 0.5f + gap, centerY - buttonSize * 0.5f,
                centerX + buttonSize * 1.5f + gap, centerY + buttonSize * 0.5f);
        drawDirectionButton(canvas, upButton, "↑");
        drawDirectionButton(canvas, downButton, "↓");
        drawDirectionButton(canvas, leftButton, "←");
        drawDirectionButton(canvas, rightButton, "→");

        paint.setColor(0xffa2b4c4);
        paint.setTextSize(13 * density);
        canvas.drawText("TERRITORY", controlsLeft, board.bottom - 36 * density, paint);
        paint.setColor(localAlive ? 0xfff2f6fa : 0xffff7777);
        paint.setTextSize(18 * density);
        paint.setFakeBoldText(true);
        canvas.drawText(String.format(Locale.ROOT, "%d cells%s", localTerritory, localAlive ? "" : " · OUT"), controlsLeft, board.bottom - 13 * density, paint);
        paint.setFakeBoldText(false);

        float leaveWidth = 68 * density;
        leaveButton.set(width - leaveWidth - padding, padding, width - padding, padding + 34 * density);
        paint.setColor(0xff26384d);
        canvas.drawRoundRect(leaveButton, 8 * density, 8 * density, paint);
        paint.setColor(0xfff2f6fa);
        paint.setTextSize(13 * density);
        canvas.drawText("LEAVE", leaveButton.left + 10 * density, leaveButton.centerY() + 4 * density, paint);

        postInvalidateDelayed(50);
    }

    private void drawDirectionButton(Canvas canvas, RectF area, String label) {
        paint.setColor(0xff26384d);
        canvas.drawRoundRect(area, 10 * density, 10 * density, paint);
        paint.setColor(0xfff2f6fa);
        paint.setTextSize(27 * density);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(label, area.centerX(), area.centerY() + 9 * density, paint);
        paint.setTextAlign(Paint.Align.LEFT);
    }

    private int tint(int color, float amount) {
        int red = (color >> 16) & 0xff;
        int green = (color >> 8) & 0xff;
        int blue = color & 0xff;
        red = Math.min(255, Math.round(red + (255 - red) * amount));
        green = Math.min(255, Math.round(green + (255 - green) * amount));
        blue = Math.min(255, Math.round(blue + (255 - blue) * amount));
        return 0xff000000 | red << 16 | green << 8 | blue;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() != MotionEvent.ACTION_DOWN) return true;
        float x = event.getX();
        float y = event.getY();
        if (leaveButton.contains(x, y)) {
            leaveAction.run();
            return true;
        }
        if (upButton.contains(x, y)) send(0);
        else if (rightButton.contains(x, y)) send(1);
        else if (downButton.contains(x, y)) send(2);
        else if (leftButton.contains(x, y)) send(3);
        return true;
    }

    private void send(int direction) {
        roomService.sendInput(new byte[]{(byte) direction});
        invalidate();
    }
}
