#!/usr/bin/env bash
# ============================================================
# coturn 每日流量统计
#   - 统计昨日通过 TURN 中继的双向流量、会话数、Top 用户
#   - 流量大头 = 钱（按使用流量计费），盯着这个不被坑
#
# 建议加到 cron, 每天 1 点跑:
#   0 1 * * * /root/turn-daily-report.sh >> /var/log/turn-daily.log 2>&1
# ============================================================

set -u

LOG=/opt/yql/turn.txt
YESTERDAY=$(date -d yesterday +%Y-%m-%d)
HOST=$(hostname)
PRICE_PER_GB=0.8   # 阿里云按使用流量计费均价 (元/GB)，按你实际改

if [[ ! -f "$LOG" ]]; then
    echo "[$(date)] $HOST: 找不到 coturn 日志 $LOG"
    exit 1
fi

echo "============================================"
echo "[$(date '+%F %T')] coturn 流量日报 — $HOST"
echo "统计日: $YESTERDAY"
echo "============================================"

# 1. 总体流量
grep "$YESTERDAY" "$LOG" 2>/dev/null | grep "peer usage" | awk '
    {
        for(i=1; i<=NF; i++) {
            if($i~/^rb=/) { gsub(/rb=|,/,"",$i); rx+=$i }
            else if($i~/^sb=/) { gsub(/sb=|,/,"",$i); tx+=$i }
        }
        sessions++
    }
    END {
        if(sessions == 0) {
            print "  无 TURN 中继会话"
        } else {
            printf "  会话数: %d\n", sessions
            printf "  接收流量 (rx): %.2f GB\n", rx/1073741824
            printf "  发送流量 (tx): %.2f GB\n", tx/1073741824
            printf "  总流量:        %.2f GB\n", (rx+tx)/1073741824
            printf "  估算成本:      ¥%.2f (按 ¥%s/GB)\n", (rx+tx)/1073741824*'"$PRICE_PER_GB"', "'"$PRICE_PER_GB"'"
        }
    }
'

echo
echo "Top 5 用户（按双向流量）:"
grep "$YESTERDAY" "$LOG" 2>/dev/null | grep "peer usage" | \
awk '
    {
        user=""
        rx=0; tx=0
        for(i=1; i<=NF; i++) {
            if($i~/<.*>/ && $i~/username/) {
                # 旧版 coturn 有时打 username=<xxx>
            }
            if($i~/^rb=/) { gsub(/rb=|,/,"",$i); rx=$i }
            else if($i~/^sb=/) { gsub(/sb=|,/,"",$i); tx=$i }
        }
        # 用 awk 提取 username=<xxx>
        match($0, /username=<[^>]+>/)
        if(RLENGTH > 0) {
            user = substr($0, RSTART+10, RLENGTH-11)
            users[user] += rx + tx
        }
    }
    END {
        for(u in users) print users[u], u
    }
' | sort -rn | head -5 | \
awk '{ printf "  %.2f MB  %s\n", $1/1048576, $2 }'

echo
echo "============================================"
