package com.zealmutex.app.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Computes one conflict-free next-day ownership state before DataStore persists it. */
public final class RuleAssignmentPlanner {
    private RuleAssignmentPlanner() {
    }

    public static Plan planGroupSave(List<Rule> tomorrowRules, Rule desiredGroup) {
        Plan plan = new Plan();
        Set<String> desiredPackages = new LinkedHashSet<>();
        for (Rule.AppMember member : desiredGroup.members) {
            desiredPackages.add(member.packageName);
        }

        for (Rule existing : tomorrowRules) {
            if (existing.packageName.equals(desiredGroup.packageName)) {
                continue;
            }
            if (!existing.group) {
                if (desiredPackages.contains(existing.packageName)) {
                    plan.migrated = true;
                    plan.deletions.add(existing.packageName);
                }
                continue;
            }

            Rule updated = existing.copy();
            boolean changed = updated.members.removeIf(
                    member -> desiredPackages.contains(member.packageName));
            if (!changed) {
                continue;
            }
            plan.migrated = true;
            if (updated.members.isEmpty()) {
                plan.deletions.add(updated.packageName);
            } else if (updated.members.size() == 1) {
                Rule single = updated.copyForSingleMember(updated.members.get(0));
                plan.deletions.add(updated.packageName);
                plan.upserts.put(single.packageName, single);
                plan.orderReplacements.put(updated.packageName, single.packageName);
            } else {
                plan.upserts.put(updated.packageName, updated);
            }
        }

        if (desiredGroup.members.size() == 1) {
            Rule single = desiredGroup.copyForSingleMember(desiredGroup.members.get(0));
            plan.deletions.add(desiredGroup.packageName);
            plan.upserts.put(single.packageName, single);
            plan.orderReplacements.put(desiredGroup.packageName, single.packageName);
        } else if (!desiredGroup.members.isEmpty()) {
            plan.upserts.put(desiredGroup.packageName, desiredGroup.copy());
        }
        return plan;
    }

    public static final class Plan {
        public final Map<String, Rule> upserts = new LinkedHashMap<>();
        public final Set<String> deletions = new LinkedHashSet<>();
        public final Map<String, String> orderReplacements = new LinkedHashMap<>();
        public boolean migrated;

        public List<Rule> upsertedRules() {
            return new ArrayList<>(upserts.values());
        }
    }
}
