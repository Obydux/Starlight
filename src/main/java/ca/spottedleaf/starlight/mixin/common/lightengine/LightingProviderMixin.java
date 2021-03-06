package ca.spottedleaf.starlight.mixin.common.lightengine;

import ca.spottedleaf.starlight.common.light.StarLightInterface;
import ca.spottedleaf.starlight.common.light.StarLightLightingProvider;
import ca.spottedleaf.starlight.common.util.CoordinateUtils;
import ca.spottedleaf.starlight.common.chunk.ExtendedChunk;
import ca.spottedleaf.starlight.common.light.SWMRNibbleArray;
import ca.spottedleaf.starlight.common.light.StarLightEngine;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.shorts.ShortOpenHashSet;
import net.minecraft.server.world.ServerLightingProvider;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.LightType;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkNibbleArray;
import net.minecraft.world.chunk.ChunkProvider;
import net.minecraft.world.chunk.light.ChunkLightingView;
import net.minecraft.world.chunk.light.LightingProvider;
import net.minecraft.world.chunk.light.LightingView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.Iterator;

@Mixin(LightingProvider.class)
public abstract class LightingProviderMixin implements LightingView, StarLightLightingProvider {

    @Unique
    protected StarLightInterface lightEngine;

    @Override
    public final StarLightInterface getLightEngine() {
        return this.lightEngine;
    }

    /**
     *
     * TODO since this is a constructor inject, check on update for new constructors
     */
    @Inject(
            method = "<init>", at = @At("TAIL")
    )
    public void construct(final ChunkProvider chunkProvider, final boolean hasBlockLight, final boolean hasSkyLight,
                          final CallbackInfo ci) {
        this.lightEngine = new StarLightInterface(chunkProvider, hasSkyLight, hasBlockLight);
    }

    /**
     * @reason Route to new light engine
     * @author Spottedleaf
     */
    @Overwrite
    public void checkBlock(final BlockPos pos) {
        this.lightEngine.blockChange(pos.toImmutable());
    }

    /**
     * @reason Avoid messing with vanilla light engine state
     * @author Spottedleaf
     */
    @Overwrite
    public void addLightSource(final BlockPos pos, final int level) {
        // this light engine only reads levels from blocks, so this is a no-op
    }

    /**
     * @reason Route to new light engine
     * @author Spottedleaf
     */
    @Overwrite
    public boolean hasUpdates() {
        // route to new light engine
        return this.lightEngine.hasUpdates();
    }

    /**
     * @reason Hook into new light engine for light updates
     * @author Spottedleaf
     */
    @Overwrite
    public int doLightUpdates(final int maxUpdateCount, final boolean doSkylight, final boolean skipEdgeLightPropagation) {
        // replace impl
        if ((Object)this instanceof ServerLightingProvider) {
            // serverside
            final boolean hadUpdates = this.hasUpdates();
            this.lightEngine.propagateChanges();
            return hadUpdates ? 1 : 0;
        } else {
            // clientside
            final boolean hadUpdates = this.hasUpdates() || !this.queuedChunkLoads.isEmpty();
            for (final Iterator<Long2ObjectMap.Entry<Boolean[]>> iterator = this.queuedChunkLoads.long2ObjectEntrySet().fastIterator(); iterator.hasNext();) {
                final Long2ObjectMap.Entry<Boolean[]> entry = iterator.next();
                final long coordinate = entry.getLongKey();
                final Boolean[] emptiness = entry.getValue();
                iterator.remove();

                this.lightEngine.loadInChunk(CoordinateUtils.getChunkX(coordinate), CoordinateUtils.getChunkZ(coordinate), emptiness);
            }
            for (final Iterator<Long2ObjectMap.Entry<ShortOpenHashSet>> iterator = this.queuedEdgeChecksSky.long2ObjectEntrySet().fastIterator(); iterator.hasNext();) {
                final Long2ObjectMap.Entry<ShortOpenHashSet> entry = iterator.next();
                final long coordinate = entry.getLongKey();
                final ShortOpenHashSet sections = entry.getValue();
                iterator.remove();

                this.lightEngine.checkSkyEdges(CoordinateUtils.getChunkX(coordinate), CoordinateUtils.getChunkZ(coordinate), sections);
            }
            for (final Iterator<Long2ObjectMap.Entry<ShortOpenHashSet>> iterator = this.queuedEdgeChecksBlock.long2ObjectEntrySet().fastIterator(); iterator.hasNext();) {
                final Long2ObjectMap.Entry<ShortOpenHashSet> entry = iterator.next();
                final long coordinate = entry.getLongKey();
                final ShortOpenHashSet sections = entry.getValue();
                iterator.remove();

                this.lightEngine.checkBlockEdges(CoordinateUtils.getChunkX(coordinate), CoordinateUtils.getChunkZ(coordinate), sections);
            }

            this.lightEngine.propagateChanges();
            return hadUpdates ? 1 : 0;
        }
    }

    /**
     * @reason New light engine hook for handling empty section changes
     * @author Spottedleaf
     */
    @Overwrite
    public void setSectionStatus(final ChunkSectionPos pos, final boolean notReady) {
        this.lightEngine.sectionChange(pos, notReady);
    }

    @Unique
    protected final Long2ObjectOpenHashMap<SWMRNibbleArray[]> blockLight = new Long2ObjectOpenHashMap<>();

