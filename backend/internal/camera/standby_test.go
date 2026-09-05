package camera

import (
	"testing"
)

func TestGenerateStandbyImage(t *testing.T) {
	img := GenerateStandbyImage(1280, 720)
	if img == nil {
		t.Fatal("GenerateStandbyImage returned nil")
	}
	if img.Rect.Dx() != 1280 || img.Rect.Dy() != 720 {
		t.Fatalf("unexpected dimensions: got %dx%d, want 1280x720", img.Rect.Dx(), img.Rect.Dy())
	}

	// Verify that the background is dark slate (not empty / black / transparent)
	bg := img.At(10, 10)
	r, g, b, a := bg.RGBA()
	if a == 0 {
		t.Fatal("alpha channel is 0; expected opaque background")
	}
	// 16-bit color in Go: 255 -> 0xffff
	if r == 0 && g == 0 && b == 0 {
		t.Fatal("background is pure black; expected dark slate theme")
	}
}

func TestVCamFeederInitAndFeed(t *testing.T) {
	feeder := NewVCamFeeder()
	if feeder == nil {
		t.Fatal("NewVCamFeeder returned nil")
	}
	defer feeder.Close()

	// Calling Feed on empty bytes or small JPEG should not crash
	_ = feeder.Feed(nil)
	feeder.NotifyStopped()
}

