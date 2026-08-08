// netwatch: YZF 核心网络模块示例 (Go)
//
// 功能:
//   1. 订阅 NetStatsEvent, 实时跟踪服务端上行/下行带宽。
//   2. 当上行带宽连续超过阈值时向网关发送限速建议动作 (rateLimit), 对
//      BlockSnapshotCallPacket 临时限速, 缓解带宽压力造成的卡顿/掉线。
//   3. 压力回落后自动解除限速。
//
// 协议: NDJSON over stdin/stdout (YZF_GATEWAY=netgateway)。
// 构建: go build -o netwatch.exe .   (纯标准库, 无第三方依赖)
// 热加载: 重新编译并覆盖 netmods/netwatch/netwatch.exe 后, 网关文件监听器
//        会自动检测变更并热重启本模块, 无需重启服务端。
package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"os"
	"time"
)

// 可调参数 (也可改为从 config.hjson 读取)
const (
	bandwidthHighBps = 150000 // 上行超过该值进入压制模式
	bandwidthLowBps  = 80000  // 回落至该值以下解除压制
	snapshotLimit    = 25     // 压制模式下快照包限速 (包/秒)
	snapshotBurst    = 30
)

type envelope struct {
	Type   string          `json:"type"`
	Event  string          `json:"event,omitempty"`
	Fields json.RawMessage `json:"fields,omitempty"`
}

type netStats struct {
	UploadBps   float64 `json:"uploadBps"`
	DownloadBps float64 `json:"downloadBps"`
	TPS         float64 `json:"tps"`
	Players     float64 `json:"players"`
}

func logf(format string, args ...any) {
	fmt.Fprintf(os.Stderr, "[netwatch] "+format+"\n", args...)
}

func sendLine(v any) {
	b, err := json.Marshal(v)
	if err != nil {
		return
	}
	fmt.Println(string(b))
}

// sendAction 发送一个 NDJSON 动作行 {"type":..., "fields": {...}}
func sendAction(action string, fields map[string]any) {
	fb, _ := json.Marshal(fields)
	sendLine(map[string]json.RawMessage{"type": json.RawMessage(`"` + action + `"`), "fields": fb})
}

func main() {
	logf("netwatch 核心网络模块启动中...")

	// 网关会先推送 hello 确认; 这里先订阅所需事件并宣告自身能力。
	sendLine(map[string]any{"type": "hello", "clientId": "netwatch", "coreModule": true})
	sendAction("subscribe", map[string]any{"event": "NetStatsEvent"})
	logf("已订阅 NetStatsEvent")

	shaping := false
	highSince := time.Time{}
	lastLog := time.Time{}

	scanner := bufio.NewScanner(os.Stdin)
	scanner.Buffer(make([]byte, 0, 64*1024), 8*1024*1024)
	for scanner.Scan() {
		line := scanner.Bytes()
		if len(line) == 0 {
			continue
		}
		var env envelope
		if err := json.Unmarshal(line, &env); err != nil {
			continue
		}
		switch env.Type {
		case "hello":
			logf("已连接网关")
		case "shutdown":
			logf("收到 shutdown, 退出")
			return
		case "event":
			if env.Event != "NetStatsEvent" {
				continue
			}
			var st netStats
			if err := json.Unmarshal(env.Fields, &st); err != nil {
				continue
			}
			now := time.Now()

			// 进入压制模式: 连续 3 秒高于高水位才生效, 避免抖动误判。
			if !shaping && st.UploadBps >= bandwidthHighBps {
				if highSince.IsZero() {
					highSince = now
				} else if now.Sub(highSince) >= 3*time.Second {
					shaping = true
					sendAction("rateLimit", map[string]any{
						"event": "send", "packet": "BlockSnapshotCallPacket",
						"perSecond": snapshotLimit, "burst": snapshotBurst,
					})
					logf("上行带宽 %.0f B/s 持续偏高, 已对快照包限速 %d pps", st.UploadBps, snapshotLimit)
				}
			} else if !shaping {
				highSince = time.Time{}
			}

			// 退出压制模式
			if shaping && st.UploadBps <= bandwidthLowBps {
				shaping = false
				highSince = time.Time{}
				sendAction("rateLimit", map[string]any{
					"event": "send", "packet": "BlockSnapshotCallPacket",
					"perSecond": 0,
				})
				logf("上行带宽回落至 %.0f B/s, 已解除快照包限速", st.UploadBps)
			}

			// 每 30 秒输出一次状态 (stderr -> 网关日志)
			if now.Sub(lastLog) >= 30*time.Second {
				lastLog = now
				logf("状态: 上行=%.0f B/s 下行=%.0f B/s TPS=%.0f 玩家=%.0f 压制=%v",
					st.UploadBps, st.DownloadBps, st.TPS, st.Players, shaping)
			}
		}
	}
	logf("stdin 已关闭, 退出")
}
