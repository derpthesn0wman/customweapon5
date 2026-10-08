package com.example.weapons;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Handles "hold right-click to charge" weapons (hold-right-click: in config.yml)
 * and the passive slow-falling effect (slow-falling: true).
 *
 * Holding right-click is detected two ways, so it works whether or not the item has a "use" animation:
 *  - the player is actively using the item (player.isHandRaised()), or
 *  - the client keeps re-sending right-click events every few ticks (we allow a small gap between them).
 */
public class ChargeManager {

    private static final int CLICK_GRACE_TICKS = 6;

    private final WeaponPlugin plugin;
    private final WeaponManager manager;
    private final Map<UUID, Charge> charges = new HashMap<>();
    private BukkitTask task;
    private long tick = 0;

    private static class Charge {
        final String weaponId;
        final Weapon.WeaponAction action;
        final String cooldownKey;
        final int chargeTicks;
        final int stages;
        final int stageTicks;
        int progress = 0;
        long lastClick;

        Charge(String weaponId, Weapon.WeaponAction action, String cooldownKey, int chargeTicks, long now) {
            this.weaponId = weaponId;
            this.action = action;
            this.cooldownKey = cooldownKey;
            this.chargeTicks = Math.max(1, chargeTicks);
            this.stages = Math.max(1, action.getStages());
            this.stageTicks = Math.max(1, this.chargeTicks / this.stages);
            this.lastClick = now;
        }
    }

    public ChargeManager(WeaponPlugin plugin, WeaponManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    public void start() {
        stop();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::run, 1L, 1L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        charges.clear();
    }

    /** Called whenever the player right-clicks with a hold-to-charge weapon. */
    public void click(Player player, Weapon weapon, Weapon.WeaponAction action) {
        Charge existing = charges.get(player.getUniqueId());
        if (existing != null && existing.weaponId.equals(weapon.getId())) {
            existing.lastClick = tick; // still holding
            return;
        }

        String key = weapon.getId() + ":" + action.getName();
        double remaining = manager.getRemainingCooldown(player.getUniqueId(), key);
        if (remaining > 0) {
            String raw = plugin.getConfig().getString("messages.cooldown", "");
            if (!raw.isBlank()) {
                actionBar(player, manager.format("cooldown", "{time}", String.format("%.0f", Math.ceil(remaining))));
            }
            return;
        }

        int chargeTicks = (int) Math.round(action.getChargeTime() * 20);
        charges.put(player.getUniqueId(), new Charge(weapon.getId(), action, key, chargeTicks, tick));
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_WARDEN_SONIC_CHARGE, 1.5f, 1f);
    }

    private void run() {
        tick++;

        if (tick % 4 == 0) applySlowFalling();

        Iterator<Map.Entry<UUID, Charge>> it = charges.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Charge> entry = it.next();
            Charge c = entry.getValue();
            Player player = Bukkit.getPlayer(entry.getKey());

            if (player == null || !player.isOnline() || player.isDead()) {
                it.remove();
                continue;
            }

            Weapon held = manager.getWeapon(player.getInventory().getItemInMainHand());
            boolean holding = held != null && held.getId().equals(c.weaponId);
            boolean pressing = player.isHandRaised() || tick - c.lastClick <= CLICK_GRACE_TICKS;

            if (!holding || !pressing) {
                it.remove(); // let go too early (or switched items): charge is lost, no cooldown spent
                continue;
            }

            c.progress++;

            if (c.progress % 3 == 0) {
                player.getWorld().spawnParticle(Particle.SCULK_SOUL,
                        player.getLocation().add(0, 1, 0), 2, 0.4, 0.6, 0.4, 0.01);
            }
            if (c.progress % c.stageTicks == 0) {
                int stage = c.progress / c.stageTicks;
                if (stage <= c.stages) stageEffect(player, stage, c.stages);
            }

            actionBar(player, progressBar(c));

            if (c.progress >= c.chargeTicks) {
                it.remove();
                release(player, held, c);
            }
        }
    }

    private void release(Player player, Weapon weapon, Charge c) {
        ActiveAbility ability = ActiveAbilities.get(c.action.getAbility());
        if (ability == null) return;

        boolean used = ability.use(plugin, player, c.action.getOptions());
        if (!used) return;

        manager.startCooldown(player.getUniqueId(), c.cooldownKey, c.action.getCooldown());

        if (weapon.usesMinecraftCooldown()) {
            int ticks = (int) Math.round(c.action.getCooldown() * 20);
            if (ticks > 0) player.setCooldown(player.getInventory().getItemInMainHand().getType(), ticks);
        }
    }

    /** Slow falling while holding a weapon with slow-falling: true. Short, refreshed effect so it ends when you put it away. */
    private void applySlowFalling() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Weapon w = manager.getWeapon(p.getInventory().getItemInMainHand());
            if (w == null || !w.hasSlowFalling()) continue;

            PotionEffect current = p.getPotionEffect(PotionEffectType.SLOW_FALLING);
            if (current != null && current.getDuration() > 20) continue; // don't shorten a longer real effect

            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 12, 0, true, false, false));
        }
    }

    /** A ding (rising in pitch) and an expanding particle ring each time a charge stage completes. */
    private void stageEffect(Player player, int stage, int stages) {
        World world = player.getWorld();
        Location base = player.getLocation().add(0, 1.0, 0);

        double radius = 0.7 + 0.15 * stage;
        int points = 12 + 4 * stage;
        for (int i = 0; i < points; i++) {
            double angle = 2 * Math.PI * i / points;
            Location p = base.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
            world.spawnParticle(Particle.END_ROD, p, 1, 0, 0.05, 0, 0.02);
        }
        world.spawnParticle(Particle.SCULK_SOUL, base, 6 + 2 * stage, 0.3, 0.5, 0.3, 0.05);

        float pitch = Math.min(2.0f, 0.8f + 1.2f * stage / stages);
        world.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1.5f, pitch);
    }

    private String progressBar(Charge c) {
        int bars = 20;
        int filled = (int) Math.round(bars * (double) c.progress / c.chargeTicks);
        StringBuilder sb = new StringBuilder("\u00a75Charging \u00a78[");
        for (int i = 0; i < bars; i++) {
            sb.append(i < filled ? "\u00a7d|" : "\u00a77|");
        }
        sb.append("\u00a78]");
        return sb.toString();
    }

    private void actionBar(Player player, String text) {
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(text));
    }
}
