#!/usr/bin/env python3
"""
buildPlugin 成功后自动调用：把压缩包上传到飞书 base 表格。

目标：
- 在 base BKBVbBr6YaM1ibsAxYVcjklbnec / 优化提单 (tbl25GYcFPtKqboB) 中
  找到「允许修复=已修复」+「完成状态=最新版本包」的最新一行
- 把 build/distributions/*.zip 上传到该行的 attachment 字段
- 没有合适 attachment 字段时，自动新建一个「最新版本包」字段

调用方式（设计）：
  python3 scripts/upload_plugin_to_feishu_base.py            # 正常上传
  python3 scripts/upload_plugin_to_feishu_base.py --dry-run  # 只打印匹配结果，不上传

设计原则：
- 不解压 / 不查看 zip 内部内容（避免触发安全扫描）
- attachment 字段选择策略：取 attachment 类型字段的第一个（如「异常截图1」「异常截图2」）
  → 后续可以扩展为按 .zip 文件名匹配优先
- 缺 scope 时 fail-fast 提示用户去授权（一次性），不反复重试
- 失败不阻塞 build：返回非 0 但不抛 stack，让 Gradle task 用 finalizedBy 触发，不影响打包本身

依赖：lark-cli 已登录并具备 base:record:read / base:record:write / base:field:write / base:file:write
（首次跑会因缺 scope 失败；运行 `lark-cli auth login --scope "..." --no-wait --json` 完成授权）
"""

import argparse
import json
import os
import subprocess
import sys
from pathlib import Path

# 飞书 base 配置
BASE_TOKEN = "BKBVbBr6YaM1ibsAxYVcjklbnec"
TABLE_ID = "tbl25GYcFPtKqboB"

# 字段 ID（与 scripts/feishu_base_schema.md 保持一致）
FIELD_ALLOW_FIX = "fldIXEu4H4"   # 「允许修复」select
FIELD_STATUS = "fldRAJJ5yD"       # 「完成状态」select
FIELD_ATTACHMENT_CANDIDATES = ("fldq1Qu5je", "fldKx8Ps00")  # 「异常截图1」「异常截图2」

# 筛选条件
TARGET_ALLOW_FIX = "已修复"
TARGET_STATUS = "最新版本包"

# 路径
ROOT_DIR = Path(__file__).resolve().parents[1]
DIST_DIR = ROOT_DIR / "build" / "distributions"


def run(cmd, *, check=True):
    """Run a subprocess; return CompletedProcess. check=False 自行处理."""
    return subprocess.run(cmd, capture_output=True, text=True, check=check)


def lark_json(*args):
    """Run lark-cli and parse JSON. Returns dict with 'ok' key (False on parse/cmd failure)."""
    p = run(["lark-cli", *args], check=False)
    if p.returncode != 0:
        return {"ok": False, "_stderr": p.stderr.strip(), "_stdout": p.stdout.strip(), "_code": p.returncode}
    try:
        return json.loads(p.stdout)
    except json.JSONDecodeError:
        return {"ok": False, "_raw": p.stdout, "_stderr": p.stderr}


def find_plugin_zip():
    """Return the most recently built plugin zip, or None."""
    if not DIST_DIR.is_dir():
        return None
    zips = sorted(DIST_DIR.glob("*.zip"), key=lambda p: p.stat().st_mtime, reverse=True)
    return zips[0] if zips else None


def list_records():
    """List all records across pagination."""
    records = []
    page_token = None
    while True:
        args = ["base", "+record-list",
                "--base-token", BASE_TOKEN,
                "--table-id", TABLE_ID,
                "--page-size", "200"]
        if page_token:
            args += ["--page-token", page_token]
        data = lark_json(*args)
        if not data.get("ok"):
            return None, data
        items = (data.get("data") or {}).get("items") or []
        records.extend(items)
        if not (data.get("data") or {}).get("has_more"):
            break
        page_token = (data.get("data") or {}).get("page_token")
    return records, None


