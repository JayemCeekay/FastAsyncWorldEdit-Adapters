package com.sk89q.worldedit.bukkit.adapter.impl.fawe;

import com.destroystokyo.paper.util.maplist.IBlockDataList;
import com.fastasyncworldedit.bukkit.adapter.CachedBukkitAdapter;
import com.fastasyncworldedit.bukkit.adapter.DelegateSemaphore;
import com.fastasyncworldedit.bukkit.adapter.NMSAdapter;
import com.fastasyncworldedit.core.Fawe;
import com.fastasyncworldedit.core.FaweCache;
import com.fastasyncworldedit.core.configuration.Settings;
import com.fastasyncworldedit.core.math.BitArrayUnstretched;
import com.fastasyncworldedit.core.util.MathMan;
import com.fastasyncworldedit.core.util.ReflectionUtils;
import com.fastasyncworldedit.core.util.TaskManager;
import com.mojang.datafixers.util.Either;
import com.sk89q.worldedit.world.biome.BiomeType;
import com.sk89q.worldedit.world.biome.BiomeTypes;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockTypesCache;
import io.papermc.lib.PaperLib;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.core.BlockPosition;
import net.minecraft.core.IRegistry;
import net.minecraft.core.SectionPosition;
import net.minecraft.nbt.GameProfileSerializer;
import net.minecraft.network.protocol.game.PacketPlayOutLightUpdate;
import net.minecraft.network.protocol.game.PacketPlayOutMapChunk;
import net.minecraft.resources.MinecraftKey;
import net.minecraft.server.level.EntityPlayer;
import net.minecraft.server.level.PlayerChunk;
import net.minecraft.server.level.PlayerChunkMap;
import net.minecraft.server.level.WorldServer;
import net.minecraft.util.DataBits;
import net.minecraft.world.level.ChunkCoordIntPair;
import net.minecraft.world.level.GeneratorAccess;
import net.minecraft.world.level.biome.BiomeBase;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ITileEntity;
import net.minecraft.world.level.block.entity.TileEntity;
import net.minecraft.world.level.block.state.IBlockData;
import net.minecraft.world.level.chunk.BiomeStorage;
import net.minecraft.world.level.chunk.Chunk;
import net.minecraft.world.level.chunk.ChunkSection;
import net.minecraft.world.level.chunk.DataPalette;
import net.minecraft.world.level.chunk.DataPaletteBlock;
import net.minecraft.world.level.chunk.DataPaletteHash;
import net.minecraft.world.level.chunk.DataPaletteLinear;
import net.minecraft.world.level.gameevent.GameEventDispatcher;
import net.minecraft.world.level.gameevent.GameEventListener;
import org.bukkit.craftbukkit.v1_17_R1.CraftChunk;
import sun.misc.Unsafe;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import java.util.stream.Stream;

public final class BukkitAdapter_1_17 extends NMSAdapter {

    /*
    NMS fields
    */
    public static final Field fieldBits;
    public static final Field fieldPalette;
    public static final Field fieldSize;

    public static final Field fieldBitsPerEntry;

    private static final Field fieldFluidCount;
    private static final Field fieldTickingBlockCount;
    private static final Field fieldNonEmptyBlockCount;
    private static final Field fieldTickingList;

    private static final Field fieldBiomeArray;

    private static final MethodHandle methodGetVisibleChunk;

    private static final int CHUNKSECTION_BASE;
    private static final int CHUNKSECTION_SHIFT;

    private static final Field fieldLock;
    private static final long fieldLockOffset;

    private static final Field fieldEventDispatcherMap;
    private static final MethodHandle methodremoveTickingBlockEntity;

    private static final Field fieldTileEntityRemoved;

