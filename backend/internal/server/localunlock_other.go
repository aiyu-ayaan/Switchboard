//go:build !windows

package server

func unlockSetupSupported() bool { return false }
