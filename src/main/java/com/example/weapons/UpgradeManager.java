package com.example.weapons;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Kill-based weapon upgrades, hit combos and thunder attacks.
 *
 * Kills are stored on the weapon item itself, so the progress travels with the item.
 * Config sections used on a weapon:
 *   kills:          turns on kill tracking (count: ALL | PLAYERS)
 *   bonus-health:   kills + hearts: raises max health while the weapon is anywhere in your inventory
 *   shield-bypass:  kills: hits ignore shield blocking
 *   combo:          hits, timeout, require-same-target, thunder {damage, radius, true-damage}
 *   right-click:    can have "required-kills" to lock an ability behind kills
 */
public class UpgradeManager implements Listener {

    private final WeaponPlugin plugin;
    private final WeaponManager manager;
    private final NamespacedKey killsKey;
    private final Attribute maxHealth;
    private final Map<UUID, Combo> combos = new HashMap<>();
    private BukkitTask task;
    private boolean thunderActive = false;

    private static class Combo {
        int hits = 0;
        long lastHit = 0;
        UUID lastTarget = null;
    }

    private static class Upgrade {
        final int kills;
        final String label;

        Upgrade(int kills, String label) {
            this.kills = kills;
            this.label = label;
        }
    }

    public UpgradeManager(WeaponPlugin plugin, WeaponManager manager) {
        this.plugin = plugin;
        this.manager = manager;
        this.killsKey = new NamespacedKey(plugin, "weapon_kills");
        this.maxHealth = resolveMaxHealth();
    }

    // ---------- Lifecycle ----------

    public void start() {
        stop();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::healthTick, 20L, 20L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        combos.clear();
    }

    /** The attribute was renamed in newer versions, so look it up by name at runtime. */
    private static Attribute resolveMaxHealth() {
        for (String name : new String[]{"GENERIC_MAX_HEALTH", "MAX_HEALTH"}) {
            try {
                Object value = Attribute.class.getField(name).get(null);
                if (value instanceof Attribute) return (Attribute) value;
            } catch (Throwable ignored) {
                // try the next name
            }
        }
        return null;
    }

    // ---------- Kills stored on the item ----------

    public int getKills(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return 0;
        Integer kills = item.getItemMeta().getPersistentDataContainer().get(killsKey, PersistentDataType.INTEGER);
        return kills == null ? 0 : kills;
    }

    /** Sets the kill count on the item and rewrites its lore to show upgrade progress. */
    public void applyKills(ItemStack item, Weapon weapon, int kills) {
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(killsKey, PersistentDataType.INTEGER, kills);

        List<String> lore = weapon.getLoreLines();
        lore.add("");
        lore.add(color("&7Kills: &f" + kills));
        for (Upgrade u : upgrades(weapon)) {
            boolean unlocked = kills >= u.kills;
            lore.add(color((unlocked ? "&a\u2714 &7" : "&8\u2718 ") + u.kills + " kills: "
                    + (unlocked ? "&f" : "&8") + u.label));
        }
        meta.setLore(lore);
        item.setItemMeta(meta);
    }

    /** Gives a freshly created weapon its starting lore (0 kills). */
    public void initialize(ItemStack item, Weapon weapon) {
        if (weapon.getKillsConfig() != null) applyKills(item, weapon, 0);
    }

    private List<Upgrade> upgrades(Weapon weapon) {
        List<Upgrade> list = new ArrayList<>();

        for (Weapon.WeaponAction a : weapon.getActions()) {
            if (a.getRequiredKills() > 0) {
                list.add(new Upgrade(a.getRequiredKills(), "Ability: " + title(a.getAbility())));
            }
        }
        ConfigurationSection health = weapon.getBonusHealth();
        if (health != null) {
            int hearts = (int) Math.round(health.getDouble("hearts", 20));
            list.add(new Upgrade(health.getInt("kills", 10), hearts + " hearts while carried"));
        }
        ConfigurationSection shield = weapon.getShieldBypass();
        if (shield != null) {
            list.add(new Upgrade(shield.getInt("kills", 15), "Bypasses shields"));
        }
        list.sort(Comparator.comparingInt(u -> u.kills));
        return list;
    }

    // ---------- Counting kills ----------

