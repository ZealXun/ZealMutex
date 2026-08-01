package com.zealmutex.app.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class RuleAssignmentPlannerTest {
    @Test
    public void selectedAppsLeaveTheirOldOwnersAtomically() {
        Rule standalone = single("mail");
        Rule source = group("group:old", "旧组", "video", "chat");
        Rule desired = group("group:new", "新组", "mail", "video");

        RuleAssignmentPlanner.Plan plan = RuleAssignmentPlanner.planGroupSave(
                Arrays.asList(standalone, source), desired);

        assertTrue(plan.migrated);
        assertTrue(plan.deletions.contains("mail"));
        assertTrue(plan.deletions.contains("group:old"));
        assertEquals("chat", plan.orderReplacements.get("group:old"));
        assertFalse(plan.upserts.get("chat").group);
        assertEquals(Arrays.asList("mail", "video"),
                memberPackages(plan.upserts.get("group:new")));
    }

    @Test
    public void emptySourceGroupIsDeletedWithoutConversion() {
        Rule source = group("group:old", "旧组", "video", "chat");
        Rule desired = group("group:new", "新组", "video", "chat");

        RuleAssignmentPlanner.Plan plan = RuleAssignmentPlanner.planGroupSave(
                Arrays.asList(source), desired);

        assertTrue(plan.deletions.contains("group:old"));
        assertFalse(plan.orderReplacements.containsKey("group:old"));
        assertEquals(1, plan.upserts.size());
    }

    @Test
    public void remainingSourceMembersKeepTheirOriginalOrder() {
        Rule source = group("group:old", "旧组", "a", "b", "c", "d");
        Rule desired = group("group:new", "新组", "b", "d");

        RuleAssignmentPlanner.Plan plan = RuleAssignmentPlanner.planGroupSave(
                Arrays.asList(source), desired);

        Rule updatedSource = plan.upserts.get("group:old");
        assertTrue(updatedSource.group);
        assertEquals(Arrays.asList("a", "c"), memberPackages(updatedSource));
    }

    @Test
    public void editedGroupWithOneMemberBecomesStandaloneAtSameOrderSlot() {
        Rule desired = group("group:focus", "专注", "mail");

        RuleAssignmentPlanner.Plan plan = RuleAssignmentPlanner.planGroupSave(
                Arrays.asList(group("group:focus", "专注", "mail", "chat")), desired);

        assertTrue(plan.deletions.contains("group:focus"));
        assertEquals("mail", plan.orderReplacements.get("group:focus"));
        assertFalse(plan.upserts.get("mail").group);
    }

    private static Rule single(String packageName) {
        Rule rule = new Rule();
        rule.packageName = packageName;
        rule.appLabel = packageName;
        return rule;
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

    private static List<String> memberPackages(Rule rule) {
        List<String> packages = new ArrayList<>();
        for (Rule.AppMember member : rule.members) {
            packages.add(member.packageName);
        }
        return packages;
    }
}
