// Package db persists the host identity and the set of paired devices.
//
// Uses modernc.org/sqlite (pure Go) so the daemon cross-compiles without a C
// toolchain on any of the three target platforms.
package db

import (
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	_ "modernc.org/sqlite"

	"switchboard/backend/internal/protocol"
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
CREATE TABLE IF NOT EXISTS transfers (
    id          TEXT PRIMARY KEY,
    device_id   TEXT NOT NULL,
    name        TEXT NOT NULL,
    size        INTEGER NOT NULL,
    direction   TEXT NOT NULL,
    status      TEXT NOT NULL,
    path        TEXT NOT NULL,
    sha256      TEXT NOT NULL,
    transferred INTEGER NOT NULL,
    started_at  INTEGER NOT NULL,
    finished_at INTEGER NOT NULL,
    error       TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS settings (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS unlock_keys (
    device_id   TEXT PRIMARY KEY,
    public_key  BLOB NOT NULL,
    enrolled_at INTEGER NOT NULL
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
	// Forgetting a phone has to take its unlock key with it, or re-pairing
	// the same device would silently inherit the right to open the lock
	// screen from a trust decision the user has already withdrawn.
	if _, err := d.sql.Exec(`DELETE FROM unlock_keys WHERE device_id = ?`, id); err != nil {
		return err
	}
	return nil
}

// ---- Remote unlock ----

// SaveUnlockKey stores a device's biometric-gated public key, replacing any
// earlier one so a reinstalled app can re-enrol.
func (d *Database) SaveUnlockKey(deviceID string, pub []byte) error {
	_, err := d.sql.Exec(
		`INSERT INTO unlock_keys (device_id, public_key, enrolled_at) VALUES (?, ?, ?)
		 ON CONFLICT(device_id) DO UPDATE SET public_key = excluded.public_key,
		                                      enrolled_at = excluded.enrolled_at`,
		deviceID, pub, time.Now().Unix())
	return err
}

// UnlockKey returns the device's enrolled public key, or ErrNotFound.
func (d *Database) UnlockKey(deviceID string) ([]byte, error) {
	var pub []byte
	err := d.sql.QueryRow(`SELECT public_key FROM unlock_keys WHERE device_id = ?`, deviceID).Scan(&pub)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	return pub, err
}

// ---- Transfers ----

// Transfer is one row of file-transfer history. Timestamps are Unix
// milliseconds to match protocol.FileProgress, which is what both UIs render.
type Transfer struct {
	ID          string
	DeviceID    string
	Name        string
	Size        int64
	Direction   string
	Status      string
	Path        string
	SHA256      string
	Transferred int64
	StartedAt   int64
	FinishedAt  int64
	Error       string
}

// SaveTransfer inserts a transfer or updates the one already stored under the
// same ID. A single upsert covers both because progress arrives as a stream of
// updates to the same row, and the first one may be lost to a restart.
func (d *Database) SaveTransfer(t Transfer) error {
	_, err := d.sql.Exec(
		`INSERT INTO transfers (id, device_id, name, size, direction, status, path, sha256,
		                        transferred, started_at, finished_at, error)
		 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
		 ON CONFLICT(id) DO UPDATE SET status = excluded.status, path = excluded.path,
		     sha256 = excluded.sha256, transferred = excluded.transferred,
		     finished_at = excluded.finished_at, error = excluded.error`,
		t.ID, t.DeviceID, t.Name, t.Size, t.Direction, t.Status, t.Path, t.SHA256,
		t.Transferred, t.StartedAt, t.FinishedAt, t.Error)
	return err
}

// ListTransfers returns history newest-first, capped so a long-lived install
// does not hand the desktop UI thousands of rows on every state poll.
func (d *Database) ListTransfers(limit int) ([]Transfer, error) {
	if limit <= 0 {
		limit = 50
	}
	rows, err := d.sql.Query(
		`SELECT id, device_id, name, size, direction, status, path, sha256,
		        transferred, started_at, finished_at, error
		   FROM transfers ORDER BY started_at DESC LIMIT ?`, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	transfers := []Transfer{}
	for rows.Next() {
		var t Transfer
		if err := rows.Scan(&t.ID, &t.DeviceID, &t.Name, &t.Size, &t.Direction, &t.Status,
			&t.Path, &t.SHA256, &t.Transferred, &t.StartedAt, &t.FinishedAt, &t.Error); err != nil {
			return nil, err
		}
		transfers = append(transfers, t)
	}
	return transfers, rows.Err()
}

// ---- Settings ----

// Settings returns every stored key. Callers layer their own defaults over the
// result: an absent key means "never set", not "set to empty".
func (d *Database) Settings() (map[string]string, error) {
	rows, err := d.sql.Query(`SELECT key, value FROM settings`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	values := map[string]string{}
	for rows.Next() {
		var k, v string
		if err := rows.Scan(&k, &v); err != nil {
			return nil, err
		}
		values[k] = v
	}
	return values, rows.Err()
}

// SetSetting stores one key.
func (d *Database) SetSetting(key, value string) error {
	_, err := d.sql.Exec(
		`INSERT INTO settings (key, value) VALUES (?, ?)
		 ON CONFLICT(key) DO UPDATE SET value = excluded.value`, key, value)
	return err
}

// ---- Stream Deck Neo ----

const deckSettingKey = "deck_config"

// DeckConfig loads the persisted Stream Deck Neo configuration, returning DefaultDeckConfig
// if none has been saved yet.
func (d *Database) DeckConfig() (protocol.DeckConfig, error) {
	var raw string
	err := d.sql.QueryRow(`SELECT value FROM settings WHERE key = ?`, deckSettingKey).Scan(&raw)
	if errors.Is(err, sql.ErrNoRows) {
		def := protocol.DefaultDeckConfig()
		_ = d.SaveDeckConfig(def)
		return def, nil
	}
	if err != nil {
		return protocol.DefaultDeckConfig(), err
	}

	var config protocol.DeckConfig
	if err := json.Unmarshal([]byte(raw), &config); err != nil {
		return protocol.DefaultDeckConfig(), nil
	}
	return config, nil
}

// SaveDeckConfig persists the Stream Deck Neo configuration in settings.
func (d *Database) SaveDeckConfig(config protocol.DeckConfig) error {
	data, err := json.Marshal(config)
	if err != nil {
		return err
	}
	return d.SetSetting(deckSettingKey, string(data))
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
