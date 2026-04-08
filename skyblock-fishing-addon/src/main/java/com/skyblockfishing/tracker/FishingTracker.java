package com.skyblockfishing.tracker;

import com.skyblockfishing.SkyblockFishingAddon;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.client.MinecraftClient;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FishingTracker — singleton that tracks session statistics.
 *
 * Fish caught:  incremented by watching chat for Skyblock fishing reward messages.
 * Turbo procs:  incremented each time TurboFishingModule fires activation.
 * XP gained:    parsed from chat XP messages.
 * Money gained: polled via /bal every 60 s, delta computed vs baseline.
 * Fish Coins:   parsed from coin reward messages in chat.
 *
 * All values are exposed as simple fields so the HUD can render them.
 */
public class FishingTracker {

    // ── Singleton ────────────────────────────────────────────────────────────
    private static FishingTracker instance;
    public static FishingTracker get() {
        if (instance == null) instance = new FishingTracker();
        return instance;
    }

    // ── Session stats ────────────────────────────────────────────────────────
    public long fishCaught      = 0;
    public long fishThisInterval = 0;   // fish caught since last 60s reset
    public long turboProcCount  = 0;
    public long xpGained        = 0;
    public long moneyGained     = 0;
    public long fishCoinsGained = 0;

    // Internal balance tracking
    private long balanceBaseline   = -1;
    private long lastBalance       = -1;
    private long fishCoinsBaseline = -1;
    private long lastFishCoins     = -1;

    private boolean waitingForBal  = false;
    private int    ticksSinceBalCmd = 0;
    private static final int BAL_INTERVAL_TICKS = 20 * 60; // 60 seconds

    private final MinecraftClient mc = MinecraftClient.getInstance();

    // ── Regex patterns for Skyblock chat messages ────────────────────────────
    // Adjust these patterns to match your specific server's chat format!

    /** "You caught a [Rare] Lava Carp!" or "You caught ... fish" */
    private static final Pattern FISH_CAUGHT_PATTERN =
        Pattern.compile("(?i)you caught (a |an )?(.+?)!", Pattern.CASE_INSENSITIVE);

    /** "+1,500 Fishing XP" or "+500 XP" */
    private static final Pattern XP_PATTERN =
        Pattern.compile("\\+([\\d,]+)\\s+(?:Fishing\\s+)?XP", Pattern.CASE_INSENSITIVE);

    /** "+250 Fish Coins" */
    private static final Pattern FISH_COINS_PATTERN =
        Pattern.compile("\\+([\\d,]+)\\s+Fish\\s*Coins?", Pattern.CASE_INSENSITIVE);

    /** Balance response: "Your balance: $1,234,567" or "Balance: 1234567" */
    private static final Pattern BAL_PATTERN =
        Pattern.compile("(?:Your\\s+)?[Bb]alance[:\\s]+[$]?([\\d,]+)", Pattern.CASE_INSENSITIVE);

    /** Alternative coin balance line from /bal: "Fish Coins: 5,000" */
    private static final Pattern BAL_FISH_COINS_PATTERN =
        Pattern.compile("Fish\\s*Coins?[:\\s]+([\\d,]+)", Pattern.CASE_INSENSITIVE);

    // ── Public API called by TurboFishingModule ──────────────────────────────
    public void onTurboActivated() {
        turboProcCount++;
    }

    public void reset() {
        fishCaught = 0;
        fishThisInterval = 0;
        turboProcCount = 0;
        xpGained = 0;
        moneyGained = 0;
        fishCoinsGained = 0;
        balanceBaseline = -1;
        lastBalance = -1;
        fishCoinsBaseline = -1;
        lastFishCoins = -1;
        ticksSinceBalCmd = 0;
    }

    // ── Tick handler: send /bal every 60s ────────────────────────────────────
    @EventHandler
    public void onTick(TickEvent.Post event) {
        if (mc.player == null) return;

        ticksSinceBalCmd++;
        if (ticksSinceBalCmd >= BAL_INTERVAL_TICKS) {
            ticksSinceBalCmd = 0;
            fishThisInterval = 0; // reset per-interval fish count
            sendBalCommand();
        }
    }

    private void sendBalCommand() {
        if (mc.player == null) return;
        waitingForBal = true;
        mc.player.networkHandler.sendChatCommand("bal");
        SkyblockFishingAddon.LOG.info("[FishingTracker] Sent /bal command.");
    }

    // ── Chat message handler: parse fish/xp/coins/bal responses ─────────────
    @EventHandler(priority = EventPriority.HIGH)
    public void onReceiveMessage(ReceiveMessageEvent event) {
        String raw = event.getMessage().getString();
        if (raw == null || raw.isBlank()) return;

        // Fish caught
        if (FISH_CAUGHT_PATTERN.matcher(raw).find()) {
            fishCaught++;
            fishThisInterval++;
        }

        // XP
        Matcher xpM = XP_PATTERN.matcher(raw);
        if (xpM.find()) {
            xpGained += parseLong(xpM.group(1));
        }

        // Fish coins from reward line
        Matcher fcM = FISH_COINS_PATTERN.matcher(raw);
        if (fcM.find()) {
            fishCoinsGained += parseLong(fcM.group(1));
        }

        // /bal response — main balance
        if (waitingForBal) {
            Matcher balM = BAL_PATTERN.matcher(raw);
            if (balM.find()) {
                long newBal = parseLong(balM.group(1));
                if (balanceBaseline < 0) {
                    balanceBaseline = newBal;
                    lastBalance = newBal;
                } else {
                    long delta = newBal - lastBalance;
                    if (delta > 0) moneyGained += delta;
                    lastBalance = newBal;
                }
                SkyblockFishingAddon.LOG.info("[FishingTracker] Balance: {}", newBal);
            }

            Matcher fcBalM = BAL_FISH_COINS_PATTERN.matcher(raw);
            if (fcBalM.find()) {
                long newFc = parseLong(fcBalM.group(1));
                if (fishCoinsBaseline < 0) {
                    fishCoinsBaseline = newFc;
                    lastFishCoins = newFc;
                } else {
                    long delta = newFc - lastFishCoins;
                    if (delta > 0) fishCoinsGained += delta;
                    lastFishCoins = newFc;
                }
                waitingForBal = false; // done parsing bal response
            }
        }
    }

    // ── Utility ──────────────────────────────────────────────────────────────
    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.replace(",", "").replace("$", "").trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static String formatNumber(long n) {
        if (n >= 1_000_000_000L) return String.format("%.1fB", n / 1_000_000_000.0);
        if (n >= 1_000_000L)     return String.format("%.1fM", n / 1_000_000.0);
        if (n >= 1_000L)         return String.format("%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }
}
