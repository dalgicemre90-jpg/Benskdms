package com.staily.skydungeonpersistfix;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;

public final class SkyDungeonMobPersistFix extends JavaPlugin implements Listener {

    private final Map<String, SavedMob> saved = new LinkedHashMap<>();

    private File file;
    private YamlConfiguration data;

    private NamespacedKey dungeonMobKey;
    private NamespacedKey bossKey;
    private NamespacedKey tierKey;
    private NamespacedKey mobTypeKey;
    private NamespacedKey spawnXKey;
    private NamespacedKey spawnYKey;
    private NamespacedKey spawnZKey;
    private NamespacedKey spawnWorldKey;

    // SkyDungeon ile aynı anahtarları kullanıyoruz.
    private static final NamespacedKey FIX_DUNGEON_MOB =
            new NamespacedKey("skydungeon", "dungeon_mob");
    private static final NamespacedKey FIX_BOSS =
            new NamespacedKey("skydungeon", "dungeon_boss");
    private static final NamespacedKey FIX_TIER =
            new NamespacedKey("skydungeon", "dungeon_tier");
    private static final NamespacedKey FIX_MOB_TYPE =
            new NamespacedKey("skydungeon", "dungeon_mobtype");

    @Override
    public void onEnable() {
        file = new File(getDataFolder(), "saved.yml");
        if (!getDataFolder().exists()) getDataFolder().mkdirs();

        dungeonMobKey = FIX_DUNGEON_MOB;
        bossKey = FIX_BOSS;
        tierKey = FIX_TIER;
        mobTypeKey = FIX_MOB_TYPE;

        spawnXKey = new NamespacedKey("skydungeon", "spawn_x");
        spawnYKey = new NamespacedKey("skydungeon", "spawn_y");
        spawnZKey = new NamespacedKey("skydungeon", "spawn_z");
        spawnWorldKey = new NamespacedKey("skydungeon", "spawn_world");

        loadData();
        Bukkit.getPluginManager().registerEvents(this, this);

        // SkyDungeon tamamen açıldıktan ve dünyalar hazırlandıktan sonra restore.
        Bukkit.getScheduler().runTaskLater(this, this::restoreAll, 40L);

        getLogger().info("SkyDungeonMobPersistFix acildi. Kayitli mob noktasi: " + saved.size());
    }

    @Override
    public void onDisable() {
        saveData();
    }

