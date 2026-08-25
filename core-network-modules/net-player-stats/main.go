// net-player-stats owns the per-player traffic policy and WEB YFUI status data.
// The YZF gateway performs the actual server-to-client byte pacing.
package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"os"
	"sort"
	"strings"
	"sync"
	"time"
)

type playerOverride struct {
	Enabled            bool  `json:"enabled"`
	BytesPerMinute     int64 `json:"bytesPerMinute"` // legacy alias
	MaxUploadPerMinute *int64 `json:"maxUploadPerMinute"`
	MaxUpload          *int64 `json:"maxUpload"`
	ChunkBytes         int   `json:"chunkBytes"`
	IntervalMs         int   `json:"intervalMs"`
	SplitMode          string `json:"splitMode"`
	SplitThreshold     int    `json:"splitThreshold"`
	SplitChunkSize     int    `json:"splitChunkSize"`
	SplitIntervalMs    int    `json:"splitIntervalMs"`
	SplitChunksPerTick int    `json:"splitChunksPerTick"`
}

type trafficPolicy struct {
	Enabled               bool                      `json:"enabled"`
	DefaultBytesPerMinute int64                     `json:"defaultBytesPerMinute"` // legacy alias
	DefaultMaxUpload      *int64                    `json:"defaultMaxUpload"`
	MaxUpload             *int64                    `json:"maxUpload"`
	ChunkBytes            int                       `json:"chunkBytes"`
	IntervalMs            int                       `json:"intervalMs"`
	SplitMode             string                    `json:"splitMode"`
	SplitThreshold        int                       `json:"splitThreshold"`
	SplitChunkSize        int                       `json:"splitChunkSize"`
	SplitIntervalMs       int                       `json:"splitIntervalMs"`
	SplitChunksPerTick    int                       `json:"splitChunksPerTick"`
	Defaults              *playerOverride           `json:"defaults"`
	Players               map[string]playerOverride `json:"players"`
	PlayerOverrides       map[string]playerOverride `json:"playerOverrides"` // legacy alias
}

type aggregateStats struct {
	UploadBps     float64 `json:"uploadBps"`
	DownloadBps   float64 `json:"downloadBps"`
	TPS           float64 `json:"tps"`
	Players       int     `json:"players"`
	AvgPacketSize float64 `json:"avgPacketSize"`
	PacketMax     float64 `json:"packetMax"`
	PacketMin     float64 `json:"packetMin"`
	PacketCount   float64 `json:"packetCount"`
	SplitPackets  float64 `json:"splitPackets"`
	PendingChunks float64 `json:"pendingChunks"`
	RateLimited   float64 `json:"rateLimited"`
}

type splitterWindow struct {
	PacketMax   int64 `json:"packetMax"`
	PacketMin   int64 `json:"packetMin"`
	PacketAvg   int64 `json:"packetAvg"`
	PacketCount int64 `json:"packetCount"`
}

type splitterStats struct {
	Online       bool           `json:"online"`
	UploadBps    int64          `json:"uploadBps"`
	Shaping      bool           `json:"shaping"`
	SplitTotal   int64          `json:"splitTotal"`
	QueuedChunks int64          `json:"queuedChunks"`
	Window60s    splitterWindow `json:"window60s"`
}

type gatewayConnection struct {
	Address                  string `json:"address"`
	UUID                     string `json:"uuid"`
	Name                     string `json:"name"`
	Online                   bool   `json:"online"`
	Override                 bool   `json:"override"`
	Shaping                  bool   `json:"shaping"`
	BytesPerMinute           int64  `json:"bytesPerMinute"`
	ChunkBytes               int    `json:"chunkBytes"`
	EffectiveChunkBytes      int    `json:"effectiveChunkBytes"`
	IntervalMs               int    `json:"intervalMs"`
	QueuedBytes              int64  `json:"queuedBytes"`
	QueuedPackets            int    `json:"queuedPackets"`
	SentBytes                int64  `json:"sentBytes"`
	SentPackets              int64  `json:"sentPackets"`
	ReceivedPackets          int64  `json:"receivedPackets"`
	ChunksSent               int64  `json:"chunksSent"`
	SplitPackets             int64  `json:"splitPackets"`
	CoalescedPackets         int64  `json:"coalescedPackets"`
	DroppedUnreliablePackets int64  `json:"droppedUnreliablePackets"`
	LastActivity             int64  `json:"lastActivity"`
}

