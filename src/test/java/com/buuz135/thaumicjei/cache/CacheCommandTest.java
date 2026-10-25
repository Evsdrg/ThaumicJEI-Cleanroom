package com.buuz135.thaumicjei.cache;

import net.minecraft.command.ICommandSender;
import net.minecraft.util.text.ITextComponent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CacheCommandTest {
    @Test
    void commandDispatchesControlActionsAndReportsStatus() throws Exception {
        var actions = new ArrayList<String>();
        List<String> messages = new ArrayList<>();
        var sender = (ICommandSender) Proxy.newProxyInstance(ICommandSender.class.getClassLoader(),
                new Class<?>[]{ICommandSender.class}, (proxy, method, args) -> {
                    if (method.getName().equals("sendMessage")) {
                        messages.add(((ITextComponent) args[0]).getUnformattedText());
                        return null;
                    }
                    if (!method.getReturnType().isPrimitive() || method.getReturnType() == void.class) return null;
                    return method.getReturnType() == boolean.class ? false : 0;
                });
        var status = new GenerationStatus(GenerationStatus.Stage.GENERATING, false, 0, 0, 1, 1, "", null);
        var command = new CacheCommand(actions::add, () -> status);

        for (var action : List.of("pause", "resume", "cancel", "rebuild")) {
            command.execute(null, sender, new String[]{action});
            assertEquals(action, actions.getLast());
            assertTrue(messages.getLast().contains("ThaumicJEI: generating"));
        }
        command.execute(null, sender, new String[0]);
        assertEquals(List.of("pause", "resume", "cancel", "rebuild"), actions);
        command.execute(null, sender, new String[]{"unknown"});
        assertTrue(messages.getLast().contains("/thaumicjei status|pause|resume|cancel|rebuild"));
    }
}
