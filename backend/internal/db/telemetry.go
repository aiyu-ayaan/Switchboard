package db

import (
	"database/sql"
	"fmt"
	"time"

	"switchboard/backend/internal/protocol"
)

// RecordMetrics saves a single telemetry snapshot to the database.
func (d *Database) RecordMetrics(pt protocol.MetricPoint) error {
	ts := pt.Timestamp
	if ts <= 0 {
		ts = time.Now().Unix()
	}

	query := `INSERT INTO system_metrics (
		timestamp, cpu_percent, ram_used_bytes, ram_total_bytes,
		gpu_percent, gpu_mem_used_bytes, gpu_mem_total_bytes,
		disk_read_bps, disk_write_bps, net_rx_bps, net_tx_bps,
		cpu_temp, gpu_temp
	) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`

	_, err := d.sql.Exec(
		query,
		ts, pt.CPU, pt.RAMUsed, pt.RAMTotal,
		pt.GPU, pt.GPUMemUsed, pt.GPUMemTotal,
		pt.DiskRead, pt.DiskWrite, pt.NetRx, pt.NetTx,
		pt.CPUTemp, pt.GPUTemp,
	)
	if err != nil {
		return fmt.Errorf("db: record metrics: %w", err)
	}
	return nil
}

// QueryMetrics retrieves metrics aggregated according to the requested range.
// Supported ranges: "1m", "1h", "12h", "24h", "1w", "30d".
func (d *Database) QueryMetrics(rangeStr string) ([]protocol.MetricPoint, error) {
	now := time.Now().Unix()

	if rangeStr == "1m" {
		query := `SELECT timestamp, cpu_percent, ram_used_bytes, ram_total_bytes,
			gpu_percent, gpu_mem_used_bytes, gpu_mem_total_bytes,
			disk_read_bps, disk_write_bps, net_rx_bps, net_tx_bps,
			cpu_temp, gpu_temp
			FROM system_metrics
			ORDER BY timestamp DESC
			LIMIT 1`
		row := d.sql.QueryRow(query)
		var pt protocol.MetricPoint
		var cpuTemp, gpuTemp sql.NullFloat64
		err := row.Scan(
			&pt.Timestamp, &pt.CPU, &pt.RAMUsed, &pt.RAMTotal,
			&pt.GPU, &pt.GPUMemUsed, &pt.GPUMemTotal,
			&pt.DiskRead, &pt.DiskWrite, &pt.NetRx, &pt.NetTx,
			&cpuTemp, &gpuTemp,
		)
		if err == sql.ErrNoRows {
			return []protocol.MetricPoint{}, nil
		}
		if err != nil {
			return nil, fmt.Errorf("db: query 1m metric: %w", err)
		}
		if cpuTemp.Valid {
			pt.CPUTemp = &cpuTemp.Float64
		}
		if gpuTemp.Valid {
			pt.GPUTemp = &gpuTemp.Float64
		}
		return []protocol.MetricPoint{pt}, nil
	}

	// For longer ranges, define window and bucket size (seconds)
	var windowSec int64
	var bucketSec int64

	switch rangeStr {
	case "1h":
		windowSec = 3600
		bucketSec = 60 // 1-minute buckets
	case "12h":
		windowSec = 12 * 3600
		bucketSec = 300 // 5-minute buckets
	case "24h":
		windowSec = 24 * 3600
		bucketSec = 600 // 10-minute buckets
	case "1w":
		windowSec = 7 * 86400
		bucketSec = 3600 // 1-hour buckets
	case "30d":
		windowSec = 30 * 86400
		bucketSec = 14400 // 4-hour buckets
	default:
		// Default to 1 hour
		windowSec = 3600
		bucketSec = 60
	}

	cutoff := now - windowSec

	query := fmt.Sprintf(`SELECT
		(timestamp / %d) * %d AS bucket,
		AVG(cpu_percent),
		CAST(AVG(ram_used_bytes) AS INTEGER),
		MAX(ram_total_bytes),
		AVG(gpu_percent),
		CAST(AVG(gpu_mem_used_bytes) AS INTEGER),
		MAX(gpu_mem_total_bytes),
		CAST(AVG(disk_read_bps) AS INTEGER),
		CAST(AVG(disk_write_bps) AS INTEGER),
		CAST(AVG(net_rx_bps) AS INTEGER),
		CAST(AVG(net_tx_bps) AS INTEGER),
		AVG(cpu_temp),
		AVG(gpu_temp)
		FROM system_metrics
		WHERE timestamp >= ?
		GROUP BY bucket
		ORDER BY bucket ASC`,
		bucketSec, bucketSec,
	)

	rows, err := d.sql.Query(query, cutoff)
	if err != nil {
		return nil, fmt.Errorf("db: query metrics (%s): %w", rangeStr, err)
	}
	defer rows.Close()

	var points []protocol.MetricPoint
	for rows.Next() {
		var pt protocol.MetricPoint
		var cpuTemp, gpuTemp sql.NullFloat64
		if err := rows.Scan(
			&pt.Timestamp, &pt.CPU, &pt.RAMUsed, &pt.RAMTotal,
			&pt.GPU, &pt.GPUMemUsed, &pt.GPUMemTotal,
			&pt.DiskRead, &pt.DiskWrite, &pt.NetRx, &pt.NetTx,
			&cpuTemp, &gpuTemp,
		); err != nil {
			return nil, fmt.Errorf("db: scan metric point: %w", err)
		}
		if cpuTemp.Valid {
			v := cpuTemp.Float64
			pt.CPUTemp = &v
		}
		if gpuTemp.Valid {
			v := gpuTemp.Float64
			pt.GPUTemp = &v
		}
		points = append(points, pt)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("db: iterate metric points: %w", err)
	}
	return points, nil
}

// PruneOldMetrics deletes entries older than retentionDays (default: 30 days).
// Returns the count of deleted rows.
func (d *Database) PruneOldMetrics(retentionDays int) (int64, error) {
	if retentionDays <= 0 {
		retentionDays = 30
	}
	cutoff := time.Now().Unix() - int64(retentionDays*86400)
	res, err := d.sql.Exec(`DELETE FROM system_metrics WHERE timestamp < ?`, cutoff)
	if err != nil {
		return 0, fmt.Errorf("db: prune old metrics: %w", err)
	}
	return res.RowsAffected()
}
