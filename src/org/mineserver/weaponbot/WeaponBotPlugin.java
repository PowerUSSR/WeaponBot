package org.mineserver.weaponbot;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.*;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;

public class WeaponBotPlugin extends JavaPlugin implements Listener {

    private FakeNPC fakeNpc;
    private Economy economy;
    private int currentPage = 0;
    private long nextRotationTime;
    private static final long CYCLE_MS = 12L * 60 * 60 * 1000; // 12 часов

    private File dataFile;
    private FileConfiguration dataConfig;

    private final List<List<WeaponItem>> pages = new ArrayList<>();
    private int[][] soldCounts = new int[2][18];

    // Деманический молот — страница 2 (индекс 1), слот 17
    private static final int DEMONIAC_PAGE = 1;
    private static final int DEMONIAC_SLOT = 17;
    private boolean demoniacAvailable = false;

    // ==================== ENABLE / DISABLE ====================

    @Override
    public void onEnable() {
        saveDefaultConfig();
        buildPages();
        loadData();

        if (!setupEconomy()) {
            getLogger().severe("Vault/Economy не найден! Плагин отключён.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getPluginManager().registerEvents(this, this);
        setupNpcInteractListener();
        getServer().getScheduler().runTaskLater(this, this::spawnOrFindBot, 10L);
        startCycleTimer();
        startRespawnTicker();
        getLogger().info("WeaponBot включён. Текущая страница: " + (currentPage + 1)
            + ". Деманический молот: " + (demoniacAvailable ? "ДОСТУПЕН" : "не появился"));
    }

    @Override
    public void onDisable() {
        if (fakeNpc != null) fakeNpc.despawnForAll();
        saveData();
        getLogger().info("WeaponBot отключён, данные сохранены.");
    }

    private void setupNpcInteractListener() {
        ProtocolLibrary.getProtocolManager().addPacketListener(
            new PacketAdapter(this, ListenerPriority.NORMAL, PacketType.Play.Client.USE_ENTITY) {
                @Override
                public void onPacketReceiving(PacketEvent event) {
                    if (fakeNpc == null || !fakeNpc.isCreated()) return;
                    int entityId = event.getPacket().getIntegers().read(0);
                    if (entityId != fakeNpc.getEntityId()) return;
                    event.setCancelled(true);
                    Player player = event.getPlayer();
                    getServer().getScheduler().runTask(WeaponBotPlugin.this, () -> {
                        if (player.isOnline()) player.openInventory(buildGui());
                    });
                }
            });
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) return false;
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        economy = rsp.getProvider();
        return economy != null;
    }

    // ==================== СТРАНИЦЫ ====================

    private void buildPages() {
        // --- Страница 1 (слоты 0–17) ---
        List<WeaponItem> p1 = new ArrayList<>();
        p1.add(new WeaponItem("iasurvival:obsidian_shield",    Material.SHIELD,        "Обсидиановый щит",       1,   25.0,  4));
        p1.add(new WeaponItem("iasurvival:ruby_shield",        Material.SHIELD,        "Рубиновый щит",          1,   40.0,  2));
        p1.add(new WeaponItem("iasurvival:bronze_shield",      Material.SHIELD,        "Бронзовый щит",          1,   40.0,  2));
        p1.add(new WeaponItem("iasurvival:red_bow",            Material.BOW,           "Красный покрашенный лук",1,   80.0,  3));
        p1.add(new WeaponItem("iasurvival:knife",              Material.STONE_SWORD,   "Нож",                    1,    5.0,  6));
        p1.add(new WeaponItem("iasurvival:bronze_sword",       Material.IRON_SWORD,    "Бронзовый меч",          1,   25.0,  2));
        p1.add(new WeaponItem("iasurvival:ruby_dagger",        Material.GOLDEN_SWORD,  "Рубиновый кинжал",       1,   17.0,  2));
        p1.add(new WeaponItem("iasurvival:emerald_sword",      Material.DIAMOND_SWORD, "Изумрудный меч",         1,   15.0,  3));
        p1.add(new WeaponItem("iasurvival:dark_amethyst_sword",Material.DIAMOND_SWORD, "Меч из тёмного аметиста",1,   30.0,  2));
        p1.add(new WeaponItem("iasurvival:ruby_sword",         Material.DIAMOND_SWORD, "Рубиновый меч",          1,   25.0,  1));
        p1.add(new WeaponItem("iasurvival:end_sword",          Material.DIAMOND_SWORD, "Меч энда",               1,   20.0,  1));
        p1.add(new WeaponItem("iaspecial_swords:baseball_bat", Material.STICK,         "Бейсбольная бита",       1,    8.0,  5));
        p1.add(new WeaponItem("iaspecial_swords:floating_sword",Material.DIAMOND_SWORD,"Плавающий меч",          1,  150.0,  1));
        p1.add(new WeaponItem("iasurvival:diamond_hammer",     Material.DIAMOND_PICKAXE,"Алмазный молот",         1,  100.0,  1));
        p1.add(new WeaponItem("iaspecial_swords:masters_sword",Material.DIAMOND_SWORD, "Меч мастера",            1,  150.0,  1));
        p1.add(new WeaponItem("iaspecial_swords:red_lightsaber",Material.BLAZE_ROD,    "Красная световая сабля", 1,  225.0,  1));
        p1.add(new WeaponItem("iasurvival:bloodnite_sword",    Material.DIAMOND_SWORD, "Кровавая ночь",          1,   30.0,  1));
        p1.add(new WeaponItem("iaspecial_swords:green_lightsaber",Material.BLAZE_ROD,  "Зелёная световая сабля", 1,  200.0,  1));
        pages.add(p1);

        // --- Страница 2 (слоты 0–17) ---
        List<WeaponItem> p2 = new ArrayList<>();
        p2.add(new WeaponItem("iasurvival:ender_shield",        Material.SHIELD,        "Щит энда",               1,   75.0,  1));
        p2.add(new WeaponItem("iasurvival:dark_amethyst_shield",Material.SHIELD,        "Щит из тёмного аметиста",1,   40.0,  2));
        p2.add(new WeaponItem("iasurvival:chisel",              Material.WOODEN_SWORD,  "Зубило",                 1,    5.0,  6));
        p2.add(new WeaponItem("iaalchemy:mysterious_sword",     Material.DIAMOND_SWORD, "Мистический меч",        1,   30.0,  2));
        p2.add(new WeaponItem("iaalchemy:astral_bow",           Material.BOW,           "Астральный лук",         1,  100.0,  1));
        p2.add(new WeaponItem("iasurvival:blue_bow",            Material.BOW,           "Синий покрашенный лук",  1,   70.0,  2));
        p2.add(new WeaponItem("iasurvival:vyderlight_sword",    Material.DIAMOND_SWORD, "Видерлайт",              1,   40.0,  2));
        p2.add(new WeaponItem("iasurvival:rusteroth_sword",     Material.DIAMOND_SWORD, "Растероз",               1,   35.0,  2));
        p2.add(new WeaponItem("iasurvival:sweets_sword",        Material.WOODEN_SWORD,  "Сладкий меч",            1,   15.0,  3));
        p2.add(new WeaponItem("iasurvival:firesword_sword",     Material.DIAMOND_SWORD, "Огненый меч",            1,   20.0,  2));
        p2.add(new WeaponItem("iaspecial_swords:repulser_sword",Material.DIAMOND_SWORD, "Репульсер",              1,  120.0,  1));
        p2.add(new WeaponItem("iasurvival:end_sword",           Material.DIAMOND_SWORD, "Меч энда",               1,   20.0,  3));
        p2.add(new WeaponItem("iaspecial_swords:gandalf_stick", Material.STICK,         "Палка Гендальфа",        1,  350.0,  1));
        p2.add(new WeaponItem("iaspecial_swords:elucidator_sword",Material.DIAMOND_SWORD,"Разъяснитель",          1,  400.0,  1));
        p2.add(new WeaponItem("iaspecial_swords:blue_lightsaber",Material.BLAZE_ROD,    "Синяя световая сабля",   1,  215.0,  1));
        p2.add(new WeaponItem("iasurvival:aqualight_sword",     Material.DIAMOND_SWORD, "Аквалайт",               1,   30.0,  3));
        p2.add(new WeaponItem("iaspecial_swords:guitar_sword",  Material.STICK,         "Гитара",                 1,  600.0,  1));
        // Деманический молот — слот 17, редкий: 33% шанс появиться
        p2.add(new WeaponItem("iaalchemy:demoniac_hammer",      Material.IRON_PICKAXE,  "Деманический молот",     1, 3000.0,  1, true));
        pages.add(p2);
    }

    // ==================== ДАННЫЕ ====================

    private void loadData() {
        dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            currentPage = 0;
            nextRotationTime = System.currentTimeMillis() + CYCLE_MS;
            soldCounts = new int[2][18];
            demoniacAvailable = false; // стартуем со страницы 1 — молота нет
            return;
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
        currentPage = dataConfig.getInt("current-page", 0);
        nextRotationTime = dataConfig.getLong("next-rotation", 0L);
        demoniacAvailable = dataConfig.getBoolean("demoniac-available", false);
        // Если сейчас не страница 2 — молот недоступен
        if (currentPage != DEMONIAC_PAGE) demoniacAvailable = false;
        if (nextRotationTime <= System.currentTimeMillis()) {
            nextRotationTime = System.currentTimeMillis() + CYCLE_MS;
            rollDemoniac();
        }
        soldCounts = new int[2][18];
        for (int pg = 0; pg < 2; pg++) {
            for (int sl = 0; sl < 18; sl++) {
                soldCounts[pg][sl] = dataConfig.getInt("sold." + pg + "." + sl, 0);
            }
        }
    }

    private void saveData() {
        if (dataConfig == null) dataConfig = new YamlConfiguration();
        dataConfig.set("current-page", currentPage);
        dataConfig.set("next-rotation", nextRotationTime);
        dataConfig.set("demoniac-available", demoniacAvailable);
        for (int pg = 0; pg < 2; pg++) {
            for (int sl = 0; sl < 18; sl++) {
                dataConfig.set("sold." + pg + "." + sl, soldCounts[pg][sl]);
            }
        }
        try { dataConfig.save(dataFile); } catch (IOException e) { e.printStackTrace(); }
    }

    /** Случайно выбирает: доступен ли Деманический молот в этом цикле (33%). */
    private void rollDemoniac() {
        demoniacAvailable = Math.random() < 0.33;
    }

    // ==================== НПС ====================

    private void spawnOrFindBot() {
        String worldName = getConfig().getString("bot-location.world", "");
        if (worldName.isEmpty()) {
            getLogger().info("WeaponBot: позиция не задана. Используйте /weaponbot spawn.");
            return;
        }
        World world = getServer().getWorld(worldName);
        if (world == null) {
            getLogger().warning("Мир '" + worldName + "' не найден! Используйте /weaponbot spawn.");
            return;
        }
        double x = getConfig().getDouble("bot-location.x", 0.5);
        double y = getConfig().getDouble("bot-location.y", 64);
        double z = getConfig().getDouble("bot-location.z", 0.5);
        float yaw = (float) getConfig().getDouble("bot-location.yaw", 0.0);
        String skinTex = getConfig().getString("skin.texture", null);
        String skinSig = getConfig().getString("skin.signature", null);
        createNPCAt(new Location(world, x, y, z, yaw, 0), skinTex, skinSig);
    }

    private void createNPCAt(Location loc, String skinTex, String skinSig) {
        if (fakeNpc != null) fakeNpc.despawnForAll();
        fakeNpc = new FakeNPC(this, loc);
        if (skinTex != null && skinSig != null) fakeNpc.setSkin(skinTex, skinSig);
        fakeNpc.create();
        fakeNpc.spawnForAll();
        getLogger().info("WeaponBot: NPC создан на " + loc.getWorld().getName() +
            " x=" + (int)loc.getX() + " y=" + (int)loc.getY() + " z=" + (int)loc.getZ());
    }

    // ==================== РОТАЦИЯ ====================

    private void startCycleTimer() {
        new BukkitRunnable() {
            @Override public void run() {
                if (System.currentTimeMillis() >= nextRotationTime) rotatePage();
            }
        }.runTaskTimer(this, 20L * 60, 20L * 60);
    }

    private void startRespawnTicker() {
        new BukkitRunnable() {
            @Override public void run() {
                if (fakeNpc == null || !fakeNpc.isCreated()) return;
                Location npcLoc = fakeNpc.getLocation();
                if (npcLoc.getWorld() == null) return;
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (!p.getWorld().getName().equals(fakeNpc.getLocationWorld())) {
                        fakeNpc.forgetPlayer(p.getUniqueId());
                        continue;
                    }
                    double distSq = p.getLocation().distanceSquared(npcLoc);
                    if (distSq <= 64 * 64) {
                        fakeNpc.spawnFor(p);
                    } else if (distSq > 80 * 80) {
                        fakeNpc.forgetPlayer(p.getUniqueId());
                    }
                }
            }
        }.runTaskTimer(this, 20L * 5, 20L * 5);
    }

