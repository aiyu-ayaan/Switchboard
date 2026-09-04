package db

// Database provides storage for paired devices, settings, and session metadata
type Database struct {
	path string
}

func NewDatabase(path string) *Database {
	return &Database{path: path}
}
