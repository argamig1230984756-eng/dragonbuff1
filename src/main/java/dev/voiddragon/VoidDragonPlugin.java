package dev.voiddragon;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EnderDragon;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class VoidDragonPlugin extends JavaPlugin {

    private final Map<UUID, DragonFight> fights = new HashMap<>();
    private ModelHook modelHook;
    private NamespacedKey wraithKey;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        wraithKey = new NamespacedKey(this, "wraith_crystal");
        if (modelHook == null) modelHook = new LoggingModelHook(getLogger());
        getServer().getPluginManager().registerEvents(new FightListener(this), this);

        // adopt dragons that already exist (server restart mid-fight)
        Bukkit.getScheduler().runTask(this, () -> {
            for (World w : Bukkit.getWorlds()) {
                if (w.getEnvironment() != World.Environment.THE_END) continue;
                for (EnderDragon d : w.getEntitiesByClass(EnderDragon.class)) adopt(d);
            }
        });
    }

    @Override
    public void onDisable() {
        for (DragonFight f : List.copyOf(fights.values())) f.end();
        fights.clear();
    }

    public ModelHook modelHook() { return modelHook; }

    /** Call this from your own plugin (or onLoad) to plug in BetterModel / ModelEngine. */
    public void setModelHook(ModelHook hook) { this.modelHook = hook; }

    NamespacedKey wraithKey() { return wraithKey; }
    Map<UUID, DragonFight> fights() { return fights; }

    DragonFight adopt(EnderDragon dragon) {
        return fights.computeIfAbsent(dragon.getUniqueId(), id -> new DragonFight(this, dragon));
    }

    void forget(UUID dragonId) { fights.remove(dragonId); }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase();
        World end = Bukkit.getWorlds().stream()
                .filter(w -> w.getEnvironment() == World.Environment.THE_END).findFirst().orElse(null);
        switch (sub) {
            case "start" -> {
                if (end == null) { sender.sendMessage("No End world found."); return true; }
                EnderDragon d = end.spawn(new Location(end, 0.5, 100, 0.5), EnderDragon.class);
                adopt(d);
                sender.sendMessage("Void Dragon spawned.");
            }
            case "phase2" -> {
                DragonFight f = fights.values().stream().findFirst().orElse(null);
                if (f == null) { sender.sendMessage("No active fight."); return true; }
                f.startPhase2();
                sender.sendMessage("Phase 2 started.");
            }
            case "reset" -> {
                for (DragonFight f : List.copyOf(fights.values())) f.end();
                sender.sendMessage("Fight state cleared (dragon left alone).");
            }
            default -> sender.sendMessage("/voiddragon <start|phase2|reset>");
        }
        return true;
    }
}
