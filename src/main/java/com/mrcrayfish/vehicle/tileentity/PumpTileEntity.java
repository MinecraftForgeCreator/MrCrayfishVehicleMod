package com.mrcrayfish.vehicle.tileentity;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.mrcrayfish.vehicle.Config;
import com.mrcrayfish.vehicle.block.FluidPipeBlock;
import com.mrcrayfish.vehicle.block.FluidPumpBlock;
import com.mrcrayfish.vehicle.init.ModBlocks;
import com.mrcrayfish.vehicle.init.ModTileEntities;
import com.mrcrayfish.vehicle.util.FluidUtils;
import net.minecraft.block.BlockState;
import net.minecraft.tileentity.ITickableTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.Direction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler;
import org.apache.commons.lang3.tuple.Pair;

import java.lang.ref.WeakReference;
import java.util.*;

/**
 * Author: MrCrayfish
 */
public class PumpTileEntity extends PipeTileEntity implements ITickableTileEntity
{
    private int lastHandlerIndex;
    private boolean validatedNetwork;
    private Map<BlockPos, PipeNode> fluidNetwork = new HashMap<>();
    private List<Pair<BlockPos, Direction>> fluidHandlers = new ArrayList<>();

    public PumpTileEntity()
    {
        super(ModTileEntities.FLUID_PUMP.get());
    }

    @Override
    public void tick()
    {
        if(this.level != null && !this.level.isClientSide())
        {
            if(!this.validatedNetwork)
            {
                this.validatedNetwork = true;
                this.generatePipeNetwork();
            }

            this.pumpFluid();
        }
    }

    public Map<BlockPos, PipeNode> getFluidNetwork()
    {
        return ImmutableMap.copyOf(this.fluidNetwork);
    }

    public void invalidatePipeNetwork()
    {
        this.validatedNetwork = false;
    }

    private void pumpFluid()
    {
        if(this.fluidHandlers.isEmpty() || this.level == null)
            return;

        List<IFluidHandler> handlers = this.getFluidHandlersOnNetwork(this.level);
        if(handlers.isEmpty())
            return;

        Optional<IFluidHandler> source = this.getSourceFluidHandler(this.level);
        if(!source.isPresent())
            return;

        IFluidHandler sourceHandler = source.get();
        int outputCount = handlers.size();
        int remainingAmount = Math.min(sourceHandler.getFluidInTank(0).getAmount(), Config.SERVER.pumpTransferAmount.get());
        int splitAmount = remainingAmount / outputCount;
        if(splitAmount > 0)
        {
            Iterator<IFluidHandler> it = handlers.listIterator();
            while(it.hasNext())
            {
                int transferredAmount = FluidUtils.transferFluid(sourceHandler, it.next(), splitAmount);
                remainingAmount -= transferredAmount;
                if(transferredAmount < splitAmount)
                {
                    // Remove fluid handler since it's full
                    it.remove();

                    // Adds the remaining fluid to the split amount
                    int deltaAmount = splitAmount - transferredAmount;
                    splitAmount += deltaAmount / handlers.size();
                    remainingAmount += deltaAmount % handlers.size();
                }
            }
        }

        // Ignore distributing if no fluid is remaining
        if(remainingAmount <= 0)
            return;

        // If only one fluid handler left, just transfer the maximum amount of remaining fluid
        if(handlers.size() == 1)
        {
            FluidUtils.transferFluid(sourceHandler, handlers.get(0), remainingAmount);
            return;
        }

        // Distributes the remaining fluid over handlers
        while(remainingAmount > 0 && !handlers.isEmpty())
        {
            int index = this.lastHandlerIndex++ % handlers.size();
            int transferred = FluidUtils.transferFluid(sourceHandler, handlers.get(index), 1);
            remainingAmount -= transferred;
            if(transferred == 0)
            {
                this.lastHandlerIndex--;
                handlers.remove(index);
            }
        }
    }

