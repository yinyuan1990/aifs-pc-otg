#!/usr/bin/env bash
# ============================================================
# coturn 加 443 兜底端口 v2 — iptables NAT REDIRECT 方案
#   - 老版本 coturn (4.5.x) 不支持 alt-listening-port 真正开第二端口
#   - 改用内核 NAT 透明转发: 443/tcp+udp → 3478/tcp+udp
#   - coturn 配置零修改，对外只新增 443 入口
#   - 通过 ufw 的 before.rules 做持久化（重启不丢失）
#
# 用法:
#   sudo bash coturn-add-443-v2.sh
#
# 回滚: coturn-rollback-443-v2.sh
# ============================================================

set -euo pipefail

if [[ -t 1 ]]; then
    RED='\033[31m'; GRN='\033[32m'; YLW='\033[33m'; CYN='\033[36m'; RST='\033[0m'
else
    RED=''; GRN=''; YLW=''; CYN=''; RST=''
fi

CONF=/etc/turnserver.conf
UFW_BEFORE=/etc/ufw/before.rules
TS=$(date +%Y%m%d-%H%M%S)
NAT_MARK_BEGIN="# === BEGIN coturn-443-redirect (deployed by coturn-add-443-v2.sh) ==="
NAT_MARK_END="# === END coturn-443-redirect ==="

step() { echo -e "${CYN}[$1]${RST} $2"; }
ok()   { echo -e "${GRN}✅ $1${RST}"; }
warn() { echo -e "${YLW}⚠️  $1${RST}"; }
err()  { echo -e "${RED}❌ $1${RST}"; }

# ---- 0. 前置 ----
[[ $EUID -eq 0 ]] || { err "请用 root 或 sudo 执行"; exit 1; }
[[ -f "$CONF" ]] || { err "找不到 $CONF"; exit 1; }
[[ -f "$UFW_BEFORE" ]] || { err "找不到 $UFW_BEFORE，本机 ufw 未安装/异常"; exit 1; }

# ---- 1. 清理 v1 留下的 alt-listening-port 配置 ----
step "1/7" "清理 v1 残留: 去除 alt-listening-port=443 (如果存在)"
if grep -q "^alt-listening-port" "$CONF"; then
    cp -p "$CONF" "${CONF}.bak.${TS}"
    sed -i '/^# === alt-listening-port: 443/d' "$CONF"
    sed -i '/^alt-listening-port=443/d' "$CONF"
    # 清理可能残留的紧邻空行注释
    sed -i '/^# === 兜底端口（应对运营商 3478 QoS 限速）===$/d' "$CONF"
    ok "已清理 v1 残留 (备份: ${CONF}.bak.${TS})"
    NEED_RESTART=1
else
    warn "没有 v1 残留，跳过"
    NEED_RESTART=0
fi

# ---- 2. 备份 before.rules ----
step "2/7" "备份 $UFW_BEFORE → ${UFW_BEFORE}.bak.${TS}"
cp -p "$UFW_BEFORE" "${UFW_BEFORE}.bak.${TS}"

# ---- 3. 写入 NAT REDIRECT 段 (幂等) ----
step "3/7" "在 ufw before.rules 顶部插入 NAT REDIRECT 段"
if grep -q "$NAT_MARK_BEGIN" "$UFW_BEFORE"; then
    warn "NAT 段已存在，跳过写入（如需更新请先回滚）"
else
    # 构造 NAT 块，写到临时文件再合并 (避免 sed 转义地狱)
    NAT_BLOCK=$(mktemp)
    cat > "$NAT_BLOCK" <<EOF
$NAT_MARK_BEGIN
*nat
:PREROUTING ACCEPT [0:0]
:POSTROUTING ACCEPT [0:0]
-A PREROUTING -p tcp --dport 443 -j REDIRECT --to-ports 3478
-A PREROUTING -p udp --dport 443 -j REDIRECT --to-ports 3478
COMMIT
$NAT_MARK_END

EOF
    # 把 NAT 块拼到 before.rules 最前面
    cat "$NAT_BLOCK" "$UFW_BEFORE" > "${UFW_BEFORE}.new"
    mv "${UFW_BEFORE}.new" "$UFW_BEFORE"
    chmod 640 "$UFW_BEFORE"
    rm -f "$NAT_BLOCK"
    ok "NAT 段已插入"
fi

# ---- 4. ufw 放行 443（双向标志，配 NAT 之前/之后都生效）----
step "4/7" "ufw 放行 443/tcp + 443/udp"
ufw allow 443/tcp comment "TURN alt-port" >/dev/null 2>&1 || true
ufw allow 443/udp comment "TURN alt-port" >/dev/null 2>&1 || true
ok "ufw allow 规则已确认"

# ---- 5. ufw reload (使 NAT 生效) ----
step "5/7" "重载 ufw"
ufw reload >/dev/null 2>&1
ok "ufw 已重载"

# ---- 6. 如有需要，重启 coturn（清理 v1 alt-listening-port 才需要）----
if [[ "$NEED_RESTART" -eq 1 ]]; then
    step "6/7" "因移除 v1 配置，重启 coturn"
    systemctl restart coturn
    sleep 3
    if systemctl is-active --quiet coturn; then
        ok "coturn 重启成功"
    else
        err "coturn 启动失败！查 journalctl -u coturn -n 80"
        exit 1
    fi
else
    step "6/7" "无需重启 coturn (NAT 是内核层，与 coturn 无关)"
fi

# ---- 7. 验证 ----
step "7/7" "验证 NAT REDIRECT 规则 + 端口监听"
echo
echo "--- iptables nat PREROUTING ---"
iptables -t nat -L PREROUTING -n --line-numbers | grep -E "REDIRECT|Chain" | sed 's/^/  /'
echo
echo "--- 监听端口 (coturn 仅在 3478, 不在 443) ---"
ss -tunlp 2>/dev/null | grep -E ":443 |:3478 " | sort | sed 's/^/  /' || echo "  (空)"
echo

NAT_TCP=$(iptables -t nat -L PREROUTING -n 2>/dev/null | grep -c "tcp dpt:443 redir ports 3478" || true)
NAT_UDP=$(iptables -t nat -L PREROUTING -n 2>/dev/null | grep -c "udp dpt:443 redir ports 3478" || true)

echo "  NAT 443 TCP REDIRECT 规则数: $NAT_TCP"
echo "  NAT 443 UDP REDIRECT 规则数: $NAT_UDP"
echo

if [[ "$NAT_TCP" -ge 1 ]] && [[ "$NAT_UDP" -ge 1 ]]; then
    ok "NAT REDIRECT 已生效，部署完成"
    echo
    echo -e "${CYN}下一步:${RST}"
    echo "  1. 外网测试: nc -zv <本机公网IP> 443  (Mac/任意机器都行)"
    echo "  2. 实时看 coturn 日志: tail -f /opt/yql/turn.txt"
    echo "  3. 看 NAT 命中计数（每隔几秒刷新）: watch -n 2 'iptables -t nat -L PREROUTING -nv | grep 443'"
    echo "  4. 灰度观察 5-10 分钟无异常后再上下一台"
    echo "  5. 后端登录接口下发的 ICE servers JSON 加上 turn:<IP>:443?transport=tcp"
    echo
    echo -e "${YLW}回滚:${RST} sudo bash coturn-rollback-443-v2.sh"
else
    err "NAT 规则没生效！可能 ufw reload 失败"
    err "回滚: sudo bash coturn-rollback-443-v2.sh"
    exit 1
fi
