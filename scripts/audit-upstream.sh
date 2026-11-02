#!/usr/bin/env bash
# 上游对齐审计：每次与上游 merge 后运行，对照 CLASSFLOW_CUSTOMIZATIONS.md 检查遗漏
# 用法: bash scripts/audit-upstream.sh [上游基线]
#   [上游基线] 默认 3eb39c2（当前同步点；merge 新版本时传入新的上游 HEAD）
set -e
cd "$(dirname "$0")/.."
UPSTREAM_REF="${1:-3eb39c2}"

echo "=============================================="
echo "上游基线: $UPSTREAM_REF"
echo "对照基准: CLASSFLOW_CUSTOMIZATIONS.md"
echo "=============================================="

echo ""
echo "=== 差异文件（按差异量排序，对照清单逐文件确认）==="
git diff "$UPSTREAM_REF" --stat -- app/src/main/java app/src/main/res \
  | sort -t'|' -k2 -rn | head -60

echo ""
echo "=== 零差异文件验证（应全部为 0 行）==="
for f in \
  "app/src/main/java/com/xingheyuzhuan/shiguangschedule/ui/theme/Color.kt" \
  "app/src/main/java/com/xingheyuzhuan/shiguangschedule/ui/theme/Theme.kt" \
  "app/src/main/java/com/xingheyuzhuan/shiguangschedule/ui/schedule/components/CourseBlock.kt" \
  "app/src/main/java/com/xingheyuzhuan/shiguangschedule/ui/schedule/components/ScheduleGridComponents.kt" \
  "app/src/main/java/com/xingheyuzhuan/shiguangschedule/ui/schedule/components/ScheduleGrid.kt"; do
  n=$(git diff "$UPSTREAM_REF" -- "$f" | grep -c '^[+-]' || true)
  if [ "$n" -eq 0 ]; then
    echo "OK   (0)  $f"
  else
    echo "DIFF ($n)  $f  ← 清单中 ScheduleGrid 允许 ~38 行（玻璃光边），其余必须为 0"
  fi
done

echo ""
echo "=== 持有修复的文件（修复标记必须存在，否则说明上游 bug 被 merge 回退）==="
grep -q "val nameToCreate = newTableName" \
  "app/src/main/java/com/xingheyuzhuan/shiguangschedule/ui/components/CourseTablePickerDialog.kt" \
  && echo "OK   CourseTablePickerDialog.kt 空名修复标记存在" \
  || echo "MISS CourseTablePickerDialog.kt 空名修复丢失（协程读已清空状态 → 空名课表）！"

echo ""
echo "=== 提醒 ==="
echo "1. 清单外的文件出现差异 = 遗漏，必须处理"
echo "2. merge 冲突区域只处理冲突，非冲突区域的遗留差异靠本脚本的 --stat 全量审计发现"

echo ""
echo "=== 包边界守卫（上游新增文件不得落在 com/shiro/classflow 路径上）==="
# MainActivity.kt 有意例外：它是唯一被搬进 com/shiro/classflow 的上游文件（见 CLASSFLOW_CUSTOMIZATIONS.md）
overlap=$(comm -12 \
  <(git ls-tree -r --name-only "$UPSTREAM_REF" -- app/src/main/java \
      | sed 's#com/xingheyuzhuan/shiguangschedule#com/shiro/classflow#' | sort) \
  <(git ls-files -- app/src/main/java/com/shiro/classflow | sort) \
  | grep -v '^app/src/main/java/com/shiro/classflow/MainActivity\.kt$' || true)
if [ -z "$overlap" ]; then
  echo "OK   上游路径与 com/shiro/classflow 无重叠"
else
  echo "WARN 上游出现与我们同路径的文件（merge 时可能 add/add 冲突）："
  echo "$overlap"
fi