    private void rotatePage() {
        currentPage = (currentPage + 1) % 2;
        soldCounts[currentPage] = new int[18];
        nextRotationTime = System.currentTimeMillis() + CYCLE_MS;
        // Деманический молот только на странице 2 — кидаем кубик только при переходе на неё
        if (currentPage == DEMONIAC_PAGE) {
            rollDemoniac();
        } else {
            demoniacAvailable = false;
        }
        saveData();

        String[] names = {"Страница 1 (Мечи и щиты)", "Страница 2 (Редкое оружие)"};
        getServer().broadcastMessage("");
        getServer().broadcastMessage(ChatColor.DARK_RED + "╔══════════════════════════════╗");
        getServer().broadcastMessage(ChatColor.DARK_RED + "║  " + ChatColor.RED + "⚔ Оружейник обновил арсенал!");
        getServer().broadcastMessage(ChatColor.DARK_RED + "║  " + ChatColor.WHITE + "Активна: " + ChatColor.YELLOW + names[currentPage]);
        getServer().broadcastMessage(ChatColor.DARK_RED + "║  " + ChatColor.GRAY + "Подойдите к Оружейнику на спавне");
        getServer().broadcastMessage(ChatColor.DARK_RED + "╚══════════════════════════════╝");
        if (demoniacAvailable) {
            getServer().broadcastMessage("");
            getServer().broadcastMessage(ChatColor.DARK_PURPLE + "✦ " + ChatColor.LIGHT_PURPLE + "РЕДКОСТЬ! "
                + ChatColor.YELLOW + "Деманический молот" + ChatColor.WHITE + " появился у Оружейника!"
                + ChatColor.GRAY + " (шанс 33%)");
            getServer().broadcastMessage(ChatColor.DARK_PURPLE + "✦ " + ChatColor.GRAY + "Только 1 штука — спешите!");
        }
        getServer().broadcastMessage("");
        getLogger().info("WeaponBot: страница сменена на " + (currentPage + 1)
            + ". Деманический молот: " + (demoniacAvailable ? "ДОСТУПЕН" : "не появился"));
    }