type connectionStatus struct {
	gatewayConnection
	FirstSeen      int64   `json:"firstSeen"`
	LastSeen       int64   `json:"lastSeen"`
	SentBytes60s   int64   `json:"sentBytes60s"`
	CurrentBps     float64 `json:"currentBps"`
	BudgetUsagePct float64 `json:"budgetUsagePct"`
}

type connectionState struct {
	Status        connectionStatus
	LastSentBytes int64
	LastCounterAt time.Time
}

type trafficSample struct {
	At    time.Time
	Key   string
	Bytes int64
}

type trafficSummary struct {
	OnlineConnections        int     `json:"onlineConnections"`
	ShapedConnections        int     `json:"shapedConnections"`
	QueuedBytes              int64   `json:"queuedBytes"`
	QueuedPackets            int64   `json:"queuedPackets"`
	SentBytes60s             int64   `json:"sentBytes60s"`
	CurrentBps               float64 `json:"currentBps"`
	ChunksSent               int64   `json:"chunksSent"`
	SplitPackets             int64   `json:"splitPackets"`
	CoalescedPackets         int64   `json:"coalescedPackets"`
	DroppedUnreliablePackets int64   `json:"droppedUnreliablePackets"`
}

type statusOutput struct {
	ModuleID        string             `json:"moduleId"`
	Timestamp       int64              `json:"timestamp"`
	Online          bool               `json:"online"`
	LastTrafficAt   int64              `json:"lastTrafficAt"`
	PolicyAppliedAt int64              `json:"policyAppliedAt"`
	StatsLog        bool               `json:"statsLog"`
	Policy          trafficPolicy      `json:"policy"`
	Limits          trafficPolicy      `json:"limits"`
	Aggregate       aggregateStats     `json:"aggregate"`
	Summary         trafficSummary     `json:"summary"`
	Splitter        splitterStats      `json:"splitter"`
	Connections     []connectionStatus `json:"connections"`
}

var (
	mu                sync.Mutex
	states            = make(map[string]*connectionState)
	samples           []trafficSample
	aggregate         aggregateStats
	splitter          splitterStats
	policy            = defaultPolicy()
	lastPolicyPayload string
	policyAppliedAt   int64
	lastTrafficAt     int64
	statsLog          bool
	sendMu            sync.Mutex
)

func defaultPolicy() trafficPolicy {
	def := int64(1024 * 1024)
	return trafficPolicy{
		Enabled:            false,
		DefaultMaxUpload:   &def,
		ChunkBytes:         1024,
		IntervalMs:         100,
		SplitMode:          "internal",
		SplitThreshold:     200,
		SplitChunkSize:     100,
		SplitIntervalMs:    60,
		SplitChunksPerTick: 4,
		PlayerOverrides:    make(map[string]playerOverride),
		Players:            make(map[string]playerOverride),
	}
}

