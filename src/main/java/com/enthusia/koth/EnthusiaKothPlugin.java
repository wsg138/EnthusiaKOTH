package com.enthusia.koth;

import com.enthusia.koth.bootstrap.PluginBootstrap;
import org.bukkit.plugin.java.JavaPlugin;

public final class EnthusiaKothPlugin extends JavaPlugin {
    private PluginBootstrap bootstrap;

    @Override
    public void onEnable() {
        bootstrap = new PluginBootstrap(this);
        bootstrap.enable();
    }

    @Override
    public void onDisable() {
        if (bootstrap != null) {
            bootstrap.disable();
            bootstrap = null;
        }
    }
}
