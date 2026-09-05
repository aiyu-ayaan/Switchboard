//go:build !windows

package main

// Remote unlock is a Windows integration; elsewhere the subcommand does not
// exist and the daemon starts as usual.
func runUnlock([]string) (bool, error) { return false, nil }
