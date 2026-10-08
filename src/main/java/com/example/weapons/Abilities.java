package com.example.weapons;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Registry of all abilities a weapon can use.
 *
 * To add a brand new ability in code, call Abilities.register("NAME", (plugin, shooter, target, options) -> { ... });
 * (the static block below is a good place), then use "ability: NAME" in config.yml.
 */
public final class Abilities {

    private static final Map<String, Ability> REGISTRY = new TreeMap<>();

    static {
        // ---- SWAP: shooter and target trade places ----
        register("SWAP", (plugin, shooter, target, options) -> {
            boolean keepRotation = options == null || options.getBoolean("keep-rotation", true);

            Location shooterLoc = shooter.getLocation();
            Location targetLoc = target.getLocation();

            Location shooterDest = targetLoc.clone();
            Location targetDest = shooterLoc.clone();

            if (keepRotation) {
                shooterDest.setYaw(shooterLoc.getYaw());
                shooterDest.setPitch(shooterLoc.getPitch());
                targetDest.setYaw(targetLoc.getYaw());
                targetDest.setPitch(targetLoc.getPitch());
            }

            // Particles at the old positions, sounds at the new ones
            shooterLoc.getWorld().spawnParticle(Particle.PORTAL, shooterLoc.clone().add(0, 1, 0), 40, 0.4, 0.8, 0.4, 0.2);
            targetLoc.getWorld().spawnParticle(Particle.PORTAL, targetLoc.clone().add(0, 1, 0), 40, 0.4, 0.8, 0.4, 0.2);

            shooter.teleport(shooterDest);
            target.teleport(targetDest);

            shooterDest.getWorld().playSound(shooterDest, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
            targetDest.getWorld().playSound(targetDest, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);

            plugin.getWeaponManager().message(shooter, "swapped", "{target}", target.getName());
            if (target instanceof Player targetPlayer) {
                plugin.getWeaponManager().message(targetPlayer, "was-swapped", "{shooter}", shooter.getName());
            }
        });

        // ---- SCRAMBLE: shuffle the target player's inventory ----
        register("SCRAMBLE", (plugin, shooter, target, options) -> {
            if (!(target instanceof Player victim)) return;
            boolean includeArmor = options != null && options.getBoolean("include-armor", false);
            boolean includeOffhand = options == null || options.getBoolean("include-offhand", true);

            PlayerInventory inv = victim.getInventory();

            // Slots: 0-8 hotbar, 9-35 main inventory, 36-39 armor, 40 off-hand
            List<Integer> slots = new ArrayList<>();
            for (int i = 0; i <= 35; i++) slots.add(i);
            if (includeArmor) {
                for (int i = 36; i <= 39; i++) slots.add(i);
            }
            if (includeOffhand) slots.add(40);

            List<ItemStack> items = new ArrayList<>();
            for (int slot : slots) {
                ItemStack item = inv.getItem(slot);
                items.add(item == null ? null : item.clone());
            }
            Collections.shuffle(items); // empty slots are shuffled too, so items end up in random places

            for (int i = 0; i < slots.size(); i++) {
                inv.setItem(slots.get(i), items.get(i));
            }
            victim.updateInventory();

            victim.getWorld().spawnParticle(Particle.PORTAL, victim.getLocation().add(0, 1, 0), 40, 0.4, 0.8, 0.4, 0.3);
            victim.getWorld().playSound(victim.getLocation(), Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1f, 1f);

            plugin.getWeaponManager().message(shooter, "scrambled-attacker", "{target}", victim.getName());
            plugin.getWeaponManager().message(victim, "scrambled-victim", "{player}", shooter.getName());
        });

        // ---- LAUNCH: throw the target into the air ----
        register("LAUNCH", (plugin, shooter, target, options) -> {
            double power = options == null ? 1.2 : options.getDouble("power", 1.2);
            target.setVelocity(target.getVelocity().setY(power));
        });

        // ---- LIGHTNING: strike the target ----
        register("LIGHTNING", (plugin, shooter, target, options) -> {
            boolean harmless = options != null && options.getBoolean("harmless", false);
            if (harmless) {
                target.getWorld().strikeLightningEffect(target.getLocation());
            } else {
                target.getWorld().strikeLightning(target.getLocation());
            }
        });

        // ---- EFFECT: apply a potion effect to target or shooter ----
        register("EFFECT", (plugin, shooter, target, options) -> {
            if (options == null) return;
            String name = options.getString("effect", "slowness").toLowerCase(Locale.ROOT);
            PotionEffectType type = PotionEffectType.getByKey(NamespacedKey.minecraft(name));
            if (type == null) {
                plugin.getLogger().warning("Unknown potion effect in config: " + name);
                return;
            }
            int ticks = (int) (options.getDouble("duration", 5) * 20);
            int amplifier = options.getInt("amplifier", 0);
            boolean onShooter = "SHOOTER".equalsIgnoreCase(options.getString("apply-to", "TARGET"));

            LivingEntity recipient = onShooter ? shooter : target;
            recipient.addPotionEffect(new PotionEffect(type, ticks, amplifier));
        });
    }

    private Abilities() {}

    public static void register(String name, Ability ability) {
        REGISTRY.put(name.toUpperCase(Locale.ROOT), ability);
    }

    public static Ability get(String name) {
        return name == null ? null : REGISTRY.get(name.toUpperCase(Locale.ROOT));
    }

    public static Set<String> names() {
        return REGISTRY.keySet();
    }
}
