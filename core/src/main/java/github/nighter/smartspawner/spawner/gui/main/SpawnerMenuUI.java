package github.nighter.smartspawner.spawner.gui.main;

import github.nighter.smartspawner.SmartSpawner;
import github.nighter.smartspawner.spawner.loot.EntityLootConfig;
import github.nighter.smartspawner.spawner.loot.LootItem;
import github.nighter.smartspawner.spawner.utils.SpawnerMobHeadTexture;
import github.nighter.smartspawner.spawner.properties.SpawnerData;
import github.nighter.smartspawner.spawner.properties.VirtualInventory;
import github.nighter.smartspawner.language.LanguageManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

public class SpawnerMenuUI {
    private static final int INVENTORY_SIZE = 27;
    private static final int CHEST_SLOT = 11;
    private static final int SPAWNER_INFO_SLOT = 13;
    private static final int TICKS_PER_SECOND = 20;
    private static final Map<String, String> EMPTY_PLACEHOLDERS = Collections.emptyMap();

    // Cache frequently used formatting strings and pattern lookups
    private static final String LOOT_ITEM_FORMAT_KEY = "spawner_storage_item.loot_items";
    private static final String EMPTY_LOOT_MESSAGE_KEY = "spawner_storage_item.loot_items_empty";

    private final SmartSpawner plugin;
    private final LanguageManager languageManager;

    // Format strings - initialized in constructor to avoid repeated lookups
    private final String lootItemFormat;
    private final String emptyLootMessage;

    public SpawnerMenuUI(SmartSpawner plugin) {
        this.plugin = plugin;
        this.languageManager = plugin.getLanguageManager();

        // Preload frequently used format strings
        this.lootItemFormat = languageManager.getGuiItemName(LOOT_ITEM_FORMAT_KEY, EMPTY_PLACEHOLDERS);
        this.emptyLootMessage = languageManager.getGuiItemName(EMPTY_LOOT_MESSAGE_KEY, EMPTY_PLACEHOLDERS);
    }

    public void openSpawnerMenu(Player player, SpawnerData spawner, boolean refresh) {
        Inventory menu = createMenu(spawner);

        // Populate menu items - create all items before opening to avoid multiple inventory updates
        ItemStack[] items = new ItemStack[INVENTORY_SIZE];
        items[CHEST_SLOT] = createLootStorageItem(spawner);
        items[SPAWNER_INFO_SLOT] = createSpawnerInfoItem(player, spawner);

        // Set all items at once instead of one by one
        for (int i = 0; i < items.length; i++) {
            if (items[i] != null) {
                menu.setItem(i, items[i]);
            }
        }

        // Open inventory and play sound if not refreshing
        player.openInventory(menu);

        if (!refresh) {
            player.playSound(player.getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 1.0f, 1.0f);
        }
    }

    private Inventory createMenu(SpawnerData spawner) {
        // Get entity name with caching
        String entityName = languageManager.getFormattedMobName(spawner.getEntityType());
        String entityNameSmallCaps = languageManager.getSmallCaps(languageManager.getFormattedMobName(spawner.getEntityType()));

        // Use string builder for efficient placeholder creation
        Map<String, String> placeholders = new HashMap<>(4);
        placeholders.put("entity", entityName);
        placeholders.put("ᴇɴᴛɪᴛʏ", entityNameSmallCaps);
        placeholders.put("amount", String.valueOf(spawner.getStackSize()));

        String title;
        if (spawner.getStackSize() > 1) {
            title = languageManager.getGuiTitle("gui_title_main.stacked_spawner", placeholders);
        } else {
            title = languageManager.getGuiTitle("gui_title_main.single_spawner", placeholders);
        }

        return Bukkit.createInventory(new SpawnerMenuHolder(spawner), INVENTORY_SIZE, title);
    }

