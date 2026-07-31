package com.zealmutex.app.update;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small semantic-version comparator shared by update discovery and JVM tests. */
final class VersionComparator {
    private static final Pattern NUMBER = Pattern.compile("\\d+");

    private VersionComparator() {
    }

    static int compare(String left, String right) {
        Version a = Version.parse(left);
        Version b = Version.parse(right);
        for (int i = 0; i < Math.max(a.core.length, b.core.length); i++) {
            int leftPart = i < a.core.length ? a.core[i] : 0;
            int rightPart = i < b.core.length ? b.core[i] : 0;
            int comparison = Integer.compare(leftPart, rightPart);
            if (comparison != 0) {
                return comparison;
            }
        }
        int rank = Integer.compare(a.preReleaseRank, b.preReleaseRank);
        return rank != 0 ? rank
                : Integer.compare(a.preReleaseNumber, b.preReleaseNumber);
    }

    private static final class Version {
        final int[] core;
        final int preReleaseRank;
        final int preReleaseNumber;

        Version(int[] core, int preReleaseRank, int preReleaseNumber) {
            this.core = core;
            this.preReleaseRank = preReleaseRank;
            this.preReleaseNumber = preReleaseNumber;
        }

        static Version parse(String raw) {
            String normalized = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            if (normalized.startsWith("v")) {
                normalized = normalized.substring(1);
            }
            String[] parts = normalized.split("-", 2);
            String[] coreParts = parts[0].split("\\.");
            int[] core = new int[Math.max(3, coreParts.length)];
            for (int i = 0; i < coreParts.length; i++) {
                Matcher matcher = NUMBER.matcher(coreParts[i]);
                core[i] = matcher.find() ? Integer.parseInt(matcher.group()) : 0;
            }
            if (parts.length == 1) {
                return new Version(core, 4, 0);
            }
            String pre = parts[1];
            int rank = pre.contains("rc") ? 3
                    : pre.contains("beta") ? 2
                    : pre.contains("alpha") ? 1 : 0;
            Matcher matcher = NUMBER.matcher(pre);
            int number = matcher.find() ? Integer.parseInt(matcher.group()) : 0;
            return new Version(core, rank, number);
        }
    }
}
