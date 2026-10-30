# Supplemental dependency licenses

Some crates omit their workspace's license files from the crates.io archive.
These upstream texts supplement the license files collected from the locked
packages by `scripts/notices.py`. Apache-2.0 also covers the Shizuku API/provider
and Gradle wrapper. egui's workspace licenses are retained with vendored eframe.

- cesu8: https://github.com/emk/cesu8-rs/blob/master/COPYRIGHT-RUST.txt
- wuff 0.2.9: https://github.com/nicoburns/wuff/blob/78e67f52713c0a2c36bd083fea18ba029565c9ec/LICENSE
- jni 0.22.4: https://github.com/jni-rs/jni-rs/blob/5ae9458a4ec44c5318f37ddc7569c1d4ae8a69e7/LICENSE-APACHE
- jni-sys-macros 0.4.1: https://github.com/jni-rs/jni-sys/blob/64d77b7a5f119d7b55b4e2c169a4668067ff59e6/LICENSE-APACHE
- ndk 0.9.0: https://github.com/rust-mobile/ndk/blob/49bbbba16c58ff63cb8a0ad0eca5a9fb7ecaec25/LICENSE-APACHE
- ndk-context 0.1.1: https://github.com/rust-mobile/ndk/blob/10f2ba388fca20f7349996ebae26ccda7a6fda5c/LICENSE-APACHE
- profiling 1.0.18: https://github.com/aclysma/profiling/blob/8271551172eb6fa4cba47369aedd93790c623df9/LICENSE-APACHE

UI font notices (OFL, Ubuntu Font License, Hack and emoji-icon-font) come directly
from epaint_default_fonts 0.31.1. No user font is distributed.
