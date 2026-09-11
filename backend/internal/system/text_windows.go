//go:build windows

package system

import (
	"unicode/utf16"
	"unsafe"
)

const keyEventUnicode = 0x0004

func keyboardSupported() bool {
	return true
}

func newUnicodeKeyInput(codeUnit uint16, up bool) rawInput {
	in := rawInput{kind: inputKeyboard}
	k := (*keyboardInput)(unsafe.Pointer(&in.data[0]))
	k.scan = codeUnit
	k.flags = keyEventUnicode
	if up {
		k.flags |= keyEventKeyUp
	}
	return in
}

func inputText(text string) error {
	if text == "" {
		return nil
	}
	codeUnits := utf16.Encode([]rune(text))
	events := make([]rawInput, 0, len(codeUnits)*2)
	for _, cu := range codeUnits {
		events = append(events, newUnicodeKeyInput(cu, false))
		events = append(events, newUnicodeKeyInput(cu, true))
	}
	return sendInputs(events...)
}