def get_field_text(field_value):
    """Extract display text from a select/multi_select field value."""
    if field_value is None:
        return None
    if isinstance(field_value, str):
        return field_value
    if isinstance(field_value, dict):
        # 新格式: {"text": "已修复", "type": "text"}
        return field_value.get("text") or field_value.get("name")
    if isinstance(field_value, list) and field_value:
        # 多选：取第一个
        return get_field_text(field_value[0])
    return None


def is_target_record(record):
    """是否满足「允许修复=已修复」+「完成状态=最新版本包」."""
    fields = record.get("fields") or {}
    af = get_field_text(fields.get(FIELD_ALLOW_FIX))
    st = get_field_text(fields.get(FIELD_STATUS))
    return af == TARGET_ALLOW_FIX and st == TARGET_STATUS


def pick_attachment_field(fields_def):
    """从字段定义里挑出 attachment 类型字段 id（按候选顺序）。"""
    by_id = {f["id"]: f for f in fields_def}
    for fid in FIELD_ATTACHMENT_CANDIDATES:
        if fid in by_id and by_id[fid].get("type") == "attachment":
            return fid
    # 兜底：取任意 attachment 字段
    for f in fields_def:
        if f.get("type") == "attachment":
            return f["id"]
    return None


def list_fields():
    data = lark_json("base", "+field-list",
                     "--base-token", BASE_TOKEN,
                     "--table-id", TABLE_ID)
    if not data.get("ok"):
        return None, data
    return (data.get("data") or {}).get("fields") or [], None


def ensure_attachment_field(fields_def, name="最新版本包"):
    """没有 attachment 字段时新建一个，返回 (field_id, created_info_or_error)。"""
    for f in fields_def:
        if f.get("type") == "attachment" and f.get("name") == name:
            return f["id"], None  # 已存在同名
    data = lark_json("base", "+field-create",
                     "--base-token", BASE_TOKEN,
                     "--table-id", TABLE_ID,
                     "--data", json.dumps({
                         "field_name": name,
                         "type": 17,  # Attachment
                     }, ensure_ascii=False))
    if not data.get("ok"):
        return None, data
    return (data.get("data") or {}).get("field", {}).get("id"), None


def upload_file_then_get_token(zip_path):
    """
    调飞书 OpenAPI 上传本地文件到 drive，返回 file_token。
    走 raw api 调用（lark-cli api POST /open-apis/drive/v1/files/upload_all）。
    返回 (file_token, None) 或 (None, error_data)。
    """
    if not zip_path.exists():
        return None, {"error": f"file not found: {zip_path}"}
    p = run([
        "lark-cli", "api", "POST",
        "/open-apis/drive/v1/files/upload_all",
        "--data", json.dumps({
            "file_name": zip_path.name,
            "parent_type": "explorer",
            "parent_node": "root",
            "size": str(zip_path.stat().st_size),
        }),
        "--file", str(zip_path),
    ], check=False)
    if p.returncode != 0:
        return None, {"stderr": p.stderr.strip(), "stdout": p.stdout.strip(), "code": p.returncode}
    try:
        data = json.loads(p.stdout)
    except json.JSONDecodeError:
        return None, {"raw": p.stdout, "stderr": p.stderr.strip()}
    if not data.get("ok"):
        return None, data
    file_token = ((data.get("data") or {}).get("file_token")
                  or (data.get("data") or {}).get("file", {}).get("file_token"))
    if not file_token:
        return None, {"error": "no file_token in response", "raw": data}
    return file_token, None


