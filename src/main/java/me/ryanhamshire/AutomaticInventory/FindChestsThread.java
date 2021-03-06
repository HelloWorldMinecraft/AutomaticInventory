package me.ryanhamshire.AutomaticInventory;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.util.Vector;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

//TODO Refractor this into a loop or two
class FindChestsThread extends Thread {
    private World world;
    private ChunkSnapshot[][] snapshots;
    private int minY;
    private int maxY;
    private int startX;
    private int startY;
    private int startZ;
    private Player player;
    private ChunkSnapshot smallestChunk;

    private boolean[][][] seen;

    FindChestsThread(World world, ChunkSnapshot[][] snapshots, int minY, int maxY, int startX, int startY, int startZ, Player player) {
        this.world = world;
        this.snapshots = snapshots;
        this.minY = minY;
        this.maxY = maxY;
        this.smallestChunk = this.snapshots[0][0];
        this.startX = startX - this.smallestChunk.getX() * 16;
        this.startY = startY;
        this.startZ = startZ - this.smallestChunk.getZ() * 16;
        if (this.maxY >= world.getMaxHeight()) this.maxY = world.getMaxHeight() - 1;
        this.player = player;
        this.seen = new boolean[48][this.maxY - this.minY + 1][48];
    }

    @Override
    public void run() {
        Queue<Location> chestLocations = new ConcurrentLinkedQueue<>();
        Queue<Vector> leftToVisit = new ConcurrentLinkedQueue<>();
        Vector start = new Vector(this.startX, this.startY, this.startZ);
        leftToVisit.add(start);
        this.markSeen(start);
        while (!leftToVisit.isEmpty()) {
            Vector current = leftToVisit.remove();

            Material type = this.getType(current);
            if (type == null) continue;
            if (type == Material.CHEST || type == Material.TRAPPED_CHEST || type == Material.ENDER_CHEST || type.name().contains("SHULKER")) {
                Material overType = this.getType(new Vector(current.getBlockX(), current.getBlockY() + 1, current.getBlockZ()));
                if (!AutomaticInventory.preventsChestOpen(overType)) {
                    chestLocations.add(this.makeLocation(current));
                }
            }

            if (this.isPassableID(type)) {
                Vector[] adjacents = new Vector[]{
                        new Vector(current.getBlockX() + 1, current.getBlockY(), current.getBlockZ()),
                        new Vector(current.getBlockX() - 1, current.getBlockY(), current.getBlockZ()),
                        new Vector(current.getBlockX(), current.getBlockY() + 1, current.getBlockZ()),
                        new Vector(current.getBlockX(), current.getBlockY() - 1, current.getBlockZ()),
                        new Vector(current.getBlockX(), current.getBlockY(), current.getBlockZ() + 1),
                        new Vector(current.getBlockX(), current.getBlockY(), current.getBlockZ() - 1),
                };

                for (Vector adjacent : adjacents) {
                    if (!this.alreadySeen(adjacent)) {
                        leftToVisit.add(adjacent);
                        this.markSeen(adjacent);
                    }
                }
            }
        }

        QuickDepositChain chain = new QuickDepositChain(chestLocations, new DepositRecord(), player, true);
        Bukkit.getScheduler().runTaskLater(AutomaticInventory.instance, chain, 1L);
    }

    private Location makeLocation(Vector location) {
        return new Location(
                this.world,
                this.smallestChunk.getX() * 16 + location.getBlockX(),
                location.getBlockY(),
                this.smallestChunk.getZ() * 16 + location.getBlockZ());
    }

    private Material getType(Vector location) {
        if (this.outOfBounds(location)) return null;
        int chunkx = location.getBlockX() / 16;
        int chunkz = location.getBlockZ() / 16;
        ChunkSnapshot chunk = this.snapshots[chunkx][chunkz];
        int x = location.getBlockX() % 16;
        int z = location.getBlockZ() % 16;
        return chunk.getBlockType(x, location.getBlockY(), z);
    }

    private boolean alreadySeen(Vector location) {
        if (this.outOfBounds(location)) return true;
        int y = location.getBlockY() - this.minY;
        return this.seen[location.getBlockX()][y][location.getBlockZ()];
    }

    private void markSeen(Vector location) {
        if (this.outOfBounds(location)) return;
        int y = location.getBlockY() - this.minY;
        this.seen[location.getBlockX()][y][location.getBlockZ()] = true;
    }

    private boolean outOfBounds(Vector location) {
        if (location.getBlockY() > this.maxY) return true;
        if (location.getBlockY() < this.minY) return true;
        if (location.getBlockX() >= 48) return true;
        if (location.getBlockX() < 0) return true;
        if (location.getBlockZ() >= 48) return true;
        return location.getBlockZ() < 0;
    }

    private boolean isPassableID(Material material) {
        switch (material) {
            case AIR:
            case CHEST:
            case TRAPPED_CHEST:
            case HOPPER:
                return true;
            default:
                return material.name().contains("SIGN");
        }
    }

    class QuickDepositChain implements Runnable {
        private Queue<Location> remainingChestLocations;
        private DepositRecord runningDepositRecord;
        private Player player;
        private boolean respectExclusions;

        QuickDepositChain(Queue<Location> remainingChestLocations, DepositRecord runningDepositRecord, Player player, boolean respectExclusions) {
            super();
            this.remainingChestLocations = remainingChestLocations;
            this.runningDepositRecord = runningDepositRecord;
            this.player = player;
            this.respectExclusions = respectExclusions;
        }

        @Override
        public void run() {
            Location chestLocation = this.remainingChestLocations.poll();
            if (chestLocation == null) {
                AutomaticInventory.sendMessage(this.player, Messages.SuccessfulDepositAll2, String.valueOf(this.runningDepositRecord.totalItems));
            } else {
                Block block = chestLocation.getBlock();
                PlayerInteractEvent fakeEvent = AutomaticInventory.instance.new FakePlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, player.getInventory().getItemInMainHand(), block, BlockFace.UP);
                Bukkit.getServer().getPluginManager().callEvent(fakeEvent);
                if (!fakeEvent.isCancelled()) {
                    BlockState state = block.getState();
                    if (state instanceof InventoryHolder) {
                        InventoryHolder chest = (InventoryHolder) state;
                        Inventory chestInventory = chest.getInventory();
                        if (!this.respectExclusions || AIEventHandler.isSortableChestInventory(chestInventory)) {
                            PlayerInventory playerInventory = player.getInventory();

                            DepositRecord deposits = AutomaticInventory.depositMatching(playerInventory, chestInventory, false);

                            this.runningDepositRecord.totalItems += deposits.totalItems;
                        }
                    }
                }

                QuickDepositChain chain = new QuickDepositChain(this.remainingChestLocations, this.runningDepositRecord, this.player, this.respectExclusions);
                Bukkit.getScheduler().runTaskLater(AutomaticInventory.instance, chain, 1L);
            }
        }
    }
}
