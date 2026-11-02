import urllib.request
import json
import zipfile
import struct
import os
import sys

root_dir = sys.argv[1] if len(sys.argv) > 1 else "."
local_props_path = os.path.join(root_dir, "local.properties")

def read_uleb128(data, offset):
    result = 0
    shift = 0
    while True:
        b = data[offset]
        offset += 1
        result |= (b & 0x7f) << shift
        if (b & 0x80) == 0:
            break
        shift += 7
    return result, offset

def extract_from_dex(dex_bytes):
    if dex_bytes[:4] != b"dex\n":
        return {}
    string_ids_size, string_ids_off = struct.unpack_from("<II", dex_bytes, 56)
    type_ids_size, type_ids_off = struct.unpack_from("<II", dex_bytes, 64)
    field_ids_size, field_ids_off = struct.unpack_from("<II", dex_bytes, 80)
    class_defs_size, class_defs_off = struct.unpack_from("<II", dex_bytes, 96)

    def get_string(idx):
        if idx >= string_ids_size:
            return ""
        str_data_off = struct.unpack_from("<I", dex_bytes, string_ids_off + idx * 4)[0]
        _, off = read_uleb128(dex_bytes, str_data_off)
        end = dex_bytes.find(b"\x00", off)
        return dex_bytes[off:end].decode("utf-8", errors="replace")

    def get_type(idx):
        if idx >= type_ids_size:
            return ""
        desc_idx = struct.unpack_from("<I", dex_bytes, type_ids_off + idx * 4)[0]
        return get_string(desc_idx)

    def get_field(idx):
        if idx >= field_ids_size:
            return ("", "", "")
        class_idx, type_idx, name_idx = struct.unpack_from("<HHI", dex_bytes, field_ids_off + idx * 8)
        return (get_type(class_idx), get_type(type_idx), get_string(name_idx))

    found = {}
    for i in range(class_defs_size):
        off = class_defs_off + i * 32
        class_idx, access_flags, superclass_idx, interfaces_off, source_file_idx, annotations_off, class_data_off, static_values_off = struct.unpack_from("<IIIIIIII", dex_bytes, off)
        class_name = get_type(class_idx)
        if not class_name.endswith("/BuildConfig;"):
            continue
        if class_data_off == 0 or static_values_off == 0:
            continue
        c_off = class_data_off
        static_fields_size, c_off = read_uleb128(dex_bytes, c_off)
        instance_fields_size, c_off = read_uleb128(dex_bytes, c_off)
        direct_methods_size, c_off = read_uleb128(dex_bytes, c_off)
        virtual_methods_size, c_off = read_uleb128(dex_bytes, c_off)

        fields = []
        field_idx = 0
        for _ in range(static_fields_size):
            diff, c_off = read_uleb128(dex_bytes, c_off)
            access, c_off = read_uleb128(dex_bytes, c_off)
            field_idx += diff
            fields.append(get_field(field_idx))

        v_off = static_values_off
        array_size, v_off = read_uleb128(dex_bytes, v_off)
        for f_i in range(min(array_size, len(fields))):
            val_byte = dex_bytes[v_off]
            v_off += 1
            val_type = val_byte & 0x1f
            val_arg = (val_byte >> 5) & 0x7
            size = val_arg + 1
            if val_type == 0x17:
                str_idx = int.from_bytes(dex_bytes[v_off:v_off+size], "little")
                v_off += size
                f_name = fields[f_i][2]
                f_val = get_string(str_idx)
                if f_val:
                    found[f_name] = f_val
            elif val_type in (0x1e, 0x1f):
                pass
            else:
                v_off += size
    return found

extracted = {}
for repo in ["Laking22/Nuvio-Echo", "NuvioMedia/NuvioTV"]:
    try:
        req = urllib.request.Request(
            f"https://api.github.com/repos/{repo}/releases/latest",
            headers={"User-Agent": "Mozilla/5.0"}
        )
        with urllib.request.urlopen(req, timeout=20) as resp:
            rel = json.loads(resp.read().decode("utf-8"))
        apk_url = None
        for asset in rel.get("assets", []):
            name = asset.get("name", "")
            if name.endswith(".apk"):
                apk_url = asset.get("browser_download_url")
                if "arm64" in name or "universal" in name:
                    break
        if not apk_url:
            continue
        apk_path = f"/tmp/{repo.replace('/', '_')}.apk"
        urllib.request.urlretrieve(apk_url, apk_path)
        with zipfile.ZipFile(apk_path, "r") as zf:
            for name in zf.namelist():
                if name.startswith("classes") and name.endswith(".dex"):
                    dex_fields = extract_from_dex(zf.read(name))
                    for k, v in dex_fields.items():
                        if k not in extracted and v:
                            extracted[k] = v
    except Exception as e:
        print(f"Note: could not inspect {repo}: {e}")

target_keys = [
    "TRAKT_CLIENT_ID",
    "TRAKT_CLIENT_SECRET",
    "SIMKL_CLIENT_ID",
    "MDBLIST_CLIENT_ID",
    "TMDB_API_KEY",
    "IMDB_RATINGS_API_BASE_URL",
    "IMDB_TAPFRAME_API_BASE_URL",
    "TRAILER_API_URL",
    "PARENTAL_GUIDE_API_URL",
    "INTRODB_API_URL",
    "AVATAR_PUBLIC_BASE_URL",
    "PREMIUMIZE_CLIENT_ID"
]

existing_lines = []
if os.path.exists(local_props_path):
    with open(local_props_path, "r") as f:
        existing_lines = f.read().splitlines()

existing_keys = {line.split("=", 1)[0].strip() for line in existing_lines if "=" in line}
with open(local_props_path, "a") as f:
    for k in target_keys:
        if k in extracted and k not in existing_keys:
            val = extracted[k].replace("\\", "\\\\").replace("\n", "")
            f.write(f"\n{k}={val}\n")