    static {
        try {
            // TODO
            fieldSize = DataPaletteBlock.class.getDeclaredField("l");
            fieldSize.setAccessible(true);
            fieldBits = DataPaletteBlock.class.getDeclaredField("c");
            fieldBits.setAccessible(true);
            fieldPalette = DataPaletteBlock.class.getDeclaredField("k");
            fieldPalette.setAccessible(true);

            fieldBitsPerEntry = DataBits.class.getDeclaredField("c");
            fieldBitsPerEntry.setAccessible(true);

            fieldFluidCount = ChunkSection.class.getDeclaredField("h");
            fieldFluidCount.setAccessible(true);
            fieldTickingBlockCount = ChunkSection.class.getDeclaredField("g");
            fieldTickingBlockCount.setAccessible(true);
            fieldNonEmptyBlockCount = ChunkSection.class.getDeclaredField("f");
            fieldNonEmptyBlockCount.setAccessible(true);
            if (PaperLib.isPaper()) {
                fieldTickingList = ChunkSection.class.getDeclaredField("i");
                fieldTickingList.setAccessible(true);
            } else {
                fieldTickingList = null;
            }

            fieldBiomeArray = BiomeStorage.class.getDeclaredField("f");
            fieldBiomeArray.setAccessible(true);

            Method declaredGetVisibleChunk = PlayerChunkMap.class.getDeclaredMethod("getVisibleChunk", long.class);
            declaredGetVisibleChunk.setAccessible(true);
            methodGetVisibleChunk = MethodHandles.lookup().unreflect(declaredGetVisibleChunk);

            Unsafe unsafe = ReflectionUtils.getUnsafe();
            fieldLock = DataPaletteBlock.class.getDeclaredField("m");
            fieldLockOffset = unsafe.objectFieldOffset(fieldLock);

            fieldEventDispatcherMap = Chunk.class.getDeclaredField("x");
            fieldEventDispatcherMap.setAccessible(true);
            Method removeTickingBlockEntity = Chunk.class.getDeclaredMethod("l", BlockPosition.class);
            removeTickingBlockEntity.setAccessible(true);
            methodremoveTickingBlockEntity = MethodHandles.lookup().unreflect(removeTickingBlockEntity);

            fieldTileEntityRemoved = TileEntity.class.getDeclaredField("p");
            fieldTileEntityRemoved.setAccessible(true);

            CHUNKSECTION_BASE = unsafe.arrayBaseOffset(ChunkSection[].class);
            int scale = unsafe.arrayIndexScale(ChunkSection[].class);
            if ((scale & (scale - 1)) != 0) {
                throw new Error("data type scale not a power of two");
            }
            CHUNKSECTION_SHIFT = 31 - Integer.numberOfLeadingZeros(scale);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable rethrow) {
            rethrow.printStackTrace();
            throw new RuntimeException(rethrow);
        }
    }

    static boolean setSectionAtomic(ChunkSection[] sections, ChunkSection expected, ChunkSection value, int layer) {
        long offset = ((long) layer << CHUNKSECTION_SHIFT) + CHUNKSECTION_BASE;
        if (layer >= 0 && layer < sections.length) {
            return ReflectionUtils.getUnsafe().compareAndSwapObject(sections, offset, expected, value);
        }
        return false;
    }

