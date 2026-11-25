package me.noname.orbital;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
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
import java.util.List;
import java.util.UUID;
import java.util.HashMap;
import java.util.Map;

public class OrbitalStrike extends JavaPlugin implements Listener, CommandExecutor {

    // ====== CHỈ OWNER MỚI DÙNG ĐƯỢC ======
    private static final String OWNER_NAME = "NoName334301";
    // ====== TÊN CẦN CÂU (chỉ dùng cho tin nhắn; item giữ tên mặc định của Minecraft) ======
    private static final String ROD_NAME = "Fishing Rod";
    // ====== CẤU HÌNH ĐỘ MẠNH (giảm xuống nếu server lag) ======
    private static final int HEIGHT = 150;          // độ cao TNT rơi xuống
    private static final float TNT_YIELD = 15f;     // sức nổ mỗi quả TNT
    private static final int[] RINGS = {0, 5, 10, 15, 20, 25, 30, 35, 40}; // bán kính các vòng
    private static final long COOLDOWN_MS = 10_000; // hồi chiêu 10 giây

    private static final String ITEM_NAME = "Orbital Strike Cannon"; // tên dùng trong lệnh /give
    private org.bukkit.NamespacedKey key;
    private final Map<UUID, Long> cooldowns = new HashMap<>();

    @Override
    public void onEnable() {
        key = new org.bukkit.NamespacedKey(this, "orbital_cannon");
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("orbital").setExecutor(this);
        getLogger().info("OrbitalStrike v1.1 da bat (Fishing Rod thuong). Owner: " + OWNER_NAME);
    }

    private boolean isOwner(CommandSender s) {
        if (s instanceof ConsoleCommandSender) return true;
        return s instanceof Player p && p.getName().equals(OWNER_NAME);
    }

    // ---------- Tạo item: cần câu thường y hệt vanilla (không tên riêng, không enchant) ----------
    private ItemStack createCannon() {
        ItemStack item = new ItemStack(Material.FISHING_ROD);
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isCannon(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    private void giveTo(CommandSender sender, String targetName) {
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null && targetName.startsWith("@")) {
            target = Bukkit.getPlayerExact(targetName.substring(1));
        }
        if (target == null) {
            sender.sendMessage("§cKhông tìm thấy người chơi: " + targetName);
            return;
        }
        target.getInventory().addItem(createCannon());
        sender.sendMessage("§aĐã đưa " + ROD_NAME + " cho " + target.getName());
    }

    // ---------- Lệnh /give @NoName334301 Orbital Strike Cannon ----------
    private String[] parseGive(String raw) {
        String msg = raw.startsWith("/") ? raw.substring(1) : raw;
        String[] a = msg.trim().split("\\s+", 3);
        if (a.length < 3) return null;
        String cmd = a[0].toLowerCase();
        if (!cmd.equals("give") && !cmd.equals("minecraft:give")) return null;
        if (!a[2].trim().equalsIgnoreCase(ITEM_NAME)) return null;
        return new String[]{a[1]};
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerCmd(PlayerCommandPreprocessEvent e) {
        String[] r = parseGive(e.getMessage());
        if (r == null) return;
        e.setCancelled(true);
        if (!isOwner(e.getPlayer())) {
            e.getPlayer().sendMessage("§cChỉ owner mới dùng được lệnh này!");
            return;
        }
        giveTo(e.getPlayer(), r[0]);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onConsoleCmd(ServerCommandEvent e) {
        String[] r = parseGive(e.getCommand());
        if (r == null) return;
        e.setCancelled(true);
        giveTo(e.getSender(), r[0]);
    }

    // Lệnh dự phòng: /orbital [tên]
    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] args) {
        if (!isOwner(s)) {
            s.sendMessage("§cChỉ owner mới dùng được lệnh này!");
            return true;
        }
        String name = args.length > 0 ? args[0] : s.getName();
        giveTo(s, name);
        return true;
    }

    // Không cho thả phao câu khi cầm cannon
    @EventHandler
    public void onFish(org.bukkit.event.player.PlayerFishEvent e) {
        if (isCannon(e.getPlayer().getInventory().getItemInMainHand())) e.setCancelled(true);
    }

    // ---------- Sử dụng cannon ----------
    @EventHandler
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = e.getItem();
        if (!isCannon(item)) return;
        e.setCancelled(true);

        Player p = e.getPlayer();
        if (!p.getName().equals(OWNER_NAME)) {
            p.sendMessage("§cBạn không phải owner, không thể dùng vũ khí này!");
            return;
        }
        long now = System.currentTimeMillis();
        long last = cooldowns.getOrDefault(p.getUniqueId(), 0L);
        if (now - last < COOLDOWN_MS) {
            p.sendMessage("§eĐang hồi chiêu: " + ((COOLDOWN_MS - (now - last)) / 1000 + 1) + "s");
            return;
        }
        cooldowns.put(p.getUniqueId(), now);

        // Mỗi lần bắn mất 1 độ bền, hết độ bền thì cần câu vỡ
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof Damageable dmg) {
            dmg.setDamage(dmg.getDamage() + 1);
            if (dmg.getDamage() >= Material.FISHING_ROD.getMaxDurability()) {
                p.getInventory().setItemInMainHand(null);
                p.playSound(p.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 1f);
            } else {
                item.setItemMeta(dmg);
            }
        }