    // ==================== GUI ====================

    private Inventory buildGui() {
        String title = ChatColor.DARK_RED + "Оружейник" + ChatColor.GRAY + " — Стр. " + (currentPage + 1) + "/2";
        Inventory inv = getServer().createInventory(null, 18, title);
        List<WeaponItem> page = pages.get(currentPage);
        for (int i = 0; i < 18; i++) {
            inv.setItem(i, buildSlot(page.get(i), i));
        }
        return inv;
    }

    private ItemStack buildSlot(WeaponItem item, int slot) {
        boolean isDemoniac = (currentPage == DEMONIAC_PAGE && slot == DEMONIAC_SLOT);
        int remaining = item.maxBuys - soldCounts[currentPage][slot];
        ItemStack stack = createItemStack(item);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) meta = getServer().getItemFactory().getItemMeta(stack.getType());
        List<String> lore = new ArrayList<>();

        if (isDemoniac && !demoniacAvailable) {
            meta.setDisplayName(ChatColor.DARK_PURPLE + "✦ " + item.displayName + ChatColor.DARK_GRAY + " [СЕКРЕТ]");
            lore.add(ChatColor.RED + "🔒 Редкий товар");
            lore.add(ChatColor.GRAY + "Шанс появления: " + ChatColor.YELLOW + "33%" + ChatColor.GRAY + " за цикл");
            lore.add(ChatColor.GRAY + "Цена при появлении: " + ChatColor.YELLOW + "$" + (int)item.price);
            lore.add("");
            lore.add(ChatColor.DARK_GRAY + "Не появился в этот цикл");
            lore.add(ChatColor.DARK_GRAY + "Следующая смена через 12ч");
        } else if (remaining > 0) {
            meta.setDisplayName(ChatColor.GOLD + item.displayName);
            lore.add(ChatColor.GRAY + "Количество: " + ChatColor.WHITE + item.amount + " шт.");
            lore.add(ChatColor.GRAY + "Цена: " + ChatColor.YELLOW + "$" + (int)item.price);
            lore.add(ChatColor.GRAY + "Осталось: " + ChatColor.GREEN + remaining
                + ChatColor.DARK_GRAY + "/" + item.maxBuys);
            if (isDemoniac) {
                lore.add("");
                lore.add(ChatColor.LIGHT_PURPLE + "✦ Редкий товар (шанс 33%/цикл)");
            }
            lore.add("");
            lore.add(ChatColor.GREEN + "▶  Нажмите чтобы купить");
        } else {
            meta.setDisplayName(ChatColor.RED + item.displayName + ChatColor.DARK_RED + " [РАСПРОДАНО]");
            lore.add(ChatColor.GRAY + "Количество: " + ChatColor.WHITE + item.amount + " шт.");
            lore.add(ChatColor.GRAY + "Цена: " + ChatColor.YELLOW + "$" + (int)item.price);
            lore.add(ChatColor.RED + "✗ Распродано до следующей смены!");
            lore.add(ChatColor.GRAY + "Обновится через 12 часов");
        }
        meta.setLore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    /** Создаёт ItemStack: сначала пробует ItemsAdder, иначе ванильный фоллбэк. */
    private ItemStack createItemStack(WeaponItem item) {
        if (getServer().getPluginManager().getPlugin("ItemsAdder") != null) {
            try {
                Class<?> csClass = Class.forName("dev.lone.itemsadder.api.CustomStack");
                java.lang.reflect.Method getInstance = csClass.getMethod("getInstance", String.class);
                Object cs = getInstance.invoke(null, item.iaId);
                if (cs != null) {
                    java.lang.reflect.Method getItemStack = csClass.getMethod("getItemStack");
                    ItemStack result = (ItemStack) getItemStack.invoke(cs);
                    if (result != null) {
                        result = result.clone();
                        result.setAmount(item.amount);
                        return result;
                    }
                }
            } catch (Exception e) {
                getLogger().warning("WeaponBot: не удалось создать IA предмет '" + item.iaId + "': " + e.getMessage());
            }
        }
        return new ItemStack(item.fallback, item.amount);
    }