func normalizePolicy(cfg *trafficPolicy) {
	// Support the new unified format: if "defaults" sub-object is present, its fields
	// override the top-level legacy fields.
	if cfg.Defaults != nil {
		if cfg.Defaults.MaxUpload != nil {
			cfg.DefaultMaxUpload = cfg.Defaults.MaxUpload
		} else if cfg.Defaults.MaxUploadPerMinute != nil {
			cfg.DefaultMaxUpload = cfg.Defaults.MaxUploadPerMinute
		}
		if cfg.Defaults.ChunkBytes > 0 {
			cfg.ChunkBytes = cfg.Defaults.ChunkBytes
		}
		if cfg.Defaults.IntervalMs > 0 {
			cfg.IntervalMs = cfg.Defaults.IntervalMs
		}
		if cfg.Defaults.SplitMode != "" {
			cfg.SplitMode = cfg.Defaults.SplitMode
		}
		if cfg.Defaults.SplitThreshold > 0 {
			cfg.SplitThreshold = cfg.Defaults.SplitThreshold
		}
		if cfg.Defaults.SplitChunkSize > 0 {
			cfg.SplitChunkSize = cfg.Defaults.SplitChunkSize
		}
		if cfg.Defaults.SplitIntervalMs > 0 {
			cfg.SplitIntervalMs = cfg.Defaults.SplitIntervalMs
		}
		if cfg.Defaults.SplitChunksPerTick > 0 {
			cfg.SplitChunksPerTick = cfg.Defaults.SplitChunksPerTick
		}
	}

	// Resolve the global default: support the new defaultMaxUpload alias and the
	// legacy defaultBytesPerMinute. 0 means unlimited (no shaping).
	if cfg.DefaultMaxUpload == nil {
		if cfg.MaxUpload != nil {
			cfg.DefaultMaxUpload = cfg.MaxUpload
		} else if cfg.DefaultBytesPerMinute > 0 {
			v := cfg.DefaultBytesPerMinute
			cfg.DefaultMaxUpload = &v
		} else {
			v := int64(1024 * 1024)
			cfg.DefaultMaxUpload = &v
		}
	}
	if *cfg.DefaultMaxUpload < 0 {
		v := int64(1024 * 1024)
		cfg.DefaultMaxUpload = &v
	}
	if cfg.ChunkBytes < 32 {
		cfg.ChunkBytes = 1024
	}
	if cfg.ChunkBytes > 16*1024 {
		cfg.ChunkBytes = 16 * 1024
	}
	if cfg.IntervalMs < 1 {
		cfg.IntervalMs = 100
	}
	if cfg.IntervalMs > 60_000 {
		cfg.IntervalMs = 60_000
	}
	if cfg.SplitMode == "" {
		cfg.SplitMode = "internal"
	}
	if cfg.SplitThreshold < 16 {
		cfg.SplitThreshold = 200
	}
	if cfg.SplitChunkSize < 16 {
		cfg.SplitChunkSize = 100
	}
	if cfg.SplitIntervalMs < 5 {
		cfg.SplitIntervalMs = 60
	}
	if cfg.SplitChunksPerTick < 1 {
		cfg.SplitChunksPerTick = 4
	}

	// Merge "players" (new) and "playerOverrides" (legacy) into one map.
	allOverrides := make(map[string]playerOverride)
	for k, v := range cfg.PlayerOverrides {
		allOverrides[k] = v
	}
	for k, v := range cfg.Players {
		allOverrides[k] = v // new "players" takes precedence
	}
	cfg.PlayerOverrides = allOverrides

	cleaned := make(map[string]playerOverride, len(cfg.PlayerOverrides))
	for rawUUID, override := range cfg.PlayerOverrides {
		uuid := strings.TrimSpace(rawUUID)
		if uuid == "" {
			continue
		}
		// Resolve per-player: nil/unset -> inherit global default; explicit 0 stays 0 (unlimited).
		if override.MaxUpload == nil {
			override.MaxUpload = override.MaxUploadPerMinute
		}
		if override.MaxUpload == nil {
			if override.BytesPerMinute > 0 {
				v := override.BytesPerMinute
				override.MaxUpload = &v
			} else {
				v := *cfg.DefaultMaxUpload
				override.MaxUpload = &v
			}
		}
		if *override.MaxUpload < 0 {
			v := *cfg.DefaultMaxUpload
			override.MaxUpload = &v
		}
		if override.ChunkBytes < 32 {
			override.ChunkBytes = cfg.ChunkBytes
		}
		if override.ChunkBytes > 16*1024 {
			override.ChunkBytes = 16 * 1024
		}
		if override.IntervalMs < 1 {
			override.IntervalMs = cfg.IntervalMs
		}
		if override.IntervalMs > 60_000 {
			override.IntervalMs = 60_000
		}
		if override.SplitMode == "" {
			override.SplitMode = cfg.SplitMode
		}
		if override.SplitThreshold < 16 {
			override.SplitThreshold = cfg.SplitThreshold
		}
		if override.SplitChunkSize < 16 {
			override.SplitChunkSize = cfg.SplitChunkSize
		}
		if override.SplitIntervalMs < 5 {
			override.SplitIntervalMs = cfg.SplitIntervalMs
		}
		if override.SplitChunksPerTick < 1 {
			override.SplitChunksPerTick = cfg.SplitChunksPerTick
		}
		cleaned[uuid] = override
	}
	cfg.PlayerOverrides = cleaned
}

