#!/usr/bin/env bash
# ============================================================
# coturn 加 443 v2 — 回滚脚本
#   - 移除 ufw before.rules 里的 NAT REDIRECT 段
#   - 移除 ufw 443 allow 规则
#   - 还原可能被 v1 改过的 turnserver.conf
# ============================================================

set -euo pipefail

if [[ -t 1 ]]; then
    RED='\033[31m'; GRN='\033[32m'; YLW='\033[33m'; CYN='\033[36m'; RST='\033[0m'
else
    RED=''; GRN=''; YLW=''; CYN=''; RST=''
fi

CONF=/etc/turnserver.conf
UFW_BEFORE=/etc/ufw/before.rules
NAT_MARK_BEGIN="# === BEGIN coturn-443-redirect"
NAT_MARK_END="# === END coturn-443-redirect"

step() { echo -e "${CYN}[$1]${RST} $2"; }
ok()   { echo -e "${GRN}✅ $1${RST}"; }
warn() { echo -e "${YLW}⚠️  $1${RST}"; }
err()  { echo -e "${RED}❌ $1${RST}"; }

[[ $EUID -eq 0 ]] || { err "请用 root 或 sudo 执行"; exit 1; }

# ---- 1. 删除 before.rules 里的 NAT 块 ----
step "1/4" "从 $UFW_BEFORE 移除 NAT REDIRECT 段"
if grep -q "$NAT_MARK_BEGIN" "$UFW_BEFORE"; then
    TS=$(date +%Y%m%d-%H%M%S)
    cp -p "$UFW_BEFORE" "${UFW_BEFORE}.rb.${TS}"
    sed -i "/${NAT_MARK_BEGIN//\//\\/}/,/${NAT_MARK_END//\//\\/}/d" "$UFW_BEFORE"
    # 清理紧随其后的孤立空行
    sed -i '/./,$!d' "$UFW_BEFORE"
    ok "NAT 段已移除 (备份: ${UFW_BEFORE}.rb.${TS})"
else
    warn "未发现 NAT 段，跳过"
fi

# ---- 2. 移除 ufw 443 规则 ----
step "2/4" "移除 ufw 443 allow 规则"
ufw delete allow 443/tcp >/dev/null 2>&1 || true
ufw delete allow 443/udp >/dev/null 2>&1 || true
ok "ufw 规则已清理"

# ---- 3. 还原 turnserver.conf (如果 v1 改过) ----
step "3/4" "检查并还原 turnserver.conf"
if grep -q "^alt-listening-port=443" "$CONF" 2>/dev/null; then
    LATEST_BAK=$(ls -t ${CONF}.bak.* 2>/dev/null | head -1 || true)
    if [[ -n "${LATEST_BAK:-}" ]]; then
        cp -p "$LATEST_BAK" "$CONF"
        ok "已还原 $CONF (从 $LATEST_BAK)"
        systemctl restart coturn && sleep 2
        if systemctl is-active --quiet coturn; then
            ok "coturn 重启成功"
        else
            err "coturn 重启失败！journalctl -u coturn -n 50"
        fi
    else
        warn "找不到 turnserver.conf 备份，手动检查 alt-listening-port 是否要删"
    fi
else
    warn "turnserver.conf 中无 alt-listening-port=443，跳过"
fi

# ---- 4. 重载 ufw ----
step "4/4" "重载 ufw"
ufw reload >/dev/null 2>&1
ok "ufw 已重载"

echo
echo "--- 当前 NAT PREROUTING ---"
iptables -t nat -L PREROUTING -n | sed 's/^/  /'
echo
echo "--- 当前监听端口 ---"
ss -tunlp 2>/dev/null | grep -E ":443 |:3478 " | sort | sed 's/^/  /' || echo "  (无 443/3478)"
echo
ok "回滚完成"
