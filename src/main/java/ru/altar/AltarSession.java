package ru.altar;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.session.ClipboardHolder;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

final class AltarSession {

    final AltarPlugin pl;
    final World world;
    final Random rnd = new Random();

    int minX, minZ, baseY, sx, sy, sz;
    Location center, chestLoc;
    BlockArrayClipboard snap;
    BlockVector3 snapMin;

    Inventory inv;
    boolean opened;
    int elapsed;
    int endAt;
    int totalGuards;
    int tickCount;

    private final Set<UUID> guards = new HashSet<>();
    private final Set<UUID> raged = new HashSet<>();
    private final Map<UUID, Location> homes = new HashMap<>();
    private final List<Block> pillarTops = new ArrayList<>();
    private final List<int[]> tickets = new ArrayList<>();
    private UUID holoId;
    private BossBar bar;
    private BukkitTask task;

    AltarSession(AltarPlugin pl, World world) {
        this.pl = pl;
        this.world = world;
    }

    private FileConfiguration cfg() { return pl.getConfig(); }

    /* ═════════════════ старт ═════════════════ */

    boolean start() {
        Clipboard cb = pl.schem;
        if (cb == null) return false;
        Region reg = cb.getRegion();
        sx = reg.getWidth(); sy = reg.getHeight(); sz = reg.getLength();
        if (!findSpot()) return false;

        com.sk89q.worldedit.world.World wew = BukkitAdapter.adapt(world);
        int m = cfg().getInt("decor-margin", 12);
        snapMin = BlockVector3.at(minX - m, Math.max(world.getMinHeight(), baseY - 14), minZ - m);
        BlockVector3 snapMax = BlockVector3.at(minX + sx - 1 + m, Math.min(world.getMaxHeight() - 1, baseY + sy + 6), minZ + sz - 1 + m);
        CuboidRegion sr = new CuboidRegion(wew, snapMin, snapMax);
        snap = new BlockArrayClipboard(sr);

        try {
            try (EditSession es = WorldEdit.getInstance().newEditSession(wew)) {
                ForwardExtentCopy fc = new ForwardExtentCopy(es, sr, snap, sr.getMinimumPoint());
                fc.setCopyingEntities(false);
                Operations.complete(fc);
            }
            pl.saveSnapshot(snap, world, snapMin);
            try (EditSession es = WorldEdit.getInstance().newEditSession(wew)) {
                // в схематике origin может быть далеко от самой постройки — выравниваем по её углу
                BlockVector3 to = BlockVector3.at(minX, baseY, minZ).subtract(reg.getMinimumPoint()).add(cb.getOrigin());
                Operations.complete(new ClipboardHolder(cb).createPaste(es).to(to).ignoreAirBlocks(false).build());
            }
        } catch (Exception ex) {
            pl.getLogger().warning("Ошибка вставки схематики: " + ex);
            restore();
            pl.clearState();
            return false;
        }

        // подсыпка под постройкой (чтобы не висела над ямами)
        for (int x = minX; x < minX + sx; x++)
            for (int z = minZ; z < minZ + sz; z++)
                for (int y = baseY - 1; y > baseY - 14 && y > world.getMinHeight(); y--) {
                    Block b = world.getBlockAt(x, y, z);
                    if (b.getType().isSolid()) break;
                    b.setType(Material.BLACKSTONE, false);
                }

        pillarTops.addAll(Decor.apply(world, minX, minZ, sx, sz, baseY, m, rnd));

        List<Integer> cl = cfg().getIntegerList("chest-local");
        if (cl.size() < 3) cl = List.of(sx / 2, sy - 8, sz / 2);
        chestLoc = new Location(world, minX + cl.get(0), baseY + cl.get(1), minZ + cl.get(2));
        chestLoc.getBlock().setType(Material.RED_SHULKER_BOX, false);
        center = new Location(world, minX + sx / 2.0, baseY, minZ + sz / 2.0);

        // держим чанки загруженными на время ивента
        double lr = cfg().getDouble("guardians.leash-radius", 22) + 4;
        for (int cx = ((int) (center.getX() - lr)) >> 4; cx <= ((int) (center.getX() + lr)) >> 4; cx++)
            for (int cz = ((int) (center.getZ() - lr)) >> 4; cz <= ((int) (center.getZ() + lr)) >> 4; cz++) {
                world.addPluginChunkTicket(cx, cz, pl);
                tickets.add(new int[]{cx, cz});
            }

        endAt = cfg().getInt("max-duration-seconds", 1200);
        spawnHolo();
        spawnGuardians();

        bar = Bukkit.createBossBar("", BarColor.RED, BarStyle.SEGMENTED_20);
        bar.setVisible(true);
        updateBar();

        announceStart();
        world.strikeLightningEffect(chestLoc);
        task = Bukkit.getScheduler().runTaskTimer(pl, this::tick, 10L, 10L);
        return true;
    }

