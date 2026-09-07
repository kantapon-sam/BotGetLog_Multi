package com.java.tools.linkoptical;

/** Huawei physical port names shared by live inventory and detail collection. */
public final class HuaweiLivePort {
    public static final String NAME_PATTERN =
            "(?:50\\|100GE|100GE|50GE|40GE|25GE|10GE|XGigabitEthernet|GigabitEthernet|GE|Ethernet)"
            + "[0-9]+(?:/[0-9]+){2,4}";

    private HuaweiLivePort() { }

    public static boolean isPhysicalPort(String port) {
        return port != null && port.length() <= 80 && port.matches("(?i)" + NAME_PATTERN);
    }

    public static boolean isDualRatePort(String port) {
        // A literal pipe is valid only in this exact hardware-interface name.
        // Never extend the generic command allow-list to arbitrary pipes.
        return port != null && port.length() <= 80
                && port.matches("(?i)50\\|100GE[0-9]+(?:/[0-9]+){2,4}");
    }

    public static String commandArgument(String validatedPort) {
        if (isDualRatePort(validatedPort)) {
            // Keep the actual interface type (not an unverified 100GE alias).
            return "50|100GE " + validatedPort.substring("50|100GE".length());
        }
        return validatedPort;
    }
}
