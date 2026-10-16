package ru.altar;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardWriter;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.session.ClipboardHolder;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

public final class AltarPlugin extends JavaPlugin implements Listener, TabExecutor {

    static final String PREFIX = "§8[§4§lАлтарь§8] §7";
    final Random random = new Random();
    NamespacedKey markKey;
    AltarSession session;
    Clipboard schem;
    YamlConfiguration loot;
    ZoneId zone = ZoneId.of("Europe/Moscow");
    private LocalDate lastAuto;
    private final Set<Integer> firedAlerts = new HashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getDataFolder().mkdirs();
        markKey = new NamespacedKey(this, "altar_entity");
        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand c = getCommand("altar");
        if (c != null) { c.setExecutor(this); c.setTabCompleter(this); }
        loadResources();
        getServer().getScheduler().runTask(this, this::restoreLeftover);
        getServer().getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
    }

    @Override
    public void onDisable() {
        if (session != null) endSession("§cСервер перезапускается — алтарь исчез.");
    }

    /* ───────────── загрузка ресурсов ───────────── */

    void loadResources() {
        try { zone = ZoneId.of(getConfig().getString("timezone", "Europe/Moscow")); }
        catch (Exception e) { zone = ZoneId.of("Europe/Moscow"); }

        schem = null;
        File sf = new File(getDataFolder(), getConfig().getString("schematic", "fire_cursed_blade_altar.schem"));
        if (sf.exists()) {
            try (InputStream in = new FileInputStream(sf)) {
                ClipboardFormat fmt = ClipboardFormats.findByFile(sf);
                try (ClipboardReader r = fmt.getReader(in)) { schem = r.read(); }
            } catch (Exception e) { getLogger().warning("Не удалось прочитать схематику: " + e); }
        } else getLogger().warning("Схематика не найдена: " + sf.getPath());

        loot = null;
        File lf = new File(getDataFolder(), getConfig().getString("loot-file", "loot.yml"));
        if (lf.exists()) loot = YamlConfiguration.loadConfiguration(lf);
        else getLogger().warning("Файл лута не найден: " + lf.getPath());
    }

    /* ───────────── расписание ───────────── */

    private LocalTime spawnTime() {
        try { return LocalTime.parse(getConfig().getString("spawn-time", "14:00")); }
        catch (Exception e) { return LocalTime.of(14, 0); }
    }

    ZonedDateTime nextSpawn(ZonedDateTime now) {
        ZonedDateTime n = now.with(spawnTime());
        if (!n.isAfter(now)) n = n.plusDays(1);
        return n;
    }

    private void tick() {
        ZonedDateTime now = ZonedDateTime.now(zone);
        ZonedDateTime today = now.with(spawnTime());
        if (!now.isBefore(today) && now.isBefore(today.plusSeconds(30)) && !now.toLocalDate().equals(lastAuto)) {
            lastAuto = now.toLocalDate();
            if (session == null) startEvent();
            return;
        }
        if (session != null) return;
        long secs = Duration.between(now, nextSpawn(now)).getSeconds();
        for (int m : getConfig().getIntegerList("alerts-minutes")) {
            long t = m * 60L;
            if (secs <= t && secs > t - 90 && firedAlerts.add(m)) {
                String when = m >= 60 ? (m / 60) + " ч" : m + " мин";
                Bukkit.broadcastMessage(PREFIX + "§4§lОгненный алтарь §7проснётся через §f" + when + "§7! §8(§7" + getConfig().getString("spawn-time", "14:00") + " МСК§8)");
                for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BELL, 0.6f, 0.6f);
            }
        }
    }

    /* ───────────── управление ивентом ───────────── */

    boolean startEvent() {
        if (session != null) return false;
        World w = Bukkit.getWorld(getConfig().getString("world", "grief-1"));
        if (w == null) { getLogger().warning("Мир не найден: " + getConfig().getString("world")); return false; }
        if (schem == null) { getLogger().warning("Схематика не загружена."); return false; }
        firedAlerts.clear();
        AltarSession s = new AltarSession(this, w);
        if (!s.start()) { getLogger().warning("Не удалось подобрать место / вставить схематику."); return false; }
        session = s;
        return true;
    }

    void endSession(String reason) {
        if (session == null) return;
        AltarSession s = session;
        session = null;
        s.end(reason);
    }

    void saveSnapshot(BlockArrayClipboard snap, World w, BlockVector3 min) {
        try (ClipboardWriter wr = BuiltInClipboardFormat.SPONGE_SCHEMATIC.getWriter(new FileOutputStream(new File(getDataFolder(), "snapshot.schem")))) {
            wr.write(snap);
        } catch (Exception e) { getLogger().warning("Не удалось сохранить снимок местности: " + e); }
        YamlConfiguration y = new YamlConfiguration();
        y.set("world", w.getName());
        y.set("x", min.getBlockX()); y.set("y", min.getBlockY()); y.set("z", min.getBlockZ());
        try { y.save(new File(getDataFolder(), "state.yml")); } catch (Exception ignored) { }
    }

    void clearState() {
        new File(getDataFolder(), "state.yml").delete();
        new File(getDataFolder(), "snapshot.schem").delete();
    }

    /** Если сервер упал во время ивента — возвращаем местность обратно. */
    private void restoreLeftover() {
        File sf = new File(getDataFolder(), "state.yml");
        File cf = new File(getDataFolder(), "snapshot.schem");
        if (!sf.exists() || !cf.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(sf);
        World w = Bukkit.getWorld(y.getString("world", ""));
        if (w == null) return;
        try (InputStream in = new FileInputStream(cf)) {
            ClipboardFormat fmt = ClipboardFormats.findByFile(cf);
            Clipboard c;
            try (ClipboardReader r = fmt.getReader(in)) { c = r.read(); }
            try (EditSession es = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(w))) {
                Operations.complete(new ClipboardHolder(c).createPaste(es)
                        .to(BlockVector3.at(y.getInt("x"), y.getInt("y"), y.getInt("z"))).ignoreAirBlocks(false).build());
            }
            getLogger().info("Восстановлена местность после незавершённого ивента.");
        } catch (Exception e) { getLogger().warning("Не удалось восстановить местность: " + e); return; }
        sf.delete(); cf.delete();
    }

    /* ───────────── команды ───────────── */

    static String hms(long secs) {
        long h = secs / 3600, m = (secs % 3600) / 60;
        return h > 0 ? h + " ч " + m + " мин" : Math.max(1, m) + " мин";
    }

    private void info(CommandSender s) {
        String line = "§8§m                                        ";
        s.sendMessage(line);
        s.sendMessage(" §4§l⚔ §c§lОГНЕННЫЙ АЛТАРЬ §4§l⚔");
        if (session != null) {
            s.sendMessage(" §7Статус: §aактивен");
            s.sendMessage(" §7Координаты: §f" + session.coords());
            s.sendMessage(session.opened
                    ? " §7Шалкер: §aоткрыт §8| §7исчезнет через §f" + AltarSession.mmss(session.endAt - session.elapsed)
                    : " §7Шалкер откроется через §f" + AltarSession.mmss(getConfig().getInt("open-seconds", 300) - session.elapsed));
            s.sendMessage(" §7Стражи: §c" + session.guardCount() + "§7/" + session.totalGuards);
            if (s instanceof Player p && p.getWorld().equals(session.world))
                s.sendMessage(" §7До алтаря: §f~" + (int) p.getLocation().distance(session.chestLoc) + " блоков");
        } else {
            ZonedDateTime now = ZonedDateTime.now(zone);
            s.sendMessage(" §7Статус: §cспит");
            s.sendMessage(" §7Следующий спавн: §f" + getConfig().getString("spawn-time", "14:00") + " МСК §8(§7через §f"
                    + hms(Duration.between(now, nextSpawn(now)).getSeconds()) + "§8)");
        }
        if (s.hasPermission("altar.admin"))
            s.sendMessage(" §8Админ: §7/altar §fstart §8| §fstop §8| §freload §8| §ftp");
        s.sendMessage(line);
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length == 0) { info(s); return true; }
        if (!s.hasPermission("altar.admin")) { s.sendMessage(PREFIX + "§cНет прав."); return true; }
        switch (a[0].toLowerCase()) {
            case "start" -> {
                if (session != null) { s.sendMessage(PREFIX + "§cАлтарь уже активен."); return true; }
                s.sendMessage(PREFIX + "§7Подбираю место и строю алтарь…");
                if (startEvent()) s.sendMessage(PREFIX + "§aАлтарь запущен: §f" + session.coords());
                else s.sendMessage(PREFIX + "§cНе удалось запустить (см. консоль).");
            }
            case "stop" -> {
                if (session == null) { s.sendMessage(PREFIX + "§cАлтарь не активен."); return true; }
                endSession("§cАлтарь остановлен администратором.");
                s.sendMessage(PREFIX + "§aАлтарь остановлен, местность восстановлена.");
            }
            case "reload" -> {
                reloadConfig();
                loadResources();
                s.sendMessage(PREFIX + "§aКонфиг, схематика и лут перезагружены." + (session != null ? " §8(активный ивент не затронут)" : ""));
            }
            case "tp" -> {
                if (!(s instanceof Player p)) { s.sendMessage(PREFIX + "§cТолько для игроков."); return true; }
                if (session == null) { p.sendMessage(PREFIX + "§cАлтарь не активен."); return true; }
                p.teleport(session.tpLocation());
                p.sendMessage(PREFIX + "§aТелепортирован к алтарю.");
            }
            default -> s.sendMessage(PREFIX + "§7Использование: §f/altar [start|stop|reload|tp]");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1 && s.hasPermission("altar.admin"))
            for (String x : List.of("start", "stop", "reload", "tp")) if (x.startsWith(a[0].toLowerCase())) out.add(x);
        return out;
    }

    static void actionBar(Player p, String msg) {
        p.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(msg));
    }

    /* ───────────── события ───────────── */

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (session == null || e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        if (!session.isChest(e.getClickedBlock().getLocation())) return;
        e.setCancelled(true);
        if (e.getHand() == EquipmentSlot.HAND) session.click(e.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (session != null && session.inProtect(e.getBlock().getLocation()) && !e.getPlayer().hasPermission("altar.bypass")) {
            e.setCancelled(true);
            actionBar(e.getPlayer(), "§cЭта зона защищена силой алтаря");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (session != null && session.inProtect(e.getBlock().getLocation()) && !e.getPlayer().hasPermission("altar.bypass")) {
            e.setCancelled(true);
            actionBar(e.getPlayer(), "§cЭта зона защищена силой алтаря");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        if (session != null) e.blockList().removeIf(b -> session.inProtect(b.getLocation()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        if (session != null) e.blockList().removeIf(b -> session.inProtect(b.getLocation()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent e) {
        if (session == null || !session.isGuard(e.getEntity())) return;
        if (e.getTarget() != null && !session.inTerritory(e.getTarget().getLocation())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onResurrect(EntityResurrectEvent e) {
        if (session != null && session.isGuard(e.getEntity()) && e.getEntity() instanceof Mob m) session.onTotem(m);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (session == null || getConfig().getBoolean("guardians.wither-effect", false)) return;
        if (session.isGuard(e.getDamager()) && e.getEntity() instanceof Player p)
            Bukkit.getScheduler().runTask(this, () -> p.removePotionEffect(PotionEffectType.WITHER));
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        if (session == null || !session.isGuard(e.getEntity())) return;
        e.getDrops().clear();
        e.setDroppedExp(getConfig().getInt("guardians.xp", 150));
        session.onGuardDeath(e.getEntity());
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (session != null && session.inv != null && e.getInventory() == session.inv) session.onClose();
    }

    /** Чистим «осиротевших» стражей / голограммы после рестарта. */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        for (Entity en : e.getEntities()) {
            if (!en.getPersistentDataContainer().has(markKey, PersistentDataType.BYTE)) continue;
            if (session == null || !session.owns(en)) en.remove();
        }
    }
}