def update_record_attachment(record_id, field_id, file_token, file_name):
    """把 file_token 写入记录的 attachment 字段。返回 (ok, error_data)。"""
    data = lark_json("base", "+record-update",
                     "--base-token", BASE_TOKEN,
                     "--table-id", TABLE_ID,
                     "--record-id", record_id,
                     "--data", json.dumps({
                         "fields": {
                             field_id: [{"file_token": file_token, "name": file_name}]
                         }
                     }, ensure_ascii=False))
    return data.get("ok"), (None if data.get("ok") else data)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true",
                    help="只打印匹配的目标行和字段，不实际上传")
    args = ap.parse_args()

    # Gradle task 下放到这里检查：feishuUploadPlugin / FEISHU_UPLOAD_PLUGIN
    # 关闭时直接退出，不调任何 lark-cli。
    enabled = (os.environ.get("FEISHU_UPLOAD_PLUGIN")
               or os.environ.get("FEISHU_UPLOAD_ENABLED")
               or "true")
    if enabled.lower() not in ("true", "1"):
        print(f"[skip] feishu upload disabled (FEISHU_UPLOAD_PLUGIN={enabled})", file=sys.stderr)
        return 0

    zip_path = find_plugin_zip()
    if not zip_path:
        print(f"[skip] no plugin zip found in {DIST_DIR}", file=sys.stderr)
        return 0
    print(f"[info] target zip: {zip_path.name} ({zip_path.stat().st_size} bytes)")

    # 1. 列记录
    records, err = list_records()
    if records is None:
        print(f"[fail] list records: {json.dumps(err, ensure_ascii=False)}", file=sys.stderr)
        if err.get("_stderr") and "missing required scope" in err["_stderr"]:
            print("[hint] run: lark-cli auth login --scope 'base:record:read' --no-wait --json", file=sys.stderr)
        return 2

    # 2. 筛选 + 排序
    targets = [r for r in records if is_target_record(r)]
    targets.sort(key=lambda r: r.get("created_time", 0) or 0, reverse=True)
    print(f"[info] matched {len(targets)} target record(s)")
    if not targets:
        print("[warn] no record matched 「允许修复=已修复」+「完成状态=最新版本包」; skip", file=sys.stderr)
        return 0
    target = targets[0]
    target_id = target.get("record_id")
    target_text = (target.get("fields") or {}).get("fldbVEZP22", {}).get("text", "") \
        if isinstance((target.get("fields") or {}).get("fldbVEZP22"), dict) else ""
    print(f"[info] target record: id={target_id}, created={target.get('created_time')}, "
          f"agent_instr={target_text!r}")

    # 3. 找 / 建 attachment 字段
    fields_def, err = list_fields()
    if fields_def is None:
        print(f"[fail] list fields: {json.dumps(err, ensure_ascii=False)}", file=sys.stderr)
        return 2

    field_id = pick_attachment_field(fields_def)
    if not field_id:
        if args.dry_run:
            print("[dry-run] would create new attachment field 「最新版本包」")
            return 0
        print("[info] no attachment field found; creating 「最新版本包」")
        new_fid, err = ensure_attachment_field(fields_def, "最新版本包")
        if err:
            print(f"[fail] create field: {json.dumps(err, ensure_ascii=False)}", file=sys.stderr)
            if "missing required scope" in (err.get("_stderr") or ""):
                print("[hint] run: lark-cli auth login --scope 'base:field:write' --no-wait --json", file=sys.stderr)
            return 2
        field_id = new_fid
        print(f"[info] created attachment field id={field_id}")
    else:
        print(f"[info] using attachment field id={field_id} "
              f"({next((f['name'] for f in fields_def if f['id'] == field_id), '?')})")

    if args.dry_run:
        print(f"[dry-run] would upload {zip_path.name} to record {target_id} field {field_id}")
        return 0

    # 4. 上传文件到 drive
    file_token, err = upload_file_then_get_token(zip_path)
    if not file_token:
        print(f"[fail] upload file: {json.dumps(err, ensure_ascii=False)}", file=sys.stderr)
        if err.get("_stderr") and "missing required scope" in err["_stderr"]:
            print("[hint] run: lark-cli auth login --scope 'base:file:write drive:file:upload' --no-wait --json", file=sys.stderr)
        return 2
    print(f"[info] uploaded file_token={file_token}")

    # 5. 写回记录
    ok, err = update_record_attachment(target_id, field_id, file_token, zip_path.name)
    if not ok:
        print(f"[fail] update record: {json.dumps(err, ensure_ascii=False)}", file=sys.stderr)
        if err.get("_stderr") and "missing required scope" in err["_stderr"]:
            print("[hint] run: lark-cli auth login --scope 'base:record:write' --no-wait --json", file=sys.stderr)
        return 2
    print(f"[ok] updated record {target_id} field {field_id} with {zip_path.name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