    public ItemStack createLootStorageItem(SpawnerData spawner) {
        // Get important data upfront
        VirtualInventory virtualInventory = spawner.getVirtualInventory();
        int currentItems = virtualInventory.getUsedSlots();
        int maxSlots = spawner.getMaxSpawnerLootSlots();
        int percentStorage = calculatePercentage(currentItems, maxSlots);

        // Create cache key for this specific spawner's storage state
        final String cacheKey = spawner.getSpawnerId() + "|storage|" + currentItems + "|" + spawner.getEntityType();

        // Check if we have a cached item for this exact spawner state
        ItemStack cachedItem = plugin.getItemCache().getIfPresent(cacheKey);
        if (cachedItem != null) {
            return cachedItem.clone();
        }

        // Not in cache, create new item
        ItemStack chestItem = new ItemStack(Material.CHEST);
        ItemMeta chestMeta = chestItem.getItemMeta();
        if (chestMeta == null) return chestItem;

        // Build base placeholders
        Map<String, String> placeholders = new HashMap<>(4);
        placeholders.put("max_slots", languageManager.formatNumber(maxSlots));
        placeholders.put("current_items", String.valueOf(currentItems));
        placeholders.put("percent_storage", String.valueOf(percentStorage));

        // Get consolidated items and prepare the loot items section
        Map<VirtualInventory.ItemSignature, Long> storedItems = virtualInventory.getConsolidatedItems();

        // Build the loot items section efficiently
        String lootItemsText = buildLootItemsText(spawner.getEntityType(), storedItems);
        placeholders.put("loot_items", lootItemsText);

        // Set display name
        chestMeta.setDisplayName(languageManager.getGuiItemName("spawner_storage_item.name", placeholders));

        // Get lore efficiently
        List<String> lore = languageManager.getGuiItemLoreWithMultilinePlaceholders("spawner_storage_item.lore", placeholders);
        chestMeta.setLore(lore);

        chestItem.setItemMeta(chestMeta);

        // Cache the result for future use
        plugin.getItemCache().put(cacheKey, chestItem.clone());

        return chestItem;
    }

    private String buildLootItemsText(EntityType entityType, Map<VirtualInventory.ItemSignature, Long> storedItems) {
        // Create material-to-amount map for quick lookups
        Map<Material, Long> materialAmountMap = new HashMap<>();
        for (Map.Entry<VirtualInventory.ItemSignature, Long> entry : storedItems.entrySet()) {
            Material material = entry.getKey().getTemplateRef().getType();
            materialAmountMap.merge(material, entry.getValue(), Long::sum);
        }

        // Get possible loot items
        EntityLootConfig lootConfig = plugin.getEntityLootRegistry().getLootConfig(entityType);
        List<LootItem> possibleLootItems = lootConfig != null
                ? lootConfig.getAllItems()
                : Collections.emptyList();

        // Return early for empty cases
        if (possibleLootItems.isEmpty() && storedItems.isEmpty()) {
            return emptyLootMessage;
        }

        // Use StringBuilder for efficient string concatenation
        StringBuilder builder = new StringBuilder(Math.max(possibleLootItems.size(), storedItems.size()) * 40);

        if (!possibleLootItems.isEmpty()) {
            // Sort items by name for consistent display
            possibleLootItems.sort(Comparator.comparing(item -> languageManager.getVanillaItemName(item.getMaterial())));

            for (LootItem lootItem : possibleLootItems) {
                Material material = lootItem.getMaterial();
                long amount = materialAmountMap.getOrDefault(material, 0L);

                String materialName = languageManager.getVanillaItemName(material);
                String materialNameSmallCaps = languageManager.getSmallCaps(languageManager.getVanillaItemName(material));
                String formattedAmount = languageManager.formatNumber(amount);
                String chance = String.format("%.1f", lootItem.getChance()) + "%";

                // Format the line with minimal string operations
                String line = lootItemFormat
                        .replace("%item_name%", materialName)
                        .replace("%ɪᴛᴇᴍ_ɴᴀᴍᴇ%", materialNameSmallCaps)
                        .replace("%amount%", formattedAmount)
                        .replace("%raw_amount%", String.valueOf(amount))
                        .replace("%chance%", chance);

                builder.append(line).append('\n');
            }
        } else if (!storedItems.isEmpty()) {
            // Sort items by name
            List<Map.Entry<VirtualInventory.ItemSignature, Long>> sortedItems =
                    new ArrayList<>(storedItems.entrySet());
            sortedItems.sort(Comparator.comparing(e -> e.getKey().getMaterialName()));

            for (Map.Entry<VirtualInventory.ItemSignature, Long> entry : sortedItems) {
                ItemStack templateItem = entry.getKey().getTemplateRef();
                Material material = templateItem.getType();
                long amount = entry.getValue();

                String materialName = languageManager.getVanillaItemName(material);
                String materialNameSmallCaps = languageManager.getSmallCaps(languageManager.getVanillaItemName(material));
                String formattedAmount = languageManager.formatNumber(amount);

                // Format with minimal replacements
                String line = lootItemFormat
                        .replace("%item_name%", materialName)
                        .replace("%ɪᴛᴇᴍ_ɴᴀᴍᴇ%", materialNameSmallCaps)
                        .replace("%amount%", formattedAmount)
                        .replace("%raw_amount%", String.valueOf(amount))
                        .replace("%chance%", "");

                builder.append(line).append('\n');
            }
        }

        // Remove trailing newline if it exists
        int length = builder.length();
        if (length > 0 && builder.charAt(length - 1) == '\n') {
            builder.setLength(length - 1);
        }

        return builder.toString();
    }