    // This can probably be optimised...
    private void generatePipeNetwork()
    {
        Preconditions.checkNotNull(this.level);

        // Removes the pump from the old network pipes
        this.removePumpFromPipes();

        this.lastHandlerIndex = 0;
        this.fluidHandlers.clear();
        this.fluidNetwork.clear();

        // Finds all the pipes in the network
        Set<BlockPos> visited = new HashSet<>();
        Queue<BlockPos> queue = new ArrayDeque<>();
        queue.add(this.worldPosition);
        while(!queue.isEmpty())
        {
            BlockPos pos = queue.poll();

            for(Direction direction : Direction.values())
            {
                BlockPos relativePos = pos.relative(direction);
                if(visited.contains(relativePos))
                    continue;

                BlockState selfState = this.level.getBlockState(pos);
                if(selfState.getBlock() instanceof FluidPipeBlock)
                {
                    if(selfState.getValue(FluidPipeBlock.POWERED))
                        continue;

                    if(!selfState.getValue(FluidPipeBlock.CONNECTED_PIPES[direction.get3DDataValue()]))
                        continue;
                }

                BlockState relativeState = this.level.getBlockState(relativePos);
                if(relativeState.getBlock() == ModBlocks.FLUID_PIPE.get())
                {
                    if(relativeState.getValue(FluidPipeBlock.CONNECTED_PIPES[direction.getOpposite().get3DDataValue()]))
                    {
                        visited.add(relativePos);
                        queue.add(relativePos);
                    }
                }
            }
        }

        // Initialise pipe nodes
        visited.forEach(pos -> this.fluidNetwork.put(pos, new PipeNode()));

        // Link pipe nodes
        this.fluidNetwork.forEach((pos, node) ->
        {
            BlockState state = this.level.getBlockState(pos);
            for(Direction direction : Direction.values())
            {
                if(state.getValue(FluidPipeBlock.CONNECTED_PIPES[direction.get3DDataValue()]))
                {
                    TileEntity selfTileEntity = this.level.getBlockEntity(pos);
                    if(selfTileEntity instanceof PipeTileEntity)
                    {
                        PipeTileEntity pipeTileEntity = (PipeTileEntity) selfTileEntity;
                        pipeTileEntity.addPump(this.worldPosition);
                        node.tileEntity = new WeakReference<>(pipeTileEntity);
                    }

                    if(state.getValue(FluidPipeBlock.POWERED))
                        continue;

                    BlockPos relativePos = pos.relative(direction);
                    TileEntity relativeTileEntity = this.level.getBlockEntity(relativePos);
                    if(relativeTileEntity != null && relativeTileEntity.getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, direction.getOpposite()).isPresent())
                    {
                        this.fluidHandlers.add(Pair.of(relativePos, direction.getOpposite()));
                    }
                }
            }
        });

        System.out.println("Generated fluid network. Found " + this.fluidNetwork.size() + " pipes and " + this.fluidHandlers.size() + " fluid handlers!");
    }

    public void removePumpFromPipes()
    {
        this.fluidNetwork.forEach((pos, node) ->
        {
            PipeTileEntity tileEntity = node.tileEntity.get();
            if(tileEntity != null)
            {
                tileEntity.removePump(this.worldPosition);
            }
        });
    }

    public List<IFluidHandler> getFluidHandlersOnNetwork(World world)
    {
        List<IFluidHandler> handlers = new ArrayList<>();
        this.fluidHandlers.forEach(pair ->
        {
            if(world.isLoaded(pair.getLeft()))
            {
                TileEntity tileEntity = world.getBlockEntity(pair.getLeft());
                if(tileEntity != null)
                {
                    LazyOptional<IFluidHandler> lazyOptional = tileEntity.getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, pair.getRight());
                    if(lazyOptional.isPresent())
                    {
                        Optional<IFluidHandler> handler = lazyOptional.resolve();
                        handler.ifPresent(handlers::add);
                    }
                }
            }
        });
        return handlers;
    }

    public Optional<IFluidHandler> getSourceFluidHandler(World world)
    {
        Direction direction = this.getBlockState().getValue(FluidPumpBlock.DIRECTION);
        TileEntity tileEntity = world.getBlockEntity(this.worldPosition.relative(direction.getOpposite()));
        if(tileEntity != null)
        {
            LazyOptional<IFluidHandler> lazyOptional = tileEntity.getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, direction);
            if(lazyOptional.isPresent())
            {
                return lazyOptional.resolve();
            }
        }
        return Optional.empty();
    }

    private static class PipeNode
    {
        // There is a finite amount of possible vertices
        private WeakReference<PipeTileEntity> tileEntity;
    }
}