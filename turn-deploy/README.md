# coturn 加 443 兜底端口部署包

## 内容

| 文件 | 用途 |
|---|---|
| `coturn-add-443.sh` | 上线脚本：备份 + 加配置 + 放火墙 + 重启 + 验证 |
| `coturn-rollback-443.sh` | 一键回滚脚本 |

## 前置条件 — **执行脚本前必须先做**

阿里云控制台 → ECS 该实例的安全组 → 入方向规则，添加：

| 协议 | 端口 | 源 |
|---|---|---|
| TCP | 443/443 | 0.0.0.0/0 |
| UDP | 443/443 | 0.0.0.0/0 |

⚠️ 没加这两条云上规则，脚本能跑通但外面连不进来。

## 上传到服务器并执行

```bash
# 假设你 Mac 终端 cd 到这个目录
scp coturn-add-443.sh coturn-rollback-443.sh root@101.133.147.93:/root/

# SSH 进去执行
ssh root@101.133.147.93
chmod +x /root/coturn-add-443.sh /root/coturn-rollback-443.sh
sudo bash /root/coturn-add-443.sh
```

## 期望输出（成功）

```
[1/6] 备份原配置 → /etc/turnserver.conf.bak.20260503-XXXXXX
[2/6] 写入 alt-listening-port=443
✅ 已写入
[3/6] ufw 放行 443/tcp + 443/udp
✅ ufw 规则已加
[4/6] coturn 配置语法自检
✅ 配置语法 OK / ⚠️  (老版本跳过)
[5/6] 重启 coturn
✅ coturn 重启成功
[6/6] 验证监听端口

--- 监听端口列表 ---
  tcp   LISTEN ... 0.0.0.0:443  ... turnserver,...
  tcp   LISTEN ... 0.0.0.0:3478 ... turnserver,...
  udp   UNCONN ... 0.0.0.0:443  ... turnserver,...
  udp   UNCONN ... 0.0.0.0:3478 ... turnserver,...

  443  TCP fd 数: 4
  443  UDP fd 数: 4
  3478 TCP fd 数: 4
  3478 UDP fd 数: 4

✅ 443 + 3478 都在监听，部署完成
```

## 外部连通性验证

在你 Mac 终端：

```bash
nc -zv 101.133.147.93 443    # 上海
nc -zv 47.250.41.165 443     # 马来（部署完后再测）
```

返回 `succeeded` 即通。

## 出错回滚

任何步骤失败、或者上线后客户端反馈异常：

```bash
sudo bash /root/coturn-rollback-443.sh
```

## 灰度建议

**不要两台同时上**。先上海 → 观察 1-24 小时无异常 → 再马来。

观察项：
1. `tail -f /opt/yql/turn.txt` 没有新增的 `error` 行
2. 客户端日志里出现 `:443` 的 ICE candidate
3. **流量账单不暴涨**（按使用流量计费，要盯紧）
4. 客户反馈"卡顿"明显减少

## 后端 ICE servers JSON 同步更新

部署完两台机器后，后端登录接口下发的 ICE servers JSON 改成：

```json
[
  {"region":"cn","urls":["stun:101.133.147.93:3478"]},
  {"region":"global","urls":["stun:47.250.41.165:3478"]},
  {"region":"cn","urls":[
      "turn:101.133.147.93:3478?transport=udp",
      "turn:101.133.147.93:3478?transport=tcp",
      "turn:101.133.147.93:443?transport=tcp"
   ],"username":"szp2puser","credential":"szTurn@200026klm98"},
  {"region":"global","urls":[
      "turn:47.250.41.165:3478?transport=udp",
      "turn:47.250.41.165:3478?transport=tcp",
      "turn:47.250.41.165:443?transport=tcp"
   ],"username":"szp2puser","credential":"szTurn@200026klm98"}
]
```

iOS 不用改代码，新登录的客户端自动拿到 443 那条候选。
