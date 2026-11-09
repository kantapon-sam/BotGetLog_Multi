# Bot 1.2.39 — ZTE xxvgei ports

ZTE physical `xxvgei-` ports are retained in LLDP and optical exports, including
their interface bandwidth, Rx/Tx values, SFP distance and operational state.
Logical subinterfaces remain excluded. Wrapped `show lacp internal` rows now
retain explicit Smartgroup membership when the closing bracket is on the next line.

Free port and Live Monitor use reported ZTE interface bandwidth before the name
prefix. Per the operator's policy, 25G counts/displays as 10G; 1G, 10G and 100G
retain their respective categories. Raw LLDP bandwidth remains the measured value.
An unknown xxvgei bandwidth is not guessed from its name. Free port retains its
existing reservation and sub-gigabit categorization rules.

Deploy with MapViewer 2.2.33, which removes the legacy exclusion of physical
xxvgei links from Rx/SFP and SPOF/Ring graphs and rebuilds affected caches.
Update the scheduled collector and Link_Optical JAR together while idle;
preserve workbooks, collected logs, daily budgets and scheduler helpers.

Regression coverage: native LLDP/optical/Free port exports, wrapped and incomplete
LACP records, live brief/detail output, administrative-down boundaries,
subinterface isolation, 1G/10G/25G/100G bandwidth and existing vendor parsers.
