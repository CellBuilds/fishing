package com.skyblockfishing;

import com.mojang.logging.LogUtils;
import com.skyblockfishing.modules.SkyblockAutoFish;
import com.skyblockfishing.hud.FishingCounterHud;
import com.skyblockfishing.tracker.FishingTracker;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudGroup;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class SkyblockFishingAddon extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final String NAME = "Skyblock Fishing";

    public static final Category CATEGORY = Categories.SKYBLOCK;
    public static final HudGroup HUD_GROUP = Categories.HUD;

    @Override
    public void onInitialize() {
        LOG.info("Initializing " + NAME);

        // Register modules
        Modules.get().add(new SkyblockAutoFish());

        // Register HUD elements
        Hud.get().register(FishingCounterHud.INFO);

        // Subscribe FishingTracker to the event bus (handles /bal tick + chat parsing)
        MeteorClient.EVENT_BUS.subscribe(FishingTracker.get());
    }

    @Override
    public String getPackage() {
        return "com.skyblockfishing";
    }
}
