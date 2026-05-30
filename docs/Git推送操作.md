# Git 推送操作（aifs-pc）

仓库远程：`https://github.com/yinyuan1990/aifs-pc.git`  
默认分支：`master`

## 一、日常推送（改完代码要推到 GitHub）

在项目根目录打开终端（Windows 可用 **Git Bash** 或 Qt Creator 自带终端），执行：

```bash
# 1. 进入仓库目录（按你本机路径改）
cd D:/javafx/Acard/aic/Aifs

# 2. 看改了哪些文件
git status

# 3. 把要提交的文件加入暂存区（全部改动）
git add .

# 或只提交指定文件，例如：
# git add MainPage.qml CMakeLists.txt

# 4. 写提交说明并提交
git commit -m "简短说明这次改了什么"

# 5. 推到远程 master
git push
```

若 `git push` 提示没有上游分支，第一次可执行：

```bash
git push -u origin master
```

之后只需 `git push`。

## 二、别人推过之后，你先拉再推

避免覆盖他人提交，推送前建议：

```bash
git pull
# 若有冲突，解决冲突后再：
git add .
git commit -m "解决合并冲突"
git push
```

## 三、在另一台电脑拉最新代码（例如 Windows 构建机）

```bash
cd D:/javafx/Acard/aic/Aifs
git pull
```

拉完后若改了 CMake / QML 预编译相关，建议在 Qt Creator 里 **清理并重新构建**。

## 四、查看最近提交

```bash
git log -5 --oneline
```

## 五、常见问题

| 现象 | 处理 |
|------|------|
| `nothing to commit, working tree clean` | 没有未提交改动，无需 `add`/`commit`，直接 `git push` 即可（若本地已领先远程） |
| `rejected` / `non-fast-forward` | 先 `git pull`，解决冲突后再 `git push` |
| 忘记提交就关了电脑 | 改动仍在工作区，`git status` 能看到，再按「一」执行 |
| 想撤销未提交的单个文件 | `git restore 文件名` |

## 六、不要做的事（除非你很清楚后果）

- 不要对 `master` 使用 `git push --force`
- 不要提交密钥、`.env`、含密码的配置文件
- 不要随意改全局 `git config`（用户名邮箱可在本仓库单独配置）

## 七、本仓库首次克隆（新机器）

```bash
git clone https://github.com/yinyuan1990/aifs-pc.git Aifs
cd Aifs
```

然后用 Qt Creator 打开该目录下的 `CMakeLists.txt` 配置 Kit 并构建。