func logf(format string, args ...any) {
	fmt.Fprintf(os.Stderr, "[net-player-stats] "+format+"\n", args...)
}

func sendLine(value any) {
	data, err := json.Marshal(value)
	if err != nil {
		return
	}
	sendMu.Lock()
	fmt.Println(string(data))
	sendMu.Unlock()
}

func sendAction(action string, fields any) {
	sendLine(map[string]any{"type": action, "fields": fields})
}

func loadPolicy() {
	data, err := os.ReadFile("limits.json")
	if err != nil {
		return
	}
	cfg := defaultPolicy()
	if err := json.Unmarshal(data, &cfg); err != nil {
		return
	}
	normalizePolicy(&cfg)
	mu.Lock()
	policy = cfg
	mu.Unlock()
}

func applyPolicy(force bool) {
	mu.Lock()
	cfg := policy
	mu.Unlock()

	// Build the clean unified payload: defaults + players.
	sendPayload := map[string]any{
		"enabled": cfg.Enabled,
		"defaults": map[string]any{
			"maxUpload":      *cfg.DefaultMaxUpload,
			"chunkBytes":     cfg.ChunkBytes,
			"intervalMs":     cfg.IntervalMs,
			"splitMode":      cfg.SplitMode,
			"splitThreshold": cfg.SplitThreshold,
			"splitChunkSize": cfg.SplitChunkSize,
			"splitIntervalMs": cfg.SplitIntervalMs,
			"splitChunksPerTick": cfg.SplitChunksPerTick,
		},
	}
	players := make(map[string]any, len(cfg.PlayerOverrides))
	for uuid, override := range cfg.PlayerOverrides {
		uuid = strings.TrimSpace(uuid)
		if uuid == "" {
			continue
		}
		maxUp := int64(0)
		if override.MaxUpload != nil {
			maxUp = *override.MaxUpload
		}
		players[uuid] = map[string]any{
			"enabled":      override.Enabled,
			"maxUpload":    maxUp,
			"chunkBytes":   override.ChunkBytes,
			"intervalMs":   override.IntervalMs,
			"splitMode":    override.SplitMode,
			"splitThreshold": override.SplitThreshold,
			"splitChunkSize": override.SplitChunkSize,
			"splitIntervalMs": override.SplitIntervalMs,
			"splitChunksPerTick": override.SplitChunksPerTick,
		}
	}
	sendPayload["players"] = players

	payload, err := json.Marshal(sendPayload)
	if err != nil {
		return
	}
	signature := string(payload)
	mu.Lock()
	if !force && signature == lastPolicyPayload {
		mu.Unlock()
		return
	}
	lastPolicyPayload = signature
	policyAppliedAt = time.Now().UnixMilli()
	mu.Unlock()
	sendAction("trafficPolicy", sendPayload)
}

// Gateway events currently use flat JSON fields. Nested fields are accepted for
// backward compatibility with older module transports.
func eventFields(line []byte) (string, string, map[string]json.RawMessage, bool) {
	var root map[string]json.RawMessage
	if json.Unmarshal(line, &root) != nil {
		return "", "", nil, false
	}
	var eventType, event string
	json.Unmarshal(root["type"], &eventType)
	json.Unmarshal(root["event"], &event)
	fields := make(map[string]json.RawMessage, len(root))
	for key, value := range root {
		if key != "type" && key != "event" && key != "fields" {
			fields[key] = value
		}
	}
	if nested, ok := root["fields"]; ok {
		var object map[string]json.RawMessage
		if json.Unmarshal(nested, &object) == nil {
			for key, value := range object {
				fields[key] = value
			}
		}
	}
	return eventType, event, fields, true
}

