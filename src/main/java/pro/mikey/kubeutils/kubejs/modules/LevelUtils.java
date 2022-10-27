package pro.mikey.kubeutils.kubejs.modules;

import dev.latvian.mods.kubejs.level.ServerLevelJS;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.ConfiguredStructureFeature;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.*;
import java.util.function.Predicate;

public class LevelUtils {
    private final ServerLevel level;

    public LevelUtils(ServerLevelJS level) {
        this.level = level.getMinecraftLevel();
    }

    /**
     * Spawn a structure into the world at the given block position. If the structure can not be found, nothing
     * will happen
     *
     * @param structureFile     the structure file as a string version of the resource location
     * @param spawnLocation     the location you wish spawn the structure at
     */
    public void spawnStructure(String structureFile, BlockPos spawnLocation) {
        ResourceLocation structureLocation = new ResourceLocation(structureFile);

        Optional<StructureTemplate> structureTemplate = level.getServer().getStructureManager().get(structureLocation);
        structureTemplate.ifPresent(e -> e.placeInWorld(level, spawnLocation, spawnLocation, new StructurePlaceSettings(), level.random, Block.UPDATE_ALL));
    }

    /**
     * Find entities within a radius based on an entity type
     *
     * @param entityId      the entity resource location (id)
     * @param start         the starting position to build the bounding box from
     * @param range         the range to select entities within
     *
     * @return the list of entities found.
     */
    public List<LivingEntity> findEntitiesWithinRadius(ResourceLocation entityId, BlockPos start, int range) {
        AABB boundingBox = new AABB(start).inflate(range);

        List<LivingEntity> entities = new ArrayList<>();
        for (Entity current : level.getEntities().getAll()) {
            if (!(current instanceof LivingEntity)) {
                continue;
            }

            ResourceLocation registryName = current.getType().getRegistryName();
            if (registryName == null || !registryName.equals(entityId)) {
                continue;
            }

            if (boundingBox.contains(current.position())) {
                entities.add((LivingEntity) current);
            }
        }

        return entities;
    }

    /**
     * Creates a bounding box in a radius from a starting position. Then searches that bounding box for a specific block returning
     * as quickly as possible. Provide absolute to validate absolute state instead of just a block check
     *
     * @param block         the block state you wish to check for (or block when absolute = false)
     * @param start         the starting pos to build the radius around
     * @param range         the range
     * @param absolute      to check for the state or the block
     *
     * @return the position of the found block or null when not found
     */
    @Nullable
    public List<BlockPos> findBlockWithinRadius(BlockState block, BlockPos start, int range, boolean absolute) {
        Iterator<BlockPos> iterator = BlockPos.betweenClosedStream(new BoundingBox(start).inflatedBy(range)).iterator();

        List<BlockPos> positions = new ArrayList<>();
        while (iterator.hasNext()) {
            var current = iterator.next();
            if (!absolute && level.getBlockState(current).getBlock() == block.getBlock()) {
                positions.add(current.immutable());
            } else if (absolute && level.getBlockState(current) == block) {
                positions.add(current.immutable());
            }
        }

        return positions;
    }

    /**
     * Generate a random location as a {@link BlockPos} at within two given bounds.
     *
     * @param playerPos     the players position
     * @param min           the min range of the bounds
     * @param max           the max range of the bounds
     *
     * @return the new generated blockpos location
     */
    public BlockPos getRandomLocation(BlockPos playerPos, int min, int max) {
        var randomSource = level.random;

        var insideBox = new BoundingBox(playerPos).inflatedBy(min);
        var outsideBox = new BoundingBox(playerPos).inflatedBy(max);

        var xRandom = Mth.randomBetween(randomSource, outsideBox.minX(), outsideBox.maxX());
        var yRandom = Mth.randomBetween(randomSource, outsideBox.minY(), outsideBox.maxY());
        var zRandom = Mth.randomBetween(randomSource, outsideBox.minZ(), outsideBox.maxZ());

        var tries = 0;
        while (tries < 50) {
            var newPos = new Vec3i(
                    xRandom,
                    Mth.clamp(yRandom, level.getMinBuildHeight(), level.getMaxBuildHeight()),
                    zRandom
            );

            if (!insideBox.isInside(newPos)) {
                return new BlockPos(newPos.getX(), newPos.getY(), newPos.getZ());
            } else {
                tries ++;
            }
        }

        return playerPos.offset(xRandom, yRandom, zRandom);
    }

    /**
     * Extremely specific method that can find a group of blocks using a predicate to validate that the group of blocks are
     * all compiling with the predicate before returning.
     *
     * @param startingPos       the starting block location
     * @param range             the size of the box to create and validate within
     * @param validator         the passing validator for each block found in the ground
     * @param belowValidator    when set, the block below the center will also have to pass a validator
     *
     * @return a list of block locations when all locations pass the predicate
     */
    public List<BlockPos> seekCollectionOfBlocks(BlockPos startingPos, int range, Predicate<BlockPos> validator, @Nullable Predicate<BlockPos> belowValidator) {
        BoundingBox box = new BoundingBox(startingPos).inflatedBy(range);

        List<BlockPos> blockPosStream = BlockPos.betweenClosedStream(box).map(BlockPos::immutable).toList();
        if (!blockPosStream.stream().allMatch(validator)) {
            return new ArrayList<>();
        }

        if (belowValidator != null) {
            BlockPos bottom = new BlockPos(box.minX() + (box.maxX() - box.minX() + 1) / 2, box.minY() - 1, box.minZ() + (box.maxZ() - box.minZ() + 1) / 2);
            if (!belowValidator.test(bottom)) {
                return new ArrayList<>();
            }
        }

        return blockPosStream;
    }

    /**
     * Attempts to check if a specific structure is at a specified block location using a structures resource location.
     *
     * @param pos           the block position you want to check against
     * @param structureId   the structure id of the structure you need to check for
     *
     * @return if the structure is there.
     */
    public boolean isStructureAtLocation(BlockPos pos, ResourceLocation structureId) {
        ConfiguredStructureFeature<?, ?> configuredStructureFeature = level.getServer().registryAccess().registryOrThrow(Registry.CONFIGURED_STRUCTURE_FEATURE_REGISTRY).get(structureId);
        if (configuredStructureFeature == null) {
            return false;
        }
        return level.structureFeatureManager().getStructureAt(pos, configuredStructureFeature).isValid();
    }

    /**
     * Gets all the structures at a given block location
     *
     * @param pos   the block location you want to check
     *
     * @return a list (set) of the structures at that location
     */
    public Set<ConfiguredStructureFeature<?, ?>> getStructuresAtLocation(BlockPos pos) {
        return level.structureFeatureManager().getAllStructuresAt(pos).keySet();
    }
}