    // ==================== СОБЫТИЯ ====================

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (fakeNpc == null || !fakeNpc.isCreated()) return;
        Player p = event.getPlayer();
        getServer().getScheduler().runTaskLater(this, () -> {
            if (p.isOnline()) fakeNpc.spawnFor(p);
        }, 20L);
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        if (fakeNpc == null || !fakeNpc.isCreated()) return;
        Player p = event.getPlayer();
        fakeNpc.forgetPlayer(p.getUniqueId());
        getServer().getScheduler().runTaskLater(this, () -> {
            if (p.isOnline()) fakeNpc.spawnFor(p);
        }, 20L);
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        if (fakeNpc == null || !fakeNpc.isCreated()) return;
        Player p = event.getPlayer();
        fakeNpc.forgetPlayer(p.getUniqueId());
        getServer().getScheduler().runTaskLater(this, () -> {
            if (p.isOnline() && p.getWorld().getName().equals(fakeNpc.getLocationWorld()))
                fakeNpc.spawnFor(p);
        }, 60L);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        String title = event.getView().getTitle();
        if (!title.contains("Оружейник")) return;
        event.setCancelled(true);

        if (event.getClickedInventory() == null) return;
        if (!event.getClickedInventory().equals(event.getView().getTopInventory())) return;

        int slot = event.getSlot();
        if (slot < 0 || slot >= 18) return;

