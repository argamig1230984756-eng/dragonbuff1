package dev.voiddragon;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.boss.BossBar;
import org.bukkit.boss.BarColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vex;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * One Void Dragon fight.
 *
 * PHASE1      normal fight, dragon is capped at the phase-2 health threshold.
 * TRANSITION  dragon hovers and roars, invulnerable, lightning everywhere, crystals respawn.
 * PHASE2      every crystal is shielded. Shields heal the dragon and cut the damage it takes.
 *             Each shield is guarded by Void Wraiths; kill them all to shatter it.
 *             Shields regrow unless the crystal is destroyed. Void Rain hunts players.
 */
public final class DragonFight {

    enum Stage { PHASE1, TRANSITION, PHASE2 }

    /** State of one crystal during phase 2. */
    private static final class Shielded {
        final UUID crystalId;
        final Location spot;
        final List<UUID> wraiths = new ArrayList<>();
        boolean shielded;
        long regenAtTick;

        Shielded(UUID crystalId, Location spot) {
            this.crystalId = crystalId;
            this.spot = spot;
        }
    }

    private final VoidDragonPlugin plugin;
    private final EnderDragon dragon;
    private final World world;
    private final Random rnd = new Random();
    private final List<Location> crystalSpots = new ArrayList<>();
    private final Map<UUID, Shielded> crystals = new HashMap<>();

    private Stage stage = Stage.PHASE1;
    private BukkitTask loop;
    private long tick;
    private double maxHealth;

