package com.sk89q.worldedit.bukkit.adapter.impl.fawe;

import com.fastasyncworldedit.bukkit.adapter.BukkitGetBlocks;
import com.fastasyncworldedit.bukkit.adapter.DelegateLock;
import com.fastasyncworldedit.core.Fawe;
import com.fastasyncworldedit.core.FaweCache;
import com.fastasyncworldedit.core.configuration.Settings;
import com.fastasyncworldedit.core.extent.processor.heightmap.HeightMapType;
import com.fastasyncworldedit.core.math.BitArrayUnstretched;
import com.fastasyncworldedit.core.queue.IChunkGet;
import com.fastasyncworldedit.core.queue.IChunkSet;
import com.fastasyncworldedit.core.queue.implementation.QueueHandler;
import com.fastasyncworldedit.core.queue.implementation.blocks.CharGetBlocks;
import com.fastasyncworldedit.core.util.collection.AdaptedMap;
import com.google.common.base.Suppliers;
import com.google.common.collect.Iterables;
import com.sk89q.jnbt.CompoundTag;
import com.sk89q.jnbt.ListTag;
import com.sk89q.jnbt.StringTag;
import com.sk89q.jnbt.Tag;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.bukkit.WorldEditPlugin;
import com.sk89q.worldedit.bukkit.adapter.impl.fawe.nbt.LazyCompoundTag_1_16_5;
import com.sk89q.worldedit.internal.Constants;
import com.sk89q.worldedit.internal.util.LogManagerCompat;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.biome.BiomeType;
import com.sk89q.worldedit.world.block.BlockTypesCache;
import io.papermc.lib.PaperLib;
import io.papermc.paper.event.block.BeaconDeactivatedEvent;
import net.minecraft.server.v1_16_R3.BiomeBase;
import net.minecraft.server.v1_16_R3.BiomeStorage;
import net.minecraft.server.v1_16_R3.BlockPosition;
import net.minecraft.server.v1_16_R3.Chunk;
import net.minecraft.server.v1_16_R3.ChunkSection;
import net.minecraft.server.v1_16_R3.DataBits;
import net.minecraft.server.v1_16_R3.DataPalette;
import net.minecraft.server.v1_16_R3.DataPaletteBlock;
import net.minecraft.server.v1_16_R3.DataPaletteHash;
import net.minecraft.server.v1_16_R3.DataPaletteLinear;
import net.minecraft.server.v1_16_R3.Entity;
import net.minecraft.server.v1_16_R3.EntityTypes;
import net.minecraft.server.v1_16_R3.EnumSkyBlock;
import net.minecraft.server.v1_16_R3.HeightMap;
import net.minecraft.server.v1_16_R3.IBlockData;
import net.minecraft.server.v1_16_R3.IRegistry;
import net.minecraft.server.v1_16_R3.LightEngine;
import net.minecraft.server.v1_16_R3.MinecraftKey;
import net.minecraft.server.v1_16_R3.NBTTagCompound;
import net.minecraft.server.v1_16_R3.NBTTagInt;
import net.minecraft.server.v1_16_R3.NibbleArray;
import net.minecraft.server.v1_16_R3.SectionPosition;
import net.minecraft.server.v1_16_R3.SoundEffects;
import net.minecraft.server.v1_16_R3.TileEntity;
import net.minecraft.server.v1_16_R3.TileEntityBeacon;
import net.minecraft.server.v1_16_R3.WorldServer;
import org.apache.logging.log4j.Logger;
import org.bukkit.World;
import org.bukkit.craftbukkit.v1_16_R3.CraftWorld;
import org.bukkit.craftbukkit.v1_16_R3.block.CraftBlock;
import org.bukkit.event.entity.CreatureSpawnEvent;
import javax.annotation.Nonnull;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;

public class BukkitGetBlocks_1_16_5 extends CharGetBlocks implements BukkitGetBlocks {

    private static final Logger LOGGER = LogManagerCompat.getLogger();

    private static final Function<BlockPosition, BlockVector3> posNms2We = v -> BlockVector3.at(v.getX(), v.getY(), v.getZ());
    private static final Function<TileEntity, CompoundTag> nmsTile2We = tileEntity -> new LazyCompoundTag_1_16_5(Suppliers.memoize(
            () -> tileEntity.save(new NBTTagCompound())));
    private final FAWE_Spigot_v1_16_R3 adapter = ((FAWE_Spigot_v1_16_R3) WorldEditPlugin.getInstance().getBukkitImplAdapter());
    private final ReadWriteLock sectionLock = new ReentrantReadWriteLock();
    private final WorldServer world;
    private final int chunkX;
    private final int chunkZ;
    private ChunkSection[] sections;
    private Chunk nmsChunk;
    private NibbleArray[] blockLight = new NibbleArray[16];
    private NibbleArray[] skyLight = new NibbleArray[16];
    private boolean createCopy = false;
    private BukkitGetBlocks_1_16_5_Copy copy = null;
    private boolean forceLoadSections = true;
    private boolean lightUpdate = false;