        Player player = (Player) event.getWhoClicked();
        WeaponItem item = pages.get(currentPage).get(slot);

        // Проверка: деманический молот не появился в этом цикле
        boolean isDemoniac = (currentPage == DEMONIAC_PAGE && slot == DEMONIAC_SLOT);
        if (isDemoniac && !demoniacAvailable) {
            player.sendMessage(ChatColor.DARK_PURPLE + "✦ " + ChatColor.RED
                + "Деманический молот не появился в этот цикл! Шанс — 33% каждые 12 часов.");
            return;
        }

        int remaining = item.maxBuys - soldCounts[currentPage][slot];
        if (remaining <= 0) {
            player.sendMessage(ChatColor.RED + "✗ Товар '" + item.displayName + "' распродан до смены арсенала!");
            return;
        }

        if (!economy.has(player, item.price)) {
            player.sendMessage(ChatColor.RED + "✗ Недостаточно средств! Нужно: "
                + ChatColor.YELLOW + "$" + (int)item.price
                + ChatColor.RED + ", у вас: "
                + ChatColor.YELLOW + "$" + String.format("%.2f", economy.getBalance(player)));
            return;
        }

        ItemStack toGive = createItemStack(item);
        if (!hasInventorySpace(player.getInventory(), toGive)) {
            player.sendMessage(ChatColor.RED + "✗ В вашем инвентаре нет места!");
            return;
        }

