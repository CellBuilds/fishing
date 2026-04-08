package com.skyblockfishing.modules;

import com.skyblockfishing.SkyblockFishingAddon;
import com.skyblockfishing.tracker.FishingTracker;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.OverlayMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.util.Hand;

/**
 * SkyblockAutoFish — Meteor Client Addon Module
 *
 * Combines two features:
 *
 *  1) AutoFish  — bobber motion/sound detection, auto reel-in and recast.
 *
 *  2) Turbo Fishing — when the chat/title/subtitle/action-bar contains
 *     "Turbo Fishing has activated", spam INTERACT_ITEM packets at the
 *     configured rate for `turbo-duration-seconds` (default 20), then stop.
 *     No deactivation message exists — the timer handles shutdown.
 *
 * Packet handling follows the reference pattern:
 *   - Data extracted on Netty thread (safe — packets are immutable at that point)
 *   - Game-object access dispatched to main thread via mc.execute()
 */
public class SkyblockAutoFish extends Module {

    // =========================================================================
    // Settings — General
    // =========================================================================
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> recastDelay = sgGeneral.add(new IntSetting.Builder()
        .name("recast-delay-ms")
        .description("Milliseconds to wait after reeling in before recasting.")
        .defaultValue(300)
        .min(0)
        .sliderRange(0, 3000)
        .build()
    );

    private final Setting<Double> motionThreshold = sgGeneral.add(new DoubleSetting.Builder()
        .name("motion-threshold")
        .description("Bobber Y-velocity dip threshold that triggers a reel-in.")
        .defaultValue(0.15)
        .min(0.01)
        .max(1.0)
        .sliderRange(0.01, 0.5)
        .build()
    );

    private final Setting<Boolean> swingArm = sgGeneral.add(new BoolSetting.Builder()
        .name("swing-arm")
        .description("Send a hand-swing animation when reeling in.")
        .defaultValue(false)
        .build()
    );

    // =========================================================================
    // Settings — Turbo
    // =========================================================================
    private final SettingGroup sgTurbo = settings.createGroup("Turbo");

    private final Setting<Integer> turboPacketCount = sgTurbo.add(new IntSetting.Builder()
        .name("turbo-packets-per-burst")
        .description("INTERACT_ITEM packets sent per turbo burst (per interval tick).")
        .defaultValue(2)
        .min(1)
        .sliderRange(1, 10)
        .build()
    );

    private final Setting<Integer> turboInterval = sgTurbo.add(new IntSetting.Builder()
        .name("turbo-interval-ticks")
        .description("Ticks between turbo bursts. 1 = every tick.")
        .defaultValue(1)
        .min(1)
        .sliderRange(1, 20)
        .build()
    );

    private final Setting<Integer> turboDurationSeconds = sgTurbo.add(new IntSetting.Builder()
        .name("turbo-duration-seconds")
        .description("How long the Turbo Fishing enchant lasts on your server (seconds).")
        .defaultValue(20)
        .min(1)
        .sliderRange(5, 60)
        .build()
    );

    private final Setting<String> turboActivateText = sgTurbo.add(new StringSetting.Builder()
        .name("turbo-activate-text")
        .description("Substring to match in any message (chat/title/subtitle/actionbar) to trigger turbo.")
        .defaultValue("Turbo Fishing has activated")
        .build()
    );

    private final Setting<Boolean> debug = sgTurbo.add(new BoolSetting.Builder()
        .name("debug-log")
        .description("Print all incoming message text to logs so you can verify the trigger string.")
        .defaultValue(false)
        .build()
    );

    // =========================================================================
    // State
    // =========================================================================
    private boolean castPending      = false;
    private long    castAt           = 0;
    private boolean reeledThisBobber = false;

    // Turbo
    private boolean turboActive    = false;
    private long    turboStartMs   = 0;
    private int     turboBurstTick = 0;

    // Packet sequence (Minecraft 1.21 requires incrementing per INTERACT_ITEM)
    private int sequence = 0;

