package com.zealmutex.app.data;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RuleWeekdayTest {
    @Test
    public void newRuleIsActiveEveryDayByDefault() {
        Rule rule = new Rule();

        for (int day = 1; day <= 7; day++) {
            assertTrue(rule.isActiveOnDay(day));
        }
    }

    @Test
    public void selectedWeekdaysControlRestrictionAndInactiveReminder() {
        Rule rule = new Rule();
        rule.activeWeekdaysMask = 0;
        rule.setActiveOnDay(1, true);
        rule.setActiveOnDay(5, true);

        assertTrue(rule.isActiveOnDay(1));
        assertFalse(rule.isActiveOnDay(2));
        assertTrue(rule.isActiveOnDay(5));

        rule.remindOnInactiveDays = false;
        assertTrue(rule.shouldRemindOnDay(1));
        assertFalse(rule.shouldRemindOnDay(2));

        rule.remindOnInactiveDays = true;
        assertTrue(rule.shouldRemindOnDay(2));
    }

    @Test
    public void weekdayChangesAreDelayedButInactiveReminderToggleIsImmediate() {
        Rule active = new Rule();
        Rule edited = new Rule();
        edited.setActiveOnDay(7, false);

        assertFalse(active.hasSameDelayedSettings(edited));

        active.applyImmediateSettingsFrom(edited);
        edited.activeWeekdaysMask = active.activeWeekdaysMask;
        edited.remindOnInactiveDays = false;
        active.applyImmediateSettingsFrom(edited);

        assertTrue(active.hasSameDelayedSettings(edited));
        assertFalse(active.remindOnInactiveDays);
    }
}
