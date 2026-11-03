# zdtun

Source: https://github.com/emanuele-f/zdtun
Pinned commit: `80b7941d4e685d8354346820878eaec0e35b6b1e`

Copyright © 2018–2021 Emanuele Faranda. LGPL-3.0-or-later; see COPYING and COPYING.GPL.
The upstream sources are unmodified. `third_party/uthash.h` carries its own BSD notice;
`third_party/net_headers.h` retains the upstream notices in that file.

Built as the separately linked `libzdtun.so` by ../CMakeLists.txt. The application bridge
is ../traffic.c. This directory includes the corresponding library source, and the complete
application source/build files are published in this repository, allowing users to rebuild
and replace the library/APK. A replacement APK must be signed with the user's own key and
may require uninstalling the installed APK (losing app data) due to Android signature rules.

Do not remove these notices or the library's source when redistributing builds. No reverse
engineering restriction is imposed for debugging modifications to this library.
