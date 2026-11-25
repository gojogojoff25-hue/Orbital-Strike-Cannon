package me.noname.orbital;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * OrbitalStrike v3.2
 * - Tự tạo folder plugins/OrbitalStrike/ (config.yml + messages.yml)
 * - Cần câu thường: câu cá bình thường
 * - Orbital: kèn Call + Resistance + TNT rơi thẳng, nổ tại chỗ, không nảy, không sét
 */
public final class OrbitalStrike extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private static final String ITEM_NAME = "orbital strike cannon";

    private NamespacedKey key;
    private FileConfiguration messages;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Map<UUID, Long> protectUntil = new HashMap<>();
    private final Set<UUID> busy = new HashSet<>();
    private final Set<TNTPrimed> activeBombs = new HashSet<>();

    @Override
    public void onEnable() {
        // ===== TỰ TẠO THƯ MỤC plugins/OrbitalStrike/ =====
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
            getLogger().info("Da tao thu muc: " + getDataFolder().getAbsolutePath());
        }

        saveDefaultConfig();
        loadMessages();

        key = new NamespacedKey(this, "orbital_cannon");
        getServer().getPluginManager().registerEvents(this, this);

        PluginCommand cmd = getCommand("orbital");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }

        getLogger().info("OrbitalStrike v3.2 da bat. Owner: " + ownerName());
        getLogger().info("Config folder: " + getDataFolder().getAbsolutePath());
    }

    @Override
    public void onDisable() {
        for (TNTPrimed bomb : activeBombs) {
            if (bomb != null && bomb.isValid()) bomb.remove();
        }
        activeBombs.clear();
        busy.clear();
        protectUntil.clear();
        cooldowns.clear();
    }

    // ===================== Messages =====================
    private void loadMessages() {
        File file = new File(getDataFolder(), "messages.yml");
        if (!file.exists()) {
            saveResource("messages.yml", false);
        }
        messages = YamlConfiguration.loadConfiguration(file);

        // Merge default nếu thiếu key
        InputStream defStream = getResource("messages.yml");
        if (defStream != null) {
            YamlConfiguration def = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defStream, StandardCharsets.UTF_8));
            messages.setDefaults(def);
        }
    }

    private String msg(String path) {
        String raw = messages.getString(path, path);
        return ChatColor.translateAlternateColorCodes('&', raw);
    }

    private String msg(String path, String placeholder, String value) {
        return msg(path).replace(placeholder, value);
    }

    // ===================== Owner =====================
    private String ownerName() {
        return getConfig().getString("owner", "NoName334301");
    }

    private boolean isOwnerPlayer(Player player) {
        if (player == null) return false;
        if (!player.getName().equalsIgnoreCase(ownerName())) return false;
        String uuid = getConfig().getString("owner-uuid", "");
        return uuid == null || uuid.isBlank() || uuid.equalsIgnoreCase(player.getUniqueId().toString());
    }

    private boolean isOwner(CommandSender sender) {
        if (sender instanceof ConsoleCommandSender) return true;
        return sender instanceof Player player && isOwnerPlayer(player);
    }

    // ===================== Item =====================
    private ItemStack createCannon() {
        ItemStack item = new ItemStack(Material.FISHING_ROD);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        if (meta instanceof Damageable damageable) {
            int max = Material.FISHING_ROD.getMaxDurability();
            int left = Math.max(1, Math.min(max, getConfig().getInt("rod-durability-left", 1)));
            damageable.setDamage(max - left);
        }
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isCannon(ItemStack item) {
        if (item == null || item.getType() != Material.FISHING_ROD || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    private void giveTo(CommandSender sender, Player target) {
        Map<Integer, ItemStack> leftover = target.getInventory().addItem(createCannon());
        for (ItemStack stack : leftover.values()) {
            target.getWorld().dropItemNaturally(target.getLocation(), stack);
        }
        sender.sendMessage(msg("give-success", "%player%", target.getName()));
        target.sendMessage(msg("receive-cannon"));
    }

    private Player resolveTarget(CommandSender sender, String name) {
        if (name.equalsIgnoreCase("@s") || name.equalsIgnoreCase("@p")) {
            return sender instanceof Player player ? player : null;
        }
        Player target = Bukkit.getPlayerExact(name);
        if (target == null && name.startsWith("@")) {
            target = Bukkit.getPlayerExact(name.substring(1));
        }
        return target;
    }

    private void handleGive(CommandSender sender, String targetName) {
        if (!isOwner(sender)) {
            sender.sendMessage(msg("only-owner"));
            return;
        }
        Player target = resolveTarget(sender, targetName);
        if (target == null) {
            sender.sendMessage(msg("player-not-found", "%player%", targetName));
            return;
        }
        giveTo(sender, target);
    }

    private String parseGive(String raw) {
        String msg = raw.trim();
        if (msg.startsWith("/")) msg = msg.substring(1);
        String[] parts = msg.split("\\s+", 3);
        if (parts.length < 3) return null;
        String cmd = parts[0].toLowerCase();
        if (!cmd.equals("give") && !cmd.equals("minecraft:give")) return null;
        String item = parts[2].toLowerCase()
                .replace("\"", "")
                .replace('_', ' ')
                .replaceAll("\\s+\\d+$", "")
                .trim();
        return item.equals(ITEM_NAME) ? parts[1] : null;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerCmd(PlayerCommandPreprocessEvent event) {
        String target = parseGive(event.getMessage());
        if (target == null) return;
        event.setCancelled(true);
        handleGive(event.getPlayer(), target);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onConsoleCmd(ServerCommandEvent event) {
        String target = parseGive(event.getCommand());
        if (target == null) return;
        event.setCancelled(true);
        handleGive(event.getSender(), target);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!isOwner(sender)) {
            sender.sendMessage(msg("only-owner"));
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            loadMessages();
            sender.sendMessage(msg("reload-success"));
            return true;
        }
        String self = sender instanceof Player player ? player.getName() : null;
        String name;
        if (args.length > 0 && args[0].equalsIgnoreCase("give")) {
            name = args.length > 1 ? args[1] : self;
        } else {
            name = args.length > 0 ? args[0] : self;
        }
        if (name == null) {
            sender.sendMessage(msg("usage"));
            return true;
        }
        handleGive(sender, name);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!isOwner(sender)) return Collections.emptyList();
        List<String> suggestions = new ArrayList<>();
        if (args.length == 1) {
            suggestions.add("give");
            suggestions.add("reload");
        }
        if (args.length <= 2) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                suggestions.add(online.getName());
            }
        }
        String prefix = args[args.length - 1].toLowerCase();
        suggestions.removeIf(s -> !s.toLowerCase().startsWith(prefix));
        return suggestions;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

        ItemStack item = event.getItem();
        if (!isCannon(item)) return;

        EquipmentSlot hand = event.getHand();
        if (hand == null) return;

        event.setCancelled(true);

        Player player = event.getPlayer();
        if (!isOwnerPlayer(player)) {
            player.sendMessage(msg("not-owner-weapon"));
            return;
        }

        UUID uuid = player.getUniqueId();
        if (busy.contains(uuid)) return;

        long now = System.currentTimeMillis();
        long cooldownMs = (long) (getConfig().getDouble("cooldown-seconds", 10.0) * 1000);
        Long last = cooldowns.get(uuid);
        if (last != null && now - last < cooldownMs) {
            long remain = (cooldownMs - (now - last)) / 1000 + 1;
            player.sendMessage(msg("cooldown", "%seconds%", String.valueOf(remain)));
            return;
        }

        Location target;
        if ("LOOK".equalsIgnoreCase(getConfig().getString("mode", "SELF"))) {
            Block block = player.getTargetBlockExact(getConfig().getInt("look-range", 300));
            if (block == null) {
                player.sendMessage(msg("no-target"));
                return;
            }
            target = block.getLocation().add(0.5, 1.0, 0.5);
        } else {
            target = player.getLocation().clone();
        }

        cooldowns.put(uuid, now);
        consumeRod(player, item, hand);
        playCallHorn(player.getLocation());
        applyResistance(player);
        strike(player, target);
    }

    private void playCallHorn(Location loc) {
        World world = loc.getWorld();
        if (world == null) return;
        try {
            world.playSound(loc, Sound.ITEM_GOAT_HORN_SOUND_5, 5.0f, 1.0f);
        } catch (Exception e) {
            world.playSound(loc, "item.goat_horn.sound.5", 5.0f, 1.0f);
        }
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    world.playSound(loc, Sound.ITEM_GOAT_HORN_SOUND_5, 4.0f, 1.0f);
                } catch (Exception ignored) {
                    world.playSound(loc, "item.goat_horn.sound.5", 4.0f, 1.0f);
                }
            }
        }.runTaskLater(this, 2L);
    }

    private void applyResistance(Player player) {
        if (!getConfig().getBoolean("give-resistance", true)) return;
        int duration = getConfig().getInt("resistance-duration-ticks", 200);
        int amplifier = getConfig().getInt("resistance-amplifier", 4);
        player.addPotionEffect(new PotionEffect(
                PotionEffectType.RESISTANCE, duration, amplifier, false, true, true
        ));
    }

    private void setHand(Player player, EquipmentSlot hand, ItemStack item) {
        if (hand == EquipmentSlot.OFF_HAND) {
            player.getInventory().setItemInOffHand(item);
        } else {
            player.getInventory().setItemInMainHand(item);
        }
    }

    private void consumeRod(Player player, ItemStack item, EquipmentSlot hand) {
        int max = Material.FISHING_ROD.getMaxDurability();
        ItemMeta meta = item.getItemMeta();
        int newDamage = max;
        if (meta instanceof Damageable current) {
            newDamage = current.getDamage() + 1;
        }
        if (newDamage >= max) {
            setHand(player, hand, null);
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
        } else if (meta instanceof Damageable updatable) {
            updatable.setDamage(newDamage);
            ItemStack copy = item.clone();
            copy.setItemMeta(updatable);
            setHand(player, hand, copy);
        }
    }

    private void strike(Player owner, Location target) {
        FileConfiguration config = getConfig();
        World world = target.getWorld();
        if (world == null) return;

        final int chargeTicks = Math.max(2, (int) (config.getDouble("charge-seconds", 3.0) * 20));
        final boolean fx = config.getBoolean("effects", true);
        final double speed = Math.max(0.8, config.getDouble("fall-speed", 3.0));

        double drop = Math.min(config.getDouble("height", 150.0), world.getMaxHeight() - target.getY() - 2);
        if (world.getEnvironment() == World.Environment.NETHER) {
            drop = Math.min(drop, 120 - target.getY());
        }
        if (drop < 10) drop = 10;
        final double dropFinal = drop;
        final int fallTicks = (int) Math.ceil(dropFinal / speed);

        if (config.getBoolean("owner-immune", true)) {
            long protectMs = (chargeTicks + fallTicks + 80L) * 50L;
            protectUntil.put(owner.getUniqueId(), System.currentTimeMillis() + protectMs);
        }
        busy.add(owner.getUniqueId());

        new BukkitRunnable() {
            int tick = 0;

            @Override
            public void run() {
                if (tick >= chargeTicks) {
                    cancel();
                    try {
                        launch(owner, target, dropFinal, speed, fallTicks);
                    } catch (Exception ex) {
                        busy.remove(owner.getUniqueId());
                        getLogger().warning("Loi khi thuc hien don danh: " + ex.getMessage());
                        ex.printStackTrace();
                    }
                    return;
                }
                if (fx) {
                    for (double y = 0; y < dropFinal + 20; y += 2.0) {
                        world.spawnParticle(Particle.END_ROD,
                                target.getX(), target.getY() + y, target.getZ(),
                                1, 0.1, 0, 0.1, 0);
                    }
                    double progress = (double) tick / chargeTicks;
                    double radius = 28 * (1 - progress) + 1.2;
                    for (int i = 0; i < 360; i += 15) {
                        double rad = Math.toRadians(i);
                        world.spawnParticle(Particle.FLAME,
                                target.getX() + Math.cos(rad) * radius,
                                target.getY() + 0.3,
                                target.getZ() + Math.sin(rad) * radius,
                                1, 0, 0, 0, 0);
                    }
                    if (tick % 10 == 0) {
                        world.playSound(target, Sound.BLOCK_BEACON_POWER_SELECT, 3.0f, (float) (0.5 + progress * 0.7));
                    }
                }
                tick += 2;
            }
        }.runTaskTimer(this, 0L, 2L);
    }

    private void launch(Player owner, Location target, double drop, double speed, int fallTicks) {
        FileConfiguration config = getConfig();
        World world = target.getWorld();
        if (world == null) {
            busy.remove(owner.getUniqueId());
            return;
        }

        final float power = (float) config.getDouble("tnt-power", 12.0);
        final int perRadius = Math.max(1, config.getInt("tnt-per-radius", 2));
        final int maxTnt = Math.max(1, config.getInt("max-tnt", 800));
        final int spread = Math.max(0, config.getInt("detonation-spread-ticks", 0));
        final boolean fx = config.getBoolean("effects", true);

        List<Integer> rings = config.getIntegerList("rings");
        if (rings.isEmpty()) {
            rings = Arrays.asList(0, 6, 12, 18, 24, 30, 36);
        }

        if (fx) {
            world.playSound(target, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 6.0f, 0.5f);
        }

        final Vector down = new Vector(0, -speed, 0);
        final List<TNTPrimed> bombs = new ArrayList<>();

        outer:
        for (int radius : rings) {
            int count = radius <= 0 ? 1 : radius * perRadius;
            for (int k = 0; k < count; k++) {
                if (bombs.size() >= maxTnt) break outer;

                double angle = 2 * Math.PI * k / count;
                Location spawnLoc = target.clone().add(
                        Math.cos(angle) * radius,
                        drop,
                        Math.sin(angle) * radius
                );

                TNTPrimed tnt = world.spawn(spawnLoc, TNTPrimed.class, entity -> {
                    entity.setFuseTicks(99999);
                    entity.setYield(power);
                    entity.setSource(owner);
                    entity.setGravity(false);
                    entity.setVelocity(down.clone());
                    entity.setSilent(true);
                    entity.setInvulnerable(true);
                    entity.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
                });

                bombs.add(tnt);
                activeBombs.add(tnt);
            }
        }

        new BukkitRunnable() {
            int tick = 0;

            @Override
            public void run() {
                tick++;

                for (TNTPrimed bomb : bombs) {
                    if (bomb.isValid()) {
                        bomb.setGravity(false);
                        bomb.setVelocity(down.clone());
                    }
                }

                if (tick < fallTicks) return;

                // Xóa entity rồi createExplosion → tuyệt đối không nảy
                int index = 0;
                for (TNTPrimed bomb : bombs) {
                    if (!bomb.isValid()) {
                        index++;
                        continue;
                    }

                    Location boomLoc = bomb.getLocation().clone();
                    if (boomLoc.getY() < target.getY() + 1.5) {
                        boomLoc.setY(target.getY() + 1.2);
                    }

                    bomb.remove();
                    activeBombs.remove(bomb);

                    final int delay = (spread > 0) ? (index % (spread + 1)) : 0;
                    final float p = power;
                    final Location loc = boomLoc;

                    if (delay <= 0) {
                        world.createExplosion(loc, p, false, true, owner);
                    } else {
                        new BukkitRunnable() {
                            @Override
                            public void run() {
                                world.createExplosion(loc, p, false, true, owner);
                            }
                        }.runTaskLater(OrbitalStrike.this, delay);
                    }
                    index++;
                }

                activeBombs.removeAll(bombs);
                busy.remove(owner.getUniqueId());

                if (fx) {
                    world.playSound(target, Sound.ENTITY_GENERIC_EXPLODE, 12.0f, 0.4f);
                    world.playSound(target, Sound.ENTITY_GENERIC_EXPLODE, 9.0f, 0.55f);
                }
                cancel();
            }
        }.runTaskTimer(this, 1L, 1L);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onExplode(EntityExplodeEvent event) {
        if (getConfig().getBoolean("drop-items", false)) return;

        Entity entity = event.getEntity();
        if (entity instanceof TNTPrimed tnt
                && tnt.getPersistentDataContainer().has(key, PersistentDataType.BYTE)) {
            event.setYield(0f);
            return;
        }
        if (entity instanceof Player p && busy.contains(p.getUniqueId())) {
            event.setYield(0f);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        Long until = protectUntil.get(player.getUniqueId());
        if (until == null) return;
        if (System.currentTimeMillis() > until) {
            protectUntil.remove(player.getUniqueId());
            return;
        }
        EntityDamageEvent.DamageCause cause = event.getCause();
        if (cause == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
                || cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION
                || cause == EntityDamageEvent.DamageCause.FALL
                || cause == EntityDamageEvent.DamageCause.FALLING_BLOCK
                || cause == EntityDamageEvent.DamageCause.SUFFOCATION) {
            event.setCancelled(true);
        }
    }
}