    DragonFight(VoidDragonPlugin plugin, EnderDragon dragon) {
        this.plugin = plugin;
        this.dragon = dragon;
        this.world = dragon.getWorld();

        FileConfiguration cfg = plugin.getConfig();
        double configured = cfg.getDouble("dragon.max-health", 400.0);
        Util.setMaxHealth(dragon, configured);
        this.maxHealth = Util.maxHealth(dragon);

        // remember where the pillar crystals are so phase 2 can bring them back
        for (EnderCrystal c : world.getEntitiesByClass(EnderCrystal.class)) {
            crystalSpots.add(c.getLocation().clone());
        }
        if (crystalSpots.isEmpty()) {
            plugin.getLogger().warning("No end crystals found when the fight started - phase 2 will have no shields.");
        }

        plugin.modelHook().attachDragon(dragon);
        loop = new BukkitRunnable() {
            @Override public void run() { tick(); }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    // ------------------------------------------------------------------ events

    /** Called for every damage event on the dragon (already normalised from body parts). */
    void onDragonDamage(EntityDamageEvent e) {
        switch (stage) {
            case TRANSITION -> e.setCancelled(true);
            case PHASE1 -> {
                double floor = maxHealth * plugin.getConfig().getDouble("phase2.health-threshold", 0.5);
                double remaining = dragon.getHealth() - floor;
                if (e.getFinalDamage() >= remaining) {
                    e.setCancelled(true);
                    dragon.setHealth(Math.max(1.0, floor));
                    startPhase2();
                }
            }
            case PHASE2 -> {
                if (shieldedCount() > 0) {
                    double mult = plugin.getConfig().getDouble("phase2.damage-multiplier-while-shielded", 0.25);
                    e.setDamage(e.getDamage() * mult);
                    world.spawnParticle(Particle.ENCHANT, dragon.getLocation().add(0, 3, 0), 12, 2, 1.5, 2, 0.3);
                    world.playSound(dragon.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_HIT, SoundCategory.HOSTILE, 1.5f, 0.6f);
                }
            }
        }
    }

    /** Someone hit a crystal. Returns true if the hit must be blocked. */
    boolean onCrystalDamage(EnderCrystal crystal, Entity damager) {
        Shielded s = crystals.get(crystal.getUniqueId());
        if (s == null || !s.shielded) return false;
        world.playSound(crystal.getLocation(), Sound.BLOCK_ANVIL_LAND, SoundCategory.HOSTILE, 0.6f, 1.8f);
        world.spawnParticle(Particle.DUST, crystal.getLocation().add(0, 0.5, 0), 20, 0.8, 0.8, 0.8, 0,
                new Particle.DustOptions(Color.fromRGB(190, 70, 255), 1.6f));
        Player p = damager instanceof Player pl ? pl
                : (damager instanceof org.bukkit.entity.Projectile pr
                        && pr.getShooter() instanceof Player sh ? sh : null);
        if (p != null) {
            p.sendActionBar(Component.text("Shielded! Slay the Void Wraiths guarding it.", NamedTextColor.LIGHT_PURPLE));
        }
        return true;
    }

    void onWraithDeath(UUID crystalId, UUID wraithId) {
        Shielded s = crystals.get(crystalId);
        if (s == null) return;
        s.wraiths.remove(wraithId);
        if (s.shielded && s.wraiths.isEmpty()) breakShield(s);
    }

    UUID dragonId() { return dragon.getUniqueId(); }
    boolean ownsCrystal(UUID id) { return crystals.containsKey(id); }
    boolean isPhase2() { return stage == Stage.PHASE2; }

    // ------------------------------------------------------------------ phase 2

    void startPhase2() {
        if (stage != Stage.PHASE1) return;
        stage = Stage.TRANSITION;

        FileConfiguration cfg = plugin.getConfig();
        int seconds = Math.max(2, cfg.getInt("phase2.transition-seconds", 6));
        dragon.setPhase(EnderDragon.Phase.HOVER);
        dragon.setAI(true);

        for (Player p : world.getPlayers()) {
            p.showTitle(Title.title(
                    Component.text("PHASE II", NamedTextColor.DARK_RED),
                    Component.text("The void answers its master", NamedTextColor.LIGHT_PURPLE)));
        }
        world.playSound(dragon.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.HOSTILE, 8f, 0.5f);

        final int total = seconds * 20;
        new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                if (!dragon.isValid()) { cancel(); return; }
                Location d = dragon.getLocation();
                if (t % 8 == 0) {
                    // lightning on random crystal spots + a pulse ring around the dragon
                    if (!crystalSpots.isEmpty()) {
                        world.strikeLightningEffect(crystalSpots.get(rnd.nextInt(crystalSpots.size())));
                    }
                    double r = 3 + (t / (double) total) * 14;
                    for (int i = 0; i < 48; i++) {
                        double a = Math.PI * 2 * i / 48;
                        world.spawnParticle(Particle.REVERSE_PORTAL, d.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r), 1, 0, 0, 0, 0);
                    }
                }
                world.spawnParticle(Particle.PORTAL, d, 40, 4, 2, 4, 0.8);
                if (t == total / 2) {
                    world.spawnParticle(Particle.EXPLOSION_EMITTER, d, 3, 3, 2, 3, 0);
                    world.playSound(d, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 6f, 0.6f);
                    plugin.modelHook().setDragonEnraged(dragon, true);
                    plugin.modelHook().setDragonAnimationSpeed(dragon, cfg.getDouble("phase2.animation-speed", 1.4));
                    recolorBossBar();
                }
                if (++t >= total) {
                    cancel();
                    finishTransition();
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void finishTransition() {
        if (!dragon.isValid()) return;
        stage = Stage.PHASE2;
        respawnAndShieldCrystals();
        dragon.setPhase(EnderDragon.Phase.CIRCLING);
        world.playSound(dragon.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.HOSTILE, 8f, 0.7f);
        for (Player p : world.getPlayers()) {
            p.sendMessage(Component.text("The crystals are shielded and the dragon feeds on them. "
                    + "Slay the wraiths to break each shield.", NamedTextColor.LIGHT_PURPLE));
        }
    }

    private void recolorBossBar() {
        if (dragon.getDragonBattle() == null) return;
        BossBar bar = dragon.getDragonBattle().getBossBar();
        bar.setColor(BarColor.RED);
        bar.setTitle(plugin.getConfig().getString("phase2.bossbar-title", "Void Dragon - Phase II"));
    }

    // ------------------------------------------------------------------ crystal shields

    private void respawnAndShieldCrystals() {
        for (Location spot : crystalSpots) {
            EnderCrystal crystal = null;
            for (Entity near : world.getNearbyEntities(spot, 1.5, 1.5, 1.5)) {
                if (near instanceof EnderCrystal c) { crystal = c; break; }
            }
            if (crystal == null) {
                crystal = world.spawn(spot, EnderCrystal.class, c -> c.setShowingBottom(false));
                world.strikeLightningEffect(spot);
            }
            Shielded s = new Shielded(crystal.getUniqueId(), spot);
            crystals.put(crystal.getUniqueId(), s);
            raiseShield(s, plugin.getConfig().getInt("shield.wraiths-per-crystal", 3));
        }
    }

    private void raiseShield(Shielded s, int wraithCount) {
        s.shielded = true;
        world.playSound(s.spot, Sound.BLOCK_BEACON_ACTIVATE, SoundCategory.HOSTILE, 2f, 0.8f);
        for (int i = 0; i < wraithCount; i++) spawnWraith(s);
    }

    private void spawnWraith(Shielded s) {
        double health = plugin.getConfig().getDouble("shield.wraith-health", 14.0);
        double ang = rnd.nextDouble() * Math.PI * 2;
        Location at = s.spot.clone().add(Math.cos(ang) * 3.5, 2 + rnd.nextDouble() * 2, Math.sin(ang) * 3.5);
        Vex vex = world.spawn(at, Vex.class, v -> {
            v.setInvisible(true);                 // the void_wraith model replaces its look
            v.setCanPickupItems(false);
            v.setLimitedLifetime(false);
            v.setPersistent(true);
            v.customName(Component.text("Void Wraith", NamedTextColor.DARK_PURPLE));
            v.setCustomNameVisible(false);
            if (v.getEquipment() != null) v.getEquipment().clear();
            Util.setMaxHealth(v, health);
            v.getPersistentDataContainer().set(plugin.wraithKey(), PersistentDataType.STRING,
                    s.crystalId.toString());
        });
        plugin.modelHook().attachWraith(vex);
        s.wraiths.add(vex.getUniqueId());
        world.spawnParticle(Particle.REVERSE_PORTAL, at, 25, 0.4, 0.6, 0.4, 0.1);
    }

    private void breakShield(Shielded s) {
        s.shielded = false;
        s.regenAtTick = tick + plugin.getConfig().getInt("shield.regen-seconds", 40) * 20L;
        Entity c = Bukkit.getEntity(s.crystalId);
        if (c instanceof EnderCrystal crystal) crystal.setBeamTarget(null);
        world.playSound(s.spot, Sound.BLOCK_GLASS_BREAK, SoundCategory.HOSTILE, 3f, 0.5f);
        world.playSound(s.spot, Sound.ENTITY_ENDER_DRAGON_HURT, SoundCategory.HOSTILE, 2f, 0.7f);
        world.spawnParticle(Particle.DUST, s.spot.clone().add(0, 0.5, 0), 120, 1.5, 1.5, 1.5, 0,
                new Particle.DustOptions(Color.fromRGB(190, 70, 255), 2.0f));
        world.spawnParticle(Particle.END_ROD, s.spot.clone().add(0, 0.5, 0), 40, 1.2, 1.2, 1.2, 0.15);
        for (Player p : world.getPlayers()) {
            if (p.getLocation().distanceSquared(s.spot) < 80 * 80) {
                p.sendActionBar(Component.text("A crystal shield shattered! Destroy it now!", NamedTextColor.GREEN));
            }
        }
    }

    private int shieldedCount() {
        int n = 0;
        for (Shielded s : crystals.values()) if (s.shielded) n++;
        return n;
    }

    // ------------------------------------------------------------------ tick

    private void tick() {
        tick++;
        if (!dragon.isValid()) { end(); return; }
        if (stage != Stage.PHASE2) return;

        FileConfiguration cfg = plugin.getConfig();

        // crystal bookkeeping every second
        if (tick % 20 == 0) {
            Iterator<Shielded> it = crystals.values().iterator();
            double heal = cfg.getDouble("shield.heal-per-second-per-crystal", 3.0);
            double leash = cfg.getDouble("shield.wraith-leash-radius", 30.0);
            while (it.hasNext()) {
                Shielded s = it.next();
                Entity c = Bukkit.getEntity(s.crystalId);
                if (c == null || !c.isValid()) {            // crystal destroyed
                    world.playSound(s.spot, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 3f, 1f);
                    for (UUID w : s.wraiths) {
                        Entity we = Bukkit.getEntity(w);
                        if (we != null) {
                            world.spawnParticle(Particle.POOF, we.getLocation(), 15, 0.3, 0.3, 0.3, 0.05);
                            plugin.modelHook().detach(we);
                            we.remove();
                        }
                    }
                    it.remove();
                    continue;
                }
                if (s.shielded) {
                    dragon.setHealth(Math.min(maxHealth, dragon.getHealth() + heal));
                    // keep wraiths near their crystal
                    for (UUID w : s.wraiths) {
                        Entity we = Bukkit.getEntity(w);
                        if (we instanceof Mob m && m.getTarget() == null
                                && we.getLocation().distanceSquared(s.spot) > leash * leash) {
                            we.teleport(s.spot.clone().add(0, 3, 0));
                        }
                    }
                } else if (tick >= s.regenAtTick) {
                    raiseShield(s, cfg.getInt("shield.regen-wraiths", 2));
                    for (Player p : world.getPlayers()) {
                        p.sendActionBar(Component.text("A crystal shield has regrown!", NamedTextColor.RED));
                    }
                }
            }
        }

        // beams + shield bubbles
        if (tick % 4 == 0) {
            Location target = dragon.getLocation().add(0, -1, 0);
            for (Shielded s : crystals.values()) {
                Entity c = Bukkit.getEntity(s.crystalId);
                if (!(c instanceof EnderCrystal crystal)) continue;
                if (s.shielded) {
                    crystal.setBeamTarget(target);
                    Location center = s.spot.clone().add(0, 0.5, 0);
                    for (int i = 0; i < 18; i++) {
                        world.spawnParticle(Particle.DUST, Util.onSphere(center, 1.7, rnd), 1, 0, 0, 0, 0,
                                new Particle.DustOptions(Color.fromRGB(150 + rnd.nextInt(60), 60, 255), 1.1f));
                    }
                } else {
                    crystal.setBeamTarget(null);
                }
            }
        }

        // void rain
        if (cfg.getBoolean("void-rain.enabled", true)) {
            long every = Math.max(1, cfg.getInt("void-rain.interval-seconds", 14)) * 20L;
            if (tick % every == 0) voidRain();
        }
    }

    // ------------------------------------------------------------------ void rain

    private void voidRain() {
        FileConfiguration cfg = plugin.getConfig();
        List<Player> candidates = new ArrayList<>();
        for (Player p : world.getPlayers()) {
            if (!p.isDead() && p.getGameMode() != org.bukkit.GameMode.SPECTATOR) candidates.add(p);
        }
        if (candidates.isEmpty()) return;
        Collections.shuffle(candidates, rnd);
        int targets = Math.min(candidates.size(), cfg.getInt("void-rain.max-targets", 3));

        final int telegraph = cfg.getInt("void-rain.telegraph-ticks", 40);
        final double radius = cfg.getDouble("void-rain.radius", 3.5);
        final double damage = cfg.getDouble("void-rain.damage", 8.0);

        world.playSound(dragon.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.HOSTILE, 5f, 1.4f);

        for (int i = 0; i < targets; i++) {
            final Location spot = candidates.get(i).getLocation().clone();
            new BukkitRunnable() {
                int t = 0;
                @Override public void run() {
                    if (!dragon.isValid()) { cancel(); return; }
                    double progress = t / (double) telegraph;
                    // warning ring that closes in on the strike point
                    double r = radius * (1.0 + (1.0 - progress));
                    for (int k = 0; k < 28; k++) {
                        double a = Math.PI * 2 * k / 28;
                        world.spawnParticle(Particle.DUST, spot.clone().add(Math.cos(a) * r, 0.2, Math.sin(a) * r),
                                1, 0, 0, 0, 0, new Particle.DustOptions(Color.fromRGB(255, 40, 90), 1.3f));
                    }
                    world.spawnParticle(Particle.PORTAL, spot.clone().add(0, 8 * (1 - progress), 0), 6, 0.3, 0.3, 0.3, 0.2);
                    if (t % 10 == 0) {
                        world.playSound(spot, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, SoundCategory.HOSTILE, 1.5f, 0.5f + (float) progress);
                    }
                    if (++t >= telegraph) {
                        cancel();
                        world.strikeLightningEffect(spot);
                        world.spawnParticle(Particle.EXPLOSION_EMITTER, spot, 1);
                        world.spawnParticle(Particle.REVERSE_PORTAL, spot, 80, radius / 2, 1, radius / 2, 0.3);
                        world.playSound(spot, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 3f, 0.7f);
                        for (Player p : world.getPlayers()) {
                            Location pl = p.getLocation();
                            double dx = pl.getX() - spot.getX(), dz = pl.getZ() - spot.getZ();
                            if (dx * dx + dz * dz <= radius * radius && Math.abs(pl.getY() - spot.getY()) <= 4) {
                                p.damage(damage, dragon);
                            }
                        }
                    }
                }
            }.runTaskTimer(plugin, 0L, 1L);
        }
    }

    // ------------------------------------------------------------------ cleanup

    void end() {
        if (loop != null) { loop.cancel(); loop = null; }
        for (Shielded s : crystals.values()) {
            Entity c = Bukkit.getEntity(s.crystalId);
            if (c instanceof EnderCrystal crystal) crystal.setBeamTarget(null);
            for (UUID w : s.wraiths) {
                Entity we = Bukkit.getEntity(w);
                if (we != null) { plugin.modelHook().detach(we); we.remove(); }
            }
        }
        crystals.clear();
        plugin.modelHook().detach(dragon);
        plugin.forget(dragon.getUniqueId());
    }
}
