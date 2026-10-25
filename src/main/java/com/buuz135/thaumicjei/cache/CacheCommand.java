package com.buuz135.thaumicjei.cache;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;

import java.util.List;

public final class CacheCommand extends CommandBase {
    @Override public String getName() { return "thaumicjei"; }
    @Override public String getUsage(ICommandSender sender) { return "/thaumicjei status|pause|resume|cancel|rebuild"; }
    @Override public int getRequiredPermissionLevel() { return 0; }
    @Override public void execute(MinecraftServer server, ICommandSender sender, String[] args) {
        String action = args.length == 0 ? "status" : args[0];
        switch (action) {
            case "status" -> { }
            case "pause" -> GenerationManager.pause();
            case "resume" -> GenerationManager.resume();
            case "cancel" -> GenerationManager.cancel();
            case "rebuild" -> GenerationManager.rebuild();
            default -> { sender.sendMessage(new TextComponentString(getUsage(sender))); return; }
        }
        sender.sendMessage(new TextComponentString("ThaumicJEI: " + GenerationManager.status()));
    }
}