    private boolean findSpot() {
        int lo = cfg().getInt("spawn-min", -5000), hi = cfg().getInt("spawn-max", 5000);
        int tol = cfg().getInt("flat-tolerance", 4);
        for (int i = 0; i < 60; i++) {
            int cx = lo + rnd.nextInt(hi - lo + 1), cz = lo + rnd.nextInt(hi - lo + 1);
            int x0 = cx - sx / 2, z0 = cz - sz / 2;
            int[][] pts = {{0, 0}, {sx - 1, 0}, {0, sz - 1}, {sx - 1, sz - 1}, {sx / 2, sz / 2},
                    {sx / 2, 0}, {0, sz / 2}, {sx - 1, sz / 2}, {sx / 2, sz - 1}};
            int mn = Integer.MAX_VALUE, mx = Integer.MIN_VALUE, sum = 0;
            boolean bad = false;
            for (int[] p : pts) {
                int y = world.getHighestBlockYAt(x0 + p[0], z0 + p[1], HeightMap.MOTION_BLOCKING_NO_LEAVES);
                Block b = world.getBlockAt(x0 + p[0], y, z0 + p[1]);
                if (b.isLiquid() || y <= world.getMinHeight() + 2) { bad = true; break; }
                mn = Math.min(mn, y); mx = Math.max(mx, y); sum += y;
            }
            if (bad || mx - mn > tol) continue;
            int by = sum / pts.length;
            if (by + sy + 8 >= world.getMaxHeight()) continue;
            minX = x0; minZ = z0; baseY = by;
            return true;
        }
        return false;
    }

