package me.noname.orbital;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Orbital Strike Cannon - cần câu thường, ai cầm cũng dùng được, chỉ owner mới lấy được bằng lệnh.
 * Toàn bộ TNT xuất hiện cùng lúc, rơi cùng tốc độ và nổ trong cùng một tick.
 */
public class OrbitalStrike extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private static final Pattern GIVE_PATTERN = Pattern.compile(
            "^/?(?:[a-z0-9_.-]+:)?(?:give|egive)\\s+(\\S+)\\s+orbital[ _]strike[ _]cannon(?:\\s+(\\d+))?\\s*$",
            Pattern.CASE_INSENSITIVE);

    private static final String MSG_DENIED = "§cChỉ owner mới dùng được!";

    private NamespacedKey rodKey;
    private NamespacedKey tntKey;

    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Map<UUID, Long> protectedUntil = new HashMap<>();
    private final Set<TNTPrimed> liveBombs = new HashSet<>();

    // ----- cấu hình (đọc từ config.yml) -----
    private final Set<String> owners = new HashSet<>();
    private final Set<String> disabledWorlds = new HashSet<>();
    private long cooldownMs;
    private int rodUses;
    private int chargeTicks;
    private int skyHeight;
    private int ringSpacing;
    private int maxRadius;
    private int density;
    private int maxTnt;
    private double fallSpeed;
    private float tntYield;
    private boolean blockDrops;
    private boolean protectShooter;

    // =====================================================================
    //  Khởi động / tắt
    // =====================================================================
    @Override
    public void onEnable() {
        rodKey = new NamespacedKey(this, "orbital_cannon");
        tntKey = new NamespacedKey(this, "orbital_tnt");
        saveDefaultConfig();
        loadSettings();
        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("orbital") != null) {
            getCommand("orbital").setExecutor(this);
            getCommand("orbital").setTabCompleter(this);
        }
        getLogger().info("OrbitalStrike v" + getDescription().getVersion() + " da bat. Owner: " + owners);
    }

    @Override
    public void onDisable() {
        for (TNTPrimed t : liveBombs) {
            if (t.isValid()) t.remove();
        }
        liveBombs.clear();
        cooldowns.clear();
        protectedUntil.clear();
    }

    private void loadSettings() {
        reloadConfig();
        FileConfiguration c = getConfig();

        owners.clear();
        for (String n : c.getStringList("owners")) {
            if (n != null && !n.isBlank()) owners.add(n.trim().toLowerCase(Locale.ROOT));
        }
        if (owners.isEmpty()) owners.add("noname334301");

        disabledWorlds.clear();
        disabledWorlds.addAll(c.getStringList("disabled-worlds"));

        cooldownMs = Math.max(0L, c.getLong("cooldown-seconds", 10L)) * 1000L;
        rodUses = clamp(c.getInt("rod-uses", 1), 1, 63);
        chargeTicks = clamp(c.getInt("charge-ticks", 60), 10, 400);
        skyHeight = clamp(c.getInt("sky-height", 150), 20, 300);
        ringSpacing = clamp(c.getInt("ring-spacing", 5), 2, 20);
        maxRadius = clamp(c.getInt("max-radius", 40), 0, 100);
        density = clamp(c.getInt("tnt-density", 3), 1, 8);
        maxTnt = clamp(c.getInt("max-tnt", 1000), 1, 3000);
        fallSpeed = clampD(c.getDouble("fall-speed", 2.5), 0.5, 5.0);
        tntYield = (float) clampD(c.getDouble("tnt-yield", 4.0), 1.0, 60.0);
        blockDrops = c.getBoolean("block-drops", true);
        protectShooter = c.getBoolean("protect-shooter", false);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double clampD(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // =====================================================================
    //  Owner
    // =====================================================================
    private boolean isOwner(CommandSender s) {
        if (s instanceof ConsoleCommandSender) return true;
        return s instanceof Player p && owners.contains(p.getName().toLowerCase(Locale.ROOT));
    }

    // =====================================================================
    //  Item: Fishing Rod thường (không tên riêng, không enchant)
    // =====================================================================
    private ItemStack createCannon() {
        ItemStack item = new ItemStack(Material.FISHING_ROD);
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof org.bukkit.inventory.meta.Damageable dmg) {
            int max = Material.FISHING_ROD.getMaxDurability();
            dmg.setDamage(Math.max(0, max - rodUses));
        }
        meta.getPersistentDataContainer().set(rodKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isCannon(ItemStack item) {
        if (item == null || item.getType() != Material.FISHING_ROD || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(rodKey, PersistentDataType.BYTE);
    }

    private Player findPlayer(CommandSender sender, String raw) {
        String name = raw.startsWith("@") ? raw.substring(1) : raw;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getName().equalsIgnoreCase(name)) return p;
        }
        if (sender instanceof Player self
                && (name.equalsIgnoreCase("s") || name.equalsIgnoreCase("p") || name.equalsIgnoreCase("me"))) {
            return self;
        }
        return null;
    }

    private void giveTo(CommandSender sender, String rawTarget, int amount) {
        Player target = findPlayer(sender, rawTarget);
        if (target == null) {
            sender.sendMessage("§cKhông tìm thấy người chơi: " + rawTarget);
            return;
        }
        amount = clamp(amount, 1, 36);
        for (int i = 0; i < amount; i++) {
            Map<Integer, ItemStack> left = target.getInventory().addItem(createCannon());
            for (ItemStack it : left.values()) {
                target.getWorld().dropItemNaturally(target.getLocation(), it);
            }
        }
        sender.sendMessage("§aĐã đưa " + amount + " Fishing Rod (Orbital Strike Cannon) cho " + target.getName());
    }

    // =====================================================================
    //  Lệnh: /give <người> Orbital Strike Cannon [số lượng]  và  /orbital
    // =====================================================================
    private void handleGive(CommandSender sender, Matcher m) {
        if (!isOwner(sender)) {
            sender.sendMessage(MSG_DENIED);
            return;
        }
        int amount = 1;
        if (m.group(2) != null) {
            try {
                amount = Integer.parseInt(m.group(2));
            } catch (NumberFormatException ignored) {
                amount = 1;
            }
        }
        giveTo(sender, m.group(1), amount);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerCommand(PlayerCommandPreprocessEvent e) {
        Matcher m = GIVE_PATTERN.matcher(e.getMessage());
        if (!m.matches()) return;
        e.setCancelled(true);
        handleGive(e.getPlayer(), m);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onConsoleCommand(ServerCommandEvent e) {
        Matcher m = GIVE_PATTERN.matcher(e.getCommand());
        if (!m.matches()) return;
        e.setCancelled(true);
        handleGive(e.getSender(), m);
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] args) {
        if (!isOwner(s)) {
            s.sendMessage(MSG_DENIED);
            return true;
        }
        if (args.length == 0) {
            if (s instanceof Player p) {
                giveTo(s, p.getName(), 1);
            } else {
                s.sendMessage("§e/orbital give <tên> [số lượng]  |  /orbital reload");
            }
            return true;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            loadSettings();
            s.sendMessage("§aĐã tải lại config.yml");
            return true;
        }
        if (args[0].equalsIgnoreCase("give")) {
            String target = args.length > 1 ? args[1] : (s instanceof Player p ? p.getName() : null);
            if (target == null) {
                s.sendMessage("§e/orbital give <tên> [số lượng]");
                return true;
            }
            int amount = 1;
            if (args.length > 2) {
                try {
                    amount = Integer.parseInt(args[2]);
                } catch (NumberFormatException ignored) {
                    amount = 1;
                }
            }
            giveTo(s, target, amount);
            return true;
        }
        giveTo(s, args[0], 1);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (!isOwner(s)) return out;
        if (args.length == 1) {
            for (String o : new String[]{"give", "reload"}) {
                if (o.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(o);
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    out.add(p.getName());
                }
            }
        }
        return out;
    }

    // =====================================================================
    //  Cần câu thường vẫn câu cá bình thường; chỉ cannon mới bị chặn thả phao
    // =====================================================================
    @EventHandler
    public void onFish(PlayerFishEvent e) {
        PlayerInventory inv = e.getPlayer().getInventory();
        ItemStack main = inv.getItemInMainHand();
        ItemStack off = inv.getItemInOffHand();
        boolean cannonCast = isCannon(main)
                || (main.getType() != Material.FISHING_ROD && isCannon(off));
        if (cannonCast) e.setCancelled(true);
    }

    // =====================================================================
    //  Sử dụng cannon
    // =====================================================================
    @EventHandler
    public void onUse(PlayerInteractEvent e) {
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        EquipmentSlot hand = e.getHand();
        if (hand == null) return;
        ItemStack item = e.getItem();
        if (!isCannon(item)) return;

        Player p = e.getPlayer();
        // Tránh kích hoạt 2 lần khi cầm cannon ở cả hai tay
        if (hand == EquipmentSlot.OFF_HAND && isCannon(p.getInventory().getItemInMainHand())) return;

        e.setCancelled(true);

        if (disabledWorlds.contains(p.getWorld().getName())) {
            p.sendMessage("§cKhông dùng được ở thế giới này!");
            return;
        }
        long now = System.currentTimeMillis();
        long last = cooldowns.getOrDefault(p.getUniqueId(), 0L);
        if (now - last < cooldownMs) {
            p.sendMessage("§eĐang hồi chiêu: " + ((cooldownMs - (now - last)) / 1000 + 1) + "s");
            return;
        }
        cooldowns.put(p.getUniqueId(), now);

        consumeUse(p, hand, item);
        strike(p, p.getLocation().clone());
    }

    // Mỗi lần bắn mất 1 độ bền; hết độ bền thì cần câu vỡ
    private void consumeUse(Player p, EquipmentSlot hand, ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof org.bukkit.inventory.meta.Damageable dmg)) return;
        int max = Material.FISHING_ROD.getMaxDurability();
        int newDamage = dmg.getDamage() + 1;
        if (newDamage >= max) {
            PlayerInventory inv = p.getInventory();
            if (hand == EquipmentSlot.HAND) {
                inv.setItemInMainHand(new ItemStack(Material.AIR));
            } else {
                inv.setItemInOffHand(new ItemStack(Material.AIR));
            }
            p.playSound(p.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 1f);
        } else {
            dmg.setDamage(newDamage);
            item.setItemMeta(dmg);
        }
    }

    // =====================================================================
    //  Đòn tấn công
    // =====================================================================
    // Giai đoạn 1: nạp - tia sáng + vòng lửa thu nhỏ
    private void strike(Player owner, Location target) {
        final World w = target.getWorld();
        if (w == null) return;
        final int charge = chargeTicks;
        final int radius = maxRadius;
        final int height = skyHeight;

        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (t >= charge) {
                    cancel();
                    launch(owner, target);
                    return;
                }
                for (double y = 0; y < height + 30; y += 1.5) {
                    w.spawnParticle(Particle.END_ROD, target.getX(), target.getY() + y, target.getZ(),
                            1, 0.1, 0, 0.1, 0);
                }
                double rr = radius * (1.0 - (double) t / charge) + 1;
                for (int i = 0; i < 360; i += 15) {
                    double rad = Math.toRadians(i);
                    w.spawnParticle(Particle.FLAME,
                            target.getX() + Math.cos(rad) * rr, target.getY() + 0.2,
                            target.getZ() + Math.sin(rad) * rr, 1, 0, 0, 0, 0);
                }
                if (t % 10 == 0) {
                    w.playSound(target, Sound.BLOCK_BEACON_POWER_SELECT, 4f, 0.5f + (float) t / charge);
                }
                t += 2;
            }
        }.runTaskTimer(this, 0L, 2L);
    }

    private List<double[]> buildOffsets() {
        List<double[]> list = new ArrayList<>();
        list.add(new double[]{0, 0});
        for (int r = ringSpacing; r <= maxRadius; r += ringSpacing) {
            int count = Math.max(6, r * density);
            if (list.size() + count > maxTnt) break;
            for (int k = 0; k < count; k++) {
                double ang = 2 * Math.PI * k / count;
                list.add(new double[]{Math.cos(ang) * r, Math.sin(ang) * r});
            }
        }
        return list;
    }

    // Giai đoạn 2: TẤT CẢ TNT cùng xuất hiện trong 1 tick, rơi cùng tốc độ,
    // rồi cùng nổ trong 1 tick.
    private void launch(Player owner, Location target) {
        final World w = target.getWorld();
        if (w == null) return;
        w.playSound(target, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 10f, 0.6f);

        double maxDrop = w.getMaxHeight() - target.getY() - 2;
        double drop = Math.max(10.0, Math.min((double) skyHeight, maxDrop));
        final int fallTicks = (int) Math.ceil(drop / fallSpeed);
        final Vector down = new Vector(0, -fallSpeed, 0);
        final Player src = owner.isOnline() ? owner : null;
        final List<TNTPrimed> bombs = new ArrayList<>();

        if (protectShooter && src != null) {
            protectedUntil.put(src.getUniqueId(), System.currentTimeMillis() + (fallTicks + 80L) * 50L);
        }

        for (double[] o : buildOffsets()) {
            Location spawn = new Location(w, target.getX() + o[0], target.getY() + drop, target.getZ() + o[1]);
            TNTPrimed tnt = w.spawn(spawn, TNTPrimed.class, b -> {
                b.setFuseTicks(4000);          // ngòi rất dài: không nổ trong lúc rơi
                b.setYield(tntYield);
                b.setIsIncendiary(false);
                b.setGravity(false);
                b.setVelocity(down);
                if (src != null) b.setSource(src);
                b.getPersistentDataContainer().set(tntKey, PersistentDataType.BYTE, (byte) 1);
            });
            bombs.add(tnt);
            liveBombs.add(tnt);
        }

        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                t++;
                if (t >= fallTicks) {
                    detonate(bombs, target);
                    cancel();
                    return;
                }
                for (TNTPrimed b : bombs) {
                    if (b.isValid()) b.setVelocity(down);
                }
            }
        }.runTaskTimer(this, 1L, 1L);
    }

    // Đặt ngòi = 1 cho tất cả cùng lúc => nổ đồng loạt trong cùng một tick
    private void detonate(List<TNTPrimed> bombs, Location target) {
        for (TNTPrimed b : bombs) {
            if (b.isValid()) {
                b.setVelocity(new Vector(0, 0, 0));
                b.setFuseTicks(1);
            }
        }
        liveBombs.removeAll(bombs);
        World w = target.getWorld();
        if (w != null) {
            w.strikeLightningEffect(target);
            w.playSound(target, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 10f, 0.5f);
        }
    }

    // =====================================================================
    //  Bảo vệ owner + tắt drop block
    // =====================================================================
    @EventHandler(ignoreCancelled = true)
    public void onExplosionDamage(EntityDamageEvent e) {
        if (!protectShooter || !(e.getEntity() instanceof Player p)) return;
        Long until = protectedUntil.get(p.getUniqueId());
        if (until == null) return;
        if (System.currentTimeMillis() > until) {
            protectedUntil.remove(p.getUniqueId());
            return;
        }
        EntityDamageEvent.DamageCause cause = e.getCause();
        if (cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION
                || cause == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onExplode(EntityExplodeEvent e) {
        if (blockDrops) return;
        if (e.getEntity() instanceof TNTPrimed tnt
                && tnt.getPersistentDataContainer().has(tntKey, PersistentDataType.BYTE)) {
            e.setYield(0f);
        }
    }
}
