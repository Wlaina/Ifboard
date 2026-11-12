#!/bin/bash
# Ifboard: stage everything that goes into the single APK.
#   - Rime engine (librime.so + addon conf + base rime-data) taken from the plugin build
#   - rime-ice schemas / dictionaries / lua / opencc and the wanxiang grammar model (shared data)
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
# 2. rime-ice shared data (overrides the plugin's default.yaml) + grammar model
R=$A/usr/share/rime-data
cp -r "$RIME_ICE_DIR"/cn_dicts "$RIME_ICE_DIR"/en_dicts "$RIME_ICE_DIR"/lua "$RIME_ICE_DIR"/opencc $R/
cp "$RIME_ICE_DIR"/*.schema.yaml "$RIME_ICE_DIR"/*.dict.yaml "$RIME_ICE_DIR"/symbols_*.yaml \
   "$RIME_ICE_DIR"/default.yaml "$RIME_ICE_DIR"/recipe.yaml $R/
[ -f "$RIME_ICE_DIR/wanxiang-lts-zh-hans.gram" ] && cp "$RIME_ICE_DIR/wanxiang-lts-zh-hans.gram" $R/
# 3. personal setup (first-run import)
U=$A/ifboard
rm -rf $U; mkdir -p $U/rime/sync $U/config/conf
cp "$RESTORE_DIR/Android/double_pinyin_flypy.custom.yaml" "$RESTORE_DIR/default.custom.yaml" $U/rime/
if [ "${INCLUDE_USERDB:-1}" != "1" ]; then
  # public release: new users start with full pinyin (雾凇拼音); 小鹤双拼 stays available in the schema list
  sed -i 's/^\( *\)- schema: double_pinyin_flypy.*$/\1- schema: rime_ice  # 雾凇拼音 - 全拼\n\1- schema: double_pinyin_flypy  # 雾凇拼音 - 小鹤双拼/' $U/rime/default.custom.yaml
  grep -q "schema: rime_ice" $U/rime/default.custom.yaml || { echo "default.custom.yaml: schema_list not patched" >&2; exit 1; }
  # same grammar model for full pinyin as the double pinyin setup
  if [ -f "$RIME_ICE_DIR/wanxiang-lts-zh-hans.gram" ]; then
    printf '%s\n' 'patch:' '  grammar:' '    language: wanxiang-lts-zh-hans' '    collocation_max_length: 6' \
      '    collocation_min_length: 3' '    collocation_penalty: -14' '    non_collocation_penalty: -6' \
      '    weak_collocation_penalty: -100' '    rear_penalty: -20' '  translator/contextual_suggestions: false' \
      '  translator/max_homophones: 8' > $U/rime/rime_ice.custom.yaml
  fi
fi
cp "$RIME_ICE_DIR"/custom_phrase*.txt $U/rime/
# personal typing history only goes into local builds (INCLUDE_USERDB=0 for public releases)
[ "${INCLUDE_USERDB:-1}" = "1" ] && for d in "$SYNC_DIR"/PC-*; do [ -f "$d/rime_ice.userdb.txt" ] && mkdir -p "$U/rime/sync/$(basename "$d")" && cp "$d/rime_ice.userdb.txt" "$U/rime/sync/$(basename "$d")/"; done
cp "$RESTORE_DIR/Android/fcitx5_profile" $U/config/profile
cp "$RESTORE_DIR/Android/fcitx5_config" $U/config/config
printf 'EnableWordHint=True\nPageSize=5\nInsertSpace=True\n' > $U/config/conf/androidkeyboard.conf
rm -rf "$T"
du -sh app/src/main/jniLibs $A/usr/share/rime-data $U
