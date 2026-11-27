package me.noname.orbital;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
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
 * TNT rơi bình thường theo vật lý mặc định và nổ đúng lúc chạm đất, tất cả trong cùng một tick.
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
    private final List<Chunk> heldChunks = new ArrayList<>();

    // ----- cấu hình (đọc từ config.yml) -----
    private final Set<String> owners = new HashSet<>();
    private final Set<String> disabledWorlds = new HashSet<>();
    private long cooldownMs;
    private int rodUses;
    private int chargeTicks;
    private int skyHeight;
    private int ringSpacing;
    private int maxRadius;
    private double tntSpacing;
    private int maxTnt;
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
        for (Chunk ch : heldChunks) ch.removePluginChunkTicket(this);
        heldChunks.clear();
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
        tntSpacing = clampD(c.getDouble("tnt-spacing", 2.0), 1.0, 8.0);
        maxTnt = clamp(c.getInt("max-tnt", 1500), 1, 4000);
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

    // Tạo các vòng tròn hoàn hảo: tâm + các vòng đồng tâm, TNT cách đều nhau theo cung tròn.
    private List<double[]> generateOffsets(double arc) {
        List<double[]> list = new ArrayList<>();
        list.add(new double[]{0, 0});
        for (int r = ringSpacing; r <= maxRadius; r += ringSpacing) {
            int count = Math.max(6, (int) Math.round(2 * Math.PI * r / arc));
            for (int k = 0; k < count; k++) {
                double ang = 2 * Math.PI * k / count;
                list.add(new double[]{Math.cos(ang) * r, Math.sin(ang) * r});
            }
        }
        return list;
    }

    // Nếu vượt giới hạn max-tnt thì giãn khoảng cách giữa các TNT (vòng tròn vẫn đầy đủ, không bỏ vòng nào)
    private List<double[]> buildOffsets() {
        double arc = tntSpacing;
        List<double[]> list = generateOffsets(arc);
        while (list.size() > maxTnt && arc < 100) {
            arc *= 1.05;
            list = generateOffsets(arc);
        }
        return list;
    }

    // Số tick TNT cần để rơi tự do (vật lý vanilla) một quãng `drop` block
    private static int fallTicksFor(double drop) {
        double speed = 0;
        double dist = 0;
        int ticks = 0;
        while (dist < drop && ticks < 2000) {
            speed += 0.04;     // trọng lực
            dist += speed;     // di chuyển
            speed *= 0.98;     // lực cản
            ticks++;
        }
        return Math.max(1, ticks);
    }

    // Giai đoạn 2: TNT rơi bình thường (vật lý mặc định), ngòi được tính đúng bằng thời gian rơi
    // => TNT chạm mặt đất đúng lúc nổ. Tất cả cùng xuất hiện 1 tick, cùng độ cao, cùng ngòi => nổ đồng loạt.
    private void launch(Player owner, Location target) {
        final World w = target.getWorld();
        if (w == null) return;

        double maxDrop = w.getMaxHeight() - target.getY() - 2;
        double drop = Math.max(10.0, Math.min((double) skyHeight, maxDrop));
        final int fuse = fallTicksFor(drop);
        final Player src = owner.isOnline() ? owner : null;
        final List<TNTPrimed> bombs = new ArrayList<>();
        final List<double[]> offsets = buildOffsets();

        if (protectShooter && src != null) {
            protectedUntil.put(src.getUniqueId(), System.currentTimeMillis() + (fuse + 80L) * 50L);
        }

        // Giữ các chunk của cả vòng tròn luôn được load và hoạt động => không TNT nào bị đứng yên / mất
        final List<Chunk> held = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (double[] o : offsets) {
            int cx = ((int) Math.floor(target.getX() + o[0])) >> 4;
            int cz = ((int) Math.floor(target.getZ() + o[1])) >> 4;
            long key = ((long) cx << 32) ^ (cz & 0xffffffffL);
            if (seen.add(key)) {
                Chunk ch = w.getChunkAt(cx, cz);
                ch.addPluginChunkTicket(this);
                held.add(ch);
            }
        }
        heldChunks.addAll(held);

        int failed = 0;
        for (double[] o : offsets) {
            Location spawn = new Location(w, target.getX() + o[0], target.getY() + drop, target.getZ() + o[1]);
            TNTPrimed tnt = spawnBomb(w, spawn, fuse, src);
            if (tnt == null) tnt = spawnBomb(w, spawn, fuse, src); // thử lại 1 lần
            if (tnt == null) {
                failed++;
                continue;
            }
            bombs.add(tnt);
            liveBombs.add(tnt);
        }
        if (failed > 0) {
            getLogger().warning("Khong the tao " + failed + "/" + offsets.size() + " TNT (bi plugin khac chan?)");
        }

        // Dọn sau khi TNT đã nổ: nhả chunk + xóa khỏi danh sách theo dõi
        Bukkit.getScheduler().runTaskLater(this, () -> {
            liveBombs.removeAll(bombs);
            for (Chunk ch : held) {
                ch.removePluginChunkTicket(this);
                heldChunks.remove(ch);
            }
        }, fuse + 20L);
    }

    private TNTPrimed spawnBomb(World w, Location spawn, int fuse, Player src) {
        try {
            TNTPrimed tnt = w.spawn(spawn, TNTPrimed.class, b -> {
                b.setFuseTicks(fuse);          // nổ đúng lúc chạm đất
                b.setYield(tntYield);
                b.setIsIncendiary(false);
                b.setVelocity(new Vector(0, 0, 0));
                if (src != null) b.setSource(src);
                b.getPersistentDataContainer().set(tntKey, PersistentDataType.BYTE, (byte) 1);
            });
            return tnt.isValid() ? tnt : null;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    // =====================================================================
    //  Bảo vệ người bắn (tùy chọn) + tắt drop block
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
