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

public class SkyblockAutoFish extends Module {

    // ── Settings — General ────────────────────────────────────────────────────
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> recastDelay = sgGeneral.add(new IntSetting.Builder()
        .name("recast-delay-ms")
        .description("Milliseconds to wait after reeling in before recasting.")
        .defaultValue(300).min(0).sliderRange(0, 3000)
        .build()
    );

    private final Setting<Double> motionThreshold = sgGeneral.add(new DoubleSetting.Builder()
        .name("motion-threshold")
        .description("Bobber Y-velocity dip that triggers reel-in.")
        .defaultValue(0.15).min(0.01).max(1.0).sliderRange(0.01, 0.5)
        .build()
    );

    private final Setting<Boolean> swingArm = sgGeneral.add(new BoolSetting.Builder()
        .name("swing-arm")
        .description("Send arm swing animation when reeling in.")
        .defaultValue(false)
        .build()
    );

    // ── Settings — Turbo ──────────────────────────────────────────────────────
    private final SettingGroup sgTurbo = settings.createGroup("Turbo");

    private final Setting<Integer> turboPacketCount = sgTurbo.add(new IntSetting.Builder()
        .name("turbo-packets-per-burst")
        .description("INTERACT_ITEM packets sent per burst.")
        .defaultValue(2).min(1).sliderRange(1, 10)
        .build()
    );

    private final Setting<Integer> turboInterval = sgTurbo.add(new IntSetting.Builder()
        .name("turbo-interval-ticks")
        .description("Ticks between bursts. 1 = every tick.")
        .defaultValue(1).min(1).sliderRange(1, 20)
        .build()
    );

    private final Setting<Integer> turboDurationSeconds = sgTurbo.add(new IntSetting.Builder()
        .name("turbo-duration-seconds")
        .description("How long the Turbo Fishing enchant lasts (seconds).")
        .defaultValue(20).min(1).sliderRange(5, 60)
        .build()
    );

    private final Setting<String> turboActivateText = sgTurbo.add(new StringSetting.Builder()
        .name("turbo-activate-text")
        .description("Substring to match in any message to trigger turbo.")
        .defaultValue("Turbo Fishing has activated")
        .build()
    );

    private final Setting<Boolean> debug = sgTurbo.add(new BoolSetting.Builder()
        .name("debug-log")
        .description("Log all incoming messages so you can verify the trigger string.")
        .defaultValue(false)
        .build()
    );

    // ── State ─────────────────────────────────────────────────────────────────
    private boolean castPending      = false;
    private long    castAt           = 0;
    private boolean reeledThisBobber = false;
    private boolean turboActive      = false;
    private long    turboStartMs     = 0;
    private int     turboBurstTick   = 0;

    public SkyblockAutoFish() {
        super(SkyblockFishingAddon.CATEGORY, "skyblock-auto-fish",
            "AutoFish + Turbo Fishing packet spammer for Skyblock servers.");
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    @Override
    public void onActivate() {
        castPending = false; reeledThisBobber = false;
        turboActive = false; turboStartMs = 0; turboBurstTick = 0;
        FishingTracker.get().reset();
    }

    @Override
    public void onDeactivate() {
        castPending = false;
        turboActive = false;
    }

    // ── Tick ──────────────────────────────────────────────────────────────────
    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        // Turbo burst + expiry
        if (turboActive) {
            if (System.currentTimeMillis() - turboStartMs >= turboDurationSeconds.get() * 1000L) {
                turboActive = false;
                info("§c[Turbo] §fEnded after " + turboDurationSeconds.get() + "s.");
            } else {
                turboBurstTick++;
                if (turboBurstTick >= turboInterval.get()) {
                    turboBurstTick = 0;
                    sendTurboBurst();
                }
            }
        }

        // Bobber motion detection
        var bobber = mc.player.fishHook;
        if (bobber == null) {
            reeledThisBobber = false;
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

    // ── Packets ───────────────────────────────────────────────────────────────
    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        if (mc.player == null) return;

        // Sound detection
        if (event.packet instanceof net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket soundPkt) {
            String id = soundPkt.getSound().value().getId().toString();
            if (id.equals("minecraft:entity.fishing_bobber.splash")) {
                mc.execute(() -> {
                    if (mc.player != null && mc.player.fishHook != null && !reeledThisBobber)
                        reelIn();
                });
            }
        }

        // Chat message
        if (event.packet instanceof GameMessageS2CPacket msgPkt) {
            String text = msgPkt.content().getString();
            mc.execute(() -> handleMessage(text));
        }

        // Action bar — 1.21.1 uses text() not getMessage()
        if (event.packet instanceof OverlayMessageS2CPacket overlayPkt) {
            String text = overlayPkt.text().getString();
            mc.execute(() -> handleMessage(text));
        }

        // Title — 1.21.1 uses text() not getTitle()
        if (event.packet instanceof TitleS2CPacket titlePkt) {
            String text = titlePkt.text().getString();
            mc.execute(() -> handleMessage(text));
        }

        // Subtitle — 1.21.1 uses text() not getSubtitle()
        if (event.packet instanceof SubtitleS2CPacket subtitlePkt) {
            String text = subtitlePkt.text().getString();
            mc.execute(() -> handleMessage(text));
        }
    }

    // ── Message handler ───────────────────────────────────────────────────────
    private void handleMessage(String text) {
        if (text == null || text.isEmpty()) return;
        if (debug.get()) SkyblockFishingAddon.LOG.info("[AutoFish] MSG: {}", text);
        if (text.contains(turboActivateText.get())) activateTurbo();
    }

    private void activateTurbo() {
        turboActive    = true;
        turboStartMs   = System.currentTimeMillis();
        turboBurstTick = 0;
        FishingTracker.get().onTurboActivated();
        info("§a[Turbo] §fActivated — " + turboPacketCount.get()
            + " packets/burst for " + turboDurationSeconds.get() + "s.");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private void reelIn() {
        reeledThisBobber = true;
        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        if (swingArm.get() && mc.getNetworkHandler() != null)
            mc.getNetworkHandler().sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));
        FishingTracker.get().fishCaught++;
        FishingTracker.get().fishThisInterval++;
        castPending = true;
        castAt = System.currentTimeMillis() + recastDelay.get();
    }

    private void sendTurboBurst() {
        if (mc.getNetworkHandler() == null) return;
        for (int i = 0; i < turboPacketCount.get(); i++) {
            // 1.21.1 constructor: hand, sequence, yaw, pitch
            mc.getNetworkHandler().sendPacket(
    new PlayerInteractItemC2SPacket(Hand.MAIN_HAND, 0, mc.player.getYaw(), mc.player.getPitch())
);
        }
        if (swingArm.get())
            mc.getNetworkHandler().sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));
    }

    // ── Public accessors for HUD ──────────────────────────────────────────────
    public boolean isTurboActive() { return turboActive; }

    public int turboSecondsRemaining() {
        if (!turboActive) return 0;
        long remaining = turboDurationSeconds.get() * 1000L - (System.currentTimeMillis() - turboStartMs);
        return (int) Math.max(0, remaining / 1000);
    }
}
