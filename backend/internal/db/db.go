// Package db persists the host identity and the set of paired devices.
//
// Uses modernc.org/sqlite (pure Go) so the daemon cross-compiles without a C
// toolchain on any of the three target platforms.
package db

import (
	"database/sql"
	"errors"
	"fmt"
	"time"

	_ "modernc.org/sqlite"
)

// ErrNotFound is returned when a lookup matches no row.
var ErrNotFound = errors.New("db: not found")

// Device is a paired mobile client.
type Device struct {
	ID        string    `json:"id"`
	Name      string    `json:"name"`
	PublicKey []byte    `json:"-"`
	PairedAt  time.Time `json:"pairedAt"`
	LastSeen  time.Time `json:"lastSeen"`
	Online    bool      `json:"online"`
}

// Database provides storage for paired devices, host identity, and settings.
type Database struct {
	sql *sql.DB
}

const schema = `
CREATE TABLE IF NOT EXISTS host_identity (
    id          INTEGER PRIMARY KEY CHECK (id = 1),
    daemon_id   TEXT NOT NULL,
    private_key BLOB NOT NULL,
    created_at  INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS devices (
    id         TEXT PRIMARY KEY,
    name       TEXT NOT NULL,
    public_key BLOB NOT NULL UNIQUE,
    paired_at  INTEGER NOT NULL,
    last_seen  INTEGER NOT NULL
);
`

// Open opens (creating if needed) the database at path and applies the schema.
func Open(path string) (*Database, error) {
	// _pragma busy_timeout keeps the Electron local API and the mobile
	// WebSocket goroutines from tripping over each other on write.
	handle, err := sql.Open("sqlite", path+"?_pragma=busy_timeout(5000)&_pragma=journal_mode(WAL)")
	if err != nil {
		return nil, err
	}
	if _, err := handle.Exec(schema); err != nil {
		handle.Close()
		return nil, fmt.Errorf("db: apply schema: %w", err)
	}
	return &Database{sql: handle}, nil
}

// Close releases the underlying handle.
func (d *Database) Close() error { return d.sql.Close() }

// HostIdentity returns the stored daemon ID and private key seed, or
// ErrNotFound on a fresh install.
func (d *Database) HostIdentity() (daemonID string, seed []byte, err error) {
	row := d.sql.QueryRow(`SELECT daemon_id, private_key FROM host_identity WHERE id = 1`)
	switch err := row.Scan(&daemonID, &seed); {
	case errors.Is(err, sql.ErrNoRows):
		return "", nil, ErrNotFound
	case err != nil:
		return "", nil, err
	}
	return daemonID, seed, nil
}

// SaveHostIdentity stores the daemon ID and private key seed.
func (d *Database) SaveHostIdentity(daemonID string, seed []byte) error {
	_, err := d.sql.Exec(
		`INSERT INTO host_identity (id, daemon_id, private_key, created_at) VALUES (1, ?, ?, ?)
		 ON CONFLICT(id) DO UPDATE SET daemon_id = excluded.daemon_id, private_key = excluded.private_key`,
		daemonID, seed, time.Now().Unix())
	return err
}

// UpsertDevice records a newly paired device, or refreshes the name of one
// that re-paired with the same key.
func (d *Database) UpsertDevice(id, name string, pub []byte) error {
	now := time.Now().Unix()
	_, err := d.sql.Exec(
		`INSERT INTO devices (id, name, public_key, paired_at, last_seen) VALUES (?, ?, ?, ?, ?)
		 ON CONFLICT(public_key) DO UPDATE SET name = excluded.name, last_seen = excluded.last_seen`,
		id, name, pub, now, now)
	return err
}

// DeviceByPublicKey looks up a trusted device during a resume handshake. A miss
// means the device is not paired and the connection must be rejected.
func (d *Database) DeviceByPublicKey(pub []byte) (*Device, error) {
	row := d.sql.QueryRow(
		`SELECT id, name, public_key, paired_at, last_seen FROM devices WHERE public_key = ?`, pub)
	return scanDevice(row)
}

// ListDevices returns every paired device, newest first.
func (d *Database) ListDevices() ([]Device, error) {
	rows, err := d.sql.Query(
		`SELECT id, name, public_key, paired_at, last_seen FROM devices ORDER BY last_seen DESC`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	devices := []Device{}
	for rows.Next() {
		dev, err := scanDevice(rows)
		if err != nil {
			return nil, err
		}
		devices = append(devices, *dev)
	}
	return devices, rows.Err()
}

// TouchDevice updates the last-seen timestamp after a successful handshake.
func (d *Database) TouchDevice(id string) error {
	_, err := d.sql.Exec(`UPDATE devices SET last_seen = ? WHERE id = ?`, time.Now().Unix(), id)
	return err
}

// RevokeDevice removes a device. Callers must also close any live socket the
// device holds: deleting the row only blocks future handshakes.
func (d *Database) RevokeDevice(id string) error {
	res, err := d.sql.Exec(`DELETE FROM devices WHERE id = ?`, id)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

// scanner covers both *sql.Row and *sql.Rows.
type scanner interface{ Scan(...any) error }

func scanDevice(s scanner) (*Device, error) {
	var (
		dev            Device
		pairedAt, seen int64
	)
	switch err := s.Scan(&dev.ID, &dev.Name, &dev.PublicKey, &pairedAt, &seen); {
	case errors.Is(err, sql.ErrNoRows):
		return nil, ErrNotFound
	case err != nil:
		return nil, err
	}
	dev.PairedAt = time.Unix(pairedAt, 0)
	dev.LastSeen = time.Unix(seen, 0)
	return &dev, nil
}
