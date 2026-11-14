#!/bin/bash
# Ifboard: stage everything that goes into the single APK.
#   - Rime engine (librime.so + addon conf + base rime-data) taken from the plugin build
#   - rime-ice schemas / dictionaries / lua / opencc (shared data)
#   - the wanxiang grammar model is NOT bundled (~400 MB): the app downloads it on demand (ifboard/GrammarModel.kt)
#   - personal setup imported on first run (assets/ifboard, gitignored)
# Env: RIME_ICE_DIR (rime-ice user dir on the PC), RESTORE_DIR (RimeSync/_RestoreAssets), SYNC_DIR (RimeSync)
set -e
cd "$(dirname "$0")/.."
RIME_ICE_DIR="${RIME_ICE_DIR:-$APPDATA/Rime}"
RESTORE_DIR="${RESTORE_DIR:-/d/OneDrive/文档/RimeSync/_RestoreAssets}"
SYNC_DIR="${SYNC_DIR:-/d/OneDrive/文档/RimeSync}"
PLUGIN_APK=$(ls plugin/rime/build/outputs/apk/release/*arm64-v8a-release.apk | head -1)
A=app/src/main/assets
T=$(mktemp -d)
unzip -q -o "$PLUGIN_APK" -d "$T"
# 1. Rime engine as a built-in addon
mkdir -p app/src/main/jniLibs/arm64-v8a
cp "$T"/lib/arm64-v8a/*.so app/src/main/jniLibs/arm64-v8a/
mkdir -p $A/usr/share
cp -r "$T"/assets/usr/share/* $A/usr/share/
# 2. rime-ice shared data (overrides the plugin's default.yaml)
R=$A/usr/share/rime-data
cp -r "$RIME_ICE_DIR"/cn_dicts "$RIME_ICE_DIR"/en_dicts "$RIME_ICE_DIR"/lua "$RIME_ICE_DIR"/opencc $R/
cp "$RIME_ICE_DIR"/*.schema.yaml "$RIME_ICE_DIR"/*.dict.yaml "$RIME_ICE_DIR"/symbols_*.yaml \
   "$RIME_ICE_DIR"/default.yaml "$R/"
if [ -f "$RIME_ICE_DIR"/recipe.yaml ]; then cp "$RIME_ICE_DIR"/recipe.yaml "$R/"; fi
# the grammar model is downloaded by the app; drop any copy left from older builds
rm -f $R/*.gram
# the full pinyin schema (雾凇拼音, default for new users) must always be there
if [ ! -f "$RIME_ICE_DIR/rime_ice.schema.yaml" ]; then
  cp ifboard/data/rime_ice.schema.yaml $R/ || { echo "rime_ice.schema.yaml not found" >&2; exit 1; }
fi
# English word frequencies for glide typing (AOSP LatinIME en_US list, Apache-2.0, see ifboard/data/NOTICE.LatinIME)
cp ifboard/data/ifb_en_freq.tsv $R/en_dicts/
# 3. first-run import
U=$A/ifboard
rm -rf $U; mkdir -p $U/rime/sync $U/config/conf
if [ "${INCLUDE_USERDB:-1}" = "1" ]; then
  # local build: my own setup (小鹤双拼 + fuzzy pinyin, key bindings, custom phrases, typing history)
  cp "$RESTORE_DIR/Android/double_pinyin_flypy.custom.yaml" "$RESTORE_DIR/default.custom.yaml" $U/rime/
  cp "$RIME_ICE_DIR"/custom_phrase*.txt $U/rime/
  for d in "$SYNC_DIR"/PC-*; do [ -f "$d/rime_ice.userdb.txt" ] && mkdir -p "$U/rime/sync/$(basename "$d")" && cp "$d/rime_ice.userdb.txt" "$U/rime/sync/$(basename "$d")/"; done
else
  # public release: plain rime-ice defaults (full pinyin first, no fuzzy pinyin, no personal phrases / history);
  # the only addition is the grammar config, for both schemas (takes effect once the app has downloaded the model)
  for s in rime_ice double_pinyin_flypy; do
    printf '%s\n' 'patch:' '  grammar:' '    language: wanxiang-lts-zh-hans' '    collocation_max_length: 6' \
      '    collocation_min_length: 3' '    collocation_penalty: -14' '    non_collocation_penalty: -6' \
      '    weak_collocation_penalty: -100' '    rear_penalty: -20' '  translator/contextual_suggestions: false' \
      '  translator/max_homophones: 8' > $U/rime/$s.custom.yaml
  done
fi
cp "$RESTORE_DIR/Android/fcitx5_profile" $U/config/profile
cp "$RESTORE_DIR/Android/fcitx5_config" $U/config/config
printf 'EnableWordHint=True\nPageSize=5\nInsertSpace=True\n' > $U/config/conf/androidkeyboard.conf
rm -rf "$T"
du -sh app/src/main/jniLibs $A/usr/share/rime-data $U
