#!/usr/bin/env python3
import argparse
import re
import struct
import sys
import zipfile

TARGET_SCOPE = "com.android.vending"
REQUIRED_MODULE_PROP = {
    "minApiVersion": "101",
    "targetApiVersion": "101",
    "staticScope": "true",
    "exceptionMode": "protective",
}
RELEASE_NATIVE_LIBRARIES = {
    "core": {"lib/arm64-v8a/libdexkit.so"},
    "full": {"lib/arm64-v8a/libdexkit.so", "lib/arm64-v8a/libandroidx.graphics.path.so"},
}
FRAMEWORK_API_PREFIX = "io.github.libxposed.api."
CORE_FORBIDDEN_PREFIXES = (
    "androidx.compose.",
    "androidx.activity.",
    "androidx.navigation3.",
    "androidx.lifecycle.",
    "androidx.core.splashscreen.",
    "com.mikepenz.aboutlibraries.",
    "me.zhanghai.compose.preference.",
    "io.github.libxposed.service.",
    "kotlinx.coroutines.",
    "eu.hxreborn.gplayadblock.settings.",
    "eu.hxreborn.gplayadblock.full.",
    "eu.hxreborn.gplayadblock.hook.AccountMenuFilter",
    "eu.hxreborn.gplayadblock.hook.ManageAppsFilter",
    "eu.hxreborn.gplayadblock.hook.PersistentNavFilter",
    "eu.hxreborn.gplayadblock.hook.SelfUpdateFilter",
)
FULL_REQUIRED_CLASSES = (
    "eu.hxreborn.gplayadblock.full.FullModuleConfiguration",
    "eu.hxreborn.gplayadblock.settings.GPlayAdblockApp",
    "io.github.libxposed.service.XposedServiceHelper",
)


def uleb128(data, offset):
    value = 0
    shift = 0
    while True:
        byte = data[offset]
        offset += 1
        value |= (byte & 0x7F) << shift
        if byte < 0x80:
            return value, offset
        shift += 7


def dex_class_names(data):
    string_ids_off = struct.unpack_from("<I", data, 0x3C)[0]
    type_ids_off = struct.unpack_from("<I", data, 0x44)[0]
    class_defs_size, class_defs_off = struct.unpack_from("<II", data, 0x60)
    names = []
    for index in range(class_defs_size):
        type_index = struct.unpack_from("<I", data, class_defs_off + index * 32)[0]
        string_index = struct.unpack_from("<I", data, type_ids_off + type_index * 4)[0]
        string_off = struct.unpack_from("<I", data, string_ids_off + string_index * 4)[0]
        _, start = uleb128(data, string_off)
        end = data.index(0, start)
        descriptor = data[start:end].decode("utf-8", "replace")
        names.append(descriptor[1:-1].replace("/", "."))
    return names


MEMBER_OWNER = re.compile(r"\s([\w$.]+)\.[\w$<>]+\(")


def mapping_originals(path):
    originals = set()
    with open(path, encoding="utf-8") as mapping:
        for line in mapping:
            if line.startswith("#") or " -> " not in line:
                continue
            if line.startswith(" "):
                owner = MEMBER_OWNER.search(line.split(" -> ", 1)[0])
                if owner:
                    originals.add(owner.group(1))
                continue
            originals.add(line.split(" -> ", 1)[0])
    return originals


def check(apk_path, flavor, release, mapping_path):
    failures = []
    with zipfile.ZipFile(apk_path) as apk:
        entries = set(apk.namelist())
        scope = apk.read("META-INF/xposed/scope.list").decode().splitlines()
        if scope != [TARGET_SCOPE]:
            failures.append(f"scope.list holds {scope}, expected [{TARGET_SCOPE}]")
        prop = dict(
            line.split("=", 1)
            for line in apk.read("META-INF/xposed/module.prop").decode().splitlines()
            if "=" in line
        )
        for key, expected in REQUIRED_MODULE_PROP.items():
            if prop.get(key) != expected:
                failures.append(f"module.prop {key}={prop.get(key)}, expected {expected}")
        entry_points = apk.read("META-INF/xposed/java_init.list").decode().split()
        classes = []
        for name in sorted(entries):
            if name.startswith("classes") and name.endswith(".dex"):
                classes += dex_class_names(apk.read(name))
        defined = set(classes)
        if len(entry_points) != 1 or entry_points[0] not in defined:
            failures.append(f"java_init.list {entry_points} is not a packaged class")
        api_classes = sorted(name for name in defined if name.startswith(FRAMEWORK_API_PREFIX))
        if api_classes:
            failures.append(f"{len(api_classes)} libxposed API classes packaged, first {api_classes[0]}")
        native = {name for name in entries if name.startswith("lib/")}
        expected_native = RELEASE_NATIVE_LIBRARIES[flavor]
        if release and native != expected_native:
            failures.append(f"native libraries {sorted(native)}, expected {sorted(expected_native)}")
    if mapping_path:
        originals = mapping_originals(mapping_path)
        if flavor == "core":
            leaked = sorted(
                name for name in originals if name.startswith(CORE_FORBIDDEN_PREFIXES)
            )
            if leaked:
                failures.append(f"core mapping holds {len(leaked)} full-only classes, first {leaked[0]}")
        else:
            missing = [name for name in FULL_REQUIRED_CLASSES if name not in originals]
            if missing:
                failures.append(f"full mapping lacks {missing}")
    elif flavor == "core":
        leaked = sorted(name for name in defined if name.startswith(CORE_FORBIDDEN_PREFIXES))
        if leaked:
            failures.append(f"core dex defines {len(leaked)} full-only classes, first {leaked[0]}")
    return failures, len(classes)


def main(argv=None):
    parser = argparse.ArgumentParser(description="Check a GPlay Adblock module APK")
    parser.add_argument("apk")
    parser.add_argument("--flavor", choices=("core", "full"), required=True)
    parser.add_argument("--release", action="store_true")
    parser.add_argument("--mapping")
    args = parser.parse_args(argv)
    failures, class_count = check(args.apk, args.flavor, args.release, args.mapping)
    for failure in failures:
        print(f"FAIL {args.apk}: {failure}")
    if failures:
        return 1
    print(f"ok {args.apk}: {args.flavor} {class_count} classes")
    return 0


if __name__ == "__main__":
    sys.exit(main())
