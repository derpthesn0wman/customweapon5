package com.example.weapons;

import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Registry of abilities that run when a weapon is fired ("on-shoot:" in config.yml).
 *
 * To add your own: ShootAbilities.register("NAME", (plugin, shooter, projectile, options) -> { ... });
 */
public final class ShootAbilities {

    private static final Map<String, ShootAbility> REGISTRY = new TreeMap<>();

    static {
        // ---- RIDE: you ride the arrow (no gravity) until you crouch off, which deletes the arrow ----
        register("RIDE", (plugin, shooter, projectile, options) -> {
            ConfigurationSection o = options != null ? options : new MemoryConfiguration();
            double speed = o.getDouble("speed", 2.5);
            double protection = o.getDouble("fall-protection", 8);
            WeaponManager manager = plugin.getWeaponManager();
            NamespacedKey rideKey = manager.getRideKey();

            // Mark it as a ride arrow (value = seconds of fall protection after hopping off)
            projectile.getPersistentDataContainer().set(rideKey, PersistentDataType.DOUBLE, protection);
            projectile.setGravity(false);
            if (projectile instanceof AbstractArrow arrow) {
                arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
            }

            // If you're still riding a previous arrow, get off it first
            Entity old = shooter.getVehicle();
            if (old != null && manager.isRideArrow(old)) {
                shooter.leaveVehicle();
                old.remove();
            }

            // The arrow isn't in the world yet during the shoot event, so mount on the next tick
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!projectile.isValid() || !shooter.isOnline()) return;

                if (speed > 0) {
                    projectile.setVelocity(shooter.getEyeLocation().getDirection().normalize().multiply(speed));
                }
                projectile.addPassenger(shooter);

                // Safety net in case the arrow vanishes mid-air (despawns, hits something that removes it)
                ActiveAbilities.NO_FALL_UNTIL.put(shooter.getUniqueId(), System.currentTimeMillis() + 60_000L);
                shooter.playSound(shooter.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 0.7f);
            });
        });
    }

    private ShootAbilities() {}

    /** Gets the player off a ride arrow and deletes the arrow. */
    public static void endRide(WeaponManager manager, Player player, Entity arrow) {
        Double protection = arrow.getPersistentDataContainer().get(manager.getRideKey(), PersistentDataType.DOUBLE);
        double seconds = protection != null ? protection : 8;

        player.leaveVehicle();
        arrow.remove();

        player.setFallDistance(0f);
        if (seconds > 0) {
            ActiveAbilities.NO_FALL_UNTIL.put(player.getUniqueId(), System.currentTimeMillis() + (long) (seconds * 1000));
        } else {
            ActiveAbilities.NO_FALL_UNTIL.remove(player.getUniqueId());
        }
    }

    public static void register(String name, ShootAbility ability) {
        REGISTRY.put(name.toUpperCase(Locale.ROOT), ability);
    }

    public static ShootAbility get(String name) {
        return name == null ? null : REGISTRY.get(name.toUpperCase(Locale.ROOT));
    }

    public static Set<String> names() {
        return REGISTRY.keySet();
    }
}