func handleNetStats(fields map[string]json.RawMessage) {
	var next aggregateStats
	json.Unmarshal(fields["uploadBps"], &next.UploadBps)
	json.Unmarshal(fields["downloadBps"], &next.DownloadBps)
	json.Unmarshal(fields["tps"], &next.TPS)
	json.Unmarshal(fields["players"], &next.Players)
	json.Unmarshal(fields["packetAvg"], &next.AvgPacketSize)
	json.Unmarshal(fields["packetMax"], &next.PacketMax)
	json.Unmarshal(fields["packetMin"], &next.PacketMin)
	json.Unmarshal(fields["packetCount"], &next.PacketCount)
	json.Unmarshal(fields["splitPackets"], &next.SplitPackets)
	json.Unmarshal(fields["pendingChunks"], &next.PendingChunks)
	json.Unmarshal(fields["rateLimited"], &next.RateLimited)
	mu.Lock()
	aggregate = next
	mu.Unlock()
}

func connectionKey(connection gatewayConnection) string {
	if uuid := strings.TrimSpace(connection.UUID); uuid != "" {
		return "uuid:" + uuid
	}
	return "address:" + strings.TrimSpace(connection.Address)
}

func handleTrafficStats(fields map[string]json.RawMessage) {
	var timestamp int64
	json.Unmarshal(fields["timestamp"], &timestamp)
	if timestamp <= 0 {
		timestamp = time.Now().UnixMilli()
	}
	var incoming []gatewayConnection
	if json.Unmarshal(fields["connections"], &incoming) != nil {
		return
	}
	now := time.UnixMilli(timestamp)
	seen := make(map[string]bool, len(incoming))

	mu.Lock()
	lastTrafficAt = timestamp
	for _, connection := range incoming {
		key := connectionKey(connection)
		if key == "address:" {
			continue
		}
		state := states[key]
		if state == nil && connection.UUID != "" {
			addressKey := "address:" + strings.TrimSpace(connection.Address)
			if previous := states[addressKey]; previous != nil {
				state = previous
				delete(states, addressKey)
			}
		}
		if state == nil {
			state = &connectionState{}
			state.Status.FirstSeen = timestamp
			states[key] = state
		}

		if !state.LastCounterAt.IsZero() && connection.SentBytes >= state.LastSentBytes {
			delta := connection.SentBytes - state.LastSentBytes
			elapsed := now.Sub(state.LastCounterAt).Seconds()
			if elapsed > 0 {
				state.Status.CurrentBps = float64(delta) / elapsed
			}
			if delta > 0 {
				samples = append(samples, trafficSample{At: now, Key: key, Bytes: delta})
			}
		} else {
			state.Status.CurrentBps = 0
		}

		state.Status.gatewayConnection = connection
		state.Status.Online = true
		state.Status.LastSeen = timestamp
		state.LastSentBytes = connection.SentBytes
		state.LastCounterAt = now
		seen[key] = true
	}
	for key, state := range states {
		if !seen[key] {
			state.Status.Online = false
			state.Status.CurrentBps = 0
		}
	}
	mu.Unlock()
}

func mergeSplitterStatus() {
	next := splitterStats{}
	data, err := os.ReadFile("../packet-splitter/status.json")
	if err == nil {
		if info, statErr := os.Stat("../packet-splitter/status.json"); statErr == nil && time.Since(info.ModTime()) < 15*time.Second {
			if json.Unmarshal(data, &next) == nil {
				next.Online = true
			}
		}
	}
	mu.Lock()
	splitter = next
	mu.Unlock()
}

func loadLogControl() {
	data, err := os.ReadFile("log-control.json")
	if err != nil {
		return
	}
	var value struct {
		StatsLog bool `json:"statsLog"`
	}
	if json.Unmarshal(data, &value) == nil {
		mu.Lock()
		statsLog = value.StatsLog
		mu.Unlock()
	}
}

