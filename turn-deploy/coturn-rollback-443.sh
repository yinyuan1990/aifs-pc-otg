#!/usr/bin/env bash
# ============================================================
# coturn 加 443 端口 — 回滚脚本
#   - 还原最近一次 .bak.* 备份
#   - 移除 ufw 443 规则
#   - 重启 coturn
#
# 用法:
#   sudo bash coturn-rollback-443.sh
# ============================================================

set -euo pipefail

if [[ -t 1 ]]; then
    RED='\033[31m'; GRN='\033[32m'; YLW='\033[33m'; CYN='\033[36m'; RST='\033[0m'
else
    RED=''; GRN=''; YLW=''; CYN=''; RST=''
fi

CONF=/etc/turnserver.conf

step() { echo -e "${CYN}[$1]${RST} $2"; }
ok()   { echo -e "${GRN}✅ $1${RST}"; }
warn() { echo -e "${YLW}⚠️  $1${RST}"; }
err()  { echo -e "${RED}❌ $1${RST}"; }

if [[ $EUID -ne 0 ]]; then
    err "请用 root 或 sudo 执行"
    exit 1
fi

# ---- 1. 找最近一次备份 ----
step "1/4" "查找最近一次备份"
LATEST=$(ls -t ${CONF}.bak.* 2>/dev/null | head -1 || true)
if [[ -z "${LATEST:-}" ]]; then
    err "找不到 ${CONF}.bak.* 备份文件，无法自动回滚"
    err "请手动从 $CONF 移除 alt-listening-port=443 那两行"
    exit 1
fi
ok "找到备份: $LATEST"

# ---- 2. 还原配置 ----
step "2/4" "还原 $CONF"
cp -p "$LATEST" "$CONF"
ok "已还原"

# ---- 3. 移除 ufw 规则 ----
step "3/4" "移除 ufw 443 规则"
if command -v ufw >/dev/null 2>&1; then
    ufw delete allow 443/tcp 2>/dev/null || true
    ufw delete allow 443/udp 2>/dev/null || true
    ok "ufw 规则已移除"
else
    warn "未检测到 ufw，跳过"
fi

# ---- 4. 重启 coturn ----
step "4/4" "重启 coturn"
systemctl restart coturn
sleep 3
if systemctl is-active --quiet coturn; then
    ok "coturn 重启成功"
else
    err "coturn 启动失败！查 journalctl -u coturn -n 50"
    exit 1
fi

echo
echo "--- 当前监听端口 ---"
ss -tunlp 2>/dev/null | grep -E ":443 |:3478 " | sort | sed 's/^/  /'
echo
ok "回滚完成"