    public BukkitGetBlocks_1_16_5(World world, int chunkX, int chunkZ) {
        this(((CraftWorld) world).getHandle(), chunkX, chunkZ);
    }

    public BukkitGetBlocks_1_16_5(WorldServer world, int chunkX, int chunkZ) {
        super(0, 15);
        this.world = world;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
    }

    public int getChunkX() {
        return chunkX;
    }

    public int getChunkZ() {
        return chunkZ;
    }

    @Override
    public boolean isCreateCopy() {
        return createCopy;
    }

    @Override
    public void setCreateCopy(boolean createCopy) {
        this.createCopy = createCopy;
    }

    @Override
    public IChunkGet getCopy() {
        return copy;
    }

    @Override
    public void setLightingToGet(char[][] light, int minSectionPosition, int maxSectionPosition) {
        if (light != null) {
            lightUpdate = true;
            try {
                fillLightNibble(light, EnumSkyBlock.BLOCK);
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void setSkyLightingToGet(char[][] light, int minSectionPosition, int maxSectionPosition) {
        if (light != null) {
            lightUpdate = true;
            try {
                fillLightNibble(light, EnumSkyBlock.SKY);
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void setHeightmapToGet(HeightMapType type, int[] data) {
        BitArrayUnstretched bitArray = new BitArrayUnstretched(9, 256);
        bitArray.fromRaw(data);
        getChunk().heightMap.get(HeightMap.Type.valueOf(type.name())).a(bitArray.getData());
    }

    @Override
    public int getMaxY() {
        return 255;
    }

    @Override
    public int getMinY() {
        return 0;
    }

    @Override
    public BiomeType getBiomeType(int x, int y, int z) {
        BiomeStorage index = getChunk().getBiomeIndex();
        BiomeBase base = null;
        if (y == -1) {
            for (y = 0; y < 256; y += 4) {
                base = index.getBiome(x >> 2, y >> 2, z >> 2);
                if (base != null) {
                    break;
                }
            }
        } else {
            base = index.getBiome(x >> 2, y >> 2, z >> 2);
        }
        return base != null ? BukkitAdapter_1_16_5.adapt(base, world) : null;
    }

    @Override
    public void removeSectionLighting(int layer, boolean sky) {
        SectionPosition sectionPosition = SectionPosition.a(getChunk().getPos(), layer);
        NibbleArray nibble = world.getChunkProvider().getLightEngine().a(EnumSkyBlock.BLOCK).a(sectionPosition);
        if (nibble != null) {
            lightUpdate = true;
            synchronized (nibble) {
                byte[] bytes = PaperLib.isPaper() ? nibble.getIfSet() : nibble.asBytes();
                if (!PaperLib.isPaper() || bytes != NibbleArray.EMPTY_NIBBLE) {
                    Arrays.fill(bytes, (byte) 0);
                }
            }
        }
        if (sky) {
            SectionPosition sectionPositionSky = SectionPosition.a(getChunk().getPos(), layer);
            NibbleArray nibbleSky = world.getChunkProvider().getLightEngine().a(EnumSkyBlock.SKY).a(sectionPositionSky);
            if (nibbleSky != null) {
                lightUpdate = true;
                synchronized (nibbleSky) {
                    byte[] bytes = PaperLib.isPaper() ? nibbleSky.getIfSet() : nibbleSky.asBytes();
                    if (!PaperLib.isPaper() || bytes != NibbleArray.EMPTY_NIBBLE) {
                        Arrays.fill(bytes, (byte) 0);
                    }
                }
            }
        }
    }

    @Override
    public CompoundTag getTile(int x, int y, int z) {
        TileEntity tileEntity = getChunk().getTileEntity(new BlockPosition((x & 15) + (
                chunkX << 4), y, (z & 15) + (
                chunkZ << 4)));
        if (tileEntity == null) {
            return null;
        }
        return new LazyCompoundTag_1_16_5(Suppliers.memoize(() -> tileEntity.save(new NBTTagCompound())));
    }

    @Override
    public Map<BlockVector3, CompoundTag> getTiles() {
        Map<BlockPosition, TileEntity> nmsTiles = getChunk().getTileEntities();
        if (nmsTiles.isEmpty()) {
            return Collections.emptyMap();
        }
        return AdaptedMap.immutable(nmsTiles, posNms2We, nmsTile2We);
    }

    @Override
    public int getSkyLight(int x, int y, int z) {
        int layer = y >> 4;
        if (skyLight[layer] == null) {
            SectionPosition sectionPosition = SectionPosition.a(getChunk().getPos(), layer);
            NibbleArray nibbleArray = world.getChunkProvider().getLightEngine().a(EnumSkyBlock.SKY).a(sectionPosition);
            // If the server hasn't generated the section's NibbleArray yet, it will be null
            if (nibbleArray == null) {
                byte[] a = new byte[2048];
                // Safe enough to assume if it's not created, it's under the sky. Unlikely to be created before lighting is fixed anyway.
                Arrays.fill(a, (byte) 15);
                nibbleArray = new NibbleArray(a);
                ((LightEngine) world.getChunkProvider().getLightEngine()).a(EnumSkyBlock.SKY, sectionPosition, nibbleArray, true);
            }
            skyLight[layer] = nibbleArray;
        }
        return skyLight[layer].a(x & 15, y & 15, z & 15);
    }

    @Override
    public int getEmittedLight(int x, int y, int z) {
        int layer = y >> 4;
        if (blockLight[layer] == null) {
            SectionPosition sectionPosition = SectionPosition.a(getChunk().getPos(), layer);
            NibbleArray nibbleArray = world.getChunkProvider().getLightEngine().a(EnumSkyBlock.BLOCK).a(sectionPosition);
            // If the server hasn't generated the section's NibbleArray yet, it will be null
            if (nibbleArray == null) {
                byte[] a = new byte[2048];
                // Safe enough to assume if it's not created, it's under the sky. Unlikely to be created before lighting is fixed anyway.
                Arrays.fill(a, (byte) 15);
                nibbleArray = new NibbleArray(a);
                ((LightEngine) world.getChunkProvider().getLightEngine()).a(
                        EnumSkyBlock.BLOCK,
                        sectionPosition,
                        nibbleArray,
                        true
                );
            }
            blockLight[layer] = nibbleArray;
        }
        return blockLight[layer].a(x & 15, y & 15, z & 15);
    }

    @Override
    public int[] getHeightMap(HeightMapType type) {
        long[] longArray = getChunk().heightMap.get(HeightMap.Type.valueOf(type.name())).a();
        BitArrayUnstretched bitArray = new BitArrayUnstretched(9, 256, longArray);
        return bitArray.toRaw(new int[256]);
    }

    @Override
    public CompoundTag getEntity(UUID uuid) {
        Entity entity = world.getEntity(uuid);
        if (entity != null) {
            org.bukkit.entity.Entity bukkitEnt = entity.getBukkitEntity();
            return BukkitAdapter.adapt(bukkitEnt).getState().getNbtData();
        }
        for (List<Entity> entry : getChunk().getEntitySlices()) {
            if (entry != null) {
                for (Entity ent : entry) {
                    if (uuid.equals(ent.getUniqueID())) {
                        org.bukkit.entity.Entity bukkitEnt = ent.getBukkitEntity();
                        return BukkitAdapter.adapt(bukkitEnt).getState().getNbtData();
                    }
                }
            }
        }
        return null;
    }

    @Override
    public Set<CompoundTag> getEntities() {
        List<Entity>[] slices = getChunk().getEntitySlices();
        int size = 0;
        for (List<Entity> slice : slices) {
            if (slice != null) {
                size += slice.size();
            }
        }
        if (slices.length == 0) {
            return Collections.emptySet();
        }
        int finalSize = size;
        return new AbstractSet<CompoundTag>() {
            @Override
            public int size() {
                return finalSize;
            }

            @Override
            public boolean isEmpty() {
                return false;
            }

            @Override
            public boolean contains(Object get) {
                if (!(get instanceof CompoundTag)) {
                    return false;
                }
                CompoundTag getTag = (CompoundTag) get;
                Map<String, Tag> value = getTag.getValue();
                CompoundTag getParts = (CompoundTag) value.get("UUID");
                UUID getUUID = new UUID(getParts.getLong("Most"), getParts.getLong("Least"));
                for (List<Entity> slice : slices) {
                    if (slice != null) {
                        for (Entity entity : slice) {
                            UUID uuid = entity.getUniqueID();
                            if (uuid.equals(getUUID)) {
                                return true;
                            }
                        }
                    }
                }
                return false;
            }

            @Nonnull
            @Override
            public Iterator<CompoundTag> iterator() {
                Iterable<CompoundTag> result = Iterables.transform(
                        Iterables.concat(slices),
                        new com.google.common.base.Function<Entity, CompoundTag>() {
                            @Nullable
                            @Override
                            public CompoundTag apply(@Nullable Entity input) {
                                NBTTagCompound tag = new NBTTagCompound();
                                return (CompoundTag) adapter.toNative(input.save(tag));
                            }
                        }
                );
                return result.iterator();
            }
        };
    }

    private void removeEntity(Entity entity) {
        entity.die();
    }

    public Chunk ensureLoaded(WorldServer nmsWorld, int chunkX, int chunkZ) {
        return BukkitAdapter_1_16_5.ensureLoaded(nmsWorld, chunkX, chunkZ);
    }

    @Override
    public synchronized <T extends Future<T>> T call(IChunkSet set, Runnable finalizer) {
        forceLoadSections = false;
        copy = createCopy ? new BukkitGetBlocks_1_16_5_Copy(world) : null;
        try {
            WorldServer nmsWorld = world;
            Chunk nmsChunk = ensureLoaded(nmsWorld, chunkX, chunkZ);
            boolean fastmode = set.isFastMode() && Settings.settings().QUEUE.NO_TICK_FASTMODE;

            // Remove existing tiles. Create a copy so that we can remove blocks
            Map<BlockPosition, TileEntity> chunkTiles = new HashMap<>(nmsChunk.getTileEntities());
            List<TileEntity> beacons = null;
            if (!chunkTiles.isEmpty()) {
                for (Map.Entry<BlockPosition, TileEntity> entry : chunkTiles.entrySet()) {
                    final BlockPosition pos = entry.getKey();
                    final int lx = pos.getX() & 15;
                    final int ly = pos.getY();
                    final int lz = pos.getZ() & 15;
                    final int layer = ly >> 4;
                    if (!set.hasSection(layer)) {
                        continue;
                    }

                    int ordinal = set.getBlock(lx, ly, lz).getOrdinal();
                    if (ordinal != 0) {
                        TileEntity tile = entry.getValue();
                        if (PaperLib.isPaper() && tile instanceof TileEntityBeacon) {
                            if (beacons == null) {
                                beacons = new ArrayList<>();
                            }
                            beacons.add(tile);
                            BukkitAdapter_1_16_5.removeBeacon(tile, nmsChunk);
                            continue;
                        }
                        nmsChunk.removeTileEntity(tile.getPosition());
                        if (createCopy) {
                            copy.storeTile(tile);
                        }
                    }
                }
            }

            int bitMask = 0;
            synchronized (nmsChunk) {
                ChunkSection[] sections = nmsChunk.getSections();

                for (int layer = 0; layer < 16; layer++) {
                    if (!set.hasSection(layer)) {
                        continue;
                    }

                    bitMask |= 1 << layer;

                    char[] tmp = set.load(layer);
                    char[] setArr = new char[4096];
                    System.arraycopy(tmp, 0, setArr, 0, 4096);

                    // synchronise on internal section to avoid circular locking with a continuing edit if the chunk was
                    // submitted to keep loaded internal chunks to queue target size.
                    synchronized (super.sectionLocks[layer]) {
                        if (createCopy) {
                            char[] tmpLoad = loadPrivately(layer);
                            char[] copyArr = new char[4096];
                            System.arraycopy(tmpLoad, 0, copyArr, 0, 4096);
                            copy.storeSection(layer, copyArr);
                        }

                        ChunkSection newSection;
                        ChunkSection existingSection = sections[layer];
                        // Don't attempt to tick section whilst we're editing
                        if (existingSection != null) {
                            BukkitAdapter_1_16_5.clearCounts(existingSection);
                        }

                        if (existingSection == null) {
                            newSection = BukkitAdapter_1_16_5.newChunkSection(layer, setArr, fastmode, adapter);
                            if (BukkitAdapter_1_16_5.setSectionAtomic(sections, null, newSection, layer)) {
                                updateGet(nmsChunk, sections, newSection, setArr, layer);
                                continue;
                            } else {
                                existingSection = sections[layer];
                                if (existingSection == null) {
                                    LOGGER.error("Skipping invalid null section. chunk:" + chunkX + ","
                                            + chunkZ + " layer: " + layer);
                                    continue;
                                }
                            }
                        }

                        //ensure that the server doesn't try to tick the chunksection while we're editing it (again).
                        DelegateLock lock = BukkitAdapter_1_16_5.applyLock(existingSection);
                        BukkitAdapter_1_16_5.clearCounts(existingSection);
                        synchronized (lock) {
                            lock.untilFree();
                            try {
                                sectionLock.writeLock().lock();
                                if (this.getChunk() != nmsChunk) {
                                    this.nmsChunk = nmsChunk;
                                    this.sections = null;
                                    this.reset();
                                } else if (existingSection != getSections(false)[layer]) {
                                    this.sections[layer] = existingSection;
                                    this.reset();
                                } else if (!Arrays.equals(update(layer, new char[4096], true), loadPrivately(layer))) {
                                    this.reset(layer);
                                } else if (lock.isModified()) {
                                    this.reset(layer);
                                }
                            } finally {
                                sectionLock.writeLock().unlock();
                            }
                            newSection = BukkitAdapter_1_16_5.newChunkSection(
                                    layer,
                                    this::loadPrivately,
                                    setArr,
                                    fastmode,
                                    adapter
                            );
                            if (!BukkitAdapter_1_16_5.setSectionAtomic(sections, existingSection, newSection, layer)) {
                                LOGGER.error("Failed to set chunk section:" + chunkX + "," + chunkZ + " layer: " + layer);
                            } else {
                                updateGet(nmsChunk, sections, newSection, setArr, layer);
                            }
                        }
                    }
                }

                // Biomes
                BiomeType[][] biomes = set.getBiomes();
                if (biomes != null) {
                    // set biomes
                    BiomeStorage currentBiomes = nmsChunk.getBiomeIndex();
                    if (createCopy) {
                        copy.storeBiomes(currentBiomes);
                    }
                    for (int layer = 0; layer < 16; layer++) {
                        if (biomes[layer] == null) {
                            continue;
                        }
                        for (int y = 0, i = 0; y < 4; y++) {
                            for (int z = 0; z < 4; z++) {
                                for (int x = 0; x < 4; x++, i++) {
                                    final BiomeType biome = biomes[layer][i];
                                    if (biome != null) {
                                        BiomeBase nmsBiome = nmsWorld.r().b(IRegistry.ay).get(MinecraftKey.a(biome.getId()));
                                        if (nmsBiome == null) {
                                            throw new NullPointerException("BiomeBase null for BiomeType " + biome.getId());
                                        }
                                        currentBiomes.setBiome(x, (layer << 2) + y, z, nmsBiome);
                                    }
                                }
                            }
                        }
                    }
                }

                Map<HeightMapType, int[]> heightMaps = set.getHeightMaps();
                for (Map.Entry<HeightMapType, int[]> entry : heightMaps.entrySet()) {
                    BukkitGetBlocks_1_16_5.this.setHeightmapToGet(entry.getKey(), entry.getValue());
                }
                BukkitGetBlocks_1_16_5.this.setLightingToGet(set.getLight(), 0, 15);
                BukkitGetBlocks_1_16_5.this.setSkyLightingToGet(set.getSkyLight(), 0, 15);

                Runnable[] syncTasks = null;

                int bx = chunkX << 4;
                int bz = chunkZ << 4;

                // Call beacon deactivate events here synchronously
                // list will be null on spigot, so this is an implicit isPaper check
                if (beacons != null && !beacons.isEmpty()) {
                    final List<TileEntity> finalBeacons = beacons;

                    syncTasks = new Runnable[4];

                    syncTasks[3] = () -> {
                        for (TileEntity beacon : finalBeacons) {
                            ((TileEntityBeacon) beacon).a(SoundEffects.BLOCK_BEACON_DEACTIVATE);
                            new BeaconDeactivatedEvent(CraftBlock.at(beacon.getWorld(), beacon.getPosition())).callEvent();
                        }
                    };
                }

                Set<UUID> entityRemoves = set.getEntityRemoves();
                if (entityRemoves != null && !entityRemoves.isEmpty()) {
                    if (syncTasks == null) {
                        syncTasks = new Runnable[3];
                    }

                    syncTasks[2] = () -> {
                        final List<Entity>[] entities = nmsChunk.getEntitySlices();

                        for (final Collection<Entity> ents : entities) {
                            if (!ents.isEmpty()) {
                                final Iterator<Entity> iter = ents.iterator();
                                while (iter.hasNext()) {
                                    final Entity entity = iter.next();
                                    if (entityRemoves.contains(entity.getUniqueID())) {
                                        if (createCopy) {
                                            copy.storeEntity(entity);
                                        }
                                        iter.remove();
                                        removeEntity(entity);
                                    }
                                }
                            }
                        }
                    };
                }

                Set<CompoundTag> entities = set.getEntities();
                if (entities != null && !entities.isEmpty()) {
                    if (syncTasks == null) {
                        syncTasks = new Runnable[2];
                    }

                    syncTasks[1] = () -> {
                        for (final CompoundTag nativeTag : entities) {
                            final Map<String, Tag> entityTagMap = nativeTag.getValue();
                            final StringTag idTag = (StringTag) entityTagMap.get("Id");
                            final ListTag posTag = (ListTag) entityTagMap.get("Pos");
                            final ListTag rotTag = (ListTag) entityTagMap.get("Rotation");
                            if (idTag == null || posTag == null || rotTag == null) {
                                LOGGER.debug("Unknown entity tag: " + nativeTag);
                                continue;
                            }
                            final double x = posTag.getDouble(0);
                            final double y = posTag.getDouble(1);
                            final double z = posTag.getDouble(2);
                            final float yaw = rotTag.getFloat(0);
                            final float pitch = rotTag.getFloat(1);
                            final String id = idTag.getValue();

                            EntityTypes<?> type = EntityTypes.a(id).orElse(null);
                            if (type != null) {
                                Entity entity = type.a(nmsWorld);
                                if (entity != null) {
                                    final NBTTagCompound tag = (NBTTagCompound) adapter.fromNative(nativeTag);
                                    for (final String name : Constants.NO_COPY_ENTITY_NBT_FIELDS) {
                                        tag.remove(name);
                                    }
                                    entity.load(tag);
                                    entity.setLocation(x, y, z, yaw, pitch);
                                    nmsWorld.addEntity(entity, CreatureSpawnEvent.SpawnReason.CUSTOM);
                                }
                            }
                        }
                    };

                }

                // set tiles
                Map<BlockVector3, CompoundTag> tiles = set.getTiles();
                if (tiles != null && !tiles.isEmpty()) {
                    if (syncTasks == null) {
                        syncTasks = new Runnable[1];
                    }

                    syncTasks[0] = () -> {
                        for (final Map.Entry<BlockVector3, CompoundTag> entry : tiles.entrySet()) {
                            final CompoundTag nativeTag = entry.getValue();
                            final BlockVector3 blockHash = entry.getKey();
                            final int x = blockHash.getX() + bx;
                            final int y = blockHash.getY();
                            final int z = blockHash.getZ() + bz;
                            final BlockPosition pos = new BlockPosition(x, y, z);

                            synchronized (nmsWorld) {
                                TileEntity tileEntity = nmsWorld.getTileEntity(pos);
                                if (tileEntity == null || tileEntity.isRemoved()) {
                                    nmsWorld.removeTileEntity(pos);
                                    tileEntity = nmsWorld.getTileEntity(pos);
                                }
                                if (tileEntity != null) {
                                    final NBTTagCompound tag = (NBTTagCompound) adapter.fromNative(nativeTag);
                                    tag.set("x", NBTTagInt.a(x));
                                    tag.set("y", NBTTagInt.a(y));
                                    tag.set("z", NBTTagInt.a(z));
                                    tileEntity.load(tileEntity.getBlock(), tag);
                                }
                            }
                        }
                    };
                }

                Runnable callback;
                if (bitMask == 0 && biomes == null && !lightUpdate) {
                    callback = null;
                } else {
                    int finalMask = bitMask != 0 ? bitMask : lightUpdate ? set.getBitMask() : 0;
                    boolean finalLightUpdate = lightUpdate;
                    callback = () -> {
                        // Set Modified
                        nmsChunk.d(true); // Set Modified
                        nmsChunk.mustNotSave = false;
                        nmsChunk.markDirty();
                        // send to player
                        if (Settings.settings().LIGHTING.MODE == 0 || !Settings.settings().LIGHTING.DELAY_PACKET_SENDING) {
                            this.send(finalMask, finalLightUpdate);
                        }
                        if (finalizer != null) {
                            finalizer.run();
                        }
                    };
                }
                if (syncTasks != null) {
                    QueueHandler queueHandler = Fawe.instance().getQueueHandler();
                    Runnable[] finalSyncTasks = syncTasks;

                    // Chain the sync tasks and the callback
                    Callable<Future> chain = () -> {
                        try {
                            // Run the sync tasks
                            for (Runnable task : finalSyncTasks) {
                                if (task != null) {
                                    task.run();
                                }
                            }
                            if (callback == null) {
                                if (finalizer != null) {
                                    finalizer.run();
                                }
                                return null;
                            } else {
                                return queueHandler.async(callback, null);
                            }
                        } catch (Throwable e) {
                            e.printStackTrace();
                            throw e;
                        }
                    };
                    //noinspection unchecked - required at compile time
                    return (T) (Future) queueHandler.sync(chain);
                } else {
                    if (callback == null) {
                        if (finalizer != null) {
                            finalizer.run();
                        }
                    } else {
                        callback.run();
                    }
                }
            }
            return null;
        } catch (Throwable e) {
            e.printStackTrace();
            return null;
        } finally {
            forceLoadSections = true;
        }
    }

    private void updateGet(
            Chunk nmsChunk,
            ChunkSection[] chunkSections,
            ChunkSection section,
            char[] arr,
            int layer
    ) {
        try {
            sectionLock.writeLock().lock();
            if (this.getChunk() != nmsChunk) {
                this.nmsChunk = nmsChunk;
                this.sections = new ChunkSection[chunkSections.length];
                System.arraycopy(chunkSections, 0, this.sections, 0, chunkSections.length);
                this.reset();
            }
            if (this.sections == null) {
                this.sections = new ChunkSection[chunkSections.length];
                System.arraycopy(chunkSections, 0, this.sections, 0, chunkSections.length);
            }
            if (this.sections[layer] != section) {
                // Not sure why it's funky, but it's what I did in commit fda7d00747abe97d7891b80ed8bb88d97e1c70d1 and I don't want to touch it >dords
                this.sections[layer] = new ChunkSection[]{section}.clone()[0];
            }
        } finally {
            sectionLock.writeLock().unlock();
        }
        this.blocks[layer] = arr;
    }

    private char[] loadPrivately(int layer) {
        if (super.sections[layer] != null) {
            synchronized (super.sectionLocks[layer]) {
                if (super.sections[layer].isFull() && super.blocks[layer] != null) {
                    char[] blocks = new char[4096];
                    System.arraycopy(super.blocks[layer], 0, blocks, 0, 4096);
                    return blocks;
                }
            }
        }
        return BukkitGetBlocks_1_16_5.this.update(layer, null, true);
    }

    @Override
    public synchronized void send(int mask, boolean lighting) {
        BukkitAdapter_1_16_5.sendChunk(world, chunkX, chunkZ, lighting);
    }

    /**
     * Update a given (nullable) data array to the current data stored in the server's chunk, associated with this
     * {@link BukkitAdapter_1_16_5} instance. Not synchronised to the {@link BukkitAdapter_1_16_5} instance as synchronisation
     * is handled where necessary in the method, and should otherwise be handled correctly by this method's caller.
     *
     * @param layer      layer index
     * @param data       array to be updated/filled with data or null
     * @param aggressive if the cached section array should be re-acquired.
     * @return the given array to be filled with data, or a new array if null is given.
     */
    @Override
    public char[] update(int layer, char[] data, boolean aggressive) {
        ChunkSection section = getSections(aggressive)[layer];
        // Section is null, return empty array
        if (section == null) {
            data = new char[4096];
            Arrays.fill(data, (char) BlockTypesCache.ReservedIDs.AIR);
            return data;
        }
        if (data != null && data.length != 4096) {
            data = new char[4096];
            Arrays.fill(data, (char) BlockTypesCache.ReservedIDs.AIR);
        }
        if (data == null || data == FaweCache.INSTANCE.EMPTY_CHAR_4096) {
            data = new char[4096];
            Arrays.fill(data, (char) BlockTypesCache.ReservedIDs.AIR);
        }
        DelegateLock lock = BukkitAdapter_1_16_5.applyLock(section);
        synchronized (lock) {
            lock.untilFree();
            lock.setModified(false);
            // Efficiently convert ChunkSection to raw data
            try {
                final DataPaletteBlock<IBlockData> blocks = section.getBlocks();
                final DataBits bits = (DataBits) BukkitAdapter_1_16_5.fieldBits.get(blocks);
                final DataPalette<IBlockData> palette = (DataPalette<IBlockData>) BukkitAdapter_1_16_5.fieldPalette.get(blocks);

                final int bitsPerEntry = (int) BukkitAdapter_1_16_5.fieldBitsPerEntry.get(bits);
                final long[] blockStates = bits.a();

                new BitArrayUnstretched(bitsPerEntry, 4096, blockStates).toRaw(data);

                int num_palette;
                if (palette instanceof DataPaletteLinear) {
                    num_palette = ((DataPaletteLinear<IBlockData>) palette).b();
                } else if (palette instanceof DataPaletteHash) {
                    num_palette = ((DataPaletteHash<IBlockData>) palette).b();
                } else {
                    // The section's palette is the global block palette.
                    for (int i = 0; i < 4096; i++) {
                        char paletteVal = data[i];
                        char ordinal = adapter.ibdIDToOrdinal(paletteVal);
                        data[i] = ordinal;
                    }
                    return data;
                }

                char[] paletteToOrdinal = FaweCache.INSTANCE.PALETTE_TO_BLOCK_CHAR.get();
                try {
                    if (num_palette != 1) {
                        for (int i = 0; i < num_palette; i++) {
                            char ordinal = ordinal(palette.a(i), adapter);
                            paletteToOrdinal[i] = ordinal;
                        }
                        for (int i = 0; i < 4096; i++) {
                            char paletteVal = data[i];
                            char val = paletteToOrdinal[paletteVal];
                            if (val == Character.MAX_VALUE) {
                                val = ordinal(palette.a(i), adapter);
                                paletteToOrdinal[i] = val;
                            }
                            data[i] = val;
                        }
                    } else {
                        char ordinal = ordinal(palette.a(0), adapter);
                        Arrays.fill(data, ordinal);
                    }
                } finally {
                    for (int i = 0; i < num_palette; i++) {
                        paletteToOrdinal[i] = Character.MAX_VALUE;
                    }
                }
                return data;
            } catch (IllegalAccessException e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            }
        }
    }

    private char ordinal(IBlockData ibd, FAWE_Spigot_v1_16_R3 adapter) {
        if (ibd == null) {
            return BlockTypesCache.ReservedIDs.AIR;
        } else {
            return adapter.adaptToChar(ibd);
        }
    }

    public ChunkSection[] getSections(boolean force) {
        force &= forceLoadSections;
        sectionLock.readLock().lock();
        ChunkSection[] tmp = sections;
        sectionLock.readLock().unlock();
        if (tmp == null || force) {
            try {
                sectionLock.writeLock().lock();
                tmp = sections;
                if (tmp == null || force) {
                    ChunkSection[] chunkSections = getChunk().getSections();
                    tmp = new ChunkSection[chunkSections.length];
                    System.arraycopy(chunkSections, 0, tmp, 0, chunkSections.length);
                    sections = tmp;
                }
            } finally {
                sectionLock.writeLock().unlock();
            }
        }
        return tmp;
    }

    public Chunk getChunk() {
        Chunk tmp = nmsChunk;
        if (tmp == null) {
            synchronized (this) {
                tmp = nmsChunk;
                if (tmp == null) {
                    nmsChunk = tmp = ensureLoaded(this.world, chunkX, chunkZ);
                }
            }
        }
        return tmp;
    }

    private void fillLightNibble(char[][] light, EnumSkyBlock skyBlock) {
        for (int Y = 0; Y < 16; Y++) {
            if (light[Y] == null) {
                continue;
            }
            SectionPosition sectionPosition = SectionPosition.a(nmsChunk.getPos(), Y);
            NibbleArray nibble = world.getChunkProvider().getLightEngine().a(skyBlock).a(sectionPosition);
            if (nibble == null) {
                byte[] a = new byte[2048];
                Arrays.fill(a, skyBlock == EnumSkyBlock.SKY ? (byte) 15 : (byte) 0);
                nibble = new NibbleArray(a);
                ((LightEngine) world.getChunkProvider().getLightEngine()).a(skyBlock, sectionPosition, nibble, true);
            }
            synchronized (nibble) {
                for (int i = 0; i < 4096; i++) {
                    if (light[Y][i] < 16) {
                        nibble.a(i, light[Y][i]);
                    }
                }
            }
        }
    }

    @Override
    public boolean hasSection(int layer) {
        return getSections(false)[layer] != null;
    }

    @Override
    public synchronized boolean trim(boolean aggressive) {
        skyLight = new NibbleArray[16];
        blockLight = new NibbleArray[16];
        if (aggressive) {
            sectionLock.writeLock().lock();
            sections = null;
            nmsChunk = null;
            sectionLock.writeLock().unlock();
            return super.trim(true);
        } else if (sections == null) {
            // don't bother trimming if there are no sections stored.
            return true;
        } else {
            for (int i = 0; i < 16; i++) {
                if (!hasSection(i) || !super.sections[i].isFull()) {
                    continue;
                }
                ChunkSection existing = getSections(true)[i];
                try {
                    final DataPaletteBlock<IBlockData> blocksExisting = existing.getBlocks();

                    final DataPalette<IBlockData> palette = (DataPalette<IBlockData>) BukkitAdapter_1_16_5.fieldPalette.get(
                            blocksExisting);
                    int paletteSize;

                    if (palette instanceof DataPaletteLinear) {
                        paletteSize = ((DataPaletteLinear<IBlockData>) palette).b();
                    } else if (palette instanceof DataPaletteHash) {
                        paletteSize = ((DataPaletteHash<IBlockData>) palette).b();
                    } else {
                        super.trim(false, i);
                        continue;
                    }
                    if (paletteSize == 1) {
                        //If the cached palette size is 1 then no blocks can have been changed i.e. do not need to update these chunks.
                        continue;
                    }
                    super.trim(false, i);
                } catch (IllegalAccessException ignored) {
                    super.trim(false, i);
                }
            }
            return true;
        }
    }

}
