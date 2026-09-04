package config

type Config struct {
	Port     int
	DBPath   string
	LogLevel string
}

func DefaultConfig() *Config {
	return &Config{
		Port:     8080,
		DBPath:   "switchboard.db",
		LogLevel: "info",
	}
}
