package com.enthusia.koth.infrastructure.gui;

import com.enthusia.koth.application.event.StartResult;
import com.enthusia.koth.application.manual.ManualStartService;
import com.enthusia.koth.domain.KothFamily;
import com.enthusia.koth.domain.TeamMode;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class StartGuiService implements Listener {
    private static final String TITLE = "EnthusiaKOTH Start";
    private final ManualStartService manualStartService;
    private final Map<UUID, StartFlowSession> sessions = new ConcurrentHashMap<>();

    public StartGuiService(ManualStartService manualStartService) {
        this.manualStartService = manualStartService;
    }

    public void open(Player player, boolean advanced) {
        StartFlowSession session = new StartFlowSession();
        session.advanced(advanced);
        sessions.put(player.getUniqueId(), session);
        render(player, session);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!event.getView().title().equals(Component.text(TITLE))) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        StartFlowSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        handleSlot(player, session, event.getRawSlot());
    }

    private void handleSlot(Player player, StartFlowSession session, int slot) {
        switch (slot) {
            case 10 -> session.family(KothFamily.CAPTURE);
            case 11 -> session.family(KothFamily.MOVING);
            case 12 -> session.family(KothFamily.CONQUEST);
            case 14 -> toggleTeamMode(session);
            case 16 -> confirm(player, session);
            default -> {
                return;
            }
        }
        if (slot != 16) {
            render(player, session);
        }
    }

    private void toggleTeamMode(StartFlowSession session) {
        session.teamMode(session.teamMode() == TeamMode.SOLO ? TeamMode.GUILD : TeamMode.SOLO);
    }

    private void confirm(Player player, StartFlowSession session) {
        StartResult result = manualStartService.request(player, session.family(), session.teamMode(), session.advanced());
        player.closeInventory();
        player.sendMessage(Component.text(result.message()));
        sessions.remove(player.getUniqueId());
    }

    private void render(Player player, StartFlowSession session) {
        Inventory inventory = Bukkit.createInventory(player, 27, Component.text(TITLE));
        inventory.setItem(10, item(Material.BEACON, "Capture"));
        inventory.setItem(11, item(Material.COMPASS, "Moving"));
        inventory.setItem(12, item(Material.COPPER_BLOCK, "Conquest scaffold"));
        inventory.setItem(14, item(Material.PLAYER_HEAD, "Mode: " + session.teamMode()));
        inventory.setItem(16, item(Material.LIME_CONCRETE, "Confirm " + session.family().key()));
        player.openInventory(inventory);
    }

    private ItemStack item(Material material, String name) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(name));
        if (!stack.setItemMeta(meta)) {
            throw new IllegalStateException("Could not set item metadata for " + material);
        }
        return stack;
    }
}
