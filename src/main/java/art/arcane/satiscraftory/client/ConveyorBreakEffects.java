package art.arcane.satiscraftory.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.TerrainParticle;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.extensions.common.IClientBlockExtensions;

public enum ConveyorBreakEffects implements IClientBlockExtensions {
    INSTANCE;

    @Override
    public boolean addDestroyEffects(BlockState state, Level level, BlockPos pos, ParticleEngine particles) {
        if (level instanceof ClientLevel client) {
            // Empty anchor shapes cannot supply the usual particle positions.
            for (int i = 0; i < 12; i++) {
                double x = client.random.nextDouble() - 0.5D;
                double y = client.random.nextDouble() * 0.25D;
                double z = client.random.nextDouble() - 0.5D;
                particles.add(new TerrainParticle(client, pos.getX() + 0.5D + x, pos.getY() + y,
                        pos.getZ() + 0.5D + z, x, 0.15D, z, state, pos));
            }
        }
        return true;
    }
}