        economy.withdrawPlayer(player, item.price);
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(toGive);
        for (ItemStack drop : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), drop);
        }
        soldCounts[currentPage][slot]++;
        saveData();

        player.sendMessage(ChatColor.GREEN + "✔ Куплено: " + ChatColor.WHITE + item.amount + "x " + item.displayName
            + ChatColor.GREEN + " за " + ChatColor.YELLOW + "$" + (int)item.price
            + ChatColor.GREEN + ". Баланс: " + ChatColor.YELLOW + "$"
            + String.format("%.2f", economy.getBalance(player)));

        // Обновить GUI
        List<WeaponItem> page = pages.get(currentPage);
        for (int i = 0; i < 18; i++) {
            event.getView().getTopInventory().setItem(i, buildSlot(page.get(i), i));
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTitle().contains("Оружейник")) event.setCancelled(true);
    }

    // ==================== УТИЛИТЫ ====================

    private boolean hasInventorySpace(PlayerInventory inv, ItemStack stack) {
        for (ItemStack s : inv.getStorageContents()) {
            if (s == null) return true;
            if (s.isSimilar(stack) && s.getAmount() + stack.getAmount() <= s.getMaxStackSize()) return true;
        }
        return false;
    }

    // ==================== КОМАНДЫ ====================

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("weaponbot")) return false;
        if (!sender.hasPermission("weaponbot.admin")) {
            sender.sendMessage(ChatColor.RED + "Нет прав.");
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "/weaponbot spawn — создать бота здесь");
            sender.sendMessage(ChatColor.YELLOW + "/weaponbot skin <ник> — установить скин");
            sender.sendMessage(ChatColor.YELLOW + "/weaponbot page <1-2> — переключить страницу");
            sender.sendMessage(ChatColor.YELLOW + "/weaponbot rotate — принудительно сменить страницу");
            sender.sendMessage(ChatColor.YELLOW + "/weaponbot info — состояние");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "spawn":
                if (!(sender instanceof Player)) { sender.sendMessage("Только для игроков."); return true; }
                Player sp = (Player) sender;
                Location spLoc = sp.getLocation();
                getConfig().set("bot-location.world", spLoc.getWorld().getName());
                getConfig().set("bot-location.x", spLoc.getX());
                getConfig().set("bot-location.y", spLoc.getY());
                getConfig().set("bot-location.z", spLoc.getZ());
                getConfig().set("bot-location.yaw", (double) spLoc.getYaw());
                saveConfig();
                createNPCAt(spLoc,
                    getConfig().getString("skin.texture"),
                    getConfig().getString("skin.signature"));
                sender.sendMessage(ChatColor.GREEN + "Оружейник создан! Установи скин: /weaponbot skin <ник>");
                return true;

            case "skin":
                if (args.length < 2) { sender.sendMessage(ChatColor.RED + "/weaponbot skin <ник>"); return true; }
                String skinTarget = args[1];
                if (!skinTarget.matches("[a-zA-Z0-9_]{1,16}")) {
                    sender.sendMessage(ChatColor.RED + "Неверный ник."); return true;
                }
                sender.sendMessage(ChatColor.YELLOW + "Загружаю скин игрока " + skinTarget + "...");
                fetchSkinAsync(sender, skinTarget);
                return true;

            case "page":
                if (args.length < 2) { sender.sendMessage("Укажите номер страницы 1-2"); return true; }
                try {
                    int pg = Integer.parseInt(args[1]) - 1;
                    if (pg < 0 || pg > 1) { sender.sendMessage("Страница 1-2"); return true; }
                    currentPage = pg;
                    saveData();
                    sender.sendMessage(ChatColor.GREEN + "Страница переключена на " + (currentPage + 1));
                } catch (NumberFormatException e) { sender.sendMessage("Укажите число 1-2"); }
                return true;

            case "rotate":
                rotatePage();
                sender.sendMessage(ChatColor.GREEN + "Страница сменена принудительно. Деманический молот: "
                    + (demoniacAvailable ? ChatColor.LIGHT_PURPLE + "ПОЯВИЛСЯ!" : ChatColor.GRAY + "не появился"));
                return true;

            case "info":
                long ms = nextRotationTime - System.currentTimeMillis();
                long h = Math.max(0, ms / 3600000), m = Math.max(0, (ms % 3600000) / 60000);
                sender.sendMessage(ChatColor.YELLOW + "Страница: " + (currentPage + 1) + "/2");
                sender.sendMessage(ChatColor.YELLOW + "До смены: " + h + "ч " + m + "мин");
                sender.sendMessage(ChatColor.YELLOW + "Деманический молот: "
                    + (demoniacAvailable ? ChatColor.LIGHT_PURPLE + "ДОСТУПЕН" : ChatColor.GRAY + "не появился"));
                List<WeaponItem> page = pages.get(currentPage);
                for (int sl = 0; sl < 18; sl++) {
                    WeaponItem it = page.get(sl);
                    int rem = it.maxBuys - soldCounts[currentPage][sl];
                    sender.sendMessage(ChatColor.GRAY + "  [" + sl + "] " + it.displayName + ": " + rem + "/" + it.maxBuys);
                }
                return true;
        }
        return false;
    }

    // ==================== SKIN FETCH ====================

    private void fetchSkinAsync(CommandSender sender, String username) {
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                URL url1 = new URL("https://api.mojang.com/users/profiles/minecraft/" + username);
                HttpURLConnection c1 = (HttpURLConnection) url1.openConnection();
                c1.setRequestProperty("Accept-Encoding", "identity");
                c1.setRequestProperty("User-Agent", "WeaponBot/1.0");
                c1.setConnectTimeout(5000);
                c1.setReadTimeout(5000);
                if (c1.getResponseCode() != 200) {
                    runSync(sender, ChatColor.RED + "Игрок '" + username + "' не найден.");
                    c1.disconnect(); return;
                }
                String resp1 = new String(c1.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
                c1.disconnect();
                if (!resp1.contains("\"id\"")) {
                    runSync(sender, ChatColor.RED + "Не удалось получить UUID для '" + username + "'.");
                    return;
                }
                String rawUUID = resp1.split("\"id\"\\s*:\\s*\"")[1].split("\"")[0];
                String uuid = rawUUID.replaceAll("(.{8})(.{4})(.{4})(.{4})(.+)", "$1-$2-$3-$4-$5");

                URL url2 = new URL("https://sessionserver.mojang.com/session/minecraft/profile/"
                    + uuid + "?unsigned=false");
                HttpURLConnection c2 = (HttpURLConnection) url2.openConnection();
                c2.setRequestProperty("Accept-Encoding", "identity");
                c2.setRequestProperty("User-Agent", "WeaponBot/1.0");
                c2.setConnectTimeout(5000);
                c2.setReadTimeout(5000);
                if (c2.getResponseCode() != 200) {
                    runSync(sender, ChatColor.RED + "Не удалось загрузить скин."); c2.disconnect(); return;
                }
                String resp2 = new String(c2.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
                c2.disconnect();
                if (!resp2.contains("\"value\"")) {
                    runSync(sender, ChatColor.RED + "У игрока '" + username + "' нет скина."); return;
                }
                String texture = resp2.split("\"value\"\\s*:\\s*\"")[1].split("\"")[0];
                String signature = resp2.contains("\"signature\"")
                    ? resp2.split("\"signature\"\\s*:\\s*\"")[1].split("\"")[0] : null;

                getServer().getScheduler().runTask(this, () -> {
                    getConfig().set("skin.texture", texture);
                    getConfig().set("skin.signature", signature);
                    saveConfig();
                    if (fakeNpc != null && fakeNpc.isCreated()) {
                        fakeNpc.setSkin(texture, signature);
                        sender.sendMessage(ChatColor.GREEN + "✔ Скин '" + username + "' установлен!");
                    } else {
                        sender.sendMessage(ChatColor.YELLOW + "Скин сохранён. Создайте NPC: /weaponbot spawn");
                    }
                });
            } catch (Exception e) {
                runSync(sender, ChatColor.RED + "Ошибка загрузки скина: " + e.getMessage());
            }
        });
    }

    private void runSync(CommandSender target, String msg) {
        getServer().getScheduler().runTask(this, () -> target.sendMessage(msg));
    }

    // ==================== WeaponItem ====================

    public static class WeaponItem {
        public final String iaId;
        public final Material fallback;
        public final String displayName;
        public final int amount;
        public final double price;
        public final int maxBuys;
        public final boolean randomUnlock;

        public WeaponItem(String iaId, Material fallback, String displayName,
                          int amount, double price, int maxBuys) {
            this(iaId, fallback, displayName, amount, price, maxBuys, false);
        }

        public WeaponItem(String iaId, Material fallback, String displayName,
                          int amount, double price, int maxBuys, boolean randomUnlock) {
            this.iaId = iaId;
            this.fallback = fallback;
            this.displayName = displayName;
            this.amount = amount;
            this.price = price;
            this.maxBuys = maxBuys;
            this.randomUnlock = randomUnlock;
        }
    }
}