func snapshotStatus() statusOutput {
	now := time.Now()
	cutoff := now.Add(-60 * time.Second)
	historyCutoff := now.Add(-60 * time.Minute).UnixMilli()

	mu.Lock()
	first := 0
	for first < len(samples) && samples[first].At.Before(cutoff) {
		first++
	}
	if first > 0 {
		samples = append([]trafficSample(nil), samples[first:]...)
	}
	windowBytes := make(map[string]int64)
	for _, sample := range samples {
		windowBytes[sample.Key] += sample.Bytes
	}

	connections := make([]connectionStatus, 0, len(states))
	summary := trafficSummary{}
	for key, state := range states {
		if !state.Status.Online && state.Status.LastSeen < historyCutoff {
			delete(states, key)
			continue
		}
		item := state.Status
		item.SentBytes60s = windowBytes[key]
		if item.BytesPerMinute > 0 {
			item.BudgetUsagePct = float64(item.SentBytes60s) * 100 / float64(item.BytesPerMinute)
		}
		connections = append(connections, item)
		if item.Online {
			summary.OnlineConnections++
			if item.Shaping {
				summary.ShapedConnections++
			}
			summary.QueuedBytes += item.QueuedBytes
			summary.QueuedPackets += int64(item.QueuedPackets)
			summary.SentBytes60s += item.SentBytes60s
			summary.CurrentBps += item.CurrentBps
			summary.ChunksSent += item.ChunksSent
			summary.SplitPackets += item.SplitPackets
			summary.CoalescedPackets += item.CoalescedPackets
			summary.DroppedUnreliablePackets += item.DroppedUnreliablePackets
		}
	}

	sort.Slice(connections, func(i, j int) bool {
		if connections[i].Online != connections[j].Online {
			return connections[i].Online
		}
		left := strings.ToLower(connections[i].Name + connections[i].UUID + connections[i].Address)
		right := strings.ToLower(connections[j].Name + connections[j].UUID + connections[j].Address)
		return left < right
	})

	cfg := policy
	result := statusOutput{
		ModuleID:        "net-player-stats",
		Timestamp:       now.UnixMilli(),
		Online:          lastTrafficAt > 0 && now.UnixMilli()-lastTrafficAt < 15_000,
		LastTrafficAt:   lastTrafficAt,
		PolicyAppliedAt: policyAppliedAt,
		StatsLog:        statsLog,
		Policy:          cfg,
		Limits:          cfg,
		Aggregate:       aggregate,
		Summary:         summary,
		Splitter:        splitter,
		Connections:     connections,
	}
	mu.Unlock()
	return result
}

func writeStatus() {
	data, err := json.Marshal(snapshotStatus())
	if err != nil {
		return
	}
	if err := os.WriteFile("status.json.tmp", data, 0644); err != nil {
		return
	}
	if err := os.Rename("status.json.tmp", "status.json"); err != nil {
		_ = os.WriteFile("status.json", data, 0644)
		_ = os.Remove("status.json.tmp")
	}
}

func heartbeat() {
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()
	for range ticker.C {
		loadLogControl()
		loadPolicy()
		applyPolicy(false)
		mergeSplitterStatus()
		writeStatus()
	}
}

func main() {
	logf("玩家流量统计与字节整形策略模块启动中...")
	loadPolicy()
	sendLine(map[string]any{"type": "hello", "clientId": "net-player-stats", "coreModule": true})
	sendAction("subscribe", map[string]any{"event": "NetStatsEvent"})
	sendAction("subscribe", map[string]any{"event": "TrafficStatsEvent"})
	go heartbeat()

	scanner := bufio.NewScanner(os.Stdin)
	scanner.Buffer(make([]byte, 0, 64*1024), 8*1024*1024)
	for scanner.Scan() {
		line := scanner.Bytes()
		if len(line) == 0 {
			continue
		}
		eventType, event, fields, ok := eventFields(line)
		if !ok {
			continue
		}
		switch eventType {
		case "hello":
			logf("已连接网关并下发玩家流量策略")
			applyPolicy(true)
		case "shutdown":
			logf("收到 shutdown, 退出")
			return
		case "event":
			switch event {
			case "NetStatsEvent":
				handleNetStats(fields)
			case "TrafficStatsEvent":
				handleTrafficStats(fields)
			}
		}
	}
	logf("stdin 已关闭, 退出")
}