    @EventHandler
    public void onDeath(org.bukkit.event.entity.EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        Player killer = dead.getKiller();
        if (killer == null || killer.equals(dead)) return;

        ItemStack hand = killer.getInventory().getItemInMainHand();
        Weapon weapon = manager.getWeapon(hand);
        if (weapon == null || weapon.getKillsConfig() == null) return;

        String count = weapon.getKillsConfig().getString("count", "ALL").toUpperCase(Locale.ROOT);
        if (count.equals("PLAYERS") && !(dead instanceof Player)) return;

        int before = getKills(hand);
        int after = before + 1;
        applyKills(hand, weapon, after);
        killer.getInventory().setItemInMainHand(hand);

        actionBar(killer, "&6Kills: &f" + after);

        for (Upgrade u : upgrades(weapon)) {
            if (before < u.kills && after >= u.kills) {
                manager.message(killer, "upgrade-unlocked", "{upgrade}", u.label);
                killer.playSound(killer.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
            }
        }
    }

    // ---------- Bonus health while carrying the weapon ----------

    private void healthTick() {
        if (maxHealth == null) return;

        Set<Double> boostValues = new HashSet<>();
        for (Weapon w : manager.getWeapons()) {
            ConfigurationSection b = w.getBonusHealth();
            if (b != null) boostValues.add(b.getDouble("hearts", 20) * 2);
        }
        if (boostValues.isEmpty()) return;

        for (Player p : Bukkit.getOnlinePlayers()) {
            double desired = 0;
            for (ItemStack item : p.getInventory().getContents()) {
                Weapon w = manager.getWeapon(item);
                if (w == null || w.getBonusHealth() == null) continue;
                ConfigurationSection b = w.getBonusHealth();
                if (getKills(item) >= b.getInt("kills", 10)) {
                    desired = Math.max(desired, b.getDouble("hearts", 20) * 2);
                }
            }

            AttributeInstance inst = p.getAttribute(maxHealth);
            if (inst == null) continue;
            double base = inst.getBaseValue();

            if (desired > 0) {
                if (base != desired) {
                    inst.setBaseValue(desired);
                    if (desired > base) {
                        p.setHealth(Math.min(desired, p.getHealth() + (desired - base)));
                    }
                }
            } else if (boostValues.contains(base)) {
                inst.setBaseValue(inst.getDefaultValue()); // mace gone: back to normal
            }
        }
    }

    // ---------- Shield bypass ----------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onShieldBypass(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;

        ItemStack hand = player.getInventory().getItemInMainHand();
        Weapon weapon = manager.getWeapon(hand);
        if (weapon == null || weapon.getShieldBypass() == null) return;
        if (getKills(hand) < weapon.getShieldBypass().getInt("kills", 15)) return;

        try {
            if (event.isApplicable(EntityDamageEvent.DamageModifier.BLOCKING)) {
                event.setDamage(EntityDamageEvent.DamageModifier.BLOCKING, 0.0);
            }
        } catch (Throwable ignored) {
            // modifier API not available on this server version
        }
    }

    // ---------- Combo + thunder ----------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (thunderActive) return; // damage dealt by the thunder attack itself doesn't count as a hit
        if (!(event.getDamager() instanceof Player player)) return;
        if (!(event.getEntity() instanceof LivingEntity target)) return;
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;

        Weapon weapon = manager.getWeapon(player.getInventory().getItemInMainHand());
        if (weapon == null || weapon.getCombo() == null) {
            combos.remove(player.getUniqueId()); // hitting with something else breaks the combo
            return;
        }

        ConfigurationSection cfg = weapon.getCombo();
        int hitsNeeded = Math.max(1, cfg.getInt("hits", 3));
        long timeoutMs = (long) (cfg.getDouble("timeout", 3) * 1000);
        boolean sameTarget = cfg.getBoolean("require-same-target", false);

        long now = System.currentTimeMillis();
        Combo combo = combos.computeIfAbsent(player.getUniqueId(), k -> new Combo());

        if (combo.hits > 0 && (now - combo.lastHit > timeoutMs
                || (sameTarget && !target.getUniqueId().equals(combo.lastTarget)))) {
            combo.hits = 0;
        }
        combo.hits++;
        combo.lastHit = now;
        combo.lastTarget = target.getUniqueId();

        boolean thunder = combo.hits >= hitsNeeded;
        hitEffect(target, combo.hits, hitsNeeded, thunder);
        actionBar(player, "&eCombo: &f" + combo.hits + "&7/&f" + hitsNeeded);

        if (thunder) {
            combo.hits = 0;
            thunderAttack(player, target, cfg.getConfigurationSection("thunder"));
        }
    }

    /** A ring of particles around the victim and a chime that rises with each hit in the combo. */
    private void hitEffect(LivingEntity target, int hit, int total, boolean thunder) {
        World world = target.getWorld();
        Location center = target.getLocation().add(0, target.getHeight() / 2.0, 0);

        double radius = 0.6 + 0.25 * hit;
        int points = 16 + 4 * hit;
        Particle particle = thunder ? Particle.ELECTRIC_SPARK : Particle.CRIT;
        for (int i = 0; i < points; i++) {
            double angle = 2 * Math.PI * i / points;
            Location p = center.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
            world.spawnParticle(particle, p, 1, 0, 0, 0, 0.02);
        }

        float pitch = 0.8f + 0.6f * (hit - 1) / Math.max(1, total - 1);
        world.playSound(center, Sound.BLOCK_NOTE_BLOCK_CHIME, 1.5f, Math.min(2f, pitch));
    }

    /** Visual lightning on the victim plus (optionally true) damage to everything around it. */
    private void thunderAttack(Player player, LivingEntity target, ConfigurationSection t) {
        double damage = t != null ? t.getDouble("damage", 8) : 8;
        double radius = t != null ? t.getDouble("radius", 4) : 4;
        boolean trueDamage = t == null || t.getBoolean("true-damage", true);

        Location loc = target.getLocation();
        World world = loc.getWorld();
        world.strikeLightningEffect(loc);
        world.spawnParticle(Particle.ELECTRIC_SPARK, loc.clone().add(0, 1, 0), 60, radius / 2, 1, radius / 2, 0.3);

        thunderActive = true;
        try {
            for (Entity e : world.getNearbyEntities(loc, radius, radius, radius)) {
                if (!(e instanceof LivingEntity living) || e.equals(player) || e instanceof ArmorStand) continue;
                if (e instanceof Player p && p.getGameMode() == GameMode.SPECTATOR) continue;
                if (e.getLocation().distanceSquared(loc) > radius * radius) continue;

                if (trueDamage) {
                    ActiveAbilities.TRUE_DAMAGE.add(living.getUniqueId());
                    try {
                        living.damage(damage, player);
                    } finally {
                        ActiveAbilities.TRUE_DAMAGE.remove(living.getUniqueId());
                    }
                } else {
                    living.damage(damage, player);
                }
            }
        } finally {
            thunderActive = false;
        }
    }

    // ---------- Helpers ----------

    private void actionBar(Player player, String text) {
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(color(text)));
    }

    private static String title(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1).toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}
