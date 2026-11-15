package art.arcane.satiscraftory.block.entity;

import art.arcane.satiscraftory.Satiscraftory;
import art.arcane.satiscraftory.block.ConveyorBlock;
import art.arcane.satiscraftory.block.entity.ConveyorPrediction.RenderItem;
import art.arcane.satiscraftory.data.route.BeltHitbox;
import art.arcane.satiscraftory.data.route.BeltRoute;
import art.arcane.satiscraftory.data.route.BeltRouteMode;
import art.arcane.satiscraftory.interaction.BeltTargeting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.items.wrapper.PlayerMainInvWrapper;

import javax.annotation.Nullable;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ConveyorBlockEntity extends BlockEntity {
    private static final double MIN_CARGO_SPACING = 0.75D;
    private static final int LEGACY_SLOTS_PER_BLOCK = 3;
    private static final int DEFAULT_TRAVEL_TICKS_PER_BLOCK = 60;
    private static final double CLIENT_POSITION_CORRECTION_GAIN = 0.25D;
    private static final double VISUAL_RANGE_BLOCKS = 16.0D;

    private static final String BELT_LENGTH_BLOCKS_TAG = "belt_length_blocks";
    private static final String TRAVEL_TICKS_PER_BLOCK_TAG = "travel_ticks_per_block";
    private static final String SYNC_REVISION_TAG = "sync_revision";
    private static final String PICKED_UP_ITEMS_TAG = "picked_up_items";

    private final ConveyorEndpoints endpoints = new ConveyorEndpoints(this);
    private int beltLengthBlocks = 1;
    private int travelTicksPerBlock = DEFAULT_TRAVEL_TICKS_PER_BLOCK;

    private final ConveyorBuffer buffer = new ConveyorBuffer(1, this::markDirtyForSync);

    private LazyOptional<IItemHandler> unsidedCapability = LazyOptional.empty();
    private final Map<Direction, LazyOptional<IItemHandler>> sidedCapabilities = new EnumMap<>(Direction.class);

    private final ConveyorTransport transport = new ConveyorTransport(this, buffer);
    private final ConveyorVisuals visuals = new ConveyorVisuals(this);
    private final Set<Long> pickedUpItems = new HashSet<>();
    private long lastSyncPacketGameTime = Long.MIN_VALUE;
    private long syncRevision;
    private boolean needsSync;
    private boolean urgentSyncPending;
    @Nullable
    private MiningImpact miningImpact;

    public ConveyorBlockEntity(BlockPos pos, BlockState blockState) {
        super(Satiscraftory.CONVEYOR_BLOCK_ENTITY.get(), pos, blockState);
        travelTicksPerBlock = resolveTravelTicksPerBlock(blockState);
        rebuildCapabilities();
    }

    @Nullable
    public BlockPos getEndPos() {
        return endpoints.getEndPos();
    }

    public Direction getEndFacing() {
        return endpoints.getEndFacing();
    }

    public int getBeltLengthBlocks() {
        return beltLengthBlocks;
    }

    public int getTravelTicksPerBlock() {
        return travelTicksPerBlock;
    }

    @Override
    public void setBlockState(BlockState state) {
        boolean changedTier = getBlockState().getBlock() != state.getBlock() && state.getBlock() instanceof ConveyorBlock;
        super.setBlockState(state);
        if (changedTier) {
            travelTicksPerBlock = resolveTravelTicksPerBlock(state);
            markDirtyForSync();
        }
    }

    public BeltRoute getRoute() {
        return endpoints.getRoute();
    }

    public BeltHitbox getHitbox() {
        return endpoints.getHitbox();
    }

    public BeltRoute getVisualRoute() {
        return endpoints.getVisualRoute();
    }

    private double visualSlotLength() {
        return getVisualRoute().length() / buffer.size();
    }

    public void setEndData(BlockPos endPos, Direction endFacing, BeltRouteMode mode) {
        BlockPos previousEndPos = endpoints.getEndPos();
        endpoints.set(endPos, endFacing, mode);
        BeltTargeting.update(this);
        recalculateLengthAndResize();
        endpoints.updateLinkedEndMarker(previousEndPos);
        setChanged();
        syncToClient();
    }

    public void removeLinkedEndMarker() {
        endpoints.removeLinkedEndMarker();
    }

    public boolean destroyAt(ServerPlayer player, Vec3 location) {
        MiningImpact previous = miningImpact;
        miningImpact = new MiningImpact(player, location);
        try {
            return player.gameMode.destroyBlock(worldPosition);
        } finally {
            miningImpact = previous;
        }
    }

    @Nullable
    public Vec3 miningImpact(Player player) {
        return miningImpact != null && miningImpact.player() == player ? miningImpact.location() : null;
    }

    private record MiningImpact(Player player, Vec3 location) {}

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        unsidedCapability.invalidate();
        for (LazyOptional<IItemHandler> capability : sidedCapabilities.values()) {
            capability.invalidate();
        }
        sidedCapabilities.clear();
    }

    @Override
    public void reviveCaps() {
        super.reviveCaps();
        rebuildCapabilities();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        BeltTargeting.update(this);
    }

    @Override
    public void onChunkUnloaded() {
        BeltTargeting.remove(this);
        super.onChunkUnloaded();
    }

    @Override
    public void setRemoved() {
        BeltTargeting.remove(this);
        visuals.clear();
        super.setRemoved();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        endpoints.save(tag);
        tag.putInt(BELT_LENGTH_BLOCKS_TAG, beltLengthBlocks);
        buffer.save(tag);
        transport.save(tag);
        tag.putInt(TRAVEL_TICKS_PER_BLOCK_TAG, travelTicksPerBlock);
        tag.putLong(SYNC_REVISION_TAG, syncRevision);
    }

    @Override
    public void load(CompoundTag tag) {
        long incomingRevision = tag.contains(SYNC_REVISION_TAG) ? tag.getLong(SYNC_REVISION_TAG) : 0L;
        boolean isClient = level != null && level.isClientSide;
        if (isClient && !visuals.acceptsSnapshot(incomingRevision)) {
            return;
        }

        super.load(tag);

        if (endpoints.load(tag)) {
            BeltTargeting.update(this);
        }

        beltLengthBlocks = Math.max(1, tag.getInt(BELT_LENGTH_BLOCKS_TAG));
        buffer.load(tag, beltLengthBlocks * LEGACY_SLOTS_PER_BLOCK);
        transport.load(tag);
        travelTicksPerBlock = tag.contains(TRAVEL_TICKS_PER_BLOCK_TAG, Tag.TAG_INT)
                ? Math.max(1, tag.getInt(TRAVEL_TICKS_PER_BLOCK_TAG))
                : resolveTravelTicksPerBlock(getBlockState());
        syncRevision = Math.max(0L, incomingRevision);
        lastSyncPacketGameTime = Long.MIN_VALUE;
        needsSync = false;
        urgentSyncPending = false;

        buffer.normalizeInventory(false);
        recalculateLengthAndResize();
        buffer.normalizeInventory(false);

        buffer.finishLoad();

        if (isClient) {
            buffer.sanitizeClientLoadedState();
            visuals.applySnapshot(level.getGameTime(), syncRevision, tag.getLongArray(PICKED_UP_ITEMS_TAG));
        }

        rebuildCapabilities();
    }

    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = saveWithoutMetadata();
        if (!pickedUpItems.isEmpty()) {
            tag.putLongArray(PICKED_UP_ITEMS_TAG, pickedUpItems.stream().mapToLong(Long::longValue).toArray());
        }
        return tag;
    }

    @Override
    public void handleUpdateTag(CompoundTag tag) {
        load(tag);
    }

    @Override
    public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket packet) {
        CompoundTag tag = packet.getTag();
        if (tag != null) {
            load(tag);
        }
    }

    @Override
    public AABB getRenderBoundingBox() {
        BlockPos endPos = getEndPos();
        if (endPos == null) {
            return super.getRenderBoundingBox();
        }

        int minX = Math.min(worldPosition.getX(), endPos.getX());
        int minY = Math.min(worldPosition.getY(), endPos.getY());
        int minZ = Math.min(worldPosition.getZ(), endPos.getZ());
        int maxX = Math.max(worldPosition.getX(), endPos.getX()) + 1;
        int maxY = Math.max(worldPosition.getY(), endPos.getY()) + 1;
        int maxZ = Math.max(worldPosition.getZ(), endPos.getZ()) + 1;
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ).inflate(2.0D);
    }

    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> cap, @Nullable Direction side) {
        if (cap == ForgeCapabilities.ITEM_HANDLER) {
            if (side == null) {
                return unsidedCapability.cast();
            }
            LazyOptional<IItemHandler> sided = sidedCapabilities.get(side);
            if (sided != null) {
                return sided.cast();
            }
            return unsidedCapability.cast();
        }
        return super.getCapability(cap, side);
    }

    public void dropContents(Level level) {
        if (level.isClientSide) {
            return;
        }
        List<RenderItem> drops = transport.sampleCargo();
        if (drops.isEmpty()) {
            return;
        }

        buffer.clear();
        for (RenderItem drop : drops) {
            Vec3 position = computeVisualPositionForSlotUnits(drop.slotUnits());
            ItemEntity entity = new ItemEntity(level, position.x, position.y, position.z, drop.stack(),
                    0.0D, 0.0D, 0.0D);
            entity.setDefaultPickUpDelay();
            level.addFreshEntity(entity);
        }
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, ConveyorBlockEntity conveyor) {
        conveyor.tickServer(level, pos, state);
    }

    public static void clientTick(Level level, BlockPos pos, BlockState state, ConveyorBlockEntity conveyor) {
        conveyor.visuals.tick(level, state);
    }

    private void tickServer(Level level, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof ConveyorBlock)) {
            return;
        }

        endpoints.validateLinkedEndMarker(level.getGameTime());

        if (recalculateLengthAndResize()) {
            buffer.normalizeInventory(true);
        }

        transport.tick(level);

        if (needsSync && syncDirtyItems()) {
            needsSync = false;
        }
        urgentSyncPending = false;
    }

    List<RenderItem> predictClientRenderItems(long elapsedTicks) {
        return transport.predictCargo(elapsedTicks);
    }

    public boolean pickUpItem(ServerPlayer player, long itemId, BeltTargeting.Ray ray) {
        if (player.level() != level || player.isSpectator() || itemId <= 0L) {
            return false;
        }
        for (int slot = 0; slot < buffer.size(); slot++) {
            if (buffer.itemId(slot) != itemId || buffer.stack(slot).isEmpty()) {
                continue;
            }
            // The display predicts between snapshots. Check a short sweep along this same item's route.
            double lagSlots = clamp(getStepsPerTick() * 4.0D, 0.75D, 3.0D);
            double position = buffer.position(slot);
            double start = Math.max(0.0D, position - lagSlots);
            double end = Math.min(buffer.size(), position + lagSlots);
            for (double sample = start; ; sample = Math.min(end, sample + 0.5D)) {
                Vec3 point = computeVisualPositionForSlotUnits(sample);
                AABB bounds = BeltTargeting.itemBounds(point);
                Vec3 hit = bounds.contains(ray.from()) ? ray.from() : bounds.clip(ray.from(), ray.to()).orElse(null);
                if (hit != null && level.getWorldBorder().isWithinBounds(BlockPos.containing(hit))
                        && level.mayInteract(player, BlockPos.containing(hit))) {
                    Vec3 look = player.getLookAngle();
                    Direction face = Direction.getNearest(-look.x, -look.y, -look.z);
                    var event = ForgeHooks.onRightClickBlock(player, InteractionHand.MAIN_HAND, worldPosition,
                            new BlockHitResult(hit, face, worldPosition, false));
                    if (event.isCanceled() || event.getUseBlock() == Event.Result.DENY
                            || isRemoved() || buffer.itemId(slot) != itemId) {
                        return false;
                    }
                    ItemStack stack = buffer.stack(slot).copyWithCount(1);
                    if (!ItemHandlerHelper.insertItemStacked(new PlayerMainInvWrapper(player.getInventory()), stack, false).isEmpty()) {
                        return false;
                    }
                    pickedUpItems.add(itemId);
                    transport.removeItemAt(slot);
                    return true;
                }
                if (sample == end) {
                    return false;
                }
            }
        }
        return false;
    }

    private boolean recalculateLengthAndResize() {
        BeltRoute route = getEndPos() == null ? null : getRoute();
        int computedLength = route == null ? 1 : route.lengthInBlocks();
        int desiredSlots = route == null ? 1 : Math.max(1, (int) Math.floor(route.length() / MIN_CARGO_SPACING));
        boolean lengthChanged = beltLengthBlocks != computedLength;
        beltLengthBlocks = computedLength;
        return buffer.resizeTo(desiredSlots) || lengthChanged;
    }

    private long getClientSyncIntervalTicks() {
        return Math.max(1L, travelTicksPerBlock / 2L);
    }

    Direction getInputSide() {
        return getStartFacing().getOpposite();
    }

    Direction getOutputSide() {
        return endpoints.getOutputSide();
    }

    void requestUrgentSync() {
        urgentSyncPending = true;
    }

    void markDirtyForSync() {
        setChanged();
        needsSync = true;
    }

    private boolean syncDirtyItems() {
        if (level == null || level.isClientSide) {
            return true;
        }

        long now = level.getGameTime();
        if (!urgentSyncPending && lastSyncPacketGameTime != Long.MIN_VALUE
                && now - lastSyncPacketGameTime < getClientSyncIntervalTicks()) {
            return false;
        }

        syncRevision++;
        lastSyncPacketGameTime = now;
        if (sendUpdateToNearbyPlayers()) {
            pickedUpItems.clear();
            return true;
        }
        return false;
    }

    private void syncToClient() {
        if (level == null || level.isClientSide) {
            return;
        }

        syncRevision++;
        lastSyncPacketGameTime = level.getGameTime();
        sendUpdateToNearbyPlayers();
    }

    boolean isPlayerWithinVisualRange(Level level) {
        Vec3 startPoint = getVisualStartPoint();
        Vec3 endPoint = getVisualEndPoint();
        double maxDistanceSqr = getVisualRangeBlocks() * getVisualRangeBlocks();

        for (Player player : level.players()) {
            if (isEntityWithinVisualRange(player, startPoint, endPoint, maxDistanceSqr)) {
                return true;
            }
        }
        return false;
    }

    private double getVisualRangeBlocks() {
        return VISUAL_RANGE_BLOCKS;
    }

    private boolean sendUpdateToNearbyPlayers() {
        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }

        ClientboundBlockEntityDataPacket packet = getUpdatePacket();
        if (packet == null) {
            return false;
        }

        Vec3 startPoint = getVisualStartPoint();
        Vec3 endPoint = getVisualEndPoint();
        double maxDistanceSqr = getVisualRangeBlocks() * getVisualRangeBlocks();
        boolean sent = false;

        for (ServerPlayer player : serverLevel.players()) {
            if (!isEntityWithinVisualRange(player, startPoint, endPoint, maxDistanceSqr)) {
                continue;
            }
            player.connection.send(packet);
            sent = true;
        }

        return sent;
    }

    private Vec3 getVisualStartPoint() {
        return new Vec3(
                worldPosition.getX() + 0.5D,
                worldPosition.getY() + 0.5D,
                worldPosition.getZ() + 0.5D
        );
    }

    private Vec3 getVisualEndPoint() {
        BlockPos endPos = getEndPos();
        BlockPos endPoint = endPos != null ? endPos : worldPosition.relative(getOutputSide());
        return new Vec3(
                endPoint.getX() + 0.5D,
                endPoint.getY() + 0.5D,
                endPoint.getZ() + 0.5D
        );
    }

    private static boolean isEntityWithinVisualRange(Entity entity, Vec3 startPoint, Vec3 endPoint, double maxDistanceSqr) {
        if (entity == null || !entity.isAlive()) {
            return false;
        }
        return entity.distanceToSqr(startPoint.x, startPoint.y, startPoint.z) <= maxDistanceSqr
                || entity.distanceToSqr(endPoint.x, endPoint.y, endPoint.z) <= maxDistanceSqr;
    }

    @Nullable
    public CargoHit pickDisplayedItem(BeltTargeting.Ray ray, float partialTick) {
        return visuals.pickDisplayedItem(ray, partialTick);
    }

    ConveyorTransport transport() {
        return transport;
    }

    double getStepsPerTick() {
        return transport.getStepsPerTick();
    }

    ConveyorVisuals visuals() {
        return visuals;
    }

    int bufferSize() {
        return buffer.size();
    }

    public record CargoHit(ConveyorBlockEntity belt, long itemId, Vec3 location) {}

    Vec3 computeVisualPositionForSlotUnits(double slotUnits) {
        return deckPosition(slotUnits / buffer.size());
    }

    private Vec3 deckPosition(double progress) {
        return getVisualRoute().position(progress)
                .add(worldPosition.getX(), worldPosition.getY() + BeltRoute.DECK_HEIGHT, worldPosition.getZ());
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    Direction getStartFacing() {
        BlockState state = getBlockState();
        if (state.hasProperty(ConveyorBlock.FACING)) {
            return state.getValue(ConveyorBlock.FACING);
        }
        return Direction.NORTH;
    }

    private static int resolveTravelTicksPerBlock(BlockState state) {
        if (state.getBlock() instanceof ConveyorBlock conveyorBlock) {
            return Math.max(1, conveyorBlock.getTravelTicksPerBlock());
        }
        return DEFAULT_TRAVEL_TICKS_PER_BLOCK;
    }

    private void rebuildCapabilities() {
        if (unsidedCapability.isPresent()) {
            unsidedCapability.invalidate();
        }
        for (LazyOptional<IItemHandler> capability : sidedCapabilities.values()) {
            capability.invalidate();
        }
        sidedCapabilities.clear();

        unsidedCapability = LazyOptional.of(() -> transport.itemHandler(null));
        for (Direction direction : Direction.values()) {
            sidedCapabilities.put(direction, LazyOptional.of(() -> transport.itemHandler(direction)));
        }
    }

    public static final class SplineVisualItemEntity extends ItemEntity {
        private ConveyorBlockEntity conveyor;
        @Nullable
        private ConveyorBlockEntity incomingFrom;
        private double slotsPerIncomingSlot;
        double previousSlotUnits;
        double slotUnits;
        private long motionTick = Long.MIN_VALUE;
        long snapshotOrder;
        int departureTicks;

        SplineVisualItemEntity(ConveyorBlockEntity conveyor, Level level, Vec3 position,
                                       ItemStack stack, double slotUnits) {
            super(Satiscraftory.CONVEYOR_ITEM_VISUAL.get(), level);
            this.conveyor = conveyor;
            this.previousSlotUnits = slotUnits;
            this.slotUnits = slotUnits;
            noPhysics = true;
            setNoGravity(true);
            setNeverPickUp();
            setInvulnerable(true);
            setItem(stack.copy());
            setPos(position.x, position.y, position.z);
            setOldPosAndRot();
        }

        public Vec3 positionOnBelt(float partialTick) {
            return locationOnBelt(partialTick).position();
        }

        public BeltLocation locationOnBelt(float partialTick) {
            double progress = Mth.lerp(partialTick, previousSlotUnits, slotUnits);
            if (progress < 0.0D && incomingFrom != null) {
                return new BeltLocation(incomingFrom, 1.0D + progress / (slotsPerIncomingSlot * incomingFrom.buffer.size()));
            }
            return new BeltLocation(conveyor, progress / conveyor.buffer.size());
        }

        public record BeltLocation(ConveyorBlockEntity belt, double progress) {
            public Vec3 position() {
                return belt.deckPosition(progress);
            }

            public Vec3 direction() {
                return belt.getVisualRoute().surfaceDirection(progress);
            }

            public double slotLength() {
                return belt.visualSlotLength();
            }
        }

        boolean canEnterNextBelt(long now) {
            return slotUnits >= 0.0D && (motionTick != now || previousSlotUnits >= 0.0D);
        }

        void enterBelt(ConveyorBlockEntity next) {
            incomingFrom = conveyor;
            // Negative progress on the new belt measures the distance remaining on the old one.
            slotsPerIncomingSlot = conveyor.visualSlotLength() / next.visualSlotLength();
            previousSlotUnits = (previousSlotUnits - conveyor.buffer.size()) * slotsPerIncomingSlot;
            slotUnits = (slotUnits - conveyor.buffer.size()) * slotsPerIncomingSlot;
            conveyor = next;
        }

        void advanceTowards(double target, double limit, long now, double slotsPerTick, double maxCorrection) {
            if (motionTick != now) {
                setOldPosAndRot();
                previousSlotUnits = slotUnits;
                motionTick = now;
            }

            double forward = slotsPerTick;
            if (previousSlotUnits >= 0.0D) {
                incomingFrom = null;
            } else if (incomingFrom != null) {
                double approachSpeed = incomingFrom.getStepsPerTick() * slotsPerIncomingSlot;
                double timeToEdge = -previousSlotUnits / approachSpeed;
                forward = timeToEdge >= 1.0D ? approachSpeed
                        : -previousSlotUnits + slotsPerTick * (1.0D - timeToEdge);
            }

            // Correct snapshot drift through speed. The queue limit, rather than the snapshot, can stop motion.
            double correction = (target - previousSlotUnits - forward) * CLIENT_POSITION_CORRECTION_GAIN;
            double advance = forward + clamp(correction, -forward * 0.5D, maxCorrection);
            slotUnits = Math.max(previousSlotUnits, Math.min(previousSlotUnits + advance, limit));
            Vec3 position = positionOnBelt(1.0F);
            setPos(position.x, position.y, position.z);
        }

        @Override
        public void tick() {
            // Client-only visual. Belt code owns movement.
        }

        @Override
        public boolean isPickable() {
            return false;
        }
    }
}
