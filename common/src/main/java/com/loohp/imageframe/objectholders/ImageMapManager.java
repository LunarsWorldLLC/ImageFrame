/*
 * This file is part of ImageFrame.
 *
 * Copyright (C) 2025. LoohpJames <jamesloohp@gmail.com>
 * Copyright (C) 2025. Contributors
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
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.loohp.imageframe.objectholders;

import com.google.common.collect.Sets;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.loohp.imageframe.ImageFrame;
import com.loohp.imageframe.api.events.ImageMapAddedEvent;
import com.loohp.imageframe.api.events.ImageMapDeletedEvent;
import com.loohp.imageframe.api.events.ImageMapUpdatedEvent;
import com.loohp.imageframe.storage.ImageFrameStorage;
import com.loohp.imageframe.utils.MapUtils;
import com.loohp.platformscheduler.Scheduler;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapCursor;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class ImageMapManager implements AutoCloseable {

    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
    public static final int FAKE_MAP_ID_START_RANGE = Integer.MAX_VALUE / 4 * 3;

    private static final AtomicInteger FAKE_MAP_ID_COUNTER = new AtomicInteger(FAKE_MAP_ID_START_RANGE);

    public static int getNextFakeMapId() {
        return FAKE_MAP_ID_COUNTER.getAndUpdate(i -> i < FAKE_MAP_ID_START_RANGE ? FAKE_MAP_ID_START_RANGE : i + 1);
    }

    private static final long SHUTDOWN_LOCK_TIMEOUT_SECONDS = 5;

    private final ImageFrameStorage imageFrameStorage;
    private final Map<Integer, ImageMap> maps;
    private final Map<MapView, ImageMap> mapsByView;
    private final List<ImageMapRenderEventListener> renderEventListeners;
    private final Set<Integer> deletedMapIds;
    private final ReentrantLock managerLock;

    // Animation tick caching to avoid repeated System.currentTimeMillis() calls
    private volatile long cachedAnimationTick;
    private volatile long cachedAnimationTickTime;

    public ImageMapManager(ImageFrameStorage imageFrameStorage) {
        this.maps = new ConcurrentHashMap<>();
        this.mapsByView = new ConcurrentHashMap<>();
        this.imageFrameStorage = imageFrameStorage;
        this.renderEventListeners = new CopyOnWriteArrayList<>();
        this.deletedMapIds = ConcurrentHashMap.newKeySet();
        this.managerLock = new ReentrantLock();
        this.cachedAnimationTick = 0;
        this.cachedAnimationTickTime = 0;
    }

    public ImageFrameStorage getStorage() {
        return imageFrameStorage;
    }

    protected long getCurrentAnimationTick() {
        long currentTime = System.currentTimeMillis();
        // Cache tick value for 25ms to reduce repeated currentTimeMillis calls within same tick
        if (currentTime - cachedAnimationTickTime > 25) {
            cachedAnimationTick = currentTime / 50;
            cachedAnimationTickTime = currentTime;
        }
        return cachedAnimationTick;
    }

    @Override
    public void close() {
        // Use tryLock with timeout to avoid blocking the main thread during shutdown
        // The deletedMapIds set is thread-safe (ConcurrentHashMap.newKeySet()), so we can
        // safely save even without the lock - we just want to ensure consistency if possible
        boolean lockAcquired = false;
        try {
            lockAcquired = managerLock.tryLock(SHUTDOWN_LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!lockAcquired) {
                Bukkit.getLogger().log(Level.WARNING, "[ImageFrame] Could not acquire lock during shutdown (timeout after " + SHUTDOWN_LOCK_TIMEOUT_SECONDS + "s). Saving deleted maps without lock.");
            }
            // Save regardless of lock status - the data structures are thread-safe
            imageFrameStorage.saveDeletedMaps(deletedMapIds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Bukkit.getLogger().log(Level.WARNING, "[ImageFrame] Interrupted while waiting for lock during shutdown. Saving deleted maps without lock.");
            imageFrameStorage.saveDeletedMaps(deletedMapIds);
        } finally {
            if (lockAcquired) {
                managerLock.unlock();
            }
        }
    }

    public void appendRenderEventListener(ImageMapRenderEventListener listener) {
        renderEventListeners.add(listener);
    }

    public void prependRenderEventListener(ImageMapRenderEventListener listener) {
        renderEventListeners.add(0, listener);
    }

    public void removeRenderEventListener(ImageMapRenderEventListener listener) {
        renderEventListeners.remove(listener);
    }

    protected void callRenderEventListener(ImageMapManager manager, ImageMap imageMap, MapView map, Player player, MutablePair<byte[], Collection<MapCursor>> renderData) {
        renderEventListeners.forEach(each -> each.accept(manager, imageMap, map, player, renderData));
    }

    public void addMap(ImageMap map) throws Exception {
        managerLock.lock();
        try {
            if (map.getManager() != this) {
                throw new IllegalArgumentException("ImageMap's manager is not set to this");
            }
            if (getFromCreator(map.getCreator(), map.getName()) != null) {
                throw new IllegalArgumentException("Duplicated map name for this creator");
            }
            int originalImageIndex = map.getImageIndex();
            imageFrameStorage.prepareImageIndex(map, i -> map.imageIndex = i);
            maps.put(map.getImageIndex(), map);
            for (MapView mapView : map.getMapViews()) {
                mapsByView.put(mapView, map);
                deletedMapIds.remove(mapView.getId());
            }
            try {
                map.save();
                Bukkit.getPluginManager().callEvent(new ImageMapAddedEvent(map));
            } catch (Throwable e) {
                maps.remove(originalImageIndex);
                for (MapView mapView : map.getMapViews()) {
                    mapsByView.remove(mapView);
                }
                throw e;
            }
            saveDeletedMapsInternal();
        } finally {
            managerLock.unlock();
        }
    }

    public boolean hasMap(int imageIndex) {
        return maps.containsKey(imageIndex);
    }

    public Collection<ImageMap> getMaps() {
        return Collections.unmodifiableCollection(maps.values());
    }

    public ImageMap getFromMapId(int id) {
        MapView mapView = Bukkit.getMap(id);
        if (mapView == null) {
            return null;
        }
        return getFromMapView(Bukkit.getMap(id));
    }

    public ImageMap getFromImageId(int imageId) {
        return maps.get(imageId);
    }

    public ImageMap getFromMapView(MapView mapView) {
        return mapsByView.get(mapView);
    }

    public Set<ImageMap> getFromCreator(UUID uuid) {
        return maps.values().stream().filter(each -> each.getCreator().equals(uuid)).collect(Collectors.toSet());
    }

    public List<ImageMap> getFromCreator(UUID uuid, Comparator<ImageMap> order) {
        return maps.values().stream().filter(each -> each.getCreator().equals(uuid)).sorted(order).collect(Collectors.toList());
    }

    public ImageMap getFromCreator(UUID uuid, String name) {
        return maps.values().stream().filter(each -> each.getCreator().equals(uuid) && each.getName().equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    public Set<UUID> getCreators() {
        return maps.values().stream().map(each -> each.getCreator()).collect(Collectors.toSet());
    }

    public ImageMap getFromFakeMapId(int fakeMapId) {
        return maps.values().stream().filter(each -> each.requiresAnimationService() && each.getFakeMapIds().contains(fakeMapId)).findFirst().orElse(null);
    }

    public boolean deleteMap(int imageIndex) {
        managerLock.lock();
        try {
            ImageMap imageMap = maps.remove(imageIndex);
            if (imageMap == null) {
                return false;
            }
            List<MapView> mapViews = imageMap.getMapViews();
            for (MapView mapView : mapViews) {
                mapsByView.remove(mapView);
            }
            if (imageMap.trackDeletedMaps()) {
                mapViews.forEach(each -> deletedMapIds.add(each.getId()));
            }
            imageMap.markInvalid();
            imageFrameStorage.deleteMap(imageIndex);
            imageMap.stop();
            saveDeletedMapsInternal();
            Bukkit.getPluginManager().callEvent(new ImageMapDeletedEvent(imageMap));
            Scheduler.runTask(ImageFrame.plugin, () -> {
                mapViews.forEach(each -> {
                    if (each.getRenderers().isEmpty()) {
                        each.addRenderer(DeletedMapRenderer.INSTANCE);
                    }
                });
            });
            return true;
        } finally {
            managerLock.unlock();
        }
    }

    public void updateMap(int imageIndex, boolean exist) {
        managerLock.lock();
        try {
            ImageMap imageMap = maps.get(imageIndex);
            try {
                if (imageMap == null) {
                    if (exist) {
                        JsonObject json = imageFrameStorage.loadImageMapData(imageIndex);
                        Scheduler.runTaskAsynchronously(ImageFrame.plugin, () -> {
                            try {
                                addMap(ImageMapLoaders.load(this, json).get());
                            } catch (Exception e) {
                                throw new RuntimeException("Unable to update map " + imageIndex + " from source", e);
                            }
                        });
                    }
                } else {
                    if (exist) {
                        JsonObject json = imageFrameStorage.loadImageMapData(imageIndex);
                        Scheduler.runTaskAsynchronously(ImageFrame.plugin, () -> {
                            if (imageMap.applyUpdate(json)) {
                                imageMap.reloadColorCache();
                                Bukkit.getPluginManager().callEvent(new ImageMapUpdatedEvent(imageMap));
                            }
                        });
                    } else {
                        deleteMap(imageIndex);
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException("Unable to update map " + imageIndex + " from source", e);
            }
        } finally {
            managerLock.unlock();
        }
    }

    public Set<Integer> getDeletedMapIds() {
        return Collections.unmodifiableSet(deletedMapIds);
    }

    public boolean isMapDeleted(int mapId) {
        return deletedMapIds.contains(mapId);
    }

    public boolean isMapDeleted(MapView mapView) {
        return isMapDeleted(mapView.getId());
    }

    public void loadMaps(IFPlayerManager ifPlayerManager) {
        managerLock.lock();
        try {
            maps.clear();
            mapsByView.clear();
        } finally {
            managerLock.unlock();
        }
        List<MutablePair<String, Future<? extends ImageMap>>> futures = imageFrameStorage.loadMaps(this, deletedMapIds, ifPlayerManager);
        Scheduler.runTaskAsynchronously(ImageFrame.plugin, () -> {
            int count = 0;
            for (MutablePair<String, Future<? extends ImageMap>> pair : futures) {
                try {
                    addMap(pair.getSecond().get());
                    count++;
                } catch (Throwable e) {
                    Bukkit.getConsoleSender().sendMessage(ChatColor.RED + "[ImageFrame] Unable to load ImageMap data in " + pair.getFirst());
                    e.printStackTrace();
                }
            }
            Bukkit.getConsoleSender().sendMessage(ChatColor.GREEN + "[ImageFrame] Data loading completed! Loaded " + count + " ImageMaps!");
        });
    }

    public void syncMaps() {
        syncMaps(false);
    }

    public void syncMaps(boolean verbose) {
        Set<Integer> indexesFromStorage = imageFrameStorage.getAllImageIndexes();
        Set<Integer> indexesFromLocal;
        managerLock.lock();
        try {
            indexesFromLocal = Set.copyOf(maps.keySet());
        } finally {
            managerLock.unlock();
        }
        int added = 0;
        int deleted = 0;
        for (int index : Sets.symmetricDifference(indexesFromStorage, indexesFromLocal)) {
           try {
               boolean exist = indexesFromStorage.contains(index);
               updateMap(index, exist);
               if (exist) {
                   added++;
               } else {
                   deleted++;
               }
           } catch (Throwable e) {
               if (verbose) {
                   Bukkit.getConsoleSender().sendMessage(ChatColor.RED + "[ImageFrame] Unable to sync ImageMap data for index " + index);
               }
               e.printStackTrace();
           }
        }
        if (verbose) {
            Bukkit.getConsoleSender().sendMessage(ChatColor.GREEN + "[ImageFrame] Data sync completed! Newly loaded " + added + " and deleted " + deleted + " ImageMaps!");
        }
    }

    private void saveDeletedMapsInternal() {
        // Called when lock is already held
        imageFrameStorage.saveDeletedMaps(deletedMapIds);
    }

    public void saveDeletedMaps() {
        managerLock.lock();
        try {
            saveDeletedMapsInternal();
        } finally {
            managerLock.unlock();
        }
    }

    public void sendAllMaps(Collection<? extends Player> players) {
        maps.values().forEach(m -> m.send(players));
    }

    public static class DeletedMapRenderer extends MapRenderer {

        public static final DeletedMapRenderer INSTANCE = new DeletedMapRenderer();

        private DeletedMapRenderer() {}

        @Override
        public void render(MapView map, MapCanvas canvas, Player player) {
            List<MapRenderer> mapRenderers = map.getRenderers();
            if (mapRenderers.size() != 1 || mapRenderers.get(0) != this) {
                Scheduler.runTaskLater(ImageFrame.plugin, () -> map.removeRenderer(this), 1);
                return;
            }
            Random random = new Random(map.getId());
            byte[] colors = MapUtils.PALETTE_GRAYSCALE;
            for (int y = 0; y < MapUtils.MAP_WIDTH; y++) {
                for (int x = 0; x < MapUtils.MAP_WIDTH; x++) {
                    canvas.setPixel(x, y, colors[random.nextInt(colors.length)]);
                }
            }
        }
    }

}
