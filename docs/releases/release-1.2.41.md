# BotGetLog Multi 1.2.41

After Huawei accepts a node password, it may ask whether to change it now. The TRUE collector answers `N` once to that optional question and continues reading the device prompt. This also applies to backup account logins. Devices that present their prompt immediately proceed as usual.

The collector still checks the returned hostname for a backup login. A rejected password, a locked account or a connection timeout keeps its existing failure handling.
