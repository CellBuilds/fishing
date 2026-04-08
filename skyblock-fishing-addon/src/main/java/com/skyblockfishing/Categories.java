package com.skyblockfishing;

import meteordevelopment.meteorclient.systems.hud.HudGroup;
import meteordevelopment.meteorclient.systems.modules.Category;

/**
 * Central place to define the mod's Module Category and HUD Group,
 * so they're referenced consistently across the codebase.
 */
public final class Categories {

    /** Module category shown in Meteor's GUI */
    public static final Category SKYBLOCK = new Category("Skyblock Fishing");

    /** HUD group for all fishing HUD elements */
    public static final HudGroup HUD = new HudGroup("Skyblock Fishing");

    private Categories() {}
}
