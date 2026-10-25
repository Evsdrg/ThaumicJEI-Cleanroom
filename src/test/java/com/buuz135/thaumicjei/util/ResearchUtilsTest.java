package com.buuz135.thaumicjei.util;

import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ResearchUtilsTest {
    @Test
    void emptyRequirementsDoNotNeedAPlayer() {
        assertTrue(ResearchUtils.knowsAllResearch(null, Set.of(), (String[]) null));
        assertTrue(ResearchUtils.knowsAllResearch(null, Set.of(), null, "", "  ", "&&"));
    }

    @Test
    void eventApprovalIsExactAndDoesNotApproveCompositeConditions() {
        assertTrue(ResearchUtils.knowsAllResearch(null, Set.of("a"), "A"));
        assertFalse(ResearchUtils.knowsAllResearch(null, Set.of("a"), "A&&B"));
        assertFalse(ResearchUtils.knowsAllResearch(null, Set.of("a||b"), "A||B"));
        assertFalse(ResearchUtils.knowsAllResearch(null, Set.of(), "A"));
    }
}
