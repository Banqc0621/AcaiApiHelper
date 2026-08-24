# Round 1 — 左侧导航与数据入口

- Task ID: `RAL-UI-OPT-20260814`
- 开发者：Banqc
- Lens: 功能正确性与信息架构
- Baseline: 扫描、导入、环境、数据和收藏入口分散。
- Changes: 扫描入口前置；cURL 导入移至接口右键；环境与数据弹窗合并；收藏多选操作；节点 A 将统一入口移动到左侧“…”并新增收藏列表安全交换。
- Evidence: commits `6132122`, `3adaf31`, `7d58f5f`, `28822a1`, `0e048ba`；冲突合并和格式拒绝测试通过。
- Review finding: 自动门禁已通过；仍需目标 IDE 人工检查左侧窄屏菜单位置。
- Result: 节点 A 通过自动验收。
