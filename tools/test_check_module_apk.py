import struct
import tempfile
import unittest
import zipfile
from pathlib import Path

import check_module_apk

MODULE_CLASS = "eu.hxreborn.gplayadblock.PlayStoreAdblockModule"
MODULE_PROP = (
    "id=eu.hxreborn.gplayadblock\nminApiVersion=101\ntargetApiVersion=101\n"
    "staticScope=true\nexceptionMode=protective\n"
)


def dex(class_names):
    descriptors = ["L" + name.replace(".", "/") + ";" for name in class_names]
    count = len(descriptors)
    string_ids_off = 0x70
    type_ids_off = string_ids_off + 4 * count
    class_defs_off = type_ids_off + 4 * count
    data_off = class_defs_off + 32 * count
    header = bytearray(0x70)
    struct.pack_into("<II", header, 0x38, count, string_ids_off)
    struct.pack_into("<II", header, 0x40, count, type_ids_off)
    struct.pack_into("<II", header, 0x60, count, class_defs_off)
    string_ids = b""
    string_data = b""
    for descriptor in descriptors:
        string_ids += struct.pack("<I", data_off + len(string_data))
        encoded = descriptor.encode()
        string_data += bytes([len(encoded)]) + encoded + b"\0"
    type_ids = b"".join(struct.pack("<I", index) for index in range(count))
    class_defs = b"".join(struct.pack("<I", index) + bytes(28) for index in range(count))
    return bytes(header) + string_ids + type_ids + class_defs + string_data


class CheckModuleApkTest(unittest.TestCase):
    def apk(self, classes, libraries=("lib/arm64-v8a/libdexkit.so",), scope="com.android.vending\n"):
        directory = tempfile.mkdtemp()
        path = Path(directory) / "module.apk"
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("META-INF/xposed/scope.list", scope)
            archive.writestr("META-INF/xposed/module.prop", MODULE_PROP)
            archive.writestr("META-INF/xposed/java_init.list", MODULE_CLASS + "\n")
            archive.writestr("classes.dex", dex(classes))
            for library in libraries:
                archive.writestr(library, b"")
        return str(path)

    def test_a_core_module_passes(self):
        failures, count = check_module_apk.check(self.apk([MODULE_CLASS]), "core", True, None)
        self.assertEqual([], failures)
        self.assertEqual(1, count)

    def test_a_bundled_framework_api_class_fails(self):
        apk = self.apk([MODULE_CLASS, "io.github.libxposed.api.XposedModule"])
        failures, _ = check_module_apk.check(apk, "core", True, None)
        self.assertIn("libxposed API classes packaged", failures[0])

    def test_a_full_only_class_in_core_fails(self):
        apk = self.apk([MODULE_CLASS, "eu.hxreborn.gplayadblock.settings.GPlayAdblockApp"])
        failures, _ = check_module_apk.check(apk, "core", True, None)
        self.assertIn("full-only classes", failures[0])

    def test_an_extra_release_abi_fails(self):
        apk = self.apk([MODULE_CLASS], libraries=("lib/arm64-v8a/libdexkit.so", "lib/x86_64/libdexkit.so"))
        failures, _ = check_module_apk.check(apk, "core", True, None)
        self.assertIn("native libraries", failures[0])

    def test_a_wrong_scope_fails(self):
        apk = self.apk([MODULE_CLASS], scope="com.android.vending\nandroid\n")
        failures, _ = check_module_apk.check(apk, "core", False, None)
        self.assertIn("scope.list", failures[0])

    def test_a_missing_entry_point_fails(self):
        failures, _ = check_module_apk.check(self.apk(["a.b.C"]), "core", False, None)
        self.assertIn("java_init.list", failures[0])

    def test_the_core_mapping_is_checked_for_full_only_classes(self):
        mapping = Path(tempfile.mkdtemp()) / "mapping.txt"
        mapping.write_text(
            "# compiler: R8\n"
            "eu.hxreborn.gplayadblock.PlayStoreAdblockModule -> a.a:\n"
            "androidx.compose.runtime.Composer -> a.b:\n"
            "    void a() -> b\n"
        )
        failures, _ = check_module_apk.check(self.apk([MODULE_CLASS]), "core", True, str(mapping))
        self.assertIn("core mapping holds 1 full-only classes", failures[0])


    def test_a_full_class_merged_into_another_counts_as_present(self):
        mapping = Path(tempfile.mkdtemp()) / "mapping.txt"
        mapping.write_text(
            "eu.hxreborn.gplayadblock.settings.GPlayAdblockApp -> a.a:\n"
            "io.github.libxposed.service.XposedServiceHelper -> a.b:\n"
            "eu.hxreborn.gplayadblock.Other -> a.c:\n"
            "    41:47:void eu.hxreborn.gplayadblock.full.FullModuleConfiguration.<init>(int):24:30 -> <init>\n"
        )
        failures, _ = check_module_apk.check(self.apk([MODULE_CLASS]), "full", False, str(mapping))
        self.assertEqual([], failures)

    def test_a_full_member_inlined_into_core_fails(self):
        mapping = Path(tempfile.mkdtemp()) / "mapping.txt"
        mapping.write_text(
            "eu.hxreborn.gplayadblock.PlayStoreAdblockModule -> a.a:\n"
            "    1:2:void eu.hxreborn.gplayadblock.full.FullRemovalPolicy.classify(java.lang.Object):3:4 -> a\n"
        )
        failures, _ = check_module_apk.check(self.apk([MODULE_CLASS]), "core", True, str(mapping))
        self.assertIn("core mapping holds 1 full-only classes", failures[0])


if __name__ == "__main__":
    unittest.main()