    public ItemStack createSpawnerInfoItem(Player player, SpawnerData spawner) {
        // Get important data upfront
        EntityType entityType = spawner.getEntityType();
        int stackSize = spawner.getStackSize();
        VirtualInventory virtualInventory = spawner.getVirtualInventory();
        int currentItems = virtualInventory.getUsedSlots();
        int maxSlots = spawner.getMaxSpawnerLootSlots();

        // Calculate percentages with decimal precision - do this once
        double percentStorageDecimal = maxSlots > 0 ? ((double) currentItems / maxSlots) * 100 : 0;
        String formattedPercentStorage = String.format("%.1f", percentStorageDecimal);

        long currentExp = spawner.getSpawnerExp();
        long maxExp = spawner.getMaxStoredExp();
        double percentExpDecimal = maxExp > 0 ? ((double) currentExp / maxExp) * 100 : 0;
        String formattedPercentExp = String.format("%.1f", percentExpDecimal);

        // Create cache key including all relevant state
        boolean hasShopPermission = plugin.hasSellIntegration() && player.hasPermission("smartspawner.sellall");
        String cacheKey = spawner.getSpawnerId() + "|info|" + stackSize + "|" + entityType + "|"
                + formattedPercentStorage + "|" + formattedPercentExp + "|" + spawner.getSpawnerRange() + "|"
                + spawner.getSpawnDelay() + "|" + spawner.getMinMobs() + "|" + spawner.getMaxMobs()
                + "|" + hasShopPermission;

        // Check if we have a cached item
        ItemStack cachedItem = plugin.getItemCache().getIfPresent(cacheKey);
        if (cachedItem != null) {
            return cachedItem.clone();
        }

        // Not in cache, create the ItemStack
        ItemStack spawnerItem = SpawnerMobHeadTexture.getCustomHead(entityType, player);
        ItemMeta spawnerMeta = spawnerItem.getItemMeta();
        if (spawnerMeta == null) return spawnerItem;

        // Get entity names with proper formatting - using cache
        String entityName = languageManager.getFormattedMobName(entityType);
        String entityNameSmallCaps = languageManager.getSmallCaps(languageManager.getFormattedMobName(entityType));

        // Prepare all placeholders - reuse the map rather than creating a new one each time
        Map<String, String> placeholders = new HashMap<>(16); // Preallocate with expected capacity

        // Entity information
        placeholders.put("entity", entityName);
        placeholders.put("ᴇɴᴛɪᴛʏ", entityNameSmallCaps);
        placeholders.put("entity_type", entityType.toString());

        // Stack information
        placeholders.put("stack_size", String.valueOf(stackSize));

        // Spawner settings
        placeholders.put("range", String.valueOf(spawner.getSpawnerRange()));
        long delaySeconds = spawner.getSpawnDelay() / TICKS_PER_SECOND;
        placeholders.put("delay", String.valueOf(delaySeconds));
        placeholders.put("delay_raw", String.valueOf(spawner.getSpawnDelay()));
        placeholders.put("min_mobs", String.valueOf(spawner.getMinMobs()));
        placeholders.put("max_mobs", String.valueOf(spawner.getMaxMobs()));

        // Storage information
        placeholders.put("current_items", String.valueOf(currentItems));
        placeholders.put("max_items", languageManager.formatNumber(maxSlots));
        placeholders.put("formatted_storage", formattedPercentStorage);

        // Experience information
        String formattedCurrentExp = languageManager.formatNumber(currentExp);
        String formattedMaxExp = languageManager.formatNumber(maxExp);

        placeholders.put("current_exp", formattedCurrentExp);
        placeholders.put("max_exp", formattedMaxExp);
        placeholders.put("raw_current_exp", String.valueOf(currentExp));
        placeholders.put("raw_max_exp", String.valueOf(maxExp));
        placeholders.put("formatted_exp", formattedPercentExp);

        // Set display name with the specified placeholders
        spawnerMeta.setDisplayName(languageManager.getGuiItemName("spawner_info_item.name", placeholders));

        // Select appropriate lore based on shop integration availability
        String loreKey = hasShopPermission
                ? "spawner_info_item.lore"
                : "spawner_info_item.lore_no_shop";

        // Get and set lore with placeholders
        List<String> lore = languageManager.getGuiItemLoreWithMultilinePlaceholders(loreKey, placeholders);
        spawnerMeta.setLore(lore);

        spawnerItem.setItemMeta(spawnerMeta);

        // Cache the result for future use
        plugin.getItemCache().put(cacheKey, spawnerItem.clone());

        return spawnerItem;
    }

    private int calculatePercentage(long current, long maximum) {
        return maximum > 0 ? (int) ((double) current / maximum * 100) : 0;
    }
}