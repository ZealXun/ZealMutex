package com.zealmutex.app.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RuleGroupTest {
    @Test
    public void groupFindsMembersAndKeepsTheirSelectionOrder() {
        Rule group = group("group:focus", "专注", "mail", "video", "chat");

        assertTrue(group.containsPackage("video"));
        assertFalse(group.containsPackage("maps"));
        assertEquals("video", group.memberLabel("video"));
        assertEquals("mail", group.members.get(0).packageName);
        assertEquals("chat", group.members.get(2).packageName);
    }

    @Test
    public void copyIsDeepAndMemberChangesWaitUntilTomorrow() {
        Rule active = group("group:focus", "专注", "mail", "video");
        Rule edited = active.copy();

        assertNotSame(active.members, edited.members);
        edited.pinned = true;
        edited.appLabel = "工作";
        active.applyImmediateSettingsFrom(edited);
        assertTrue(active.pinned);
        assertEquals("工作", active.appLabel);
        assertTrue(active.hasSameDelayedSettings(edited));

        edited.members.add(new Rule.AppMember("chat", "chat"));
        assertFalse(active.hasSameDelayedSettings(edited));
        assertEquals(2, active.members.size());
    }

    @Test
    public void oneMemberGroupConvertsToStandaloneWithSharedSettings() {
        Rule group = group("group:focus", "专注", "mail");
        group.dailyLimitMinutes = 45;
        group.pinned = true;

        Rule single = group.copyForSingleMember(group.members.get(0));

        assertFalse(single.group);
        assertEquals("mail", single.packageName);
        assertEquals("mail", single.appLabel);
        assertEquals(45, single.dailyLimitMinutes);
        assertTrue(single.pinned);
        assertTrue(single.members.isEmpty());
    }

    private static Rule group(String key, String name, String... packages) {
        Rule rule = new Rule();
        rule.group = true;
        rule.packageName = key;
        rule.appLabel = name;
        for (String packageName : packages) {
            rule.members.add(new Rule.AppMember(packageName, packageName));
        }
        return rule;
    }
}
