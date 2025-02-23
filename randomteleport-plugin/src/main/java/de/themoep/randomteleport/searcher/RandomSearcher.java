package de.themoep.randomteleport.searcher;

/*
 * RandomTeleport - randomteleport-plugin - $project.description
 * Copyright (c) 2019 Max Lee aka Phoenix616 (mail@moep.tv)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import de.themoep.randomteleport.RandomTeleport;
import de.themoep.randomteleport.ValidatorRegistry;
import de.themoep.randomteleport.searcher.options.NotFoundException;
import de.themoep.randomteleport.searcher.validators.LocationValidator;
import io.papermc.lib.PaperLib;
import org.apache.commons.lang.Validate;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class RandomSearcher {
    private final RandomTeleport plugin;
    private final CommandSender initiator;
    private final UUID uniqueId = UUID.randomUUID();

    private final ValidatorRegistry validators = new ValidatorRegistry();

    private static final List<int[]> RANDOM_LIST = new ArrayList<>();

    static {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                RANDOM_LIST.add(new int[]{x, z});
            }
        }
    }

    private Random random = RandomTeleport.RANDOM;

    private Set<Entity> targets = Collections.newSetFromMap(new LinkedHashMap<>());

    private String id = null;
    private long seed = -1;
    private Location center;
    private int minRadius = 0;
    private int maxRadius = Integer.MAX_VALUE;
    private int checkDelay = 1;
    private int minY;
    private int maxY;
    private boolean loadedOnly = false;
    private boolean generatedOnly = false;
    private int maxTries = 100;
    private int cooldown;
    private Map<String, String> options = new LinkedHashMap<>();

    private long lastCheck;
    private int checks = 0;
    private final Multimap<Integer, Integer> checked = MultimapBuilder.hashKeys().hashSetValues().build();

    private CompletableFuture<Location> future = null;

    public RandomSearcher(RandomTeleport plugin, CommandSender initiator, Location center, int minRadius, int maxRadius, LocationValidator... validators) {
        this.plugin = plugin;
        this.initiator = initiator;
        setCenter(center);
        setMinRadius(minRadius);
        setMaxRadius(maxRadius);
        minY = plugin.getMinHeight(center.getWorld());
        if (center.getWorld().getEnvironment() == World.Environment.NETHER) {
            maxY = 126;
        } else {
            maxY = center.getWorld().getMaxHeight();
        }
        this.validators.getRaw().putAll(plugin.getLocationValidators().getRaw());
        Arrays.asList(validators).forEach(this.validators::add);
    }

    /**
     * Get all entities targeted by this searcher
     * @return The entitiy to target
     */
    public Set<Entity> getTargets() {
        return targets;
    }

    public ValidatorRegistry getValidators() {
        return validators;
    }

    /**
     * Get a ID unique to each searcher
     * @return The searcher's version 4 UUID
     */
    public UUID getUniqueId() {
        return uniqueId;
    }

    /**
     * Set the ID of this searcher used for cooldowns. Set to null to use an automatically generated one!
     * @param id The ID of the searcher
     */
    public void setId(String id) {
        this.id = id;
    }

    /**
     * Get the ID of the searcher used for cooldowns. If no specific one is set then one generated by the settings will be returned
     * @return The ID of the searcher
     */
    public String getId() {
        if (id == null) {
            return toString();
        }
        return id;
    }

    /**
     * Set the seed that should be used when selecting locations. See {@link Random#setSeed(long)}.
     * @param seed The seed.
     */
    public void setSeed(long seed) {
        this.seed = seed;
        if (random == RandomTeleport.RANDOM) {
            random = new Random(seed);
        } else {
            random.setSeed(seed);
        }
    }

    /**
     * Get the seed of this random searcher. Returns -1 if none was set.
     * @return The seed or -1
     */
    public long getSeed() {
        return seed;
    }

    /**
     * Directly set the Random instance used for selecting coordinates
     * @param random The random instance
     */
    public void setRandom(Random random) {
        this.random = random;
    }

    /**
     * Get the random instance that is used for finding locations
     * @return The random instance; {@link RandomTeleport#RANDOM} by default
     */
    public Random getRandom() {
        return random;
    }

    /**
     * Get the center for this searcher
     * @return The center location
     */
    public Location getCenter() {
        return center;
    }

    /**
     * Set the center of this searcher
     * @param center The center location; never null
     */
    public void setCenter(Location center) {
        Validate.notNull(center, "Center cannot be null!");
        Validate.notNull(center.getWorld(), "Center world cannot be null!");
        this.center = center;
    }

    /**
     * Get the minimum radius
     * @return The minimum radius, always positive and less than the max radius!
     */
    public int getMinRadius() {
        return minRadius;
    }

    /**
     * Set the minimum search radius
     * @param minRadius The min radius; has to be positive and less than the max radius!
     */
    public void setMinRadius(int minRadius) {
        Validate.isTrue(minRadius >= 0 && minRadius < maxRadius, "Min radius has to be positive and less than the max radius!");
        this.minRadius = minRadius;
    }

    /**
     * Get the maximum radius
     * @return The maximum radius, always greater than the minimum radius
     */
    public int getMaxRadius() {
        return maxRadius;
    }

    /**
     * Set the maximum search radius
     * @param maxRadius The max radius; has to be greater than the min radius!
     */
    public void setMaxRadius(int maxRadius) {
        Validate.isTrue(maxRadius > minRadius, "Max radius has to be greater than the min radius!");
        this.maxRadius = maxRadius;
    }

    /**
     * Get the delay in ticks between checking chunks when searching
     * @return The delay in ticks
     */
    public int getCheckDelay() {
        return checkDelay;
    }

    /**
     * Set the delay in ticks between checking chunks when searching
     * @param checkDelay The delay in ticks
     */
    public void setCheckDelay(int checkDelay) {
        this.checkDelay = checkDelay;
    }

    /**
     * Get the minimum Y
     * @return The minimum Y, always positive and less than the max Y!
     */
    public int getMinY() {
        return minY;
    }

    /**
     * Set the minimum search Y
     * @param minY The min Y; has to be positive and less than the max Y!
     */
    public void setMinY(int minY) {
        Validate.isTrue(minY >= plugin.getMinHeight(center.getWorld()), "Min Y has to be at least the world's minimum height!");
        Validate.isTrue(minY < maxY, "Min Y has to be less than the max Y!");
        this.minY = minY;
    }

    /**
     * Get the maximum Y
     * @return The maximum Y, always greater than the minimum Y
     */
    public int getMaxY() {
        return maxY;
    }

    /**
     * Set the maximum search Y
     * @param maxY The max Y; has to be greater than the min Y!
     */
    public void setMaxY(int maxY) {
        Validate.isTrue(maxY <= center.getWorld().getMaxHeight() && maxY > minY, "Max Y has to be greater than the min Y and at most the world's max height!");
        this.maxY = maxY;
    }

    /**
     * By default it will search for coordinates in any chunk, even unloaded ones prompting the server to load new
     * chunks which might result in some performance impact if the server doesn't support async loading. This disables
     * that and only searches in already loaded chunks. (But might fail more often)
     * @param loadedOnly Whether or not to search in loaded chunks only
     */
    public void searchInLoadedOnly(boolean loadedOnly) {
        this.loadedOnly = loadedOnly;
    }

    /**
     * By default it will search for coordinates in any chunk, even ungenerated ones prompting the world to get
     * generated at the point which might result in some performance impact. This disables that and only searches
     * in already generated chunks.
     * @param generatedOnly Whether or not to search in generated chunks only
     */
    public void searchInGeneratedOnly(boolean generatedOnly) {
        this.generatedOnly = generatedOnly;
    }

    public int getMaxTries() {
        return maxTries;
    }

    public void setMaxTries(int maxTries) {
        this.maxTries = maxTries;
    }

    /**
     * Set the cooldown that a player has to wait before using a searcher with similar settings again
     * @param cooldown The cooldown in seconds
     */
    public void setCooldown(int cooldown) {
        Validate.isTrue(cooldown >= 0, "Cooldown can't be negative!");
        this.cooldown = cooldown;
    }

    /**
     * Get the cooldown that a player has to wait before using a searcher with similar settings again
     * @return The cooldown in seconds
     */
    public int getCooldown() {
        return cooldown;
    }

    /**
     * Get additional options
     * @return A map of additional options
     */
    public Map<String, String> getOptions() {
        return options;
    }

    /**
     * Search for a valid location
     * @return A CompletableFuture for when the search task is complete
     * @throws IllegalStateException when the searcher is already running
     */
    public CompletableFuture<Location> search() {
        if (plugin.getRunningSearchers().containsKey(uniqueId)) {
            throw new IllegalStateException("Searcher " + uniqueId + " is already running!");
        }
        plugin.getRunningSearchers().put(uniqueId, this);
        if (targets.isEmpty() && initiator instanceof Entity) {
            targets.add((Entity) initiator);
        }
        future = new CompletableFuture<>();
        checks = 0;
        checked.clear();
        plugin.getServer().getScheduler().runTask(plugin, () -> checkRandom(future));
        future.whenComplete((l, e) -> plugin.getRunningSearchers().remove(uniqueId));
        return future;
    }

    private void checkRandom(CompletableFuture<Location> future) {
        if (checks >= maxTries) {
            future.completeExceptionally(new NotFoundException("location"));
            return;
        }
        if (future.isCancelled() || future.isDone() || future.isCompletedExceptionally()) {
            return;
        }
        lastCheck = center.getWorld().getTime();
        Location randomLoc = center.clone();
        randomLoc.setY(plugin.getMinHeight(center.getWorld()));
        int minChunk = minRadius >> 4;
        int maxChunk = maxRadius >> 4;
        int randChunkX;
        int randChunkZ;
        Chunk[] loadedChunks = new Chunk[0];
        if (loadedOnly) {
            loadedChunks = randomLoc.getWorld().getLoadedChunks();
            if (loadedChunks.length == 0) {
                future.completeExceptionally(new NotFoundException("loaded chunk"));
                return;
            }
        }

        do {
            checks++;
            if (checks >= maxTries) {
                future.completeExceptionally(new NotFoundException("location"));
                return;
            }
            if (loadedOnly) {
                Chunk chunk = loadedChunks[random.nextInt(loadedChunks.length)];
                randChunkX = chunk.getX();
                randChunkZ = chunk.getZ();
            } else {
                randChunkX = (random.nextBoolean() ? 1 : -1) * random.nextInt(maxChunk + 1);
                randChunkZ = (random.nextBoolean() ? 1 : -1) * random.nextInt(maxChunk + 1);
            }
        } while (!checked.put(randChunkX, randChunkZ)
                || !inRadius(Math.abs(randChunkX), Math.abs(randChunkZ), minChunk, maxChunk)
                || (generatedOnly && !PaperLib.isChunkGenerated(randomLoc.getWorld(), randChunkX, randChunkZ)));

        randomLoc.setX(((center.getBlockX() >> 4) + randChunkX) * 16);
        randomLoc.setZ(((center.getBlockZ() >> 4) + randChunkZ) * 16);
        PaperLib.getChunkAtAsync(randomLoc).thenApply(c -> {
            checks++;
            if (c == null) {
                // Chunk not generated, test another one
                checkRandom(future);
                return false;
            }
            int indexOffset = random.nextInt(RANDOM_LIST.size());
            Location foundLoc = null;
            for (int i = 0; i < RANDOM_LIST.size(); i++) {
                int index = (i + indexOffset) % RANDOM_LIST.size();
                boolean validated = true;
                Location loc = randomLoc.clone().add(RANDOM_LIST.get(index)[0], 0, RANDOM_LIST.get(index)[1]);

                if (!inRadius(loc)) {
                    continue;
                }

                for (LocationValidator validator : getValidators().getAll()) {
                    if (!validator.validate(this, loc)) {
                        validated = false;
                        break;
                    }
                }
                if (validated) {
                    foundLoc = loc;
                    break;
                }
            }

            if (foundLoc != null) {
                // all checks are for the top block, put we want a location above that so add 1 to y
                future.complete(foundLoc.add(0, 1, 0));
                return true;
            }
            long diff = center.getWorld().getTime() - lastCheck;
            if (diff < checkDelay) {
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> checkRandom(future), checkDelay - diff);
            } else {
                checkRandom(future);
            }
            return false;
        }).exceptionally(future::completeExceptionally);
    }

    private boolean inRadius(Location location) {
        int diffX = Math.abs(location.getBlockX() - center.getBlockX());
        int diffZ = Math.abs(location.getBlockZ() - center.getBlockZ());
        return inRadius(diffX, diffZ, minRadius, maxRadius);
    }

    private boolean inRadius(int diffX, int diffZ, int minRadius, int maxRadius) {
        return diffX >= minRadius && diffX <= maxRadius && diffZ <= maxRadius
                || diffZ >= minRadius && diffZ <= maxRadius && diffX <= maxRadius;
    }

    public RandomTeleport getPlugin() {
        return plugin;
    }

    /**
     * The sender who initiated this search
     * @return The initiator
     */
    public CommandSender getInitiator() {
        return initiator;
    }

    /**
     * Get the currently running search future
     * @return The currently running search future or null if none is running
     */
    public CompletableFuture<Location> getFuture() {
        return future;
    }

    @Override
    public String toString() {
        return "RandomSearcher{" +
                "id='" + id + '\'' +
                ", seed=" + seed +
                ", center=" + center +
                ", minRadius=" + minRadius +
                ", maxRadius=" + maxRadius +
                ", loadedOnly=" + loadedOnly +
                ", generatedOnly=" + generatedOnly +
                ", maxTries=" + maxTries +
                ", cooldown=" + cooldown +
                '}';
    }
}
