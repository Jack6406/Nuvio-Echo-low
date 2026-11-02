import urllib.request
import json
import zipfile
import struct
import os
import re
import sys

root_dir = sys.argv[1] if len(sys.argv) > 1 else "."
local_props_path = os.path.join(root_dir, "local.properties")

HEX64_RE = re.compile(r"^[0-9a-fA-F]{64}$")
HEX32_RE = re.compile(r"^[0-9a-fA-F]{32}$")

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

def analyze_dex(dex_bytes, found):
    if dex_bytes[:4] != b"dex\n":
        return
    string_ids_size, string_ids_off = struct.unpack_from("<II", dex_bytes, 56)
    type_ids_size, type_ids_off = struct.unpack_from("<II", dex_bytes, 64)
    field_ids_size, field_ids_off = struct.unpack_from("<II", dex_bytes, 80)
    method_ids_size, method_ids_off = struct.unpack_from("<II", dex_bytes, 88)
    class_defs_size, class_defs_off = struct.unpack_from("<II", dex_bytes, 96)

    strings = []
    for idx in range(string_ids_size):
        str_data_off = struct.unpack_from("<I", dex_bytes, string_ids_off + idx * 4)[0]
        _, off = read_uleb128(dex_bytes, str_data_off)
        end = dex_bytes.find(b"\x00", off)
        strings.append(dex_bytes[off:end].decode("utf-8", errors="replace"))

    def get_string(idx):
        return strings[idx] if 0 <= idx < len(strings) else ""

    def get_type(idx):
        if 0 <= idx < type_ids_size:
            desc_idx = struct.unpack_from("<I", dex_bytes, type_ids_off + idx * 4)[0]
            return get_string(desc_idx)
        return ""

    def get_field(idx):
        if 0 <= idx < field_ids_size:
            class_idx, type_idx, name_idx = struct.unpack_from("<HHI", dex_bytes, field_ids_off + idx * 8)
            return (get_type(class_idx), get_type(type_idx), get_string(name_idx))
        return ("", "", "")

    def get_method(idx):
        if 0 <= idx < method_ids_size:
            class_idx, proto_idx, name_idx = struct.unpack_from("<HHI", dex_bytes, method_ids_off + idx * 8)
            return (get_type(class_idx), get_string(name_idx))
        return ("", "")

    def decode_method_code(code_off):
        if code_off == 0 or code_off + 16 > len(dex_bytes):
            return [], []
        insns_size = struct.unpack_from("<I", dex_bytes, code_off + 12)[0]
        insns_start = code_off + 16
        insns_end = min(len(dex_bytes), insns_start + insns_size * 2)
        m_strings = []
        m_calls = []
        pc = insns_start
        while pc + 2 <= insns_end:
            word = struct.unpack_from("<H", dex_bytes, pc)[0]
            opcode = word & 0xff
            # Handle packed-switch / sparse-switch / fill-array-data payload pseudo-instructions
            if word in (0x0100, 0x0200, 0x0300):
                break
            if opcode == 0x1a and pc + 4 <= insns_end:
                str_idx = struct.unpack_from("<H", dex_bytes, pc + 2)[0]
                m_strings.append(get_string(str_idx))
                pc += 4
            elif opcode == 0x1b and pc + 6 <= insns_end:
                str_idx = struct.unpack_from("<I", dex_bytes, pc + 2)[0]
                m_strings.append(get_string(str_idx))
                pc += 6
            elif opcode in (0x6e, 0x6f, 0x70, 0x71, 0x72, 0x74, 0x75, 0x76, 0x77, 0x78) and pc + 6 <= insns_end:
                meth_idx = struct.unpack_from("<H", dex_bytes, pc + 2)[0]
                m_calls.append(get_method(meth_idx))
                pc += 6
            else:
                # Advance by instruction format size
                if opcode in (0x00, 0x01, 0x04, 0x07, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10, 0x11, 0x12, 0x1d, 0x1e, 0x27, 0x28) or (0x7b <= opcode <= 0x8f) or (0xb0 <= opcode <= 0xcf):
                    pc += 2
                elif opcode in (0x14, 0x17, 0x26, 0x2a, 0x2b, 0x2c) or (0x52 <= opcode <= 0x6d and False):
                    pc += 6
                elif opcode == 0x18:
                    pc += 10
                else:
                    pc += 4
        return m_strings, m_calls

    simkl_config_classes = set()
    mdblist_config_classes = set()
    all_methods = []

    for i in range(class_defs_size):
        off = class_defs_off + i * 32
        class_idx, access_flags, superclass_idx, interfaces_off, source_file_idx, annotations_off, class_data_off, static_values_off = struct.unpack_from("<IIIIIIII", dex_bytes, off)
        class_name = get_type(class_idx)

        # 1. Extract static BuildConfig fields if present
        if class_data_off != 0 and static_values_off != 0 and class_name.endswith("/BuildConfig;"):
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
                    if f_val and f_name not in found:
                        found[f_name] = f_val
                elif val_type in (0x1e, 0x1f):
                    pass
                else:
                    v_off += size

        if class_data_off == 0:
            continue
        c_off = class_data_off
        static_fields_size, c_off = read_uleb128(dex_bytes, c_off)
        instance_fields_size, c_off = read_uleb128(dex_bytes, c_off)
        direct_methods_size, c_off = read_uleb128(dex_bytes, c_off)
        virtual_methods_size, c_off = read_uleb128(dex_bytes, c_off)
        for _ in range(static_fields_size + instance_fields_size):
            _, c_off = read_uleb128(dex_bytes, c_off)
            _, c_off = read_uleb128(dex_bytes, c_off)

        class_strings = []
        m_idx = 0
        for _ in range(direct_methods_size):
            diff, c_off = read_uleb128(dex_bytes, c_off)
            access, c_off = read_uleb128(dex_bytes, c_off)
            code_off, c_off = read_uleb128(dex_bytes, c_off)
            m_idx += diff
            m_strs, m_calls = decode_method_code(code_off)
            class_strings.extend(m_strs)
            all_methods.append((class_name, get_method(m_idx)[1], m_strs, m_calls))

        m_idx = 0
        for _ in range(virtual_methods_size):
            diff, c_off = read_uleb128(dex_bytes, c_off)
            access, c_off = read_uleb128(dex_bytes, c_off)
            code_off, c_off = read_uleb128(dex_bytes, c_off)
            m_idx += diff
            m_strs, m_calls = decode_method_code(code_off)
            class_strings.extend(m_strs)
            all_methods.append((class_name, get_method(m_idx)[1], m_strs, m_calls))

        # Detect classes by their string constants
        if "https://api.simkl.com" in class_strings:
            simkl_config_classes.add(class_name)
        if "https://api.mdblist.com" in class_strings:
            mdblist_config_classes.add(class_name)

        # Trakt interceptor class or TraktAuthService class
        if "trakt-api-key" in class_strings:
            for idx_s, s in enumerate(class_strings):
                if s == "trakt-api-key" and idx_s + 1 < len(class_strings):
                    cand = class_strings[idx_s + 1].strip()
                    if HEX64_RE.match(cand) and "TRAKT_CLIENT_ID" not in found:
                        found["TRAKT_CLIENT_ID"] = cand
            for s in class_strings:
                if HEX64_RE.match(s) and "TRAKT_CLIENT_ID" not in found:
                    found["TRAKT_CLIENT_ID"] = s

        if "TraktAuthService" in class_strings or "Network error while refreshing token" in class_strings:
            hex64s = []
            for s in class_strings:
                if HEX64_RE.match(s) and s not in hex64s:
                    hex64s.append(s)
            if hex64s:
                if "TRAKT_CLIENT_ID" not in found:
                    found["TRAKT_CLIENT_ID"] = hex64s[0]
                for h in hex64s:
                    if h != found.get("TRAKT_CLIENT_ID") and "TRAKT_CLIENT_SECRET" not in found:
                        found["TRAKT_CLIENT_SECRET"] = h

        # TMDB API Key in ApiKeys class ("api_keys", "_on")
        if "api_keys" in class_strings and "_on" in class_strings:
            for s in class_strings:
                if HEX32_RE.match(s) and "TMDB_API_KEY" not in found:
                    found["TMDB_API_KEY"] = s

    # Second pass across methods using call graph to SimklApiConfiguration and MdbListConfiguration
    for class_name, meth_name, m_strs, m_calls in all_methods:
        called_classes = {c[0] for c in m_calls}
        if (called_classes & simkl_config_classes) or ("https://api.simkl.com" in m_strs and "nuvio" in m_strs):
            for s in m_strs:
                if HEX64_RE.match(s) and s != found.get("TRAKT_CLIENT_ID") and s != found.get("TRAKT_CLIENT_SECRET"):
                    if "SIMKL_CLIENT_ID" not in found:
                        found["SIMKL_CLIENT_ID"] = s
        if (called_classes & mdblist_config_classes) or ("https://api.mdblist.com" in m_strs):
            for s in m_strs:
                s_clean = s.strip()
                if (
                    len(s_clean) >= 16
                    and not s_clean.startswith("http")
                    and not s_clean.startswith("1.")
                    and not s_clean.startswith("0.")
                    and " " not in s_clean
                    and "/" not in s_clean
                    and s_clean not in ("clientId", "appVersion", "baseUrl", "configuration")
                    and s_clean != found.get("TRAKT_CLIENT_ID")
                    and s_clean != found.get("TRAKT_CLIENT_SECRET")
                    and s_clean != found.get("SIMKL_CLIENT_ID")
                ):
                    if "MDBLIST_CLIENT_ID" not in found:
                        found["MDBLIST_CLIENT_ID"] = s_clean

    # Third pass fallback: scan all 64-char hex strings in the entire DEX string table near Simkl/Trakt strings
    for idx, s in enumerate(strings):
        if s == "trakt-api-key":
            for neighbor in strings[max(0, idx - 5):min(len(strings), idx + 6)]:
                if HEX64_RE.match(neighbor) and "TRAKT_CLIENT_ID" not in found:
                    found["TRAKT_CLIENT_ID"] = neighbor
        if s in ("https://api.simkl.com", "NuvioTV", "SIMKL_CLIENT_ID"):
            for neighbor in strings[max(0, idx - 8):min(len(strings), idx + 9)]:
                if HEX64_RE.match(neighbor) and neighbor != found.get("TRAKT_CLIENT_ID") and neighbor != found.get("TRAKT_CLIENT_SECRET"):
                    if "SIMKL_CLIENT_ID" not in found:
                        found["SIMKL_CLIENT_ID"] = neighbor

