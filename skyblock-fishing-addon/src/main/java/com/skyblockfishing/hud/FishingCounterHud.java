package com.skyblockfishing.hud;

import com.skyblockfishing.SkyblockFishingAddon;
import com.skyblockfishing.modules.SkyblockAutoFish;
import com.skyblockfishing.tracker.FishingTracker;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;

public class FishingCounterHud extends HudElement {

    public static final HudElementInfo<FishingCounterHud> INFO =
        new HudElementInfo<>(SkyblockFishingAddon.HUD_GROUP, "fishing-counter",
            "Displays fishing session statistics.", FishingCounterHud::new);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<SettingColor> titleColor = sgGeneral.add(new ColorSetting.Builder()
        .name("title-color").defaultValue(new SettingColor(80, 200, 255, 255)).build());
    private final Setting<SettingColor> labelColor = sgGeneral.add(new ColorSetting.Builder()
        .name("label-color").defaultValue(new SettingColor(200, 200, 200, 255)).build());
    private final Setting<SettingColor> valueColor = sgGeneral.add(new ColorSetting.Builder()
        .name("value-color").defaultValue(new SettingColor(255, 255, 255, 255)).build());
    private final Setting<SettingColor> activeColor = sgGeneral.add(new ColorSetting.Builder()
        .name("turbo-active-color").defaultValue(new SettingColor(80, 255, 80, 255)).build());
    private final Setting<SettingColor> inactiveColor = sgGeneral.add(new ColorSetting.Builder()
        .name("turbo-inactive-color").defaultValue(new SettingColor(255, 80, 80, 255)).build());
    private final Setting<SettingColor> bgColor = sgGeneral.add(new ColorSetting.Builder()
        .name("background-color").defaultValue(new SettingColor(0, 0, 0, 140)).build());
    private final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("scale").defaultValue(1.0).min(0.5).sliderMax(3.0).build());

    private static final int PADDING     = 5;
    private static final int LINE_HEIGHT = 11;
    private static final int BASE_W      = 175;
    private static final int BASE_H      = PADDING * 2 + LINE_HEIGHT * 6 + 4;

    public FishingCounterHud() {
        super(INFO);
        box.setSize(BASE_W, BASE_H);
    }

    @Override
    public void render(HudRenderer renderer) {
        double s   = scale.get();
        // Use box.x and box.y directly (public fields in Meteor's HudBox)
        int    x   = (int) box.x;
        int    y   = (int) box.y;
        int    w   = (int) (BASE_W * s);
        int    h   = (int) (BASE_H * s);
        int    pad = (int) (PADDING * s);
        int    lh  = (int) (LINE_HEIGHT * s);

        box.setSize(w, h);
        renderer.quad(x, y, w, h, bgColor.get());
        renderer.quad(x, y + pad + lh + 2, w, 1, new Color(80, 200, 255, 160));

        int tx = x + pad;
        int ty = y + pad;

        renderer.text("FISHING", tx, ty, titleColor.get(), false, s);
        ty += lh + 4;

        SkyblockAutoFish mod = Modules.get().get(SkyblockAutoFish.class);
        boolean active      = mod != null && mod.isTurboActive();
        int     secondsLeft = mod != null ? mod.turboSecondsRemaining() : 0;
        FishingTracker t    = FishingTracker.get();

        row(renderer, tx, ty, s, "Fish Caught:",  String.valueOf(t.fishCaught));               ty += lh;
        row(renderer, tx, ty, s, "Turbo Procs:",  String.valueOf(t.turboProcCount));            ty += lh;
        row(renderer, tx, ty, s, "XP Gained:",    FishingTracker.formatNumber(t.xpGained));    ty += lh;
        row(renderer, tx, ty, s, "Money Gained:", FishingTracker.formatNumber(t.moneyGained)); ty += lh;

        renderer.text("Turbo Active:", tx, ty, labelColor.get(), false, s);
        int lw = (int) renderer.textWidth("Turbo Active:", false, s);
        String status = active ? "YES (" + secondsLeft + "s)" : "NO";
        renderer.text(status, tx + lw + (int)(4 * s), ty,
            active ? activeColor.get() : inactiveColor.get(), false, s);
    }

    private void row(HudRenderer r, int x, int y, double s, String label, String value) {
        r.text(label, x, y, labelColor.get(), false, s);
        int lw = (int) r.textWidth(label, false, s);
        r.text(value, x + lw + (int)(4 * s), y, valueColor.get(), false, s);
    }
}
