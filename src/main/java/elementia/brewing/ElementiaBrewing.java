
package elementia.brewing;

import com.gamingmesh.jobs.Jobs;
import com.gamingmesh.jobs.api.JobsBlockOwnershipRegisterEvent;
import com.gamingmesh.jobs.container.blockOwnerShip.BlockTypes;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BrewingStand;
import org.bukkit.block.TileState;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class ElementiaBrewing
        extends JavaPlugin
        implements Listener, CommandExecutor {

    private NamespacedKey ownerKey;
    private NamespacedKey ownerNameKey;
    private NamespacedKey standKey;

    private static final long SESSION_MS = 300000;
    private static final long ACTIVITY_MS = 120000;
    private static final long CONFIRM_MS = 120000;
    private static final long NOTICE_MS = 30000;

    private record Activity(UUID player, long time) {}
    private record Pending(UUID stand, String token, long time) {}

    private final Map<UUID, Activity> activity = new HashMap<>();
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Map<UUID, Map<UUID, Long>> approved = new HashMap<>();

    @Override
    public void onEnable() {
        ownerKey = new NamespacedKey(this, "owner");
        ownerNameKey = new NamespacedKey(this, "owner_name");
        standKey = new NamespacedKey(this, "stand_id");

        Bukkit.getPluginManager().registerEvents(this, this);

        Objects.requireNonNull(getCommand("eiksironay"))
                .setExecutor(this);

        getLogger().info(
                "ELEMENTIA iksir sahiplik sistemi etkin.");
    }

    private String read(Block block, NamespacedKey key) {
        if (!(block.getState() instanceof TileState tile)) {
            return null;
        }

        return tile.getPersistentDataContainer()
                .get(key, PersistentDataType.STRING);
    }

    private UUID parse(String value) {
        if (value == null) return null;

        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private UUID owner(Block block) {
        return parse(read(block, ownerKey));
    }

    private UUID standId(Block block) {
        return parse(read(block, standKey));
    }

    // ====================================================
    // 1. STANDI YERLESTIREN OYUNCU SAHIP OLUR
    // ====================================================

    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {

        if (event.getBlockPlaced().getType()
                != Material.BREWING_STAND) {
            return;
        }

        if (!event.canBuild()) return;

        Block block = event.getBlockPlaced();
        Player player = event.getPlayer();

        Bukkit.getScheduler().runTask(this, () -> {

            if (block.getType() != Material.BREWING_STAND) {
                return;
            }

            if (!(block.getState() instanceof TileState tile)) {
                return;
            }

            Jobs.getInstance().removeBlockOwnerShip(block);

            tile.getPersistentDataContainer().set(
                    ownerKey,
                    PersistentDataType.STRING,
                    player.getUniqueId().toString());

            tile.getPersistentDataContainer().set(
                    ownerNameKey,
                    PersistentDataType.STRING,
                    player.getName());

            tile.getPersistentDataContainer().set(
                    standKey,
                    PersistentDataType.STRING,
                    UUID.randomUUID().toString());

            tile.update(true, false);

            Jobs.getInstance()
                    .getBlockOwnerShip(BlockTypes.BREWING_STAND)
                    .ifPresent(ship -> {

                        var result = ship.register(player, block);

                        if (result.name().equals("tooMany")) {
                            player.sendMessage(Component.text(
                                    "ELEMENTIA: Jobs ödülü veren "
                                    + "iksir standı sınırına ulaştın. "
                                    + "Bu stand sana ait ancak "
                                    + "Jobs ödülü vermeyecek.",
                                    NamedTextColor.YELLOW));
                        }
                    });
        });
    }

    // ====================================================
    // 2. BASKASI SAHIPLIGI DEVRALAMAZ
    // ====================================================

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRegister(
            JobsBlockOwnershipRegisterEvent event) {

        Block block = event.getBlock();

        if (block.getType() != Material.BREWING_STAND) {
            return;
        }

        UUID savedOwner = owner(block);

        if (savedOwner != null
                && !savedOwner.equals(
                        event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    // ====================================================
    // 3. STANDI KULLANAN OYUNCUYU TAKIP ET
    // ====================================================

    private void track(Player player, Inventory inventory) {
        if (inventory.getType() != InventoryType.BREWING) {
            return;
        }

        if (!(inventory.getHolder() instanceof BrewingStand stand)) {
            return;
        }

        UUID id = standId(stand.getBlock());

        if (id == null) return;

        long now = System.currentTimeMillis();

        activity.put(id,
                new Activity(player.getUniqueId(), now));

        Map<UUID, Long> playerApprovals =
                approved.get(player.getUniqueId());

        if (playerApprovals != null) {
            Long last = playerApprovals.get(id);

            if (last != null) {
                if (now - last <= SESSION_MS) {
                    playerApprovals.put(id, now);
                } else {
                    playerApprovals.remove(id);
                }
            }
        }
    }

    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        if (event.getClickedInventory() == null) return;

        track(player, event.getView().getTopInventory());
    }

    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        Inventory top = event.getView().getTopInventory();

        boolean affectsStand = event.getRawSlots().stream()
                .anyMatch(slot -> slot < top.getSize());

        if (affectsStand) {
            track(player, top);
        }
    }

    // ====================================================
    // 4. IKSIR BITINCE UYARI VE SES
    // ====================================================

    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true)
    public void onBrew(BrewEvent event) {

        Block block = event.getBlock();

        UUID id = standId(block);
        UUID savedOwner = owner(block);

        if (id == null || savedOwner == null) return;

        Activity last = activity.get(id);

        if (last == null) return;

        long now = System.currentTimeMillis();

        if (now - last.time() > ACTIVITY_MS) return;

        if (last.player().equals(savedOwner)) return;

        Player user = Bukkit.getPlayer(last.player());

        if (user == null || !user.isOnline()) return;

        Map<UUID, Long> userApprovals =
                approved.get(user.getUniqueId());

        if (userApprovals != null) {
            Long time = userApprovals.get(id);

            if (time != null && now - time <= SESSION_MS) {
                return;
            }
        }

        Pending previous = pending.get(user.getUniqueId());

        if (previous != null
                && previous.stand().equals(id)
                && now - previous.time() < NOTICE_MS) {
            return;
        }

        String name = read(block, ownerNameKey);

        if (name == null) {
            name = savedOwner.toString();
        }

        String token = UUID.randomUUID().toString();

        pending.put(
                user.getUniqueId(),
                new Pending(id, token, now));

        user.playSound(
                user.getLocation(),
                Sound.BLOCK_NOTE_BLOCK_BELL,
                1.0f,
                1.0f);

        user.sendMessage(Component.text(
                "━━━━━━━━ ELEMENTIA ━━━━━━━━",
                NamedTextColor.GOLD));

        user.sendMessage(Component.text(
                "DİKKAT! Bu iksir standı sana ait değil!",
                NamedTextColor.RED));

        user.sendMessage(Component.text(
                "Standın sahibi: " + name,
                NamedTextColor.YELLOW));

        user.sendMessage(Component.text(
                "Jobs şartları sağlanıyorsa meslek XP'si "
                + "ve puanlar standın sahibine verilir. "
                + "Sana verilmez.",
                NamedTextColor.GRAY));

        user.sendMessage(Component.text(
                "[☐ Okudum ve onaylıyorum]",
                NamedTextColor.GREEN
        ).clickEvent(ClickEvent.runCommand(
                "/eiksironay " + token)));
    }

    // ====================================================
    // 5. TIKLANABILIR ONAY SISTEMI
    // ====================================================

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args) {

        if (!(sender instanceof Player player)) {
            return true;
        }

        Pending request = pending.get(player.getUniqueId());

        if (request == null
                || args.length != 1
                || !request.token().equals(args[0])
                || System.currentTimeMillis() - request.time()
                        > CONFIRM_MS) {

            player.sendMessage(Component.text(
                    "ELEMENTIA: Geçersiz veya süresi "
                    + "dolmuş onay.",
                    NamedTextColor.RED));

            return true;
        }

        approved.computeIfAbsent(
                player.getUniqueId(),
                ignored -> new HashMap<>()
        ).put(
                request.stand(),
                System.currentTimeMillis());

        pending.remove(player.getUniqueId());

        player.sendMessage(Component.text(
                "[☑] Onaylandı! Bu standı kullanmaya "
                + "devam ettiğin sürece uyarı "
                + "tekrarlanmayacak.",
                NamedTextColor.GREEN));

        return true;
    }

    // ====================================================
    // 6. STAND KIRILINCA SAHIPLIK TEMIZLENIR
    // ====================================================

    private void clear(Block block) {
        UUID id = standId(block);

        if (id == null) return;

        activity.remove(id);

        pending.values().removeIf(
                value -> value.stand().equals(id));

        for (Map<UUID, Long> values : approved.values()) {
            values.remove(id);
        }
    }

    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {

        Block block = event.getBlock();

        if (block.getType() != Material.BREWING_STAND) {
            return;
        }

        clear(block);
        Jobs.getInstance().removeBlockOwnerShip(block);
    }

    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {

        for (Block block : event.blockList()) {
            if (block.getType() == Material.BREWING_STAND) {
                clear(block);
                Jobs.getInstance().removeBlockOwnerShip(block);
            }
        }
    }

    @EventHandler(
            priority = EventPriority.MONITOR,
            ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {

        for (Block block : event.blockList()) {
            if (block.getType() == Material.BREWING_STAND) {
                clear(block);
                Jobs.getInstance().removeBlockOwnerShip(block);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();

        pending.remove(playerId);
        approved.remove(playerId);
    }
}