extracted = {}
for repo in ["NuvioMedia/NuvioTV", "Laking22/Nuvio-Echo"]:
    try:
        req = urllib.request.Request(
            f"https://api.github.com/repos/{repo}/releases?per_page=8",
            headers={"User-Agent": "Mozilla/5.0"}
        )
        with urllib.request.urlopen(req, timeout=25) as resp:
            releases = json.loads(resp.read().decode("utf-8"))
        if not isinstance(releases, list):
            continue
        apk_urls = []
        for rel in releases:
            for asset in rel.get("assets", []):
                name = asset.get("name", "")
                if name.endswith(".apk"):
                    apk_urls.append(asset.get("browser_download_url"))
                    break
            if len(apk_urls) >= 2:
                break
        for apk_i, apk_url in enumerate(apk_urls):
            if not apk_url:
                continue
            apk_path = f"/tmp/{repo.replace('/', '_')}_{apk_i}.apk"
            urllib.request.urlretrieve(apk_url, apk_path)
            with zipfile.ZipFile(apk_path, "r") as zf:
                for name in sorted(zf.namelist()):
                    if name.startswith("classes") and name.endswith(".dex"):
                        analyze_dex(zf.read(name), extracted)
            if all(k in extracted for k in ("TRAKT_CLIENT_ID", "SIMKL_CLIENT_ID", "TMDB_API_KEY")):
                break
    except Exception as e:
        print(f"Note: could not inspect {repo}: {e}")

print("Extracted official keys:", list(extracted.keys()))

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

existing_map = {}
for line in existing_lines:
    if "=" in line and not line.strip().startswith("#"):
        k, v = line.split("=", 1)
        if v.strip():
            existing_map[k.strip()] = v.strip()

with open(local_props_path, "a") as f:
    for k in target_keys:
        if k in extracted and extracted[k] and k not in existing_map:
            val = extracted[k].replace("\\", "\\\\").replace("\n", "")
            f.write(f"\n{k}={val}\n")
