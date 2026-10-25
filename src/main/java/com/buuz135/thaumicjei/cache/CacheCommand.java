package com.buuz135.thaumicjei.cache;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;

import java.util.function.Consumer;
import java.util.function.Supplier;

public final class CacheCommand extends CommandBase {
    private final Consumer<String> dispatch;
    private final Supplier<GenerationStatus> status;

    public CacheCommand() {
        this(CacheCommand::dispatchToManager, GenerationManager::status);
    }

    CacheCommand(Consumer<String> dispatch, Supplier<GenerationStatus> status) {
        this.dispatch = dispatch;
        this.status = status;
    }

    @Override
    public String getName() {
        return "thaumicjei";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/thaumicjei status|pause|resume|cancel|rebuild";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) {
        String action = args.length == 0 ? "status" : args[0];
        switch (action) {
            case "status" -> { }
            case "pause", "resume", "cancel", "rebuild" -> dispatch.accept(action);
            default -> {
                sender.sendMessage(new TextComponentString(getUsage(sender)));
                return;
            }
        }
        sender.sendMessage(new TextComponentString("ThaumicJEI: " + status.get().describe()));
    }

    private static void dispatchToManager(String action) {
        switch (action) {
            case "pause" -> GenerationManager.pause();
            case "resume" -> GenerationManager.resume();
            case "cancel" -> GenerationManager.cancel();
            case "rebuild" -> GenerationManager.rebuild();
            default -> throw new IllegalArgumentException("Unknown cache control action");
        }
    }
}
