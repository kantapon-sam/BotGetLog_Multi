package com.java.shared;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Read-only Huawei interface inventory, available even with an older workbook. */
public final class HuaweiInterfaceCommands {
    public static final String NAME = "HW-Interface";
    // The collector uses element zero as the command-set header.
    public static final List<String> ROWS = Collections.unmodifiableList(Arrays.asList(
            NAME, "screen-length 0 temporary", "display interface brief", "display interface", "quit"));

    private HuaweiInterfaceCommands() { }

    public static boolean matches(String name) {
        return name != null && NAME.equalsIgnoreCase(name.trim());
    }

    public static boolean permitsVendor(String requested, String vendor) {
        return !matches(requested) || "HW".equalsIgnoreCase(vendor);
    }
}
