package com.sk89q.worldedit.bukkit.adapter.impl.fawe;

import com.fastasyncworldedit.core.configuration.Settings;
import com.fastasyncworldedit.core.extent.processor.ProcessorScope;
import com.fastasyncworldedit.core.queue.IBatchProcessor;
import com.fastasyncworldedit.core.queue.IChunk;
import com.fastasyncworldedit.core.queue.IChunkGet;
import com.fastasyncworldedit.core.queue.IChunkSet;
import com.fastasyncworldedit.core.registry.state.PropertyKey;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockTypes;
import com.sk89q.worldedit.world.block.BlockTypesCache;
import net.minecraft.server.v1_15_R1.BlockPosition;
import net.minecraft.server.v1_15_R1.FluidType;
import net.minecraft.server.v1_15_R1.FluidTypes;
import net.minecraft.server.v1_15_R1.TickListPriority;
import net.minecraft.server.v1_15_R1.WorldServer;

import javax.annotation.Nullable;

public class PostProcessor_1_15_2 implements IBatchProcessor {

    @Override
    public IChunkSet processSet(final IChunk chunk, final IChunkGet get, final IChunkSet set) {
        return set;
    }

    @SuppressWarnings("deprecation")
    @Override
    public void postProcess(final IChunk chunk, final IChunkGet iChunkGet, final IChunkSet iChunkSet) {
        boolean tickFluid = Settings.settings().EXPERIMENTAL.ALLOW_TICK_FLUIDS;
        // The PostProcessor shouldn't be added, but just in case
        if (!tickFluid) {
            return;
        }
        BukkitGetBlocks_1_15_2_Copy getBlocks = (BukkitGetBlocks_1_15_2_Copy) iChunkGet;
        layer:
        for (int layer = iChunkSet.getMinSectionPosition(); layer <= iChunkSet.getMaxSectionPosition(); layer++) {
            char[] set = iChunkSet.loadIfPresent(layer);
            if (set == null) {
                // No edit means no need to process
                continue;
            }
            char[] get = null;
            for (int i = 0; i < 4096; i++) {
                char ordinal = set[i];
                char replacedOrdinal = BlockTypesCache.ReservedIDs.__RESERVED__;
                boolean fromGet = false; // Used for liquids
                if (ordinal == BlockTypesCache.ReservedIDs.__RESERVED__) {
                    if (get == null) {
                        get = getBlocks.load(layer);
                    }
                    // If this is null, then it's because we're loading a layer in the range of 0->15, but blocks aren't
                    // actually being set
                    if (get == null) {
                        continue layer;
                    }
                    fromGet = true;
                    ordinal = replacedOrdinal = get[i];
                }
                if (ordinal == BlockTypesCache.ReservedIDs.__RESERVED__) {
                    continue;
                } else if (!fromGet) { // if fromGet, don't do the same again
                    if (get == null) {
                        get = getBlocks.load(layer);
                    }
                    replacedOrdinal = get[i];
                }
                boolean ticking = BlockTypesCache.ticking[ordinal];
                boolean replacedWasTicking = BlockTypesCache.ticking[replacedOrdinal];
                boolean replacedWasLiquid = false;
                BlockState replacedState = null;
                if (!ticking) {
                    // If the block being replaced was not ticking, it cannot be a liquid
                    if (!replacedWasTicking) {
                        continue;
                    }
                    // If the block being replaced is not fluid, we do not need to worry
                    if (!(replacedWasLiquid =
                            (replacedState = BlockState.getFromOrdinal(replacedOrdinal)).getMaterial().isLiquid())) {
                        continue;
                    }
                }
                BlockState state = BlockState.getFromOrdinal(ordinal);
                boolean liquid = state.getMaterial().isLiquid();
                int x = i & 15;
                int y = (i >> 8) & 15;
                int z = (i >> 4) & 15;
                BlockPosition position = new BlockPosition((chunk.getX() << 4) + x, (layer << 4) + y, (chunk.getZ() << 4) + z);
                if (liquid || replacedWasLiquid) {
                    if (liquid) {
                        addFluid(getBlocks.world, state, position);
                        continue;
                    }
                    // If the replaced fluid (is?) adjacent to water. Do not bother to check adjacent chunks(sections) as this
                    // may be time consuming. Chances are any fluid blocks in adjacent chunks are being replaced or will end up
                    // being ticked anyway. We only need it to be "hit" once.
                    if (!wasAdjacentToWater(get, set, i, x, y, z)) {
                        continue;
                    }
                    addFluid(getBlocks.world, replacedState, position);
                }
            }
        }
    }

    @Nullable
    @Override
    public Extent construct(final Extent child) {
        throw new UnsupportedOperationException("Processing only");
    }

    @Override
    public ProcessorScope getScope() {
        return ProcessorScope.READING_SET_BLOCKS;
    }

    private boolean wasAdjacentToWater(char[] get, char[] set, int i, int x, int y, int z) {
        if (set == null || get == null) {
            return false;
        }
        char ordinal;
        char reserved = BlockTypesCache.ReservedIDs.__RESERVED__;
        if (x > 0 && set[i - 1] != reserved) {
            if (BlockTypesCache.ticking[(ordinal = get[i - 1])] && isFluid(ordinal)) {
                return true;
            }
        }
        if (x < 15 && set[i + 1] != reserved) {
            if (BlockTypesCache.ticking[(ordinal = get[i + 1])] && isFluid(ordinal)) {
                return true;
            }
        }
        if (z > 0 && set[i - 16] != reserved) {
            if (BlockTypesCache.ticking[(ordinal = get[i - 16])] && isFluid(ordinal)) {
                return true;
            }
        }
        if (z < 15 && set[i + 16] != reserved) {
            if (BlockTypesCache.ticking[(ordinal = get[i + 16])] && isFluid(ordinal)) {
                return true;
            }
        }
        if (y > 0 && set[i - 256] != reserved) {
            if (BlockTypesCache.ticking[(ordinal = get[i - 256])] && isFluid(ordinal)) {
                return true;
            }
        }
        if (y < 15 && set[i + 256] != reserved) {
            return BlockTypesCache.ticking[(ordinal = get[i + 256])] && isFluid(ordinal);
        }
        return false;
    }

    @SuppressWarnings("deprecation")
    private boolean isFluid(char ordinal) {
        return BlockState.getFromOrdinal(ordinal).getMaterial().isLiquid();
    }

    @SuppressWarnings("deprecation")
    private void addFluid(final WorldServer worldServer, final BlockState replacedState, final BlockPosition position) {
        FluidType type;
        if (replacedState.getBlockType() == BlockTypes.LAVA) {
            type = (int) replacedState.getState(PropertyKey.LEVEL) == 0 ? FluidTypes.LAVA : FluidTypes.FLOWING_LAVA;
        } else {
            type = (int) replacedState.getState(PropertyKey.LEVEL) == 0 ? FluidTypes.WATER : FluidTypes.FLOWING_WATER;
        }
        worldServer.getFluidTickList().schedule(
                position,
                type,
                type.a(worldServer),
                TickListPriority.NORMAL
        );
    }

}
