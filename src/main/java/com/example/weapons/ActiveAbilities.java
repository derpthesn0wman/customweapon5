package com.example.weapons;

import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.bukkit.Material;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Registry of abilities that are triggered by clicking with a weapon.
 *
 * To add your own: ActiveAbilities.register("NAME", (plugin, player, options) -> { ...; return true; });
 * then use "ability: NAME" under right-click: or sneak-right-click: in config.yml.
 */
public final class ActiveAbilities {

    private static final Map<String, ActiveAbility> REGISTRY = new TreeMap<>();

    /** Players who are immune to fall damage until the given time (ms) after dashing. */
    public static final Map<UUID, Long> NO_FALL_UNTIL = new HashMap<>();

    /** Entities currently being hit by a "true damage" attack (armor etc. is ignored for these hits). */
    public static final Set<UUID> TRUE_DAMAGE = new HashSet<>();

    static {
        // ---- DASH: fling yourself in the direction you're looking (any direction, incl. up/down) ----
        register("DASH", (plugin, player, options) -> {
            ConfigurationSection o = options != null ? options : new MemoryConfiguration();
            double power = o.getDouble("power", 1.8);
            double minLift = o.getDouble("min-lift", 0.35);
            double fallProtection = o.getDouble("fall-protection", 6);

            Vector velocity = player.getEyeLocation().getDirection().normalize().multiply(power);
            // Leave the ground a little, otherwise ground friction kills a flat dash instantly
            if (player.isOnGround() && velocity.getY() < minLift) {
                velocity.setY(minLift);
            }

            player.setVelocity(velocity);
            player.setFallDistance(0f);
            if (fallProtection > 0) {
                NO_FALL_UNTIL.put(player.getUniqueId(), System.currentTimeMillis() + (long) (fallProtection * 1000));
            }

            World world = player.getWorld();
            world.playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 0.8f);

            // Short particle trail while flying
            new org.bukkit.scheduler.BukkitRunnable() {
                int ticks = 0;

                @Override
                public void run() {
                    if (!player.isOnline() || ticks++ > 15) {
                        cancel();
                        return;
                    }
                    player.getWorld().spawnParticle(Particle.CLOUD,
                            player.getLocation().add(0, 1, 0), 4, 0.25, 0.25, 0.25, 0.01);
                }
            }.runTaskTimer(plugin, 0L, 1L);

            return true;
        });

        // ---- SONIC_BOOM: a warden-style beam that damages everything along a line ----
        register("SONIC_BOOM", (plugin, player, options) -> {
            ConfigurationSection o = options != null ? options : new MemoryConfiguration();
            double damage = o.getDouble("damage", 20);
            double range = o.getDouble("range", 20);
            double knockback = o.getDouble("knockback", 1.5);
            double hitRadius = o.getDouble("hit-radius", 0.8);
            boolean stopAtBlocks = o.getBoolean("stop-at-blocks", false);
            boolean hitPlayers = o.getBoolean("hit-players", true);
            boolean trueDamage = o.getBoolean("true-damage", true);

            Location eye = player.getEyeLocation();
            World world = eye.getWorld();
            Vector dir = eye.getDirection().normalize();
            Vector origin = eye.toVector();

            double maxDist = range;
            if (stopAtBlocks) {
                RayTraceResult blockHit = world.rayTraceBlocks(eye, dir, range, FluidCollisionMode.NEVER, true);
                if (blockHit != null) maxDist = blockHit.getHitPosition().distance(origin);
            }

            // Visuals + sound
            for (double d = 1.0; d <= maxDist; d += 1.0) {
                world.spawnParticle(Particle.SONIC_BOOM, eye.clone().add(dir.clone().multiply(d)), 1, 0, 0, 0, 0);
            }
            world.playSound(eye, Sound.ENTITY_WARDEN_SONIC_BOOM, 3f, 1f);

            // Damage everything the beam passes through
            Location mid = eye.clone().add(dir.clone().multiply(maxDist / 2.0));
            double half = maxDist / 2.0 + 2.0;
            for (Entity e : world.getNearbyEntities(mid, half, half, half)) {
                if (!(e instanceof LivingEntity living) || e.equals(player) || e instanceof ArmorStand) continue;
                if (e instanceof Player target) {
                    if (!hitPlayers || target.getGameMode() == GameMode.SPECTATOR) continue;
                }

                BoundingBox box = e.getBoundingBox().expand(hitRadius);
                if (box.rayTrace(origin, dir, maxDist) == null) continue;

                if (trueDamage) {
                    TRUE_DAMAGE.add(living.getUniqueId());
                    try {
                        living.damage(damage, player);
                    } finally {
                        TRUE_DAMAGE.remove(living.getUniqueId());
                    }
                } else {
                    living.damage(damage, player);
                }

                if (knockback > 0) {
                    Vector push = dir.clone().multiply(knockback);
                    push.setY(Math.max(push.getY(), 0.3));
                    living.setVelocity(living.getVelocity().add(push));
                }
            }
            return true;
        });

        // ---- BLIND_AREA: blind everyone within a radius ----
        register("BLIND_AREA", (plugin, player, options) -> {
            ConfigurationSection o = options != null ? options : new MemoryConfiguration();
            double radius = o.getDouble("radius", 50);
            int ticks = (int) Math.round(o.getDouble("duration", 30) * 20);
            int amplifier = o.getInt("amplifier", 255);
            boolean excludeSelf = o.getBoolean("exclude-self", true);
            boolean includeMobs = o.getBoolean("include-mobs", false);

            Location origin = player.getLocation();
            PotionEffect effect = new PotionEffect(PotionEffectType.BLINDNESS, ticks, amplifier);

            int count = 0;
            for (Entity e : player.getNearbyEntities(radius, radius, radius)) {
                if (!(e instanceof LivingEntity living)) continue;
                if (!(e instanceof Player) && !includeMobs) continue;
                if (e instanceof Player p && p.getGameMode() == GameMode.SPECTATOR) continue;
                if (!e.getWorld().equals(origin.getWorld())) continue;
                if (e.getLocation().distanceSquared(origin) > radius * radius) continue;

                living.addPotionEffect(effect);
                count++;

                if (e instanceof Player victim) {
                    victim.playSound(victim.getLocation(), Sound.ENTITY_ELDER_GUARDIAN_CURSE, 1f, 1f);
                    plugin.getWeaponManager().message(victim, "blinded", "{player}", player.getName());
                }
            }

            if (count == 0) {
                plugin.getWeaponManager().message(player, "blind-no-targets");
                return false;
            }

            if (!excludeSelf) player.addPotionEffect(effect);

            player.getWorld().spawnParticle(Particle.CLOUD, origin.clone().add(0, 1, 0), 80, 1, 1, 1, 0.1);
            plugin.getWeaponManager().message(player, "blind-used", "{count}", String.valueOf(count));
            return true;
        });
    }

    private ActiveAbilities() {}

    public static void register(String name, ActiveAbility ability) {
        REGISTRY.put(name.toUpperCase(Locale.ROOT), ability);
    }

    public static ActiveAbility get(String name) {
        return name == null ? null : REGISTRY.get(name.toUpperCase(Locale.ROOT));
    }

    public static Set<String> names() {
        return REGISTRY.keySet();
    }
}
