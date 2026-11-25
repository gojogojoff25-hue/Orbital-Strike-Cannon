package me.noname.orbital;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
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
import org.bukkit.scheduler.BukkitRunnable;

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
 * OrbitalStrike v2.0
 * - Cần câu "Fishing Rod" thường (không tên, không enchant), chỉ owner dùng được.
 * - Chuột phải: gọi đòn Orbital Strike, TNT rơi cùng lúc và nổ cùng một tick.
 * - Mọi thông số chỉnh trong plugins/OrbitalStrike/config.yml, dùng /orbital reload để áp dụng.
 */
public class OrbitalStrike extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private static final String ITEM_NAME = "orbital strike cannon"; // tên dùng trong lệnh /give

    private NamespacedKey key;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Map<UUID, Long> protectUntil = new HashMap<>();
    private final Set<UUID> busy = new HashSet<>();
    private final Set<TNTPrimed> activeBombs = new HashSet<>();

    // =====================================================================
    //  Khởi động / tắt
    // =====================================================================
    @Override
    public void onEnable() {
        saveDefaultConfig();
        key = new NamespacedKey(this, "orbital_cannon");
        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand cmd = getCommand("orbital");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        getLogger().info("OrbitalStrike v2.0 da bat. Owner: " + ownerName());
    }

    @Override
    public void onDisable() {
        for (TNTPrimed b : activeBombs) {
            if (b.isValid()) b.remove();
        }
        activeBombs.clear();
        busy.clear();
    }

    // =====================================================================
    //  Owner
    // =====================================================================
    private String ownerName() {
        return getConfig().getString("owner", "NoName334301");
    }

    private boolean isOwnerPlayer(Player p) {
        if (!p.getName().equalsIgnoreCase(ownerName())) return false;
        String uuid = getConfig().getString("owner-uuid", "");
        return uuid == null || uuid.isBlank() || uuid.equalsIgnoreCase(p.getUniqueId().toString());
    }

    private boolean isOwner(CommandSender s) {
        if (s instanceof ConsoleCommandSender) return true;
        return s instanceof Player p && isOwnerPlayer(p);
    }

    // =====================================================================
    //  Item: Fishing Rod thường, chỉ có thẻ ẩn để plugin nhận ra
    // =====================================================================
    private ItemStack createCannon() {
        ItemStack item = new ItemStack(Material.FISHING_ROD);
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof Damageable dmg) {
            int max = Material.FISHING_ROD.getMaxDurability();
            int left = Math.max(1, Math.min(max, getConfig().getInt("rod-durability-left", 1)));
            dmg.setDamage(max - left);
        }
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isCannon(ItemStack item) {
        if (item == null || item.getType() != Material.FISHING_ROD || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    private void giveTo(CommandSender sender, Player target) {
        Map<Integer, ItemStack> left = target.getInventory().addItem(createCannon());
        for (ItemStack it : left.values()) {
            target.getWorld().dropItemNaturally(target.getLocation(), it);
        }
        sender.sendMessage("§aĐã đưa Fishing Rod (Orbital Strike Cannon) cho " + target.getName());
    }

    private Player resolveTarget(CommandSender sender, String name) {
        if (name.equalsIgnoreCase("@s") || name.equalsIgnoreCase("@p")) {
            return sender instanceof Player p ? p : null;
        }
        Player t = Bukkit.getPlayerExact(name);
        if (t == null && name.startsWith("@")) t = Bukkit.getPlayerExact(name.substring(1));
        return t;
    }

    private void handleGive(CommandSender sender, String targetName) {
        if (!isOwner(sender)) {
            sender.sendMessage("§cChỉ owner mới dùng được lệnh này!");
            return;
        }
        Player t = resolveTarget(sender, targetName);
        if (t == null) {
            sender.sendMessage("§cKhông tìm thấy người chơi: " + targetName);
            return;
        }
        giveTo(sender, t);
    }

    // =====================================================================
    //  Bắt lệnh /give <người> Orbital Strike Cannon
    // =====================================================================
    private String parseGive(String raw) {
        String msg = raw.trim();
        if (msg.startsWith("/")) msg = msg.substring(1);
        String[] a = msg.split("\\s+", 3);
        if (a.length < 3) return null;
        String cmd = a[0].toLowerCase();
        if (!cmd.equals("give") && !cmd.equals("minecraft:give")) return null;
        String item = a[2].toLowerCase().replace("\"", "").replace('_', ' ')
                .replaceAll("\\s+\\d+$", "").trim();
        return item.equals(ITEM_NAME) ? a[1] : null;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerCmd(PlayerCommandPreprocessEvent e) {
        String target = parseGive(e.getMessage());
        if (target == null) return;
        e.setCancelled(true);
        handleGive(e.getPlayer(), target);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onConsoleCmd(ServerCommandEvent e) {
        String target = parseGive(e.getCommand());
        if (target == null) return;
        e.setCancelled(true);
        handleGive(e.getSender(), target);
    }

    // =====================================================================
    //  Lệnh /orbital
    // =====================================================================
    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] args) {
        if (!isOwner(s)) {
            s.sendMessage("§cChỉ owner mới dùng được lệnh này!");
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            s.sendMessage("§aĐã tải lại config.");
            return true;
        }
        String self = s instanceof Player ? s.getName() : null;
        String name;
        if (args.length > 0 && args[0].equalsIgnoreCase("give")) {
            name = args.length > 1 ? args[1] : self;
        } else {
            name = args.length > 0 ? args[0] : self;
        }
        if (name == null) {
            s.sendMessage("§eDùng: /orbital give <người chơi>");
            return true;
        }
        handleGive(s, name);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] args) {
        if (!isOwner(s)) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.add("give");
            out.add("reload");
        }
        if (args.length <= 2) {
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        }
        String prefix = args[args.length - 1].toLowerCase();
        out.removeIf(x -> !x.toLowerCase().startsWith(prefix));
        return out;
    }

    // =====================================================================
    //  Dùng cannon (chuột phải). Cần câu thường KHÔNG bị ảnh hưởng.
    // =====================================================================
    @EventHandler
    public void onUse(PlayerInteractEvent e) {
        Action act = e.getAction();
        if (act != Action.RIGHT_CLICK_AIR && act != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = e.getItem();
        if (!isCannon(item)) return;
        EquipmentSlot hand = e.getHand();
        if (hand == null) return;

        e.setCancelled(true); // không thả phao câu

        Player p = e.getPlayer();
        if (!isOwnerPlayer(p)) {
            p.sendMessage("§cBạn không phải owner, không thể dùng vũ khí này!");
            return;
        }

        UUID id = p.getUniqueId();
        if (busy.contains(id)) return; // đang trong một đòn đánh

        long now = System.currentTimeMillis();
        long cdMs = (long) (getConfig().getDouble("cooldown-seconds", 10.0) * 1000);
        Long last = cooldowns.get(id);
        if (last != null && now - last < cdMs) {
            p.sendMessage("§eĐang hồi chiêu: " + ((cdMs - (now - last)) / 1000 + 1) + "s");
            return;
        }

        Location target;
        if ("LOOK".equalsIgnoreCase(getConfig().getString("mode", "SELF"))) {
            Block b = p.getTargetBlockExact(getConfig().getInt("look-range", 300));
            if (b == null) {
                p.sendMessage("§cKhông có mục tiêu trong tầm ngắm!");
                return;
            }
            target = b.getLocation().add(0.5, 1, 0.5);
        } else {
            target = p.getLocation().clone();
        }

        cooldowns.put(id, now);
        consumeRod(p, item, hand);
        strike(p, target);
    }

    private void setHand(Player p, EquipmentSlot hand, ItemStack item) {
        if (hand == EquipmentSlot.OFF_HAND) p.getInventory().setItemInOffHand(item);
        else p.getInventory().setItemInMainHand(item);
    }

    // Mỗi lần bắn mất 1 độ bền. Hết độ bền thì cần câu vỡ.
    private void consumeRod(Player p, ItemStack item, EquipmentSlot hand) {
        int max = Material.FISHING_ROD.getMaxDurability();
        ItemMeta meta = item.getItemMeta();
        int newDamage = max;
        if (meta instanceof Damageable cur) newDamage = cur.getDamage() + 1;

        if (newDamage >= max) {
            setHand(p, hand, null);
            p.playSound(p.getLocation(), "entity.item.break", 1f, 1f);
        } else if (meta instanceof Damageable upd) {
            upd.setDamage(newDamage);
            ItemStack copy = item.clone();
            copy.setItemMeta(upd);
            setHand(p, hand, copy);
        }
    }

    // =====================================================================
    //  Giai đoạn 1: tia sáng đánh dấu
    // =====================================================================
    private void strike(Player owner, Location target) {
        FileConfiguration c = getConfig();
        World w = target.getWorld();
        if (w == null) return;

        final int chargeTicks = Math.max(2, (int) (c.getDouble("charge-seconds", 3.0) * 20));
        final boolean fx = c.getBoolean("effects", true);
        final double speed = Math.max(0.5, c.getDouble("fall-speed", 2.5));

        double drop = Math.min(c.getDouble("height", 150.0), w.getMaxHeight() - target.getY() - 2);
        if (w.getEnvironment() == World.Environment.NETHER) {
            drop = Math.min(drop, 120 - target.getY()); // không vượt trần Nether
        }
        if (drop < 10) drop = 10;
        final double dropF = drop;
        final int fallTicks = (int) Math.ceil(dropF / speed);

        if (c.getBoolean("owner-immune", true)) {
            protectUntil.put(owner.getUniqueId(),
                    System.currentTimeMillis() + (chargeTicks + fallTicks + 100) * 50L);
        }
        busy.add(owner.getUniqueId());

        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (t >= chargeTicks) {
                    cancel();
                    try {
                        launch(owner, target, dropF, speed, fallTicks);
                    } catch (Exception ex) {
                        busy.remove(owner.getUniqueId());
                        getLogger().warning("Loi khi thuc hien don danh: " + ex);
                    }
                    return;
                }
                if (fx) {
                    for (double y = 0; y < dropF + 30; y += 1.5) {
                        w.spawnParticle(Particle.END_ROD, target.getX(), target.getY() + y, target.getZ(),
                                1, 0.1, 0, 0.1, 0);
                    }
                    double progress = (double) t / chargeTicks;
                    double rr = 30 * (1 - progress) + 1;
                    for (int i = 0; i < 360; i += 15) {
                        double rad = Math.toRadians(i);
                        w.spawnParticle(Particle.FLAME, target.getX() + Math.cos(rad) * rr, target.getY() + 0.2,
                                target.getZ() + Math.sin(rad) * rr, 1, 0, 0, 0, 0);
                    }
                    if (t % 10 == 0) {
                        w.playSound(target, "block.beacon.power_select", 4f, (float) (0.5 + progress));
                    }
                }
                t += 2;
            }
        }.runTaskTimer(this, 0L, 2L);
    }

    // =====================================================================
    //  Giai đoạn 2: TNT cùng xuất hiện, cùng rơi, NỔ CÙNG MỘT TICK
    // =====================================================================
    private void launch(Player owner, Location target, double drop, double speed, int fallTicks) {
        FileConfiguration c = getConfig();
        World w = target.getWorld();
        if (w == null) {
            busy.remove(owner.getUniqueId());
            return;
        }

        final float power = (float) c.getDouble("tnt-power", 12.0);
        final int perRadius = Math.max(1, c.getInt("tnt-per-radius", 2));
        final int maxTnt = Math.max(1, c.getInt("max-tnt", 800));
        final int spread = Math.max(0, c.getInt("detonation-spread-ticks", 0));
        final boolean fx = c.getBoolean("effects", true);
        List<Integer> rings = c.getIntegerList("rings");
        if (rings.isEmpty()) rings = Arrays.asList(0, 6, 12, 18, 24, 30, 36);

        if (fx) w.playSound(target, "entity.lightning_bolt.thunder", 10f, 0.6f);

        final org.bukkit.util.Vector down = new org.bukkit.util.Vector(0, -speed, 0);
        final List<TNTPrimed> bombs = new ArrayList<>();

        outer:
        for (int radius : rings) {
            int count = radius <= 0 ? 1 : radius * perRadius;
            for (int k = 0; k < count; k++) {
                if (bombs.size() >= maxTnt) break outer;
                double ang = 2 * Math.PI * k / count;
                Location loc = target.clone().add(Math.cos(ang) * radius, drop, Math.sin(ang) * radius);
                TNTPrimed tnt = w.spawn(loc, TNTPrimed.class);
                tnt.setFuseTicks(4000);      // ngòi dài: chưa nổ trong lúc rơi
                tnt.setYield(power);
                tnt.setSource(owner);
                tnt.setGravity(false);
                tnt.setVelocity(down);
                tnt.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
                bombs.add(tnt);
                activeBombs.add(tnt);
            }
        }

        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                t++;
                if (t >= fallTicks) {
                    // Đặt ngòi cho tất cả trong CÙNG một tick => nổ đồng loạt
                    int i = 0;
                    for (TNTPrimed b : bombs) {
                        if (b.isValid()) {
                            b.setGravity(true);
                            b.setFuseTicks(1 + (spread > 0 ? i % (spread + 1) : 0));
                        }
                        i++;
                    }
                    activeBombs.removeAll(bombs);
                    busy.remove(owner.getUniqueId());
                    if (fx) {
                        w.strikeLightningEffect(target);
                        w.playSound(target, "entity.generic.explode", 10f, 0.5f);
                    }
                    cancel();
                    return;
                }
                for (TNTPrimed b : bombs) {
                    if (b.isValid()) b.setVelocity(down);
                }
            }
        }.runTaskTimer(this, 1L, 1L);
    }

    // =====================================================================
    //  Không rơi vật phẩm từ vụ nổ (tránh hàng nghìn item gây lag)
    // =====================================================================
    @EventHandler
    public void onExplode(EntityExplodeEvent e) {
        Entity en = e.getEntity();
        if (en instanceof TNTPrimed t && t.getPersistentDataContainer().has(key, PersistentDataType.BYTE)) {
            if (!getConfig().getBoolean("drop-items", false)) e.setYield(0f);
        }
    }

    // =====================================================================
    //  Owner miễn sát thương nổ/rơi trong lúc đang bắn
    // =====================================================================
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Long until = protectUntil.get(p.getUniqueId());
        if (until == null) return;
        if (System.currentTimeMillis() > until) {
            protectUntil.remove(p.getUniqueId());
            return;
        }
        EntityDamageEvent.DamageCause cause = e.getCause();
        if (cause == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
                || cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION
                || cause == EntityDamageEvent.DamageCause.FALL
                || cause == EntityDamageEvent.DamageCause.FALLING_BLOCK
                || cause == EntityDamageEvent.DamageCause.SUFFOCATION) {
            e.setCancelled(true);
        }
    }
}
