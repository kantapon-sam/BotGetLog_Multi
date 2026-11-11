package com.truelinkoptical.shared;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pair a live LLDP system name with a destination IPv4 on the very same interface description. */
public final class DiscoveryPeer {
    private static final Pattern ADDRESS = Pattern.compile("(?<![0-9.])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![0-9.])");
    private static final Pattern NUMBERED = Pattern.compile("^(RN|PN|DN|AN|AGN)-([A-Z]{2,5}[0-9]{3,5})-([0-9]+)(?:_|$)");
    private DiscoveryPeer() {}

    public static String destination(String source, String description) {
        Matcher addresses = ADDRESS.matcher(description);
        String found = "";
        while (addresses.find()) {
            String address = addresses.group();
            if (address.equals(source)) continue;
            if (!found.isEmpty() && !found.equals(address)) return "";
            found = address;
        }
        return found;
    }

    public static boolean hasNeighbor(String neighbor, String state) {
        String name = neighbor.trim().toUpperCase(Locale.ROOT);
        return (state.trim().isEmpty() || state.trim().equalsIgnoreCase("up"))
                && name.length() >= 3 && !name.equals("N/A") && !name.equals("UNKNOWN") && !name.equals("NONE");
    }

    public static boolean confirms(String neighbor, String description, String state) {
        if (!hasNeighbor(neighbor, state)) return false;
        String name = neighbor.trim().toUpperCase(Locale.ROOT);
        String desc = description.toUpperCase(Locale.ROOT);
        if (containsName(desc, name)) return true;
        Matcher numbered = NUMBERED.matcher(name);
        if (numbered.find()) {
            String type = numbered.group(1), site = numbered.group(2), unit = numbered.group(3);
            return containsName(desc, type + "-" + site + "-" + unit) || containsName(desc, type + unit + "-" + site);
        }
        return false;
    }

    private static boolean containsName(String description, String name) {
        int index = -1;
        while ((index = description.indexOf(name, index + 1)) >= 0) {
            int end = index + name.length();
            if ((index == 0 || !Character.isLetterOrDigit(description.charAt(index - 1)))
                    && (end == description.length() || !Character.isLetterOrDigit(description.charAt(end)))) return true;
        }
        return false;
    }
}