    static DelegateSemaphore applyLock(ChunkSection section) {
        //todo there has to be a better way to do this. Maybe using a() in DataPaletteBlock which acquires the lock in NMS?
        try {
            synchronized (section) {
                Unsafe unsafe = ReflectionUtils.getUnsafe();
                DataPaletteBlock<IBlockData> blocks = section.getBlocks();
                Semaphore currentLock = (Semaphore) unsafe.getObject(blocks, fieldLockOffset);
                if (currentLock instanceof DelegateSemaphore) {
                    return (DelegateSemaphore) currentLock;
                }
                DelegateSemaphore newLock = new DelegateSemaphore(1, currentLock);
                unsafe.putObject(blocks, fieldLockOffset, newLock);
                return newLock;
            }
        } catch (Throwable e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    public static Chunk ensureLoaded(WorldServer world, int chunkX, int chunkZ) {
        if (!PaperLib.isPaper()) {
            Chunk nmsChunk = world.getChunkProvider().getChunkAt(chunkX, chunkZ, false);
            if (nmsChunk != null) {
                return nmsChunk;
            }
            if (Fawe.isMainThread()) {
                return world.getChunkAt(chunkX, chunkZ);
            }
        } else {
            Chunk nmsChunk = world.getChunkProvider().getChunkAtIfCachedImmediately(chunkX, chunkZ);
            if (nmsChunk != null) {
                return nmsChunk;
            }
            nmsChunk = world.getChunkProvider().getChunkAtIfLoadedImmediately(chunkX, chunkZ);
            if (nmsChunk != null) {
                return nmsChunk;
            }
            // Avoid "async" methods from the main thread.
            if (Fawe.isMainThread()) {
                return world.getChunkAt(chunkX, chunkZ);
            }
            CompletableFuture<org.bukkit.Chunk> future = world.getWorld().getChunkAtAsync(chunkX, chunkZ, true, true);
            try {
                CraftChunk chunk = (CraftChunk) future.get();
                return chunk.getHandle();
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }
        return TaskManager.taskManager().sync(() -> world.getChunkAt(chunkX, chunkZ));
    }

    public static PlayerChunk getPlayerChunk(WorldServer nmsWorld, final int chunkX, final int chunkZ) {
        PlayerChunkMap chunkMap = nmsWorld.getChunkProvider().a;
        try {
            return (PlayerChunk) methodGetVisibleChunk.invoke(chunkMap, ChunkCoordIntPair.pair(chunkX, chunkZ));
        } catch (Throwable thr) {
            throw new RuntimeException(thr);
        }
    }

    public static void sendChunk(WorldServer nmsWorld, int chunkX, int chunkZ, boolean lighting) {
        PlayerChunk playerChunk = getPlayerChunk(nmsWorld, chunkX, chunkZ);
        if (playerChunk == null) {
            return;
        }
        ChunkCoordIntPair chunkCoordIntPair = new ChunkCoordIntPair(chunkX, chunkZ);
        // UNLOADED_CHUNK
        Optional<Chunk> optional = ((Either) playerChunk.a().getNow(PlayerChunk.c)).left();
        if (PaperLib.isPaper()) {
            // getChunkAtIfLoadedImmediately is paper only
            optional = optional.or(() -> Optional.ofNullable(nmsWorld
                    .getChunkProvider()
                    .getChunkAtIfLoadedImmediately(chunkX, chunkZ)));
        }
        if (optional.isEmpty()) {
            return;
        }
        Chunk chunk = optional.get();
        TaskManager.taskManager().task(() -> {
            PacketPlayOutMapChunk chunkPacket = new PacketPlayOutMapChunk(chunk);
            nearbyPlayers(nmsWorld, chunkCoordIntPair).forEach(p -> {
                p.b.sendPacket(chunkPacket);
            });
            if (lighting) {
                //This needs to be true otherwise Minecraft will update lighting from/at the chunk edges (bad)
                boolean trustEdges = true;
                PacketPlayOutLightUpdate packet =
                        new PacketPlayOutLightUpdate(chunkCoordIntPair, nmsWorld.getChunkProvider().getLightEngine(),
                                null, null, trustEdges
                        );
                nearbyPlayers(nmsWorld, chunkCoordIntPair).forEach(p -> {
                    p.b.sendPacket(packet);
                });
            }
        });
    }

    private static Stream<EntityPlayer> nearbyPlayers(WorldServer world, ChunkCoordIntPair chunkCoordIntPair) {
        return world.getChunkProvider().a.a(chunkCoordIntPair, false);
    }

    /*
    NMS conversion
     */
    public static ChunkSection newChunkSection(
            final int layer,
            final char[] blocks,
            boolean fastmode,
            CachedBukkitAdapter adapter
    ) {
        return newChunkSection(layer, null, blocks, fastmode, adapter);
    }

    public static ChunkSection newChunkSection(
            final int layer,
            final Function<Integer, char[]> get,
            char[] set,
            boolean fastmode,
            CachedBukkitAdapter adapter
    ) {
        if (set == null) {
            return newChunkSection(layer);
        }
        final int[] blockToPalette = FaweCache.INSTANCE.BLOCK_TO_PALETTE.get();
        final int[] paletteToBlock = FaweCache.INSTANCE.PALETTE_TO_BLOCK.get();
        final long[] blockStates = FaweCache.INSTANCE.BLOCK_STATES.get();
        final int[] blocksCopy = FaweCache.INSTANCE.SECTION_BLOCKS.get();
        try {
            int num_palette;
            if (get == null) {
                num_palette = createPalette(blockToPalette, paletteToBlock, blocksCopy, set, adapter);
            } else {
                num_palette = createPalette(layer, blockToPalette, paletteToBlock, blocksCopy, get, set, adapter);
            }
            // BlockStates
            int bitsPerEntry = MathMan.log2nlz(num_palette - 1);
            if (Settings.settings().PROTOCOL_SUPPORT_FIX || num_palette != 1) {
                bitsPerEntry = Math.max(bitsPerEntry, 4); // Protocol support breaks <4 bits per entry
            } else {
                bitsPerEntry = Math.max(bitsPerEntry, 1); // For some reason minecraft needs 4096 bits to store 0 entries
            }
            if (bitsPerEntry > 8) {
                bitsPerEntry = MathMan.log2nlz(Block.p.a() - 1);
            }

            final int blocksPerLong = MathMan.floorZero((double) 64 / bitsPerEntry);
            final int blockBitArrayEnd = MathMan.ceilZero((float) 4096 / blocksPerLong);

            if (num_palette == 1) {
                for (int i = 0; i < blockBitArrayEnd; i++) {
                    blockStates[i] = 0;
                }
            } else {
                final BitArrayUnstretched bitArray = new BitArrayUnstretched(bitsPerEntry, 4096, blockStates);
                bitArray.fromRaw(blocksCopy);
            }

            ChunkSection section = newChunkSection(layer);
            // set palette & data bits
            final DataPaletteBlock<IBlockData> dataPaletteBlocks = section.getBlocks();
            // private DataPalette<T> h;
            // protected DataBits a;
            final long[] bits = Arrays.copyOfRange(blockStates, 0, blockBitArrayEnd);
            final DataBits nmsBits = new DataBits(bitsPerEntry, 4096, bits);
            final DataPalette<IBlockData> palette;
            if (bitsPerEntry <= 4) {
                palette = new DataPaletteLinear<>(Block.p, bitsPerEntry, dataPaletteBlocks, GameProfileSerializer::c);
            } else if (bitsPerEntry < 9) {
                palette = new DataPaletteHash<>(
                        Block.p,
                        bitsPerEntry,
                        dataPaletteBlocks,
                        GameProfileSerializer::c,
                        GameProfileSerializer::a
                );
            } else {
                palette = ChunkSection.d;
            }

            // set palette if required
            if (bitsPerEntry < 9) {
                for (int i = 0; i < num_palette; i++) {
                    final int ordinal = paletteToBlock[i];
                    blockToPalette[ordinal] = Integer.MAX_VALUE;
                    final BlockState state = BlockTypesCache.states[ordinal];
                    final IBlockData ibd = ((BlockMaterial_1_17) state.getMaterial()).getState();
                    palette.a(ibd);
                }
            }
            try {
                fieldBits.set(dataPaletteBlocks, nmsBits);
                fieldPalette.set(dataPaletteBlocks, palette);
                fieldSize.set(dataPaletteBlocks, bitsPerEntry);
            } catch (final IllegalAccessException e) {
                throw new RuntimeException(e);
            }

            if (!fastmode) {
                section.recalcBlockCounts();
            }
            return section;
        } catch (final Throwable e) {
            throw e;
        } finally {
            Arrays.fill(blockToPalette, Integer.MAX_VALUE);
            Arrays.fill(paletteToBlock, Integer.MAX_VALUE);
            Arrays.fill(blockStates, 0);
            Arrays.fill(blocksCopy, 0);
        }
    }

    private static ChunkSection newChunkSection(int layer) {
        return new ChunkSection(layer);
    }

    public static void clearCounts(final ChunkSection section) throws IllegalAccessException {
        fieldFluidCount.setShort(section, (short) 0);
        fieldTickingBlockCount.setShort(section, (short) 0);
        fieldNonEmptyBlockCount.setShort(section, (short) 0);
        if (PaperLib.isPaper()) {
            ((IBlockDataList) fieldTickingList.get(section)).clear();
        }
    }

    public static BiomeBase[] getBiomeArray(BiomeStorage storage) {
        try {
            return (BiomeBase[]) fieldBiomeArray.get(storage);
        } catch (IllegalAccessException e) {
            e.printStackTrace();
            return null;
        }
    }

    public static BiomeType adapt(BiomeBase base, GeneratorAccess world) {
        MinecraftKey key = world.t().b(IRegistry.aO).getKey(base);
        if (key == null) {
            return world.t().b(IRegistry.aO).getId(base) == -1 ? BiomeTypes.OCEAN : null;
        }
        return BiomeTypes.get(key.toString().toLowerCase(Locale.ROOT));
    }

    static void removeBeacon(TileEntity beacon, Chunk nmsChunk) {
        try {
            // Do the method ourselves to avoid trying to reflect generic method parameters
            if (nmsChunk.h || nmsChunk.level.isClientSide()) {
                TileEntity tileentity = nmsChunk.l.remove(beacon.getPosition());
                if (tileentity != null) {
                    if (!nmsChunk.level.y) {
                        Block block = beacon.getBlock().getBlock();
                        if (block instanceof ITileEntity) {
                            GameEventListener gameeventlistener = ((ITileEntity) block).a(nmsChunk.level, beacon);
                            if (gameeventlistener != null) {
                                int i = SectionPosition.a(beacon.getPosition().getY());
                                GameEventDispatcher gameeventdispatcher = nmsChunk.a(i);
                                gameeventdispatcher.b(gameeventlistener);
                                if (gameeventdispatcher.a()) {
                                    try {
                                        ((Int2ObjectMap<GameEventDispatcher>) fieldEventDispatcherMap.get(nmsChunk))
                                                .remove(i);
                                    } catch (IllegalAccessException e) {
                                        e.printStackTrace();
                                    }
                                }
                            }
                        }
                    }
                    fieldTileEntityRemoved.set(beacon, true);
                }
            }
            methodremoveTickingBlockEntity.invoke(nmsChunk, beacon.getPosition());
        } catch (Throwable throwable) {
            throwable.printStackTrace();
        }
    }

}