    @Unique
    protected final Long2ObjectOpenHashMap<SWMRNibbleArray[]> skyLight = new Long2ObjectOpenHashMap<>();

    @Unique
    protected final Long2ObjectOpenHashMap<Boolean[]> queuedChunkLoads = new Long2ObjectOpenHashMap<>();

    @Unique
    protected final Long2ObjectOpenHashMap<ShortOpenHashSet> queuedEdgeChecksBlock = new Long2ObjectOpenHashMap<>();

    @Unique
    protected final Long2ObjectOpenHashMap<ShortOpenHashSet> queuedEdgeChecksSky = new Long2ObjectOpenHashMap<>();

    /**
     * @reason Replace hook clientside for enabling light data, we use it for loading in light data previously queued
     * @author Spottedleaf
     */
    @Overwrite
    public void setColumnEnabled(final ChunkPos pos, final boolean lightEnabled) {
        final Chunk chunk = this.getLightEngine().getAnyChunkNow(pos.x, pos.z);
        if (chunk != null) {
            final SWMRNibbleArray[] blockNibbles = this.blockLight.get(CoordinateUtils.getChunkKey(pos));
            final SWMRNibbleArray[] skyNibbles = this.skyLight.get(CoordinateUtils.getChunkKey(pos));
            if (blockNibbles != null) {
                ((ExtendedChunk)chunk).setBlockNibbles(blockNibbles);
            }
            if (skyNibbles != null) {
                ((ExtendedChunk)chunk).setSkyNibbles(skyNibbles);
            }

            this.queuedChunkLoads.put(CoordinateUtils.getChunkKey(pos), StarLightEngine.getEmptySectionsForChunk(chunk));
        } else if (!lightEnabled) {
            this.blockLight.remove(CoordinateUtils.getChunkKey(pos));
            this.skyLight.remove(CoordinateUtils.getChunkKey(pos));
            this.queuedChunkLoads.remove(CoordinateUtils.getChunkKey(pos));
            this.queuedEdgeChecksBlock.remove(CoordinateUtils.getChunkKey(pos));
            this.queuedEdgeChecksSky.remove(CoordinateUtils.getChunkKey(pos));
        }
    }

    /**
     * @reason Replace light views with our own that hook into the new light engine instead of vanilla's
     * @author Spottedleaf
     */
    @Overwrite
    public ChunkLightingView get(final LightType lightType) {
        return lightType == LightType.BLOCK ? this.lightEngine.getBlockReader() : this.lightEngine.getSkyReader();
    }

    /**
     * @reason Data is loaded in differently on the client
     * @author Spottedleaf
     */
    @Overwrite
    public void enqueueSectionData(final LightType lightType, final ChunkSectionPos pos, final ChunkNibbleArray nibble,
                                   final boolean trustEdges) {
        // data storage changed with new light impl
        final Chunk chunk = this.getLightEngine().getAnyChunkNow(pos.getX(), pos.getZ());
        switch (lightType) {
            case BLOCK: {
                final SWMRNibbleArray[] blockNibbles = this.blockLight.computeIfAbsent(CoordinateUtils.getChunkKey(pos), (final long keyInMap) -> {
                    return StarLightEngine.getFilledEmptyLight();
                });

                final SWMRNibbleArray replacement;
                if (nibble == null) {
                    replacement = new SWMRNibbleArray(null, true);
                } else if (nibble.isUninitialized()) {
                    replacement = new SWMRNibbleArray();
                } else {
                    replacement = new SWMRNibbleArray(nibble.asByteArray().clone()); // make sure we don't write to the parameter later
                }

                blockNibbles[pos.getY() + 1] = replacement;

                if (chunk != null) {
                    ((ExtendedChunk)chunk).setBlockNibbles(blockNibbles);
                    this.lightEngine.getLightAccess().onLightUpdate(LightType.BLOCK, pos);
                }
                break;
            }
            case SKY: {
                final SWMRNibbleArray[] skyNibbles = this.skyLight.computeIfAbsent(CoordinateUtils.getChunkKey(pos), (final long keyInMap) -> {
                    return StarLightEngine.getFilledEmptyLight();
                });

                final SWMRNibbleArray replacement;
                if (nibble == null) {
                    replacement = new SWMRNibbleArray(null, true);
                } else if (nibble.isUninitialized()) {
                    replacement = new SWMRNibbleArray();
                } else {
                    replacement = new SWMRNibbleArray(nibble.asByteArray().clone()); // make sure we don't write to the parameter later
                }

                skyNibbles[pos.getY() + 1] = replacement;

                if (chunk != null) {
                    ((ExtendedChunk)chunk).setSkyNibbles(skyNibbles);
                    this.lightEngine.getLightAccess().onLightUpdate(LightType.SKY, pos);
                }
                break;
            }
        }
    }

    /**
     * @reason Avoid messing with the vanilla light engine state
     * @author Spottedleaf
     */
    @Overwrite
    public void setRetainData(final ChunkPos pos, final boolean retainData) {
        // not used by new light impl
    }

    /**
     * @reason Need to use our own hooks for retrieving light data
     * @author Spottedleaf
     */
    @Overwrite
    public int getLight(final BlockPos pos, final int ambientDarkness) {
        // need to use new light hooks for this
        final int sky = this.lightEngine.getSkyReader().getLightLevel(pos) - ambientDarkness;
        final int block = this.lightEngine.getBlockReader().getLightLevel(pos);
        return Math.max(sky, block);
    }
}