    /**
     * SkyDungeon World#spawnEntity() çağırdığı anda PDC işareti aynı event tick'inde
     * sonradan yazıldığı için, burada doğrudan kaydetmiyoruz. 1 tick bekleyip
     * PDC'yi kontrol ediyoruz.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        Entity entity = event.getEntity();

        Bukkit.getScheduler().runTask(this, () -> {
            if (!entity.isValid() || entity.isDead()) return;

            LivingEntity living = (LivingEntity) entity;
            PersistentDataContainer pdc = living.getPersistentDataContainer();

            if (pdc.has(dungeonMobKey, PersistentDataType.BYTE)) {
                Integer tier = pdc.get(tierKey, PersistentDataType.INTEGER);
                String type = pdc.get(mobTypeKey, PersistentDataType.STRING);

                if (tier != null && type != null) {
                    remember(living.getLocation(), type.toLowerCase(Locale.ROOT), tier);
                }
            } else if (pdc.has(bossKey, PersistentDataType.BYTE)) {
                remember(living.getLocation(), "boss", 0);
            }
        });
    }

    /**
     * Bazı serverlarda CreatureSpawnEvent yerine entity zaten mevcut olabilir.
     * Chunk yüklenince de 1 tick sonra tarıyoruz; böylece eski kayıtlar bozulmaz.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        PersistentDataContainer pdc = entity.getPersistentDataContainer();

        if (pdc.has(dungeonMobKey, PersistentDataType.BYTE)) {
            Integer tier = pdc.get(tierKey, PersistentDataType.INTEGER);
            String type = pdc.get(mobTypeKey, PersistentDataType.STRING);
            if (tier != null && type != null) {
                remember(getSavedSpawn(entity, pdc), type.toLowerCase(Locale.ROOT), tier);
            }
        } else if (pdc.has(bossKey, PersistentDataType.BYTE)) {
            remember(getSavedSpawn(entity, pdc), "boss", 0);
        }
    }

    private Location getSavedSpawn(LivingEntity entity, PersistentDataContainer pdc) {
        try {
            Double x = pdc.get(spawnXKey, PersistentDataType.DOUBLE);
            Double y = pdc.get(spawnYKey, PersistentDataType.DOUBLE);
            Double z = pdc.get(spawnZKey, PersistentDataType.DOUBLE);
            String worldName = pdc.get(spawnWorldKey, PersistentDataType.STRING);

            if (x != null && y != null && z != null && worldName != null) {
                World world = Bukkit.getWorld(worldName);
                if (world != null) return new Location(world, x, y, z);
            }
        } catch (Exception ignored) {}
        return entity.getLocation().clone();
    }

    private void remember(Location location, String type, int tier) {
        if (location == null || location.getWorld() == null) return;

        String key = key(location);
        SavedMob old = saved.get(key);

        // Aynı noktayı tekrar tekrar yazmak yerine mevcut kaydı güncelliyoruz.
        if (old == null ||
                !old.type.equals(type) ||
                old.tier != tier ||
                old.x != location.getX() ||
                old.y != location.getY() ||
                old.z != location.getZ()) {

            saved.put(key, new SavedMob(
                    location.getWorld().getName(),
                    location.getX(),
                    location.getY(),
                    location.getZ(),
                    type,
                    tier
            ));

            saveData();
            getLogger().info("Mob noktasi kaydedildi: " + key + " -> " + type + " T" + tier);
        }
    }

    private void restoreAll() {
        Plugin skyDungeon = Bukkit.getPluginManager().getPlugin("SkyDungeon");
        if (skyDungeon == null || !skyDungeon.isEnabled()) {
            getLogger().severe("SkyDungeon aktif degil; mob restore yapilamadi.");
            return;
        }

        int restored = 0;

        for (SavedMob record : new ArrayList<>(saved.values())) {
            World world = Bukkit.getWorld(record.world);
            if (world == null) {
                getLogger().warning("Dunya bulunamadi, kayit atlandi: " + record.world);
                continue;
            }

            Location location = new Location(world, record.x, record.y, record.z);

            if (hasSkyDungeonEntityNear(location)) continue;

            try {
                if ("boss".equalsIgnoreCase(record.type)) {
                    LivingEntity boss = invokeBoss(skyDungeon, location);
                    if (boss != null) restored++;
                } else {
                    LivingEntity mob = invokeMob(skyDungeon, location, record.type, record.tier);
                    if (mob != null) restored++;
                }
            } catch (Exception e) {
                getLogger().severe("Restore hatasi (" + record.type + " T" + record.tier + "): "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }

        getLogger().info("SkyDungeon mob restore tamamlandi. Geri yuklenen: " + restored
                + " / kayit: " + saved.size());
    }

    private LivingEntity invokeBoss(Plugin plugin, Location location) throws Exception {
        return (LivingEntity) plugin.getClass()
                .getMethod("getMobManager")
                .invoke(plugin)
                .getClass()
                .getMethod("spawnBoss", Location.class)
                .invoke(plugin.getClass().getMethod("getMobManager").invoke(plugin), location);
    }

    private LivingEntity invokeMob(Plugin plugin, Location location, String typeName, int tierNumber)
            throws Exception {

        EntityType entityType = parseEntityType(typeName);
        if (entityType == null) return null;

        Object tier = plugin.getClass()
                .getMethod("getTiers")
                .invoke(plugin);

        @SuppressWarnings("unchecked")
        Map<Integer, Object> tiers = (Map<Integer, Object>) tier;

        Object dungeonTier = tiers.get(tierNumber);
        if (dungeonTier == null) {
            getLogger().warning("Zindan seviyesi bulunamadi: " + tierNumber);
            return null;
        }

        Object manager = plugin.getClass().getMethod("getMobManager").invoke(plugin);

        return (LivingEntity) manager.getClass()
                .getMethod("spawnDungeonMob", Location.class, EntityType.class,
                        Class.forName("com.staily.skydungeon.util.DungeonTier"))
                .invoke(manager, location, entityType, dungeonTier);
    }

    private EntityType parseEntityType(String value) {
        if (value == null) return null;

        String s = value.toLowerCase(Locale.ROOT).trim();
        return switch (s) {
            case "zombi", "zombie" -> EntityType.ZOMBIE;
            case "iskelet", "skeleton" -> EntityType.SKELETON;
            case "blaze" -> EntityType.BLAZE;
            case "orumcek", "örümcek", "spider" -> EntityType.SPIDER;
            default -> {
                try {
                    yield EntityType.valueOf(s.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    yield null;
                }
            }
        };
    }

    private boolean hasSkyDungeonEntityNear(Location location) {
        if (location.getWorld() == null) return false;

        for (Entity entity : location.getWorld().getNearbyEntities(location, 2.5, 3.0, 2.5)) {
            if (!(entity instanceof LivingEntity living)) continue;

            PersistentDataContainer pdc = living.getPersistentDataContainer();

            if (pdc.has(dungeonMobKey, PersistentDataType.BYTE)
                    || pdc.has(bossKey, PersistentDataType.BYTE)) {
                return true;
            }
        }
        return false;
    }

    private String key(Location location) {
        return location.getWorld().getName()
                + "|" + Double.toString(location.getX())
                + "|" + Double.toString(location.getY())
                + "|" + Double.toString(location.getZ());
    }

    private void loadData() {
        data = YamlConfiguration.loadConfiguration(file);
        saved.clear();

        // Eski SkyDungeonPersist pluginunun kayitlarini da otomatik devral.
        File oldFile = new File("plugins/SkyDungeonPersist/saved.yml");
        YamlConfiguration source = oldFile.isFile()
                ? YamlConfiguration.loadConfiguration(oldFile)
                : data;

        for (String line : source.getStringList("mobs")) {
            try {
                String[] p = line.split("\\|", -1);

                if (p.length != 6) continue;

                String world = p[0];
                double x = Double.parseDouble(p[1]);
                double y = Double.parseDouble(p[2]);
                double z = Double.parseDouble(p[3]);
                String type = p[4];
                int tier = Integer.parseInt(p[5]);

                World bukkitWorld = Bukkit.getWorld(world);
                Location loc = bukkitWorld == null
                        ? null
                        : new Location(bukkitWorld, x, y, z);

                if (loc != null) {
                    saved.put(key(loc), new SavedMob(world, x, y, z, type, tier));
                }
            } catch (Exception ignored) {
                getLogger().warning("Bozuk mob kaydi atlandi: " + line);
            }
        }
    }

    private void saveData() {
        if (data == null) data = new YamlConfiguration();

        List<String> list = new ArrayList<>();

        for (SavedMob mob : saved.values()) {
            list.add(mob.world + "|"
                    + mob.x + "|"
                    + mob.y + "|"
                    + mob.z + "|"
                    + mob.type + "|"
                    + mob.tier);
        }

        data.set("mobs", list);

        try {
            data.save(file);
        } catch (IOException e) {
            getLogger().severe("saved.yml yazilamadi: " + e.getMessage());
        }
    }

    private static final class SavedMob {
        final String world;
        final double x;
        final double y;
        final double z;
        final String type;
        final int tier;

        SavedMob(String world, double x, double y, double z, String type, int tier) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.type = type;
            this.tier = tier;
        }
    }
}
