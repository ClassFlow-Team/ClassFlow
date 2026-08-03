import subprocess
import os
import re
import sys 
from datetime import date

# --- 1. 常量与配置 ---

COMMIT_TYPES = {
    'feat': '✨ 新增功能 (Features)',
    'fix': '🐛 Bug 修复 (Bug Fixes)',
    'improve': '💡 功能与体验优化 (Improvements)',
    'perf': '🚀 性能与代码改进 (Improvements)',
    'refactor': '🚀 性能与代码改进 (Improvements)',
    'style': '🚀 性能与代码改进 (Improvements)',
    'docs': '📚 文档更新 (Documentation)',
}

# 维护性提交将被排除在更新日志之外
EXCLUDED_TYPES = ['chore', 'ci', 'build', 'test']
OTHER_CATEGORY = '🚧 其他提交 (Other Commits)'
CHANGELOG_PATH = 'CHANGELOG.md'

# --- 2. 辅助函数 ---

def get_first_commit_hash():
    try:
        return subprocess.check_output('git rev-list --max-parents=0 HEAD', shell=True, text=True, encoding='utf-8').strip()
    except Exception:
        return None

def is_valid_ref(ref):
    if not ref: return False
    try:
        subprocess.check_output(f'git rev-parse --verify {ref}', shell=True, text=True, encoding='utf-8', stderr=subprocess.DEVNULL)
        return True
    except Exception:
        return False

def generate_changelog(version_title, previous_tag):
    # 1. 验证标签逻辑保持不变
    if previous_tag and not is_valid_ref(previous_tag):
        print(f"警告：指定的对比标签 '{previous_tag}' 不存在，将从头开始计算。")
        previous_tag = None

    # 2. 确定范围
    if not previous_tag:
        initial_commit = get_first_commit_hash()
        range_str = f"{initial_commit}...HEAD" if initial_commit else "HEAD"
    else:
        range_str = f"{previous_tag}...HEAD"

    # 3. 执行 Git Log
    log_format = '%H|||%s|||%an'
    log_command = f'git -c i18n.logOutputEncoding=UTF-8 log --pretty=format:"{log_format}" {range_str}'

    try:
        logs_output = subprocess.check_output(log_command, shell=True, text=True, encoding='utf-8').strip()
        logs = logs_output.split('\n')
    except Exception as e:
        print(f"执行 git log 失败: {e}")
        return

    # 4. 解析与分类 (核心修正：增强正则以支持中文冒号和空格)
    categories = {}
    # 支持 : 或 ： 后面跟可选空格
    commit_regex = re.compile(r'^(\w+)(?:\([^)]+\))?[:：]\s*(.*)', re.UNICODE)

    for log in logs:
        if not log or '|||' not in log: continue
        parts = log.split('|||')
        if len(parts) < 3: continue

        commit_hash, subject, author = parts[0], parts[1], parts[2]
        match = commit_regex.match(subject)

        description = subject
        category_title = OTHER_CATEGORY

        if match:
            type_prefix = match.group(1).lower()
            description = match.group(2)
            if type_prefix in EXCLUDED_TYPES: continue
            category_title = COMMIT_TYPES.get(type_prefix, OTHER_CATEGORY)
        else:
            # 过滤掉合并提交和排除类型
            if subject.startswith('Merge ') or any(subject.startswith(ex + ':') for ex in EXCLUDED_TYPES):
                continue

        if category_title not in categories:
            categories[category_title] = []
        categories[category_title].append(f"- {description}")

    # 5. 生成 Markdown
    new_changelog = f"## {version_title}\n\n"

    ordered_titles = [
        COMMIT_TYPES['feat'], COMMIT_TYPES['fix'], COMMIT_TYPES['improve'],
        COMMIT_TYPES['perf'], COMMIT_TYPES['docs'], OTHER_CATEGORY
    ]

    has_content = False
    for title in ordered_titles:
        if title in categories and categories[title]:
            new_changelog += f"### {title}\n\n"
            new_changelog += '\n'.join(categories[title]) + '\n\n'
            has_content = True

    if not has_content:
        print("警告: 范围内没有有效的提交记录。")
        return

    # 6. 写入文件
    with open(CHANGELOG_PATH, 'w', encoding='utf-8') as f:
        f.write(new_changelog)

    print(f"CHANGELOG.md 已更新: {version_title}")

if __name__ == '__main__':
    try:
        v_title = sys.argv[1] if len(sys.argv) > 1 else f"v{date.today().isoformat()}"
        p_tag = sys.argv[2] if len(sys.argv) > 2 else None
        generate_changelog(v_title, p_tag)
    except Exception as e:
        import traceback
        traceback.print_exc(file=sys.stdout)
        sys.exit(1)