    // =========================================================================
    // Constructor
    // =========================================================================
    public SkyblockAutoFish() {
        super(
            SkyblockFishingAddon.CATEGORY,
            "skyblock-auto-fish",
            "AutoFish + Turbo Fishing packet spammer for Skyblock servers."
        );
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================
    @Override
    public void onActivate() {
        castPending      = false;
        reeledThisBobber = false;
        turboActive      = false;
        turboStartMs     = 0;
        turboBurstTick   = 0;
        FishingTracker.get().reset();
        SkyblockFishingAddon.LOG.info("[SkyblockAutoFish] Activated.");
    }

    @Override
    public void onDeactivate() {
        castPending  = false;
        turboActive  = false;
        SkyblockFishingAddon.LOG.info("[SkyblockAutoFish] Deactivated.");
    }

    // =========================================================================
    // Tick — bobber motion check + recast scheduling + turbo burst
    // =========================================================================
    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        // ── Turbo burst ───────────────────────────────────────────────────────
        if (turboActive) {
            // Check if 20s has elapsed
            if (System.currentTimeMillis() - turboStartMs >= turboDurationSeconds.get() * 1000L) {
                turboActive = false;
                info("§c[Turbo] §fEnded after " + turboDurationSeconds.get() + "s.");
                SkyblockFishingAddon.LOG.info("[SkyblockAutoFish] Turbo expired.");
            } else {
                turboBurstTick++;
                if (turboBurstTick >= turboInterval.get()) {
                    turboBurstTick = 0;
                    sendTurboBurst();
                }
            }
        }

        // ── Bobber motion detection ───────────────────────────────────────────
        var bobber = mc.player.fishHook;

        if (bobber == null) {
            reeledThisBobber = false;
            // Fire scheduled recast
            if (castPending && System.currentTimeMillis() >= castAt) {
                castPending = false;
                mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
            }
            return;
        }

        if (!reeledThisBobber && bobber.getVelocity().y < -motionThreshold.get()) {
            reelIn();
        }
    }

    // =========================================================================
    // Packet receive — sound detection + message parsing
    //
    // Runs on the Netty thread. Extract data here, then mc.execute() for anything
    // that touches game state to avoid multi-thread crashes.
    // =========================================================================
    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        if (mc.player == null) return;

        // ── Sound detection ───────────────────────────────────────────────────
        if (event.packet instanceof net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket soundPkt) {
            String id = soundPkt.getSound().value().getId().toString();
            if (id.equals("minecraft:entity.fishing_bobber.splash")) {
                mc.execute(() -> {
                    if (mc.player != null && mc.player.fishHook != null && !reeledThisBobber) {
                        reelIn();
                    }
                });
            }
        }

        // ── Chat / system message ─────────────────────────────────────────────
        if (event.packet instanceof GameMessageS2CPacket msgPkt) {
            String text = msgPkt.content().getString();
            mc.execute(() -> handleMessage(text));
        }

        // ── Action bar ────────────────────────────────────────────────────────
        if (event.packet instanceof OverlayMessageS2CPacket overlayPkt) {
            String text = overlayPkt.getMessage().getString();
            mc.execute(() -> handleMessage(text));
        }

        // ── Title ─────────────────────────────────────────────────────────────
        if (event.packet instanceof TitleS2CPacket titlePkt) {
            String text = titlePkt.getTitle().getString();
            mc.execute(() -> handleMessage(text));
        }

        // ── Subtitle ──────────────────────────────────────────────────────────
        if (event.packet instanceof SubtitleS2CPacket subtitlePkt) {
            String text = subtitlePkt.getSubtitle().getString();
            mc.execute(() -> handleMessage(text));
        }
    }

    // =========================================================================
    // Message handler (runs on main thread)
    // =========================================================================
    private void handleMessage(String text) {
        if (text == null || text.isEmpty()) return;

        if (debug.get()) {
            SkyblockFishingAddon.LOG.info("[SkyblockAutoFish] MSG: {}", text);
        }

        if (text.contains(turboActivateText.get())) {
            activateTurbo();
        }
    }

    private void activateTurbo() {
        // Re-proc while already active? Restart the timer.
        turboActive    = true;
        turboStartMs   = System.currentTimeMillis();
        turboBurstTick = 0;

        FishingTracker.get().onTurboActivated();

        info("§a[Turbo] §fActivated — " + turboPacketCount.get()
            + " packets/burst for " + turboDurationSeconds.get() + "s.");
        SkyblockFishingAddon.LOG.info("[SkyblockAutoFish] Turbo ACTIVATED.");
    }

    // =========================================================================
    // Helpers
    // =========================================================================
    private void reelIn() {
        reeledThisBobber = true;
        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);

        if (swingArm.get() && mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));
        }

        FishingTracker.get().fishCaught++;
        FishingTracker.get().fishThisInterval++;

        castPending = true;
        castAt = System.currentTimeMillis() + recastDelay.get();
    }

    private void sendTurboBurst() {
        if (mc.getNetworkHandler() == null) return;
        for (int i = 0; i < turboPacketCount.get(); i++) {
            mc.getNetworkHandler().sendPacket(
                new PlayerInteractItemC2SPacket(Hand.MAIN_HAND, sequence++)
            );
        }
        if (swingArm.get()) {
            mc.getNetworkHandler().sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));
        }
    }

    // ── Public accessors for HUD ─────────────────────────────────────────────
    public boolean isTurboActive() { return turboActive; }

    public int turboSecondsRemaining() {
        if (!turboActive) return 0;
        long elapsed = System.currentTimeMillis() - turboStartMs;
        long remaining = turboDurationSeconds.get() * 1000L - elapsed;
        return (int) Math.max(0, remaining / 1000);
    }
}
