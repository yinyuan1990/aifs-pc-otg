#!/usr/bin/env bash
# ============================================================
# coturn 加 443 兜底端口部署脚本
#   - 应对国内运营商对 3478 端口的 QoS 限速
#   - 同时听 3478 (主) + 443 (alt)，跑同一个 coturn 进程
#   - 幂等：重复执行不会重复加配置
#
# 用法:
#   sudo bash coturn-add-443.sh
#
# 回滚: 同目录的 coturn-rollback-443.sh
# ============================================================

set -euo pipefail

# ---- 颜色 ----
if [[ -t 1 ]]; then
    RED='\033[31m'; GRN='\033[32m'; YLW='\033[33m'; CYN='\033[36m'; RST='\033[0m'
else
    RED=''; GRN=''; YLW=''; CYN=''; RST=''
fi

CONF=/etc/turnserver.conf
TS=$(date +%Y%m%d-%H%M%S)
BAK="${CONF}.bak.${TS}"
MARK="# === alt-listening-port: 443 兜底端口 (deployed by coturn-add-443.sh) ==="

step() { echo -e "${CYN}[$1]${RST} $2"; }
ok()   { echo -e "${GRN}✅ $1${RST}"; }
warn() { echo -e "${YLW}⚠️  $1${RST}"; }
err()  { echo -e "${RED}❌ $1${RST}"; }

# ---- 0. 前置检查 ----
if [[ $EUID -ne 0 ]]; then
    err "请用 root 或 sudo 执行"
    exit 1
fi

if [[ ! -f "$CONF" ]]; then
    err "找不到 $CONF，coturn 是否已安装？"
    exit 1
fi

if ! systemctl is-active --quiet coturn; then
    warn "coturn 当前未运行，脚本会照常执行，结束后会启动它"
fi

# ---- 1. 备份 ----
step "1/6" "备份原配置 → $BAK"
cp -p "$CONF" "$BAK"

# ---- 2. 写入 alt-listening-port (幂等) ----
step "2/6" "写入 alt-listening-port=443"
if grep -q "^alt-listening-port" "$CONF"; then
    warn "alt-listening-port 已存在，跳过写入"
else
    {
        echo ""
        echo "$MARK"
        echo "alt-listening-port=443"
    } >> "$CONF"
    ok "已写入"
fi

# ---- 3. ufw 放行 (幂等) ----
step "3/6" "ufw 放行 443/tcp + 443/udp"
if command -v ufw >/dev/null 2>&1; then
    ufw allow 443/tcp comment "TURN alt-port" >/dev/null 2>&1 || true
    ufw allow 443/udp comment "TURN alt-port" >/dev/null 2>&1 || true
    ok "ufw 规则已加"
else
    warn "未检测到 ufw，跳过（请确认其它防火墙已放行 443）"
fi

# ---- 4. 配置 syntax 自检 (避免 systemctl 重启失败) ----
step "4/6" "coturn 配置语法自检"
if turnserver -c "$CONF" --check-config >/dev/null 2>&1; then
    ok "配置语法 OK"
elif turnserver -c "$CONF" -h >/dev/null 2>&1; then
    # 老版本 coturn 没有 --check-config，跳过
    warn "coturn 版本较老，跳过 --check-config 自检"
else
    err "turnserver 命令不可用"
    exit 1
fi

# ---- 5. 重启 coturn ----
step "5/6" "重启 coturn"
systemctl restart coturn
sleep 3

if systemctl is-active --quiet coturn; then
    ok "coturn 重启成功"
else
    err "coturn 启动失败！立即回滚:"
    err "  bash coturn-rollback-443.sh"
    err "查看错误: journalctl -u coturn -n 80 --no-pager"
    exit 1
fi

# ---- 6. 验证监听端口 ----
step "6/6" "验证监听端口"
echo
echo "--- 监听端口列表 ---"
ss -tunlp 2>/dev/null | grep -E ":443 |:3478 " | sort | sed 's/^/  /'
echo

PORT_443_TCP=$(ss -tnlp 2>/dev/null | grep -c ":443 " || true)
PORT_443_UDP=$(ss -unlp 2>/dev/null | grep -c ":443 " || true)
PORT_3478_TCP=$(ss -tnlp 2>/dev/null | grep -c ":3478 " || true)
PORT_3478_UDP=$(ss -unlp 2>/dev/null | grep -c ":3478 " || true)

echo "  443  TCP fd 数: $PORT_443_TCP"
echo "  443  UDP fd 数: $PORT_443_UDP"
echo "  3478 TCP fd 数: $PORT_3478_TCP"
echo "  3478 UDP fd 数: $PORT_3478_UDP"
echo

if [[ "$PORT_443_TCP" -gt 0 ]] && [[ "$PORT_3478_TCP" -gt 0 ]]; then
    ok "443 + 3478 都在监听，部署完成"
    echo
    echo -e "${CYN}下一步建议:${RST}"
    echo "  1. 从外网测试: nc -zv <本机公网IP> 443"
    echo "  2. 实时看 coturn 日志: tail -f /opt/yql/turn.txt"
    echo "  3. 灰度观察 5-10 分钟无异常后再做下一台"
    echo "  4. 后端登录接口下发的 ICE servers JSON 加上 turn:<IP>:443?transport=tcp"
    echo
    echo -e "${YLW}回滚:${RST} bash coturn-rollback-443.sh"
else
    err "443 端口没有被监听！可能是 coturn 版本不支持 alt-listening-port"
    err "立即回滚: bash coturn-rollback-443.sh"
    err "coturn 版本: $(turnserver -h 2>&1 | grep -i version | head -1)"
    exit 1
fi