        // Đánh ngay vị trí người đứng
        strike(p, p.getLocation().clone());
    }

    private void strike(Player owner, Location target) {
        World w = target.getWorld();

        // Giai đoạn 1: tia sáng đánh dấu 3 giây
        new BukkitRunnable() {
            int t = 0;
            @Override
            public void run() {
                if (t >= 60) {
                    cancel();
                    launch(owner, target);
                    return;
                }
                for (double y = 0; y < HEIGHT + 30; y += 1.5) {
                    w.spawnParticle(Particle.END_ROD, target.getX(), target.getY() + y, target.getZ(), 1, 0.1, 0, 0.1, 0);
                }
                for (int i = 0; i < 360; i += 20) {
                    double rad = Math.toRadians(i);
                    double rr = 25 * (1 - t / 60.0) + 1;
                    w.spawnParticle(Particle.FLAME, target.getX() + Math.cos(rad) * rr, target.getY() + 0.2,
                            target.getZ() + Math.sin(rad) * rr, 1, 0, 0, 0, 0);
                }
                if (t % 10 == 0) {
                    w.playSound(target, Sound.BLOCK_BEACON_POWER_SELECT, 4f, 0.5f + t / 60f);
                }
                t += 2;
            }
        }.runTaskTimer(this, 0L, 2L);
    }

    // Giai đoạn 2: TẤT CẢ TNT cùng xuất hiện, cùng rơi với tốc độ không đổi, và NỔ CÙNG MỘT TICK
    private static final double FALL_SPEED = 2.5; // block mỗi tick

    private void launch(Player owner, Location target) {
        World w = target.getWorld();
        w.playSound(target, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 10f, 0.6f);

        double drop = Math.min(HEIGHT, w.getMaxHeight() - target.getY() - 2);
        if (drop < 10) drop = 10;
        final int fallTicks = (int) Math.ceil(drop / FALL_SPEED);
        final org.bukkit.util.Vector down = new org.bukkit.util.Vector(0, -FALL_SPEED, 0);
        final List<TNTPrimed> bombs = new ArrayList<>();

        for (int radius : RINGS) {
            int count = radius == 0 ? 1 : radius * 3;
            for (int k = 0; k < count; k++) {
                double ang = 2 * Math.PI * k / count;
                Location spawn = target.clone().add(Math.cos(ang) * radius, drop, Math.sin(ang) * radius);
                bombs.add(w.spawn(spawn, TNTPrimed.class, tnt -> {
                    tnt.setFuseTicks(2000);   // ngòi dài, chưa nổ trong lúc rơi
                    tnt.setYield(TNT_YIELD);
                    tnt.setSource(owner);
                    tnt.setGravity(false);
                    tnt.setVelocity(down);
                }));
            }
        }

        new BukkitRunnable() {
            int t = 0;
            @Override
            public void run() {
                t++;
                if (t >= fallTicks) {
                    // Cùng một tick: đặt ngòi = 1 cho tất cả => nổ đồng loạt
                    for (TNTPrimed b : bombs) {
                        if (b.isValid()) {
                            b.setGravity(true);
                            b.setFuseTicks(1);
                        }
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
}