    private void announceStart() {
        String line = "§8§m                                                ";
        int open = cfg().getInt("open-seconds", 300), max = cfg().getInt("max-duration-seconds", 1200);
        Bukkit.broadcastMessage(line);
        Bukkit.broadcastMessage("  §4§l⚔ §c§lОГНЕННЫЙ АЛТАРЬ §4§lпробудился! §4§l⚔");
        Bukkit.broadcastMessage("  §7Координаты: §f" + coords());
        Bukkit.broadcastMessage("  §7Шалкер откроется через §f" + open / 60 + " мин §8| §7алтарь исчезнет через §f" + max / 60 + " мин");
        Bukkit.broadcastMessage("  §7Алтарь охраняют §c" + totalGuards + " стража §7в незерите. §8Команда: §f/altar");
        Bukkit.broadcastMessage(line);
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.5f, 0.7f);
            p.sendTitle("§4§l⚔ ОГНЕННЫЙ АЛТАРЬ ⚔", "§cОн пробудился...", 10, 50, 20);
        }
    }

    /* ═════════════════ стражи ═════════════════ */

    private void spawnGuardians() {
        totalGuards = cfg().getInt("guardians.count", 2);
        double mx = minX + sx / 2.0;
        double[][] spots = {{mx, minZ - 2.5}, {mx, minZ + sz + 2.5}, {minX - 2.5, minZ + sz / 2.0}, {minX + sx + 2.5, minZ + sz / 2.0}};
        for (int i = 0; i < totalGuards; i++) {
            double[] s = spots[i % spots.length];
            int y = world.getHighestBlockYAt((int) s[0], (int) s[1], HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
            spawnGuard(new Location(world, s[0] + 0.5, y, s[1] + 0.5));
        }
    }

    private void spawnGuard(Location at) {
        EntityType type;
        try { type = EntityType.valueOf(cfg().getString("guardians.type", "WITHER_SKELETON")); }
        catch (Exception e) { type = EntityType.WITHER_SKELETON; }
        double hp = cfg().getDouble("guardians.health", 80);
        double dmg = cfg().getDouble("guardians.damage", 6);
        double speed = cfg().getDouble("guardians.speed", 0.32);

        Entity ent = world.spawnEntity(at, type);
        if (!(ent instanceof Mob m)) { ent.remove(); return; }
        setAttr(m, Attribute.GENERIC_MAX_HEALTH, hp);
        m.setHealth(hp);
        setAttr(m, Attribute.GENERIC_ATTACK_DAMAGE, dmg);
        setAttr(m, Attribute.GENERIC_MOVEMENT_SPEED, speed);
        setAttr(m, Attribute.GENERIC_FOLLOW_RANGE, 24);
        setAttr(m, Attribute.GENERIC_KNOCKBACK_RESISTANCE, 0.6);

        m.setRemoveWhenFarAway(false);
        m.setCanPickupItems(false);
        m.setCustomNameVisible(true);
        m.getPersistentDataContainer().set(pl.markKey, PersistentDataType.BYTE, (byte) 1);
        equip(m);
        Bukkit.getScheduler().runTask(pl, () -> equip(m)); // на случай, если спавн докинул экипировку

        guards.add(m.getUniqueId());
        homes.put(m.getUniqueId(), at.clone());
        m.setCustomName(guardName(m));
    }

    private void equip(Mob m) {
        var eq = m.getEquipment();
        if (eq == null) return;
        eq.setHelmet(armor(Material.NETHERITE_HELMET));
        eq.setChestplate(armor(Material.NETHERITE_CHESTPLATE));
        eq.setLeggings(armor(Material.NETHERITE_LEGGINGS));
        eq.setBoots(armor(Material.NETHERITE_BOOTS));
        eq.setItemInMainHand(null);
        if (!raged.contains(m.getUniqueId())) eq.setItemInOffHand(new ItemStack(Material.TOTEM_OF_UNDYING));
        eq.setHelmetDropChance(0); eq.setChestplateDropChance(0);
        eq.setLeggingsDropChance(0); eq.setBootsDropChance(0);
        eq.setItemInMainHandDropChance(0); eq.setItemInOffHandDropChance(0);
    }

    private ItemStack armor(Material mat) {
        ItemStack it = new ItemStack(mat);
        it.addUnsafeEnchantment(Enchantment.PROTECTION_ENVIRONMENTAL, 4);
        return it;
    }

    private void setAttr(LivingEntity e, Attribute a, double v) {
        var inst = e.getAttribute(a);
        if (inst != null) inst.setBaseValue(v);
    }

    private String guardName(LivingEntity m) {
        int hp = (int) Math.ceil(m.getHealth());
        return raged.contains(m.getUniqueId())
                ? "§4§l✖ Страж Алтаря §c§l[ЯРОСТЬ] §8[§c" + hp + "§8]"
                : "§4§l⚔ Страж Алтаря §8[§c" + hp + "§8]";
    }

    /** Тотем «снесён» (сработал) — страж впадает в ярость. */
    void onTotem(Mob m) {
        UUID id = m.getUniqueId();
        raged.add(id);
        double dmg = cfg().getDouble("guardians.damage-no-totem", 8);
        double after = cfg().getDouble("guardians.health-after-totem", 40);
        Bukkit.getScheduler().runTask(pl, () -> {
            if (m.isDead()) return;
            setAttr(m, Attribute.GENERIC_ATTACK_DAMAGE, dmg);
            m.removePotionEffect(PotionEffectType.REGENERATION);
            m.removePotionEffect(PotionEffectType.ABSORPTION);
            var maxHp = m.getAttribute(Attribute.GENERIC_MAX_HEALTH);
            m.setHealth(Math.min(maxHp == null ? after : maxHp.getValue(), after));
            m.setCustomName(guardName(m));
            Location l = m.getLocation().add(0, 1, 0);
            world.strikeLightningEffect(m.getLocation());
            world.spawnParticle(Particle.SOUL_FIRE_FLAME, l, 60, .5, .8, .5, .08);
            world.playSound(l, Sound.ENTITY_WITHER_AMBIENT, 1.5f, 0.6f);
            for (Player p : world.getPlayers())
                if (p.getLocation().distanceSquared(l) < 40 * 40)
                    AltarPlugin.actionBar(p, "§4§l✖ §cТотем стража разбит — он в ярости! §7(урон §f" + (int) dmg + "§7)");
        });
    }

    void onGuardDeath(Entity e) {
        guards.remove(e.getUniqueId());
        raged.remove(e.getUniqueId());
        homes.remove(e.getUniqueId());
        for (Player p : world.getPlayers())
            if (p.getLocation().distanceSquared(center) < 80 * 80)
                p.sendMessage(AltarPlugin.PREFIX + "§cСтраж алтаря повержен §8(§7осталось §f" + guards.size() + "§8)");
    }

    boolean isGuard(Entity e) { return guards.contains(e.getUniqueId()); }
    int guardCount() { return guards.size(); }
    boolean owns(Entity e) { return guards.contains(e.getUniqueId()) || e.getUniqueId().equals(holoId); }

    /** Не даём стражам покидать территорию. */
    private void leash() {
        double r = cfg().getDouble("guardians.leash-radius", 22);
        for (UUID id : new ArrayList<>(guards)) {
            Entity e = Bukkit.getEntity(id);
            if (!(e instanceof Mob m) || m.isDead()) continue;
            Location home = homes.get(id);
            double dx = m.getLocation().getX() - center.getX(), dz = m.getLocation().getZ() - center.getZ();
            double d2 = dx * dx + dz * dz;
            if (d2 > r * r) {
                m.setTarget(null);
                if (d2 > (r + 3) * (r + 3)) m.teleport(home);
                else m.getPathfinder().moveTo(home);
            } else if (m.getTarget() == null && home != null && m.getLocation().distanceSquared(home) > 16) {
                m.getPathfinder().moveTo(home);
            }
            m.setCustomName(guardName(m));
        }
    }

    boolean inTerritory(Location l) {
        if (l.getWorld() != world) return false;
        double r = cfg().getDouble("guardians.leash-radius", 22);
        double dx = l.getX() - center.getX(), dz = l.getZ() - center.getZ();
        return dx * dx + dz * dz <= r * r;
    }

    /* ═════════════════ шалкер ═════════════════ */

    boolean isChest(Location l) {
        return l.getWorld() == world && l.getBlockX() == chestLoc.getBlockX()
                && l.getBlockY() == chestLoc.getBlockY() && l.getBlockZ() == chestLoc.getBlockZ();
    }

    void click(Player p) {
        if (!opened) {
            int left = cfg().getInt("open-seconds", 300) - elapsed;
            AltarPlugin.actionBar(p, "§c🔒 Шалкер закрыт §7— откроется через §f" + mmss(left));
            p.playSound(p.getLocation(), Sound.BLOCK_CHEST_LOCKED, 1f, 0.8f);
            return;
        }
        p.openInventory(inv);
    }

    private void open() {
        opened = true;
        chestLoc.getBlock().setType(Material.BLACK_SHULKER_BOX, false);
        var y = pl.loot;
        String title = y != null ? y.getString("inv-name", "§f⚔ §4§lОГНЕННЫЙ АЛТАРЬ §f⚔") : "§f⚔ §4§lОГНЕННЫЙ АЛТАРЬ §f⚔";
        int size = y != null ? y.getInt("chest-inventory-size", 54) : 54;
        inv = Bukkit.createInventory(null, size, title);

        if (y != null) {
            ConfigurationSection root = y.getConfigurationSection("inv");
            ConfigurationSection sec = null;
            if (root != null) {
                String key = cfg().getString("loot-key", "");
                if (!key.isEmpty()) sec = root.getConfigurationSection(key);
                else for (String k : root.getKeys(false)) { sec = root.getConfigurationSection(k); if (sec != null) break; }
            }
            if (sec != null) {
                for (String slotKey : sec.getKeys(false)) {
                    ConfigurationSection chances = sec.getConfigurationSection(slotKey);
                    if (chances == null) continue;
                    int slot;
                    try { slot = Integer.parseInt(slotKey); } catch (NumberFormatException e) { continue; }
                    if (slot < 0 || slot >= size) continue;
                    for (String ch : chances.getKeys(false)) {
                        double chance;
                        try { chance = Double.parseDouble(ch); } catch (NumberFormatException e) { continue; }
                        ItemStack it = chances.getItemStack(ch);
                        if (it != null && rnd.nextDouble() * 100.0 < chance) { inv.setItem(slot, it.clone()); break; }
                    }
                }
            }
        }

        world.strikeLightningEffect(chestLoc);
        world.playSound(chestLoc, Sound.BLOCK_END_PORTAL_SPAWN, 3f, 0.8f);
        world.spawnParticle(Particle.TOTEM, chestLoc.clone().add(.5, 1, .5), 120, .6, .8, .6, .35);
        Bukkit.broadcastMessage(AltarPlugin.PREFIX + "§a§lШалкер Огненного алтаря открыт! §7Координаты: §f" + coords());
        for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 0.8f);
    }

    void onClose() {
        if (!cfg().getBoolean("end-when-empty", true) || inv == null) return;
        for (ItemStack it : inv.getContents()) if (it != null && !it.getType().isAir()) return;
        int newEnd = elapsed + 10;
        if (newEnd < endAt) {
            endAt = newEnd;
            Bukkit.broadcastMessage(AltarPlugin.PREFIX + "§7Шалкер опустошён — алтарь исчезнет через §f10 сек§7.");
        }
    }

    /* ═════════════════ тик ═════════════════ */

    private void tick() {
        tickCount++;
        leash();
        fx();
        if (tickCount % 2 == 0) second();
    }

    private void second() {
        elapsed++;
        int open = cfg().getInt("open-seconds", 300);
        if (!opened && elapsed >= open) open();
        if (elapsed >= endAt) { pl.endSession("§c§lОгненный алтарь рассыпался в прах. §7Ивент завершён."); return; }

        int left = endAt - elapsed;
        if (opened && (left == 60 || left == 30 || left == 10))
            Bukkit.broadcastMessage(AltarPlugin.PREFIX + "§cАлтарь исчезнет через §f" + left + " сек§c!");
        if (!opened && open - elapsed == 60)
            Bukkit.broadcastMessage(AltarPlugin.PREFIX + "§eШалкер откроется через §f1 минуту§e!");

        updateBar();
        updateHolo();
    }

    private void fx() {
        Location c = chestLoc.clone().add(.5, .8, .5);
        world.spawnParticle(Particle.SOUL_FIRE_FLAME, c, 3, .25, .3, .25, .01);
        if (opened) world.spawnParticle(Particle.TOTEM, c, 2, .3, .4, .3, .05);
        else world.spawnParticle(Particle.ENCHANTMENT_TABLE, c, 6, .4, .5, .4, .5);
        for (Block b : pillarTops) world.spawnParticle(Particle.SMOKE_LARGE, b.getLocation().add(.5, 1.2, .5), 1, .1, .2, .1, .01);
        world.spawnParticle(Particle.ASH, center.getX(), baseY + 4, center.getZ(), 30, sx / 2.0 + 6, 6, sz / 2.0 + 6, 0);
        if (tickCount % 30 == 0) world.playSound(center, Sound.AMBIENT_SOUL_SAND_VALLEY_MOOD, 2f, 0.7f);
    }

    private void updateBar() {
        int open = cfg().getInt("open-seconds", 300), max = cfg().getInt("max-duration-seconds", 1200);
        String title; double prog;
        if (!opened) {
            int left = open - elapsed;
            prog = left / (double) open;
            title = "§4§l⚔ §c§lОГНЕННЫЙ АЛТАРЬ §4§l⚔ §8| §7Шалкер откроется через §f" + mmss(left)
                    + " §8| §7Стражи: §c" + guards.size() + "§7/" + totalGuards;
            bar.setColor(BarColor.RED); bar.setStyle(BarStyle.SEGMENTED_20);
        } else {
            int left = endAt - elapsed;
            prog = left / (double) Math.max(1, max - open);
            title = "§4§l⚔ §a§lШАЛКЕР ОТКРЫТ §4§l⚔ §8| §7Алтарь исчезнет через §f" + mmss(left)
                    + " §8| §7Стражи: §c" + guards.size() + "§7/" + totalGuards;
            bar.setColor(left <= 30 ? BarColor.RED : left <= 120 ? BarColor.YELLOW : BarColor.GREEN);
            bar.setStyle(BarStyle.SEGMENTED_10);
        }
        bar.setTitle(title);
        bar.setProgress(Math.max(0, Math.min(1, prog)));

        double rad = cfg().getDouble("bossbar-radius", -1);
        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean show = rad < 0 || (p.getWorld() == world && p.getLocation().distanceSquared(center) <= rad * rad);
            if (show) bar.addPlayer(p); else bar.removePlayer(p);
        }
    }

    /* ═════════════════ голограмма ═════════════════ */

    private String holoText() {
        String head = "§f⚔ §4§lОГНЕННЫЙ АЛТАРЬ §f⚔";
        if (opened) return head + "\n§aОткрыто";
        return head + "\n§cЗакрыто!\n§aДо открытия: §f" + mmss(cfg().getInt("open-seconds", 300) - elapsed);
    }

    private void spawnHolo() {
        Location hl = chestLoc.clone().add(.5, 1.8, .5);
        TextDisplay td = world.spawn(hl, TextDisplay.class, t -> {
            t.setBillboard(Display.Billboard.CENTER);
            t.setBackgroundColor(Color.fromARGB(110, 0, 0, 0));
            t.setText(holoText());
            t.getPersistentDataContainer().set(pl.markKey, PersistentDataType.BYTE, (byte) 1);
        });
        holoId = td.getUniqueId();
    }

    private void updateHolo() {
        if (holoId == null) return;
        Entity e = Bukkit.getEntity(holoId);
        if (e instanceof TextDisplay td) td.setText(holoText());
    }

    /* ═════════════════ утилиты ═════════════════ */

    boolean inProtect(Location l) {
        if (l.getWorld() != world) return false;
        double pr = cfg().getDouble("protect-radius", 50);
        double dx = l.getX() - center.getX(), dz = l.getZ() - center.getZ();
        return dx * dx + dz * dz <= pr * pr;
    }

    String coords() {
        return chestLoc.getBlockX() + " " + chestLoc.getBlockY() + " " + chestLoc.getBlockZ() + " §8(§7" + world.getName() + "§8)";
    }

    Location tpLocation() {
        int x = (int) center.getX(), z = minZ - 3;
        int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
        Location l = new Location(world, x + .5, y, z + .5);
        l.setYaw(0f); // смотрит на юг, на алтарь
        return l;
    }

    static String mmss(int s) {
        s = Math.max(0, s);
        return String.format("%02d:%02d", s / 60, s % 60);
    }

    private void restore() {
        if (snap == null) return;
        try (EditSession es = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(world))) {
            Operations.complete(new ClipboardHolder(snap).createPaste(es).to(snapMin).ignoreAirBlocks(false).build());
        } catch (Exception ex) {
            pl.getLogger().warning("Не удалось восстановить местность: " + ex);
        }
    }

    /* ═════════════════ завершение ═════════════════ */

    void end(String reason) {
        if (task != null) task.cancel();
        if (bar != null) bar.removeAll();

        if (inv != null) for (HumanEntity h : new ArrayList<>(inv.getViewers())) h.closeInventory();
        for (UUID id : guards) { Entity e = Bukkit.getEntity(id); if (e != null) e.remove(); }
        guards.clear();
        if (holoId != null) { Entity e = Bukkit.getEntity(holoId); if (e != null) e.remove(); }
        for (Entity e : world.getEntities())
            if (e.getPersistentDataContainer().has(pl.markKey, PersistentDataType.BYTE)) e.remove();

        if (chestLoc != null) world.playSound(chestLoc, Sound.ENTITY_GENERIC_EXPLODE, 3f, 0.6f);
        restore();
        for (int[] t : tickets) world.removePluginChunkTicket(t[0], t[1], pl);
        pl.clearState();
        Bukkit.broadcastMessage(AltarPlugin.PREFIX + reason);
    }
}
