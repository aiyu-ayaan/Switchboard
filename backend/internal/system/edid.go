package system

import "strings"

// EDID is the block every panel carries describing itself, and it is the only
// place the model name a person would recognise is written down. Both host
// backends end up reading it -- Windows out of the registry key the display
// driver populates, Linux out of the connector's `edid` file in sysfs -- so the
// parsing lives here rather than in either one.

// edidMonitorName extracts the descriptor tagged 0xFC (monitor name) from a
// 128-byte EDID block. The four 18-byte descriptors start at offset 54.
func edidMonitorName(edid []byte) string {
	const (
		firstDescriptor = 54
		descriptorSize  = 18
		tagMonitorName  = 0xFC
	)
	for i := 0; i < 4; i++ {
		off := firstDescriptor + i*descriptorSize
		if off+descriptorSize > len(edid) {
			return ""
		}
		d := edid[off : off+descriptorSize]
		// A display descriptor has a zero pixel clock in the first two bytes.
		if d[0] != 0 || d[1] != 0 || d[3] != tagMonitorName {
			continue
		}
		name := strings.TrimSpace(strings.SplitN(string(d[5:]), "\n", 2)[0])
		return strings.TrimRight(name, "\x00 ")
	}
	return ""
}
