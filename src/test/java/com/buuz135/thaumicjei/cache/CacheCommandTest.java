package com.buuz135.thaumicjei.cache;

import net.minecraft.command.ICommandSender;
import net.minecraft.util.text.ITextComponent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CacheCommandTest {
    private static void set(String name, Object value) throws Exception {
        var field = GenerationManager.class.getDeclaredField(name); field.setAccessible(true); field.set(null, value);
    }

    @Test
    void commandDispatchesControlActionsAndReportsStatus() throws Exception {
        GenerationManager.cancel();
        set("cancelled", false); set("paused", false); set("failed", false); set("state", "generating");
        List<String> messages = new ArrayList<>();
        var sender = (ICommandSender) Proxy.newProxyInstance(ICommandSender.class.getClassLoader(),
                new Class[]{ICommandSender.class}, (proxy, method, args) -> {
                    if (method.getName().equals("sendMessage")) {
                        messages.add(((ITextComponent) args[0]).getUnformattedText()); return null;
                    }
                    if (!method.getReturnType().isPrimitive()) return null;
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == void.class) return null;
                    return 0;
                });
        var command = new CacheCommand();
        command.execute(null, sender, new String[]{"pause"});
        assertTrue(messages.getLast().contains("paused"));
        command.execute(null, sender, new String[]{"resume"});
        assertTrue(messages.getLast().contains("generating"));
        command.execute(null, sender, new String[0]);
        assertTrue(messages.getLast().contains("ThaumicJEI: generating"));
        command.execute(null, sender, new String[]{"cancel"});
        assertTrue(messages.getLast().contains("cancelled"));
        command.execute(null, sender, new String[]{"unknown"});
        assertTrue(messages.getLast().contains("/thaumicjei status|pause|resume|cancel|rebuild"));
        GenerationManager.cancel();
    }
}
